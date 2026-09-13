#!/usr/bin/env bash
# D:\Ledger\scripts\seed-clearing-accounts.sh
#
# Verifies the platform-owned FX clearing accounts exist before any cross-currency transfer
# is attempted.
#
# These accounts are NOT created here via POST /accounts: AccountService deliberately rejects
# any accountRef under the "fx-clearing-" prefix (ReservedAccountRefException, added during
# Task 9's review) -- unconditionally, before it even checks whether the account already
# exists -- because TransactionPoster grants that prefix an unlimited-overdraft privilege.
# Allowing it to be created through the ordinary customer-facing endpoint would let any
# authenticated caller mint money by debiting a self-created clearing account without limit.
# (This also means POST /accounts cannot be used to *verify* the accounts either: it always
# returns 400 for this prefix, never 409, regardless of whether the row exists.)
#
# Instead, the three clearing accounts (fx-clearing-USD, fx-clearing-EUR, fx-clearing-GBP) are
# seeded as baseline platform data by ledger-service's own Flyway migration
# (V4__seed_fx_clearing_accounts.sql), which runs automatically against ledger-postgres when
# ledger-service starts. This script confirms that migration has run and the rows are present
# by querying ledger-postgres directly (the same pattern scripts/smoke-test.sh already uses to
# seed/verify accounts), run at the same point in provisioning where other one-time platform
# setup (e.g. the CDC grant/publication) is confirmed.
#
# Idempotent: safe to re-run any number of times; it only reads.
set -euo pipefail

echo "Verifying FX clearing accounts were seeded by ledger-service's Flyway migration..."
COUNT=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -A -c \
  "SELECT count(*) FROM accounts WHERE account_ref IN ('fx-clearing-USD','fx-clearing-EUR','fx-clearing-GBP');")
COUNT=$(echo "$COUNT" | tr -d '[:space:]')

if [ "$COUNT" != "3" ]; then
  echo "  ERROR: expected 3 FX clearing accounts, found $COUNT. This means" >&2
  echo "  V4__seed_fx_clearing_accounts.sql has not run (yet) against ledger-postgres." >&2
  echo "  Cross-currency transfers will fail with AccountNotFoundException until this" >&2
  echo "  is fixed -- check that ledger-service is up and its Flyway migration succeeded." >&2
  exit 1
fi

echo "  all 3 clearing accounts present (fx-clearing-USD, fx-clearing-EUR, fx-clearing-GBP)."
echo "Clearing accounts verified present."
