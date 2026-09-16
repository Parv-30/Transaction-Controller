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
echo "Verifying gateway route disambiguation between Ledger Service (/accounts/**) and Holds"
echo "Service (/accounts/*/available-balance) -- the ledger-accounts route added for V3 uses a"
echo "superset pattern of the pre-existing holds-available-balance route, so route declaration"
echo "order matters: if ledger-accounts were declared first, every available-balance request"
echo "would be silently misrouted to Ledger Service (which has no such endpoint) instead of"
echo "Holds Service. This checks both routes land on the correct backend."
CREATE_ACCT_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/accounts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accountRef":"smoke-route-check","currency":"USD"}')
CREATE_ACCT_HTTP_CODE=$(echo "$CREATE_ACCT_RESPONSE" | tail -n1)
CREATE_ACCT_BODY=$(echo "$CREATE_ACCT_RESPONSE" | head -n-1)
echo "  POST /accounts -> ($CREATE_ACCT_HTTP_CODE) $CREATE_ACCT_BODY"
if [ "$CREATE_ACCT_HTTP_CODE" != "201" ] && [ "$CREATE_ACCT_HTTP_CODE" != "409" ]; then
  echo "Smoke test FAILED: expected HTTP 201 or 409 from Ledger Service creating an account"
  echo "through the gateway's ledger-accounts route, got ($CREATE_ACCT_HTTP_CODE) $CREATE_ACCT_BODY"
  exit 1
fi

AVAIL_BAL_RESPONSE=$(curl -s -w "\n%{http_code}" "$GATEWAY_URL/accounts/smoke-a/available-balance" \
  -H "Authorization: Bearer $TOKEN")
AVAIL_BAL_HTTP_CODE=$(echo "$AVAIL_BAL_RESPONSE" | tail -n1)
AVAIL_BAL_BODY=$(echo "$AVAIL_BAL_RESPONSE" | head -n-1)
echo "  GET /accounts/smoke-a/available-balance -> ($AVAIL_BAL_HTTP_CODE) $AVAIL_BAL_BODY"
if [ "$AVAIL_BAL_HTTP_CODE" != "200" ] || ! echo "$AVAIL_BAL_BODY" | grep -q 'availableBalance\|balance'; then
  echo "Smoke test FAILED: expected HTTP 200 with a balance body from Holds Service's"
  echo "available-balance route, got ($AVAIL_BAL_HTTP_CODE) $AVAIL_BAL_BODY -- this likely means"
  echo "route ordering is wrong and available-balance requests are being misrouted to Ledger"
  echo "Service instead of Holds Service."
  exit 1
fi

echo ""
echo "Verifying the Gateway Simulator deposit-then-withdrawal round trip..."
echo "Seeding a fresh account for the deposit/withdrawal flow..."
docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
  "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), 'smoke-gwsim', 0) ON CONFLICT (account_ref) DO UPDATE SET balance_minor = 0;"

echo "Simulating an external deposit of 2500 into smoke-gwsim..."
DEPOSIT_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/simulator/deposits" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accountRef":"smoke-gwsim","amountMinor":2500,"currency":"USD"}')
DEPOSIT_HTTP_CODE=$(echo "$DEPOSIT_RESPONSE" | tail -n1)
DEPOSIT_BODY=$(echo "$DEPOSIT_RESPONSE" | head -n-1)
echo "  POST /simulator/deposits -> ($DEPOSIT_HTTP_CODE) $DEPOSIT_BODY"
if [ "$DEPOSIT_HTTP_CODE" != "201" ]; then
  echo "Smoke test FAILED: expected HTTP 201 simulating a deposit, got ($DEPOSIT_HTTP_CODE) $DEPOSIT_BODY"
  exit 1
fi

EXTERNAL_REF=$(echo "$DEPOSIT_BODY" | grep -o '"externalReference":"[^"]*"' | cut -d'"' -f4)
echo "External reference: $EXTERNAL_REF"

echo "Polling GET /external-deposits/{ref} until CREDITED..."
DEPOSIT_STATUS=""
for i in $(seq 1 15); do
  DEPOSIT_STATUS_RESPONSE=$(curl -sf "$GATEWAY_URL/external-deposits/$EXTERNAL_REF" \
    -H "Authorization: Bearer $TOKEN")
  echo "  [attempt $i] $DEPOSIT_STATUS_RESPONSE"
  if echo "$DEPOSIT_STATUS_RESPONSE" | grep -q '"status":"CREDITED"'; then
    DEPOSIT_STATUS="CREDITED"
    break
  fi
  sleep 2
