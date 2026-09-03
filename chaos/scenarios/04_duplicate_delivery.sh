#!/usr/bin/env bash
# chaos/scenarios/04_duplicate_delivery.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 4: Duplicate RabbitMQ delivery ==="

reset_all_toxics
seed_account "chaos4-a" 10000
seed_account "chaos4-b" 0

IDEM_KEY="chaos4-$(date +%s)"
RESPONSE=$(post_transaction "chaos4-a" "chaos4-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

wait_for_processed_status "$TXN_ID" "CONSUMED" 60 || fail "event never reached CONSUMED before duplicate-delivery test began"

OUTBOX_ID=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT id FROM outbox WHERE aggregate_id = '$TXN_ID';" | tr -d ' \r\n')
PAYLOAD=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT payload::text FROM outbox WHERE id = '$OUTBOX_ID';" | tr -d '\r\n')

echo "Firing 10 duplicate publishes of outboxEventId=$OUTBOX_ID directly via the RabbitMQ management API..."
for i in $(seq 1 10); do
  curl -sf -u guest:guest -X POST "http://localhost:15672/api/exchanges/%2f/ledger.events/publish" \
    -H "Content-Type: application/json" \
    -d "{
      \"properties\": {\"headers\": {\"outboxEventId\": \"$OUTBOX_ID\", \"aggregateId\": \"$TXN_ID\", \"eventType\": \"TRANSACTION_POSTED\"}, \"content_type\": \"application/json\"},
      \"routing_key\": \"ledger.transaction.posted\",
      \"payload\": $(echo "$PAYLOAD" | sed 's/"/\\"/g' | sed 's/^/"/;s/$/"/'),
      \"payload_encoding\": \"string\"
    }" > /dev/null &
done
wait

sleep 5

DELIVERY_COUNT=$(docker compose exec -T processor-postgres psql -U processor -d processor_db -t -c \
  "SELECT delivery_count FROM processed_events WHERE outbox_event_id = '$OUTBOX_ID';" | tr -d ' \r\n')
[ "$DELIVERY_COUNT" -ge "10" ] || fail "expected delivery_count >= 10 after 10 duplicate publishes, got $DELIVERY_COUNT"

FINAL_STATUS=$(get_processed_event_status_by_aggregate "$TXN_ID")
[ "$FINAL_STATUS" = "CONSUMED" ] || fail "expected status still CONSUMED after duplicates, got $FINAL_STATUS"

BALANCE_A=$(get_account_balance "chaos4-a")
[ "$BALANCE_A" = "9500" ] || fail "expected chaos4-a balance debited exactly once (9500), got $BALANCE_A"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 4"

pass "Scenario 4: 10 duplicate deliveries produced exactly one business effect"
