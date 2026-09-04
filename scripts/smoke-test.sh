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

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://localhost:8180/realms/ledger/protocol/openid-connect/token}"

echo "Fetching a Keycloak service-account token for smoke-test-client..."
TOKEN=$(curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
  -d "grant_type=client_credentials" \
  -d "client_id=smoke-test-client" \
  -d "client_secret=smoke-test-secret" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

echo "Seeding two test accounts..."
docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
  "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), 'smoke-a', 5000), (gen_random_uuid(), 'smoke-b', 0) ON CONFLICT (account_ref) DO NOTHING;"

echo "Posting a transaction..."
RESPONSE=$(curl -sf -X POST "$GATEWAY_URL/transactions" \
  -H "Authorization: Bearer $TOKEN" \
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
  RECON_RESPONSE=$(curl -sf -X POST "$GATEWAY_URL/reconciliation/runs" \
    -H "Authorization: Bearer $TOKEN")
  echo "  [attempt $i] $RECON_RESPONSE"
  if echo "$RECON_RESPONSE" | grep -q '"entriesImbalanceCount":0' \
     && echo "$RECON_RESPONSE" | grep -q '"outboxMissingCount":0' \
     && echo "$RECON_RESPONSE" | grep -q '"outboxStuckCount":0'; then
    CLEAN="yes"
    break
  fi
  sleep 4
done

if [ "$CLEAN" != "yes" ]; then
  echo ""
  echo "Smoke test FAILED: reconciliation did not reach a clean state within the timeout."
  echo "Last result: $RECON_RESPONSE"
  exit 1
fi

echo ""
echo "Reconciliation clean. Verifying the Holds flow through the gateway..."
echo "(Holds Service's own account_balance_cache is synced asynchronously from RabbitMQ,"
echo "downstream of the same CDC pipeline just verified above -- a clean ledger-side"
echo "reconciliation does not guarantee Holds Service has consumed this transaction's event"
echo "yet, so retry a few times rather than treating the first attempt as authoritative.)"
HOLD_STATUS=""
HOLD_HTTP_CODE=""
HOLD_BODY=""
for i in $(seq 1 10); do
  HOLD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/holds" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: smoke-hold-$(date +%s)-$i" \
    -d '{"accountRef":"smoke-a","destinationAccountRef":"smoke-b","amountMinor":500,"currency":"USD","expiresInSeconds":3600}')
  HOLD_HTTP_CODE=$(echo "$HOLD_RESPONSE" | tail -n1)
  HOLD_BODY=$(echo "$HOLD_RESPONSE" | head -n-1)
  echo "  [attempt $i] ($HOLD_HTTP_CODE) $HOLD_BODY"
  if [ "$HOLD_HTTP_CODE" = "201" ] && echo "$HOLD_BODY" | grep -q '"status":"ACTIVE"'; then
    HOLD_STATUS="ok"
    break
  fi
  sleep 3
done

if [ "$HOLD_STATUS" != "ok" ]; then
  echo "Smoke test FAILED: expected HTTP 201 with status ACTIVE creating a hold through the"
  echo "gateway, got ($HOLD_HTTP_CODE) $HOLD_BODY"
  exit 1
fi

HOLD_ID=$(echo "$HOLD_BODY" | grep -o '"holdId":"[^"]*"' | cut -d'"' -f4)
echo "Hold ID: $HOLD_ID"

echo ""
echo "Capturing the hold through the gateway..."
CAPTURE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/holds/$HOLD_ID/capture" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amountMinor":500}')
CAPTURE_HTTP_CODE=$(echo "$CAPTURE_RESPONSE" | tail -n1)
CAPTURE_BODY=$(echo "$CAPTURE_RESPONSE" | head -n-1)
echo "  ($CAPTURE_HTTP_CODE) $CAPTURE_BODY"

if [ "$CAPTURE_HTTP_CODE" != "200" ] || ! echo "$CAPTURE_BODY" | grep -q '"status":"CAPTURED"'; then
  echo "Smoke test FAILED: expected HTTP 200 with status CAPTURED capturing the hold through"
  echo "the gateway, got ($CAPTURE_HTTP_CODE) $CAPTURE_BODY"
  exit 1
fi

echo ""
echo "Smoke test complete: reconciliation is clean and the Holds flow (create + capture) works"
echo "through the gateway."
echo "Final reconciliation result: $RECON_RESPONSE"
exit 0