done

if [ "$DEPOSIT_STATUS" != "CREDITED" ]; then
  echo "Smoke test FAILED: deposit never reached CREDITED status within the timeout."
  exit 1
fi

DEPOSIT_BALANCE=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT balance_minor FROM accounts WHERE account_ref = 'smoke-gwsim';" | tr -d ' \r\n')
[ "$DEPOSIT_BALANCE" = "2500" ] || { echo "Smoke test FAILED: expected balance 2500 after deposit, got $DEPOSIT_BALANCE"; exit 1; }
echo "Deposit credited; smoke-gwsim balance is now $DEPOSIT_BALANCE."

echo ""
echo "Posting a WITHDRAWAL_EXTERNAL transaction of 1000 from smoke-gwsim..."
WITHDRAWAL_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/transactions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-withdrawal-$(date +%s)" \
  -d '{"debitAccountRef":"smoke-gwsim","creditAccountRef":"external-clearing-USD","amountMinor":1000,"currency":"USD","description":"smoke withdrawal","transactionType":"WITHDRAWAL_EXTERNAL"}')
WITHDRAWAL_HTTP_CODE=$(echo "$WITHDRAWAL_RESPONSE" | tail -n1)
WITHDRAWAL_BODY=$(echo "$WITHDRAWAL_RESPONSE" | head -n-1)
echo "  POST /transactions (WITHDRAWAL_EXTERNAL) -> ($WITHDRAWAL_HTTP_CODE) $WITHDRAWAL_BODY"
if [ "$WITHDRAWAL_HTTP_CODE" != "201" ]; then
  echo "Smoke test FAILED: expected HTTP 201 posting the withdrawal transaction, got"
  echo "($WITHDRAWAL_HTTP_CODE) $WITHDRAWAL_BODY"
  exit 1
fi

WITHDRAWAL_TXN_ID=$(echo "$WITHDRAWAL_BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)
echo "Withdrawal source transaction ID: $WITHDRAWAL_TXN_ID"

WITHDRAWAL_DEBIT_BALANCE=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT balance_minor FROM accounts WHERE account_ref = 'smoke-gwsim';" | tr -d ' \r\n')
[ "$WITHDRAWAL_DEBIT_BALANCE" = "1500" ] || { echo "Smoke test FAILED: expected balance 1500 immediately after withdrawal debit, got $WITHDRAWAL_DEBIT_BALANCE"; exit 1; }

echo "Polling gateway-sim-db directly for the external_withdrawals row to reach SUBMITTED"
echo "(querying the DB directly rather than adding a read-by-transaction-id endpoint, matching"
echo "how chaos scenario 4 already reads outbox/processed_events directly via psql)..."
WITHDRAWAL_ID=""
WITHDRAWAL_STATUS=""
for i in $(seq 1 20); do
  WITHDRAWAL_ROW=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -A -F'|' -c \
    "SELECT id, status FROM external_withdrawals WHERE source_transaction_id = '$WITHDRAWAL_TXN_ID';" | tr -d '\r')
  WITHDRAWAL_ID=$(echo "$WITHDRAWAL_ROW" | cut -d'|' -f1)
  WITHDRAWAL_STATUS=$(echo "$WITHDRAWAL_ROW" | cut -d'|' -f2)
  echo "  [attempt $i] id=$WITHDRAWAL_ID status=$WITHDRAWAL_STATUS"
  [ "$WITHDRAWAL_STATUS" = "SUBMITTED" ] && break
  sleep 2
done

if [ "$WITHDRAWAL_STATUS" != "SUBMITTED" ]; then
  echo "Smoke test FAILED: withdrawal never reached SUBMITTED status, last seen: $WITHDRAWAL_STATUS"
  exit 1
fi

