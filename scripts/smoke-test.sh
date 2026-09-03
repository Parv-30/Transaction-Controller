#!/usr/bin/env bash
# D:\Ledger\scripts\smoke-test.sh
#
# End-to-end smoke test for the full V1 Docker Compose stack: posts a real transaction
# through ledger-service, waits for the Transaction Processor's embedded Debezium CDC engine
# to capture the resulting outbox row, publish it to RabbitMQ, and consume it, then triggers
# a reconciliation run and expects a clean result.
#
# Replication-slot-timing note (see Task 7 / Task 9 brief): DebeziumEngineLifecycle.start()
# returns before the Postgres replication slot is actually confirmed to exist -- slot
# creation happens on a background thread, roughly 700-800ms later. If this script posted the
# transaction immediately after ledger-service's /actuator/health reported healthy, there
# would be a real, observed-in-practice race: a transaction committed before the slot exists
# is invisible to logical replication forever (Postgres does not retroactively capture
# pre-slot changes -- this is not a bug, it's how logical replication slots work). Rather than
# either (a) blindly sleeping a fixed, guessed duration before posting, or (b) blindly
# sleeping a fixed duration after posting and hoping it was enough, this script does two
# things:
#   1. Waits for ledger-service health AND gives the stack a fixed grace period before
#      posting, long enough to comfortably clear the ~700-800ms slot-creation window many
#      times over (the CDC engine starts during transaction-processor's own boot, well before
#      this script even begins polling ledger-service's health endpoint in practice, but the
#      grace period is kept as defense-in-depth documentation of the known race).
#   2. Critically, replaces the brief's single blind `sleep 30` + one-shot check with a
#      polling loop that repeatedly triggers reconciliation and inspects the actual outcome
#      (outboxMissingCount / outboxStuckCount) up to a bounded timeout, so the test verifies
#      the real success condition instead of assuming a fixed sleep was "long enough". This
#      also makes the script fail fast when the pipeline is broken instead of always waiting
#      the full timeout.
set -euo pipefail

TOXIPROXY_API="http://localhost:8474"

echo "Configuring Toxiproxy proxies..."
curl -sf -X POST "$TOXIPROXY_API/proxies" -d '{
  "name": "ledger-postgres-app-proxy",
  "listen": "0.0.0.0:15432",
  "upstream": "ledger-postgres:5432"
}' > /dev/null || echo "  (ledger-postgres-app-proxy already exists)"

curl -sf -X POST "$TOXIPROXY_API/proxies" -d '{
  "name": "ledger-postgres-cdc-proxy",
  "listen": "0.0.0.0:15433",
  "upstream": "ledger-postgres:5432"
}' > /dev/null || echo "  (ledger-postgres-cdc-proxy already exists)"

curl -sf -X POST "$TOXIPROXY_API/proxies" -d '{
  "name": "rabbitmq-proxy",
  "listen": "0.0.0.0:15674",
  "upstream": "rabbitmq:5672"
}' > /dev/null || echo "  (rabbitmq-proxy already exists)"

echo "Restarting ledger-service so its datasource connects through the now-configured"
echo "toxiproxy proxy (on first \"docker compose up\", ledger-service starts before this"
echo "script has had a chance to create the proxies above, so its initial connection attempt"
echo "to toxiproxy:15432 is refused and the container exits -- confirmed by reproduction)..."
docker compose restart ledger-service > /dev/null

echo "Waiting for ledger-service to be healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Granting CDC privileges on outbox and creating the Debezium publication..."
# ledger-postgres-init/01-debezium-user.sql deliberately only creates the debezium_replicator
# role: Postgres initdb.d scripts run before ledger-service has ever connected and run its
# Flyway migration, so the "outbox" table does not exist yet at that point -- a GRANT or
# CREATE PUBLICATION referencing it there fails and crashes the whole ledger-postgres
# container (confirmed by reproduction). By this point in the script, ledger-service has
# reported healthy above, so Flyway has run and "outbox" exists. This step is idempotent
# (safe to re-run: DO blocks below no-op if the grant/publication already exist).
docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -v ON_ERROR_STOP=0 -c \
  "GRANT SELECT ON public.outbox TO debezium_replicator;"
docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -v ON_ERROR_STOP=0 -c \
  "DO \$\$ BEGIN
     IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'ledger_outbox_pub') THEN
       CREATE PUBLICATION ledger_outbox_pub FOR TABLE public.outbox;
     END IF;
   END \$\$;"

echo "Restarting transaction-processor so its Debezium engine (which needs the publication"
echo "and grant above to already exist -- publication.autocreate.mode=disabled) starts clean..."
docker compose restart transaction-processor > /dev/null
echo "Waiting for transaction-processor to be healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

# Defense-in-depth grace period: see the replication-slot-timing note above. Even after
# transaction-processor reports healthy, DebeziumEngineLifecycle.start() returns before the
# Postgres replication slot is actually confirmed to exist -- slot creation happens on a
# background thread, roughly 700-800ms later. This pause gives that window room to close
# before this script posts a transaction that the CDC pipeline must observe.
echo "Grace period for CDC replication slot creation..."
sleep 5

echo "Seeding two test accounts..."
docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
  "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), 'smoke-a', 5000), (gen_random_uuid(), 'smoke-b', 0) ON CONFLICT (account_ref) DO NOTHING;"

echo "Posting a transaction..."
RESPONSE=$(curl -sf -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -d '{"debitAccountRef":"smoke-a","creditAccountRef":"smoke-b","amountMinor":500,"currency":"USD","description":"smoke test"}')
echo "Response: $RESPONSE"

TXN_ID=$(echo "$RESPONSE" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)
echo "Transaction ID: $TXN_ID"

echo "Polling reconciliation until clean (or timeout) -- verifies the CDC pipeline actually"
echo "captured, published, and consumed the outbox row, rather than trusting a fixed sleep..."
CLEAN=""
RECON_RESPONSE=""
for i in $(seq 1 15); do
  RECON_RESPONSE=$(curl -sf -X POST http://localhost:8080/reconciliation/runs)
  echo "  [attempt $i] $RECON_RESPONSE"
  if echo "$RECON_RESPONSE" | grep -q '"entriesImbalanceCount":0' \
     && echo "$RECON_RESPONSE" | grep -q '"outboxMissingCount":0' \
     && echo "$RECON_RESPONSE" | grep -q '"outboxStuckCount":0'; then
    CLEAN="yes"
    break
  fi
  sleep 4
done

echo ""
if [ "$CLEAN" = "yes" ]; then
  echo "Smoke test complete: reconciliation is clean."
  echo "Final result: $RECON_RESPONSE"
  exit 0
else
  echo "Smoke test FAILED: reconciliation did not reach a clean state within the timeout."
  echo "Last result: $RECON_RESPONSE"
  exit 1
fi
