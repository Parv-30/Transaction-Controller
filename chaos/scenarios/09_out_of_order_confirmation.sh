#!/usr/bin/env bash
# chaos/scenarios/09_out_of_order_confirmation.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 9: Confirmation races the withdrawal's own RabbitMQ consumption ==="

reset_all_toxics
seed_account "chaos9-withdrawal-source" 10000

echo "Adding latency to gateway-simulator's RabbitMQ connection to widen the race window..."
add_toxic "gateway-sim-rabbitmq" "gwsim-consume-latency" "latency" '{"latency": 5000}'

IDEM_KEY="chaos9-$(date +%s)"
RESPONSE=$(post_transaction_with_type "chaos9-withdrawal-source" "external-clearing-USD" 2000 "$IDEM_KEY" "WITHDRAWAL_EXTERNAL")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting withdrawal transaction, got $HTTP_CODE: $BODY"
SOURCE_TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Attempting confirm-by-transaction-id immediately, before the delayed RabbitMQ message has been consumed..."
EARLY_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
  "$GATEWAY_URL/simulator/withdrawals/by-transaction/$SOURCE_TXN_ID/confirm" \
  -H "Authorization: Bearer $(get_chaos_suite_token)" \
  -H "Content-Type: application/json" -d '{"outcome":"CONFIRMED"}')
EARLY_HTTP_CODE=$(echo "$EARLY_RESPONSE" | tail -n1)
[ "$EARLY_HTTP_CODE" = "409" ] || fail "expected 409 confirming before the withdrawal row exists, got $EARLY_HTTP_CODE"

echo "Removing the latency toxic so the delayed message can now be consumed..."
remove_toxic "gateway-sim-rabbitmq" "gwsim-consume-latency"

echo "Retrying confirm-by-transaction-id until it succeeds..."
RETRY_HTTP_CODE=""
for i in $(seq 1 30); do
  RETRY_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "$GATEWAY_URL/simulator/withdrawals/by-transaction/$SOURCE_TXN_ID/confirm" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" -d '{"outcome":"CONFIRMED"}')
  RETRY_HTTP_CODE=$(echo "$RETRY_RESPONSE" | tail -n1)
  [ "$RETRY_HTTP_CODE" = "200" ] && break
  sleep 2
done
[ "$RETRY_HTTP_CODE" = "200" ] || fail "confirm never succeeded after removing the toxic, last code: $RETRY_HTTP_CODE"

FINAL_STATUS=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
  "SELECT status FROM external_withdrawals WHERE source_transaction_id = '$SOURCE_TXN_ID';" | tr -d ' \r\n')
[ "$FINAL_STATUS" = "CONFIRMED" ] || fail "expected final status CONFIRMED, got $FINAL_STATUS"

BALANCE=$(get_account_balance "chaos9-withdrawal-source")
[ "$BALANCE" = "8000" ] || fail "expected balance 8000 (10000 - 2000 debited, no reversal since CONFIRMED), got $BALANCE"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 9"

pass "Scenario 9: out-of-order confirmation correctly rejected with 409, succeeded on retry"