echo "Confirming the withdrawal via the gateway with outcome FAILED (should trigger a reversal)..."
CONFIRM_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/simulator/withdrawals/$WITHDRAWAL_ID/confirm" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"outcome":"FAILED"}')
CONFIRM_HTTP_CODE=$(echo "$CONFIRM_RESPONSE" | tail -n1)
CONFIRM_BODY=$(echo "$CONFIRM_RESPONSE" | head -n-1)
echo "  POST /simulator/withdrawals/$WITHDRAWAL_ID/confirm -> ($CONFIRM_HTTP_CODE) $CONFIRM_BODY"
if [ "$CONFIRM_HTTP_CODE" != "200" ]; then
  echo "Smoke test FAILED: expected HTTP 200 confirming the withdrawal as FAILED, got"
  echo "($CONFIRM_HTTP_CODE) $CONFIRM_BODY"
  exit 1
fi

echo "Polling smoke-gwsim's balance until the reversal restores it to 2500..."
FINAL_BALANCE=""
for i in $(seq 1 15); do
  FINAL_BALANCE=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT balance_minor FROM accounts WHERE account_ref = 'smoke-gwsim';" | tr -d ' \r\n')
  echo "  [attempt $i] balance=$FINAL_BALANCE"
  [ "$FINAL_BALANCE" = "2500" ] && break
  sleep 2
done

if [ "$FINAL_BALANCE" != "2500" ]; then
  echo "Smoke test FAILED: expected balance restored to 2500 after the withdrawal reversal, got $FINAL_BALANCE"
  exit 1
fi

echo "Gateway Simulator deposit-then-withdrawal round trip verified: deposit credited, withdrawal"
echo "debited and reversed correctly on FAILED confirmation."

echo ""
echo "Verifying the admin-role boundary on POST /transactions/{id}/reverse..."
echo "(Keycloak's realm_access.roles claim is mapped to Spring Security authorities by"
echo "KeycloakRealmRoleConverter; the gateway's SecurityConfig gates this route behind"
echo "ROLE_admin. A non-admin token must be rejected with 403 before ever reaching the"
echo "controller, while an admin token must pass the gate and reach ledger-service, which"
echo "then legitimately 404s on a nonexistent transaction id -- proving the gate itself"
echo "is the thing being tested, not just downstream 404 behavior.)"
USER_ROLE_TOKEN=$(bash "$(dirname "${BASH_SOURCE[0]}")/get-token.sh" alice)
ADMIN_ROLE_TOKEN=$(bash "$(dirname "${BASH_SOURCE[0]}")/get-token.sh" admin)
FAKE_TXN_ID="00000000-0000-0000-0000-000000000000"

USER_REVERSE_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "$GATEWAY_URL/transactions/$FAKE_TXN_ID/reverse" \
  -H "Authorization: Bearer $USER_ROLE_TOKEN")
echo "  POST /transactions/{id}/reverse (alice, user role) -> $USER_REVERSE_HTTP_CODE"
if [ "$USER_REVERSE_HTTP_CODE" != "403" ]; then
  echo "Smoke test FAILED: expected HTTP 403 for a non-admin token on the reverse endpoint,"
  echo "got $USER_REVERSE_HTTP_CODE -- the admin role gate may not be wired correctly."
  exit 1
fi

ADMIN_REVERSE_HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  "$GATEWAY_URL/transactions/$FAKE_TXN_ID/reverse" \
  -H "Authorization: Bearer $ADMIN_ROLE_TOKEN")
echo "  POST /transactions/{id}/reverse (admin, admin role) -> $ADMIN_REVERSE_HTTP_CODE"
if [ "$ADMIN_REVERSE_HTTP_CODE" = "403" ]; then
  echo "Smoke test FAILED: expected a non-403 response for an admin-role token (the fake id"
  echo "should legitimately 404 past the authorization gate), but got 403 -- the admin token"
  echo "is being rejected by the gate instead of passing it."
  exit 1
fi

echo "Admin-role boundary verified: alice (user role) is rejected with 403, admin passes the"
echo "authorization gate (got $ADMIN_REVERSE_HTTP_CODE, not 403)."

echo ""
echo "Smoke test complete: reconciliation is clean, the Holds flow (create + capture) works"
echo "through the gateway, gateway route disambiguation between Ledger Service and Holds"
echo "Service is correct, the Gateway Simulator deposit/withdrawal round trip is correct, and"
echo "the admin-role authorization boundary on POST /transactions/{id}/reverse is enforced."
echo "Final reconciliation result: $RECON_RESPONSE"
exit 0
