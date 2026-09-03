#!/usr/bin/env bash
# chaos/scenarios/05_partition_during_lock.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 5: Network partition mid-transaction, verify rollback + safe retry ==="

reset_all_toxics
seed_account "chaos5-a" 10000
seed_account "chaos5-b" 0
BALANCE_A_BEFORE=$(get_account_balance "chaos5-a")

echo "Adding a 5s latency toxic to guarantee the request is still inside its DB transaction when we cut it..."
add_toxic "ledger-postgres-app-proxy" "delay-then-cut" "latency" '{"latency": 5000}'

IDEM_KEY="chaos5-$(date +%s)"

( sleep 2 && add_toxic "ledger-postgres-app-proxy" "hard-cut" "reset_peer" '{"timeout": 0}' ) &
CUT_PID=$!

set +e
RESPONSE=$(post_transaction "chaos5-a" "chaos5-b" 500 "$IDEM_KEY")
set -e
wait "$CUT_PID"

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
echo "Request during partition returned HTTP $HTTP_CODE (expected a 5xx or connection error, not 201)"
[ "$HTTP_CODE" != "201" ] || fail "expected the partitioned request to fail, but it returned 201"

echo "Restoring connectivity..."
remove_toxic "ledger-postgres-app-proxy" "delay-then-cut"
remove_toxic "ledger-postgres-app-proxy" "hard-cut"

sleep 3

TXN_COUNT=$(transaction_count_for_idem_key "$IDEM_KEY")
[ "$TXN_COUNT" = "0" ] || fail "expected 0 transactions for $IDEM_KEY after rollback, found $TXN_COUNT — torn write occurred"

BALANCE_A_AFTER_FAILURE=$(get_account_balance "chaos5-a")
[ "$BALANCE_A_AFTER_FAILURE" = "$BALANCE_A_BEFORE" ] || fail "balance changed despite the transaction rolling back"

echo "Retrying the exact same request with the same Idempotency-Key..."
RETRY_RESPONSE=$(post_transaction "chaos5-a" "chaos5-b" 500 "$IDEM_KEY")
RETRY_HTTP_CODE=$(echo "$RETRY_RESPONSE" | tail -n1)
[ "$RETRY_HTTP_CODE" = "201" ] || fail "expected retry to succeed with 201, got $RETRY_HTTP_CODE"

BALANCE_A_FINAL=$(get_account_balance "chaos5-a")
[ "$BALANCE_A_FINAL" = "9500" ] || fail "expected exactly one successful transfer (9500), got $BALANCE_A_FINAL"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 5"

pass "Scenario 5: mid-transaction partition rolled back cleanly and retry succeeded exactly once"
