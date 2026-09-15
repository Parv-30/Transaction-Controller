#!/usr/bin/env bash
# chaos/lib/common.sh
# Sourced by every scenario script. Provides: toxic injection/removal, account
# seeding, transaction posting, and polling helpers against the running compose stack.
#
# Note: this deliberately does NOT create the Toxiproxy proxies themselves (that's
# scripts/provision.sh's job -- run automatically by `make up`, or once by hand against a
# freshly-up stack -- per the Task 9 lessons; CDC provisioning, including the
# ledger_outbox_pub publication and the debezium_replicator GRANT, is also only done there).
# Scenarios assume the stack is already up and provisioned; they only add/remove toxics on
# the already-existing proxies and reset them to a clean slate at the start of each run.

TOXIPROXY_API="${TOXIPROXY_API:-http://localhost:8474}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8080}"
PROCESSOR_URL="${PROCESSOR_URL:-http://localhost:8081}"
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://localhost:8180/realms/ledger/protocol/openid-connect/token}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"

get_chaos_suite_token() {
  curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
    -d "grant_type=client_credentials" \
    -d "client_id=chaos-suite-client" \
    -d "client_secret=chaos-suite-secret" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
}

add_toxic() {
  local proxy_name="$1"
  local toxic_name="$2"
  local toxic_type="$3"
  local attributes_json="$4"
  curl -sf -X POST "$TOXIPROXY_API/proxies/$proxy_name/toxics" -d "{
    \"name\": \"$toxic_name\",
    \"type\": \"$toxic_type\",
    \"attributes\": $attributes_json
  }" > /dev/null
}

remove_toxic() {
  local proxy_name="$1"
  local toxic_name="$2"
  curl -sf -X DELETE "$TOXIPROXY_API/proxies/$proxy_name/toxics/$toxic_name" > /dev/null || true
}

reset_all_toxics() {
  for proxy in ledger-postgres-app-proxy ledger-postgres-cdc-proxy rabbitmq-proxy gateway-sim-rabbitmq; do
    for toxic in $(curl -sf "$TOXIPROXY_API/proxies/$proxy/toxics" | grep -o '"name":"[^"]*"' | cut -d'"' -f4); do
      remove_toxic "$proxy" "$toxic"
    done
  done
}

seed_account() {
  local account_ref="$1"
  local balance_minor="$2"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
    "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), '$account_ref', $balance_minor) ON CONFLICT (account_ref) DO UPDATE SET balance_minor = $balance_minor;" > /dev/null
}

post_transaction() {
  local debit_ref="$1"
  local credit_ref="$2"
  local amount="$3"
  local idem_key="$4"
  curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/transactions" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $idem_key" \
    -d "{\"debitAccountRef\":\"$debit_ref\",\"creditAccountRef\":\"$credit_ref\",\"amountMinor\":$amount,\"currency\":\"USD\",\"description\":\"chaos test\"}"
}

post_transaction_with_type() {
  local debit_ref="$1"
  local credit_ref="$2"
  local amount="$3"
  local idem_key="$4"
  local transaction_type="$5"
  curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/transactions" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $idem_key" \
    -d "{\"debitAccountRef\":\"$debit_ref\",\"creditAccountRef\":\"$credit_ref\",\"amountMinor\":$amount,\"currency\":\"USD\",\"description\":\"chaos test\",\"transactionType\":\"$transaction_type\"}"
}

seed_account_with_currency() {
  local account_ref="$1"
  local balance_minor="$2"
  local currency="$3"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
    "INSERT INTO accounts (id, account_ref, currency, balance_minor) VALUES (gen_random_uuid(), '$account_ref', '$currency', $balance_minor) ON CONFLICT (account_ref) DO UPDATE SET currency = '$currency', balance_minor = $balance_minor;" > /dev/null
}

