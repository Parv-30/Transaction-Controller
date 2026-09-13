#!/usr/bin/env bash
# chaos/scenarios/06_fx_saga_crash_mid_leg.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 6: FX saga crash mid-leg (ledger-service restart during cross-currency transfer) ==="

# Timing-mechanism note: the cross-currency saga (CrossCurrencyTransferService#transfer) posts
# leg 1 and leg 2 synchronously inside a single HTTP request-handling thread, with no external
# I/O gap between them under normal conditions -- the whole saga (two currency lookups, a
# quote-lock call to fx-service, the initial PendingFxTransfer insert, then leg 1 and leg 2)
# completes in well under a second, which is far too narrow a window to reliably land a
# "docker compose restart" (container stop + recreate, itself taking a couple of seconds)
# inside it. This scenario widens the window with a small `latency` toxic on ledger-service's
# own DB connection (ledger-postgres-app-proxy) -- the same technique scenario 3 uses on the
# RabbitMQ proxy to widen its own crash window -- rather than trying to catch one specific
# leg boundary with a precisely-timed toxic+restart combination on separate connections;
# adding latency to every one of the saga's several DB round trips stretches the whole
# request without changing its logic, giving `docker compose restart` a much larger window
# to land somewhere inside it.
#
# The exact toxic latency (300ms) and restart offset (1.1s) below were tuned empirically
# against this stack: smaller values let the (still synchronous, still fast) request finish
# and receive its response before the restart could take effect; larger values caused the
# request to fail before the initial PendingFxTransfer row was even inserted (a legitimate outcome
# in its own right -- handled below as the NO_ROW case -- but not the "mid-saga" crash this
# scenario is specifically trying to exercise). At these tuned values the crash was confirmed,
# across repeated runs, to reliably interrupt the saga after the row is inserted (status
# PENDING) but before leg 1 posts -- exactly the case FxTransferRecoverySweep's PENDING branch
# exists to recover. Because exact timing against a real process kill is inherently a little
# sensitive to host load, this scenario still treats COMPLETED, COMPENSATED, and NO_ROW as all
# valid/handled resolutions (see below) rather than hard-failing if a given run's crash lands a
# few hundred milliseconds earlier or later than the common case -- what's actually asserted is
# that whichever state the crash left the row in, the platform resolves it to a safe, consistent
# outcome with no partial/imbalanced money movement.

reset_all_toxics

SOURCE_REF="chaos6-usd-src"
DEST_REF="chaos6-eur-dest"
echo "Seeding a funded USD source account and an empty EUR destination account..."
seed_account_with_currency "$SOURCE_REF" 100000 "USD"
seed_account_with_currency "$DEST_REF" 0 "EUR"
BALANCE_SRC_BEFORE=$(get_account_balance "$SOURCE_REF")

echo "Adding a 300ms latency toxic on ledger-service's own DB connection to widen the saga's"
echo "window -- without this, the whole synchronous saga (lock quote, post leg 1, post leg 2,"
echo "all in one request thread) completes in well under a second end-to-end, which is too"
echo "narrow to reliably land a 'docker compose restart' (container stop + recreate) inside it."
echo "(Timing was tuned empirically against this stack: a 300ms per-DB-round-trip toxic with a"
echo "1.1s restart offset was confirmed, across repeated runs, to reliably interrupt the saga"
echo "after the initial PendingFxTransfer row is inserted but before leg 1 posts -- landing the"
echo "row in PENDING status for the recovery sweep to pick up, rather than either completing"
echo "before the restart or crashing before any row exists at all.)"
add_toxic "ledger-postgres-app-proxy" "widen-saga-window" "latency" '{"latency": 300}'

IDEM_KEY="chaos6-$(date +%s)"
echo "Firing POST /transfers/cross-currency in the background (transferring 1000 minor USD)..."
RESPONSE_FILE="$(mktemp)"
( post_cross_currency_transfer "$SOURCE_REF" "$DEST_REF" 1000 "$IDEM_KEY" > "$RESPONSE_FILE" 2>&1 ) &
TRANSFER_PID=$!

echo "Restarting ledger-service at a 1.1s offset -- inside the now-widened saga window..."
sleep 1.1
docker compose restart ledger-service

echo "Removing the DB latency toxic so the recovery sweep isn't slowed down after restart..."
remove_toxic "ledger-postgres-app-proxy" "widen-saga-window"

# The in-flight background request will very likely fail (connection reset / no response) once
# ledger-service goes down mid-request -- that is expected and not itself a failure condition
# for this scenario. Reap the background job without letting `set -e` kill the script on its
# non-zero exit.
set +e
wait "$TRANSFER_PID"
set -e
echo "Backgrounded transfer request finished (its own success/failure is not asserted -- only"
echo "the final row state and account balances matter). Raw result: $(cat "$RESPONSE_FILE")"
rm -f "$RESPONSE_FILE"

