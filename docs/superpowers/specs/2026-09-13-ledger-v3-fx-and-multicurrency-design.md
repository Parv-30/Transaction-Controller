# Ledger V3 — FX Service + Multi-Currency Accounts: Design Spec

## Context

V1 (Ledger Service + Transaction Processor) and V2 (Holds Service + API Gateway + Keycloak
auth) are complete, merged, and pushed. V3 adds cross-currency transfers: a new FX Service
providing exchange rates, and an extension to Ledger Service allowing accounts in different
currencies to be linked into a "wallet" and transferred between.

This spec refines and, in a few places, deliberately deviates from the V3 section of the
original platform architecture doc
(`docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md`). Deviations are called
out explicitly below with rationale — they are documented decisions, not omissions.

## Architecture & Boundaries

**FX Service** (new module, port `8083`, own `fx_db`): owns exchange rate data and quote-locking only. It
never touches funds, never posts transactions, never owns accounts. It syncs rates from
**Frankfurter** (`https://api.frankfurter.dev` or `.app` — free, no API key required,
ECB-backed reference rates, updated once daily by the provider) on an hourly `@Scheduled` job,
caching results in `fx_rates`. Serves `GET /rates/{base}/{quote}` and
`POST /conversions/quote` from that cache, including a `stale: true` flag when Frankfurter is
unreachable and the cache has aged past the sync interval.

**Ledger Service** gains multi-currency account support (schema migration, same service) and
owns the entire cross-currency transfer saga.

**Deviation from the original spec**: the original architecture doc assigned saga
orchestration (quote-lock → leg 1 → leg 2 → compensate) to Transaction Processor. This spec
instead puts it on Ledger Service, as a new `POST /transfers/cross-currency` endpoint.
Rationale:
- Ledger Service already owns `TransactionService`/`TransactionPoster` and the
  accounts/transactions/entries tables the saga must stay consistent with. Posting both legs
  is two internal calls rather than a new HTTP round-trip back into itself.
- Transaction Processor has been pure infrastructure since V1 — CDC relay, dedup, publish
  retry — with zero business/domain logic. Giving it saga orchestration would be a real shift
  in that service's character.
- Co-locating saga state (`pending_fx_transfers`) in the same DB as the transactions it
  tracks avoids a new class of cross-service drift bug — the same category of bug V2's final
  whole-branch review caught when Holds Service was wired to the wrong backend service for
  posting transactions.

Transaction Processor is untouched by V3.

## Data Model

### FX Service (`fx_db`)

```sql
CREATE TABLE fx_rates (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate           NUMERIC(18,8) NOT NULL,
    source         VARCHAR(32) NOT NULL DEFAULT 'frankfurter',
    fetched_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (base_currency, quote_currency, fetched_at)
);
CREATE INDEX idx_fx_rates_pair_fetched ON fx_rates(base_currency, quote_currency, fetched_at DESC);
-- Append-only history. The "current" rate for a pair is the row with the latest fetched_at.
-- No separate mutable "latest" table — this gives GET /rates/{base}/{quote}?at=<ts> for free
-- and keeps the sync job a plain insert, never an upsert-with-conflict-resolution.

CREATE TABLE fx_quotes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate_used      NUMERIC(18,8) NOT NULL,
    locked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,      -- locked_at + 60s
    consumed_at    TIMESTAMPTZ                 -- set when a caller reports using it (audit only)
);
```

A quote is a real, persisted row — not a signed/ephemeral token — so `POST /conversions/quote`
is itself auditable and idempotent-safe (a caller can always look up what a given `quoteId`
resolved to).

### Ledger Service (extends existing `ledger_db`)

```sql
ALTER TABLE accounts ADD COLUMN account_group_id UUID; -- nullable; ungrouped accounts stay valid
CREATE INDEX idx_accounts_group_id ON accounts(account_group_id);

CREATE TABLE pending_fx_transfers (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key          VARCHAR(255) NOT NULL UNIQUE,
    quote_id                 UUID NOT NULL,        -- fx_quotes.id, from FX Service
    source_account_ref       VARCHAR(128) NOT NULL,
    dest_account_ref         VARCHAR(128) NOT NULL,
    source_amount_minor      BIGINT NOT NULL,
    rate_used                NUMERIC(18,8) NOT NULL,
    dest_amount_minor        BIGINT NOT NULL,      -- fixed at creation time; never recomputed
    status                   VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING','LEG1_POSTED','LEG2_POSTED',
                                                  'COMPLETED','COMPENSATING','COMPENSATED','FAILED')),
    leg1_transaction_id      UUID,
    leg2_transaction_id      UUID,
    compensation_transaction_id UUID,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_fx_transfers_status ON pending_fx_transfers(status);
```

