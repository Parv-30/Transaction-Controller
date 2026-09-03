#!/usr/bin/env bash
# D:\Ledger\scripts\smoke-test.sh
#
# End-to-end verification for the V1 Docker Compose stack: posts a real transaction through
# ledger-service, waits for the Transaction Processor's embedded Debezium CDC engine to
# capture the resulting outbox row, publish it to RabbitMQ, and consume it, then triggers a
# reconciliation run and expects a clean result.
#
# This script assumes infrastructure provisioning has already happened -- Toxiproxy proxy
# creation, the CDC grant/publication, and the service restarts that depend on them. That is
# scripts/provision.sh's job, not this script's: `make up` runs `docker compose up -d --build`
# followed by `provision.sh` automatically, so by the time this script runs against a stack
# brought up via `make up`, provisioning is already done. If you brought the stack up some
# other way (e.g. `docker compose up -d --build` directly), run `bash scripts/provision.sh`
# once before this script -- otherwise the CDC pipeline has never been wired up and this
# script's reconciliation-polling loop will simply time out.
set -euo pipefail

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
