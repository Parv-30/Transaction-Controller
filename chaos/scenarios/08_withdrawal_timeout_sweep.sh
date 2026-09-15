#!/usr/bin/env bash
# chaos/scenarios/08_withdrawal_timeout_sweep.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 8: Withdrawal never confirmed, timeout sweep reverses it ==="

echo "Restarting gateway-simulator with GATEWAY_SIM_WITHDRAWAL_TIMEOUT_SECONDS=10 (chaos-suite-only"
echo "override -- see chaos/docker-compose.chaos-override.yml -- so this scenario doesn't have to"
echo "wait out the 60s production default) ..."
docker compose -f "$SCRIPT_DIR/../../docker-compose.yml" -f "$SCRIPT_DIR/../docker-compose.chaos-override.yml" \
  up -d gateway-simulator

restore_gateway_simulator_defaults() {
  echo "Restoring gateway-simulator to its production config (removing the chaos-only timeout override)..."
  docker compose -f "$SCRIPT_DIR/../../docker-compose.yml" up -d gateway-simulator > /dev/null 2>&1 || true
}
trap restore_gateway_simulator_defaults EXIT

echo "Waiting for gateway-simulator to be healthy after the restart..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8084/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

seed_account "chaos8-withdrawal-source" 10000

IDEM_KEY="chaos8-$(date +%s)"
RESPONSE=$(post_transaction_with_type "chaos8-withdrawal-source" "external-clearing-USD" 3000 "$IDEM_KEY" "WITHDRAWAL_EXTERNAL")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting withdrawal transaction, got $HTTP_CODE: $BODY"

BALANCE_AFTER_DEBIT=$(get_account_balance "chaos8-withdrawal-source")
[ "$BALANCE_AFTER_DEBIT" = "7000" ] || fail "expected balance 7000 immediately after withdrawal debit, got $BALANCE_AFTER_DEBIT"

echo "Waiting for the withdrawal to be picked up and marked SUBMITTED..."
STATUS=""
for i in $(seq 1 30); do
  STATUS=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
    "SELECT status FROM external_withdrawals WHERE account_ref = 'chaos8-withdrawal-source';" | tr -d ' \r\n')
  [ "$STATUS" = "SUBMITTED" ] && break
  sleep 2
done
[ "$STATUS" = "SUBMITTED" ] || fail "withdrawal never reached SUBMITTED status, last seen: $STATUS"

echo "Not confirming — waiting for the sweep (10s timeout, 15s sweep interval) to mark it"
echo "TIMED_OUT and reverse it..."
for i in $(seq 1 30); do
  STATUS=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
    "SELECT status FROM external_withdrawals WHERE account_ref = 'chaos8-withdrawal-source';" | tr -d ' \r\n')
  [ "$STATUS" = "REVERSED" ] && break
  sleep 2
done
[ "$STATUS" = "REVERSED" ] || fail "withdrawal never reached REVERSED status after timeout, last seen: $STATUS"

BALANCE_AFTER_REVERSAL=$(get_account_balance "chaos8-withdrawal-source")
[ "$BALANCE_AFTER_REVERSAL" = "10000" ] || fail "expected balance restored to 10000 after reversal, got $BALANCE_AFTER_REVERSAL"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 8"

pass "Scenario 8: unconfirmed withdrawal timed out and was correctly reversed"
