#!/usr/bin/env bash
# chaos/scenarios/07_duplicate_webhook.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 7: Duplicate deposit webhook delivery ==="

seed_account "chaos7-deposit-target" 0

EXTERNAL_REF="chaos7-webhook-$(date +%s)"
for i in 1 2 3 4 5; do
  curl -sf -X POST "$GATEWAY_URL/webhooks/deposits" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" \
    -d "{\"externalReference\":\"$EXTERNAL_REF\",\"accountRef\":\"chaos7-deposit-target\",\"amountMinor\":1000,\"currency\":\"USD\"}" \
    > /dev/null
done

sleep 3

BALANCE=$(get_account_balance "chaos7-deposit-target")
[ "$BALANCE" = "1000" ] || fail "expected exactly one credit of 1000 despite 5 duplicate webhooks, got balance $BALANCE"

DEPOSIT_COUNT=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
  "SELECT count(*) FROM external_deposits WHERE external_reference = '$EXTERNAL_REF';" | tr -d ' \r\n')
[ "$DEPOSIT_COUNT" = "1" ] || fail "expected exactly one external_deposits row, found $DEPOSIT_COUNT"

WEBHOOK_COUNT=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
  "SELECT webhook_count FROM webhook_dedup WHERE external_reference = '$EXTERNAL_REF';" | tr -d ' \r\n')
[ "$WEBHOOK_COUNT" = "5" ] || fail "expected webhook_count=5 after 5 deliveries, got $WEBHOOK_COUNT"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 7"

pass "Scenario 7: 5 duplicate webhook deliveries produced exactly one credit"
