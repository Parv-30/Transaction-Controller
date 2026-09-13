#!/usr/bin/env bash
# D:\Ledger\scripts\provision.sh
#
# One-time (idempotent, safe to re-run) infrastructure provisioning for the V1 Docker Compose
# stack, run AFTER `docker compose up -d --build`. `docker compose up` alone is not enough to
# produce a working stack: the Toxiproxy proxies that ledger-service/transaction-processor
# route their DB/RabbitMQ connections through do not exist until this script creates them, and
# the Debezium CDC pipeline needs a Postgres GRANT + publication that can only be created after
# ledger-service's Flyway migration has created the "outbox" table (see
# ledger-postgres-init/01-debezium-user.sql for why that can't happen in initdb.d). This script
# was previously folded into scripts/smoke-test.sh; it is split out here so that provisioning
# (infrastructure setup) is not silently bundled inside what should be a pure verification
# script -- `make up` now runs this automatically, so a fresh stack is fully working without
# requiring a `smoke-test` run first.
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
  if curl -sf http://localhost:8090/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

bash "$(dirname "${BASH_SOURCE[0]}")/seed-clearing-accounts.sh"

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

# Defense-in-depth grace period: even after transaction-processor reports healthy,
# DebeziumEngineLifecycle.start() returns before the Postgres replication slot is actually
# confirmed to exist -- slot creation happens on a background thread, roughly 700-800ms later.
# This pause gives that window room to close before anything (e.g. smoke-test.sh) posts a
# transaction that the CDC pipeline must observe.
echo "Grace period for CDC replication slot creation..."
sleep 5

echo "Provisioning complete: proxies configured, CDC grant/publication created, both services"
echo "restarted and healthy."