Two clearing accounts (one per actively-traded currency, e.g. `fx-clearing-USD`,
`fx-clearing-EUR`) are seeded as ordinary `accounts` rows — no schema change needed for them;
they are platform-owned accounts, not a new concept.

**Deliberate denormalization**: `rate_used` is stored on both `fx_quotes` and
`pending_fx_transfers`. This is intentional — Ledger Service should not need to call back to
FX Service to know what rate it already locked for an in-flight or completed saga.

## The Saga: Two Legs + Compensation

`POST /transfers/cross-currency` — synchronous end-to-end (the client's HTTP request blocks
through quote-lock, both legs, and compensation if needed, before responding). This matches
V1's existing `POST /transactions` style (synchronous, atomic-or-fails, return-when-done)
rather than introducing a new polling/webhook pattern. It is a real tradeoff: this one
endpoint is noticeably slower and more failure-exposed than any other in the platform,
accepted deliberately rather than left implicit.

### Steps

1. **Quote-lock**: call FX Service's `POST /conversions/quote` with
   `{baseCurrency, quoteCurrency, amountMinor}`. Receive `{quoteId, rateUsed, expiresAt}`.
2. **Create the saga row**: insert `pending_fx_transfers` with `status = PENDING`,
   `dest_amount_minor` computed from `rate_used` **once, permanently** — this amount is never
   recomputed later in the saga, even if `expires_at` passes before leg 2 posts. The quote's
   `expires_at` is informational/audit only (did the rate look fresh when locked), never a
   re-check gate later in the flow.
3. **Post leg 1**: debit source account, credit the source-currency clearing account, via
   Ledger's existing `POST /transactions`-equivalent internal call, with idempotency key
   `fx-leg1-{pendingTransferId}`. On success, update `status = LEG1_POSTED`,
   `leg1_transaction_id = <id>`.
4. **Post leg 2**: debit the dest-currency clearing account, credit dest account, idempotency
   key `fx-leg2-{pendingTransferId}`, using the amount fixed in step 2. On success, update
   `status = COMPLETED`, `leg2_transaction_id = <id>`.
5. **On leg 2 failure**: transition `status = COMPENSATING`, post a reversal — debit the
   source-currency clearing account, credit the original source account — idempotency key
   `fx-compensate-{pendingTransferId}`. On success, `status = COMPENSATED`,
   `compensation_transaction_id = <id>`.

**Every step's write to Ledger Service uses a deterministic idempotency key derived from
`pendingTransferId` and its step name.** This is load-bearing, not optional: it is what makes
every step safely re-executable by the crash-recovery sweep below without risk of double
posting or double compensating. (An earlier draft of this spec gave only the compensation
step a key; that gap would have let the sweep double-credit a destination account on a
crash between leg 2 posting and the status update recording it. Fixed before implementation.)

### Crash-recovery sweep

A `@Scheduled` job on Ledger Service (same pattern as V1's `PublishRetryService` and V2's
`HoldExpirySweep` — a periodic self-healing sweep, not a new architectural concept) scans
`pending_fx_transfers` for rows stuck past a timeout:

- `PENDING` or `LEG1_POSTED` past ~30s → retry the next step (leg 1 or leg 2 respectively).
  Safe to retry unconditionally because of the deterministic idempotency keys above.
- `COMPENSATING` past a shorter interval (e.g. 10s) → retry the compensation post.

**Explicit non-goal**: if compensation itself fails repeatedly (e.g. the source account was
frozen between leg 1 and the reversal attempt), the row stays `COMPENSATING` and the sweep
keeps retrying it indefinitely. There is no dead-letter queue or manual-intervention path in
V3 — this is a documented accepted gap, in the same style as V1's single-instance-processor
limitation and V2's overdraw gap, not a silently swallowed failure mode.

## API Surface

**FX Service:**
- `GET /rates/{base}/{quote}` — latest cached rate; `?at=<timestamp>` for a historical lookup.
  Response includes `stale: boolean` and `fetchedAt`. 404 if the pair has never been synced.
