#!/usr/bin/env bash
# chaos/scenarios/01_rabbitmq_down_mid_publish.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 1: RabbitMQ down mid-publish ==="

reset_all_toxics
seed_account "chaos1-a" 10000
seed_account "chaos1-b" 0

IDEM_KEY="chaos1-$(date +%s)"
RESPONSE=$(post_transaction "chaos1-a" "chaos1-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Injecting RabbitMQ outage..."
add_toxic "rabbitmq-proxy" "outage" "timeout" '{"timeout": 0}'

sleep 5

echo "Restoring RabbitMQ connectivity..."
remove_toxic "rabbitmq-proxy" "outage"

wait_for_processed_status "$TXN_ID" "CONSUMED" 60 || fail "event never reached CONSUMED after RabbitMQ outage recovered"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 1"

pass "Scenario 1: event survived RabbitMQ outage and was eventually consumed exactly once"
