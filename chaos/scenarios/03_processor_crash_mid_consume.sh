#!/usr/bin/env bash
# chaos/scenarios/03_processor_crash_mid_consume.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 3: Transaction Processor crash mid-consume ==="

reset_all_toxics
seed_account "chaos3-a" 10000
seed_account "chaos3-b" 0

echo "Adding a 3s latency toxic on the RabbitMQ proxy to widen the crash window..."
add_toxic "rabbitmq-proxy" "delay-consume" "latency" '{"latency": 3000}'

IDEM_KEY="chaos3-$(date +%s)"
RESPONSE=$(post_transaction "chaos3-a" "chaos3-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Killing transaction-processor at a 1s offset (mid-flight relative to the delayed publish)..."
sleep 1
docker compose kill -s SIGKILL transaction-processor

remove_toxic "rabbitmq-proxy" "delay-consume"

echo "Restarting transaction-processor..."
docker compose up -d transaction-processor
echo "Waiting for transaction-processor to become healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

wait_for_processed_status "$TXN_ID" "CONSUMED" 90 || fail "event never reached CONSUMED after Processor crash+restart"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 3"

pass "Scenario 3: event reached CONSUMED exactly once despite a mid-flight Processor crash"