- `POST /conversions/quote` — body `{baseCurrency, quoteCurrency, amountMinor}` → creates and
  returns an `fx_quotes` row (`quoteId`, `rateUsed`, `expiresAt`, `stale`). 422 if no rate
  exists for the pair at all.

(`POST /conversions` was considered for recording completed conversions but cut —
`pending_fx_transfers` on Ledger Service is the single source of truth for a completed
conversion; a duplicate record in FX Service's DB would be redundant data to keep in sync
with no consumer.)

**Ledger Service (new):**
- `POST /accounts` — body `{accountRef, currency, accountGroupId?}`. If `accountGroupId` is
  omitted, one is generated (a lone account is a wallet-of-one). Returns the created account.
- `GET /wallets/{groupId}/accounts` — lists accounts sharing that group ID. Returns an empty
  list (200) for an unknown/empty group ID — consistent with how V2's `available-balance`
  endpoint treats an unknown account as zero-state rather than erroring, not a 404.
- `POST /transfers/cross-currency` — body
  `{sourceAccountRef, destAccountRef, sourceAmountMinor, idempotencyKey}`. Runs the saga above
  synchronously; returns the final `pending_fx_transfers` state (`COMPLETED`, or on failure
  `COMPENSATED` with an explanatory error). A retry with the same `idempotencyKey` returns the
  existing row's current state rather than re-running the saga (DB unique constraint on
  `idempotency_key`, matching V1's transaction idempotency pattern — never an
  application-level read-then-write check).

**Gateway routes**: `/rates/**` and `/conversions/**` → FX Service; `/accounts/**`,
`/wallets/**`, `/transfers/**` join the existing `/transactions/**` routing to Ledger Service.

## Error Handling & Correctness

- **Stale/missing rate**: `422` if the pair has never synced at all; a merely-aging cache
  still serves its last-known-good rate with `stale: true` rather than erroring — the caller
  decides whether to trust it. The saga does not reject stale quotes; the flag is carried
  through into the saga's audit trail for visibility.
- **Rate-changed-mid-transaction**: solved by the quote-lock. `fx_quotes.rate_used` is
  immutable once written, and `pending_fx_transfers.dest_amount_minor` is fixed once at saga
  creation — never re-fetched or recomputed, even past `expires_at`.
- **Two-legged non-atomicity**: explicitly accepted as eventual-consistency-with-compensation,
  not attempted as a distributed transaction — consistent with the project's existing style of
  documenting real gaps rather than claiming false atomicity (V1's single-instance-processor
  limitation, V2's overdraw gap).
- **Idempotency**: every externally-triggered write (`POST /transfers/cross-currency`) and
  every internal saga step (leg 1, leg 2, compensation) has a deterministic key backed by a DB
  unique constraint — never an application-level check.

## Testing Strategy

- **FX Service**: unit tests for quote-locking (pure function: rate + amount → quote).
  Testcontainers integration tests for the Frankfurter sync job against a WireMock stub (not
  the real API — keeps tests offline and deterministic), covering both a successful sync and
  a simulated provider outage (asserting the staleness flag surfaces correctly).
- **Ledger Service saga**: Testcontainers integration tests against a stubbed FX Service
  (same pattern as V2's `HoldCaptureIntegrationTest` stubbing Ledger Service) covering: happy
  path (`COMPLETED`), leg-2-failure triggers compensation (`COMPENSATED`), and a crash-recovery
  test that manually stalls a row in `LEG1_POSTED` and asserts the sweep resolves it correctly
  without double-posting (verified via account balance assertions, not just status field).
- **New 6th chaos scenario**: kill the process (or sever the FX Service connection) between
  leg 1 and leg 2 posting; verify the sweep drives the saga to completion or compensation on
  restart, and that reconciliation shows no orphaned clearing-account balance afterward.

## Explicitly Out of Scope for V3

- Any wallet API beyond listing accounts by group (no wallet-level balance aggregation across
  currencies, no wallet-level transaction history).
- Manual-intervention/dead-letter handling for a saga stuck in `COMPENSATING` indefinitely.
- Any currency pair Frankfurter doesn't support (Frankfurter covers major/ECB-tracked
  currencies; an unsupported pair simply never gets an `fx_rates` row and returns 422).
- Rate margin/spread (the platform uses Frankfurter's mid-market rate as-is; no fee is added
  at the FX layer — fee application is V4's job, if pursued, applied as an ordinary
  transaction like any other fee).
