#!/usr/bin/env bash
# chaos/scenarios/02_ledger_db_crash_post_commit.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 2: Ledger DB crash after commit, before Debezium sees it ==="

reset_all_toxics
seed_account "chaos2-a" 10000
seed_account "chaos2-b" 0

echo "Delaying the CDC connection by 5s so the restart lands before Debezium polls..."
add_toxic "ledger-postgres-cdc-proxy" "delay-cdc" "latency" '{"latency": 5000}'

IDEM_KEY="chaos2-$(date +%s)"
RESPONSE=$(post_transaction "chaos2-a" "chaos2-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Hard-restarting ledger-postgres immediately..."
docker compose restart ledger-postgres
echo "Waiting for ledger-postgres to become healthy again..."
for i in $(seq 1 30); do
  if docker compose exec -T ledger-postgres pg_isready -U ledger -d ledger_db > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Removing the CDC latency toxic..."
remove_toxic "ledger-postgres-cdc-proxy" "delay-cdc"

OUTBOX_ROW_COUNT=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT COUNT(*) FROM outbox WHERE aggregate_id = '$TXN_ID';" | tr -d ' \r\n')
[ "$OUTBOX_ROW_COUNT" = "1" ] || fail "outbox row for $TXN_ID missing after DB restart -- WAL durability violated"

wait_for_processed_status "$TXN_ID" "CONSUMED" 90 || fail "event never reached CONSUMED after ledger-postgres restart"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 2"

pass "Scenario 2: outbox row survived source DB restart and was eventually delivered"