echo "Waiting for ledger-service to become healthy again..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Polling pending_fx_transfers until the recovery sweep drives it to a terminal status (or"
echo "confirming no row was ever persisted, if the crash landed before the saga's first write)..."
echo "(sweep interval defaults to 15s, stuck-threshold defaults to 30s -- allow ample time)"
FINAL_STATUS=$(wait_for_fx_transfer_terminal_status "$IDEM_KEY" 120) \
  || fail "pending_fx_transfer for $IDEM_KEY never reached a resolved outcome after ledger-service crash+restart (last status: $FINAL_STATUS)"

echo "Resolved outcome: $FINAL_STATUS"
[ "$FINAL_STATUS" = "COMPLETED" ] || [ "$FINAL_STATUS" = "COMPENSATED" ] || [ "$FINAL_STATUS" = "NO_ROW" ] \
  || fail "expected COMPLETED, COMPENSATED, or NO_ROW, got $FINAL_STATUS"

if [ "$FINAL_STATUS" = "NO_ROW" ]; then
  echo "No pending_fx_transfer row was ever persisted -- the crash landed before the saga wrote"
  echo "anything (e.g. during quote-lock or before the initial insert committed). Verifying no"
  echo "partial balance effect occurred, then retrying the identical request (same idempotency"
  echo "key) to confirm the client-driven retry path -- mirroring scenario 5's retry-after-"
  echo "failure pattern -- still completes the transfer correctly."
  BALANCE_SRC_UNTOUCHED=$(get_account_balance "$SOURCE_REF")
  BALANCE_DEST_UNTOUCHED=$(get_account_balance "$DEST_REF")
  [ "$BALANCE_SRC_UNTOUCHED" = "$BALANCE_SRC_BEFORE" ] \
    || fail "no row was persisted, but source balance changed anyway ($BALANCE_SRC_UNTOUCHED != $BALANCE_SRC_BEFORE) -- partial effect with no record of it"
  [ "$BALANCE_DEST_UNTOUCHED" = "0" ] \
    || fail "no row was persisted, but destination balance changed anyway ($BALANCE_DEST_UNTOUCHED != 0) -- partial effect with no record of it"

  RETRY_RESPONSE=$(post_cross_currency_transfer "$SOURCE_REF" "$DEST_REF" 1000 "$IDEM_KEY")
  RETRY_HTTP_CODE=$(echo "$RETRY_RESPONSE" | tail -n1)
  RETRY_BODY=$(echo "$RETRY_RESPONSE" | head -n-1)
  echo "  retry -> ($RETRY_HTTP_CODE) $RETRY_BODY"
  [ "$RETRY_HTTP_CODE" = "200" ] || fail "retry after NO_ROW outcome expected HTTP 200, got $RETRY_HTTP_CODE: $RETRY_BODY"
  echo "$RETRY_BODY" | grep -q '"status":"COMPLETED"' \
    || fail "retry after NO_ROW outcome expected status COMPLETED, got $RETRY_BODY"
  FINAL_STATUS="COMPLETED"
fi

echo "Verifying account balances are internally consistent with the final saga outcome..."
BALANCE_SRC_AFTER=$(get_account_balance "$SOURCE_REF")
BALANCE_DEST_AFTER=$(get_account_balance "$DEST_REF")

if [ "$FINAL_STATUS" = "COMPLETED" ]; then
  # Source debited exactly once; destination credited exactly once.
  [ "$BALANCE_SRC_AFTER" = "$((BALANCE_SRC_BEFORE - 1000))" ] \
    || fail "COMPLETED transfer but source balance is $BALANCE_SRC_AFTER, expected $((BALANCE_SRC_BEFORE - 1000)) (before=$BALANCE_SRC_BEFORE)"
  [ "$BALANCE_DEST_AFTER" != "0" ] \
    || fail "COMPLETED transfer but destination balance is still 0 -- leg 2 never actually credited it"
else
  # COMPENSATED: source's debit was fully reversed (net zero change), destination never credited.
  [ "$BALANCE_SRC_AFTER" = "$BALANCE_SRC_BEFORE" ] \
    || fail "COMPENSATED transfer but source balance is $BALANCE_SRC_AFTER, expected fully-reversed $BALANCE_SRC_BEFORE"
  [ "$BALANCE_DEST_AFTER" = "0" ] \
    || fail "COMPENSATED transfer but destination balance is $BALANCE_DEST_AFTER, expected 0 (never credited)"
fi

assert_reconciliation_clean || fail "reconciliation found issues after scenario 6"

pass "Scenario 6: FX saga survived a ledger-service crash mid-transfer, resolving to $FINAL_STATUS with consistent balances"