post_cross_currency_transfer() {
  local source_ref="$1"
  local dest_ref="$2"
  local amount="$3"
  local idem_key="$4"
  curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/transfers/cross-currency" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" \
    -d "{\"sourceAccountRef\":\"$source_ref\",\"destAccountRef\":\"$dest_ref\",\"sourceAmountMinor\":$amount,\"idempotencyKey\":\"$idem_key\"}"
}

get_pending_fx_transfer_status() {
  local idem_key="$1"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT status FROM pending_fx_transfers WHERE idempotency_key = '$idem_key';" | tr -d ' \r\n'
}

fx_transfer_row_count() {
  local idem_key="$1"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT COUNT(*) FROM pending_fx_transfers WHERE idempotency_key = '$idem_key';" | tr -d ' \r\n'
}

# Polls up to timeout_seconds for either a terminal status (COMPLETED/COMPENSATED) or
# confirmation that no row was ever persisted for this idempotency key at all -- both are valid
# outcomes of a crash landing at different points in the saga (see 06_fx_saga_crash_mid_leg.sh
# for why "no row" is legitimate: a crash before the PendingFxTransfer insert commits leaves
# nothing for the recovery sweep to act on, since nothing was ever recorded as started).
# Echoes one of: COMPLETED, COMPENSATED, NO_ROW. Returns non-zero on timeout with neither.
wait_for_fx_transfer_terminal_status() {
  local idem_key="$1"
  local timeout_seconds="${2:-90}"
  local elapsed=0
  local status=""
  local row_count=""
  while [ "$elapsed" -lt "$timeout_seconds" ]; do
    row_count=$(fx_transfer_row_count "$idem_key")
    if [ "$row_count" = "0" ]; then
      # Give any in-flight insert a little more time to land before concluding "no row" --
      # avoids a false NO_ROW read on the very first poll, right after the crash.
      if [ "$elapsed" -ge 6 ]; then
        echo "NO_ROW"
        return 0
      fi
    else
      status=$(get_pending_fx_transfer_status "$idem_key")
      if [ "$status" = "COMPLETED" ] || [ "$status" = "COMPENSATED" ]; then
        echo "$status"
        return 0
      fi
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  echo "TIMEOUT waiting for pending_fx_transfers terminal status (last saw: $status, row_count=$row_count)" >&2
  echo "$status"
  return 1
}

get_account_balance() {
  local account_ref="$1"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT balance_minor FROM accounts WHERE account_ref = '$account_ref';" | tr -d ' \r\n'
}

transaction_count_for_idem_key() {
  local idem_key="$1"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT COUNT(*) FROM transactions WHERE idempotency_key = '$idem_key';" | tr -d ' \r\n'
}

get_processed_event_status_by_aggregate() {
  local aggregate_id="$1"
  docker compose exec -T processor-postgres psql -U processor -d processor_db -t -c \
    "SELECT status FROM processed_events WHERE aggregate_id = '$aggregate_id';" | tr -d ' \r\n'
}

wait_for_processed_status() {
  local aggregate_id="$1"
  local expected_status="$2"
  local timeout_seconds="${3:-60}"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout_seconds" ]; do
    local status
    status=$(get_processed_event_status_by_aggregate "$aggregate_id")
    if [ "$status" = "$expected_status" ]; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "TIMEOUT waiting for processed_events status=$expected_status (last saw: $status)" >&2
  return 1
}

trigger_reconciliation() {
  curl -sf -X POST "$GATEWAY_URL/reconciliation/runs" \
    -H "Authorization: Bearer $(get_chaos_suite_token)"
}

assert_reconciliation_clean() {
  local result
  result=$(trigger_reconciliation)
  echo "$result"
  if echo "$result" | grep -q '"entriesImbalanceCount":0' && \
     echo "$result" | grep -q '"outboxMissingCount":0' && \
     echo "$result" | grep -q '"outboxStuckCount":0'; then
    return 0
  fi
  echo "RECONCILIATION FOUND ISSUES: $result" >&2
  return 1
}

pass() {
  echo "PASS: $1"
}

fail() {
  echo "FAIL: $1" >&2
  exit 1
}
