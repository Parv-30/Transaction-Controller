# Ledger Hardening: Hold-Aware Atomicity, Quote Expiry, Observability — Design Spec

## Context

V1, V2, and V3 are complete, merged, and pushed. Before starting V5 (Gateway Simulator), three
targeted improvements close real, previously-documented gaps and add operational visibility the
platform has lacked since V1:

1. **Hold/transaction atomicity** — V2's accepted overdraw gap (a direct `POST /transactions`
   bypassing Holds Service can overdraw an account with active holds) gets closed by making
   `TransactionPoster` respect holds directly, rather than relying on client convention.
2. **FX quote expiry enforcement** — a gap explicitly flagged during V3's Task 9 review
   (`FxQuote.expiresAt`/`stale` exist but are never checked) gets fixed.
3. **Observability** — Prometheus + Grafana, with 7 metrics spanning correctness signals
   (reconciliation mismatches, saga compensations) and operational signals (latency, failures,
   replays, redeliveries) across all 5 services.

These are independent of each other and independent of V5; this spec covers all three since
they're small enough individually to share one implementation plan.

---

## 1. Hold-Aware Transaction Atomicity

### Problem

`TransactionPoster.postInTransaction` (Ledger Service) checks
`debitAccount.getBalanceMinor() < request.amountMinor()` — the account's *posted* balance only.
It has no way to know an account has funds held against it by Holds Service, so a direct
`POST /transactions` call can debit an account below its truly available balance even while a
hold is active. This was accepted as a known limitation in V2's design (clients are expected to
route spend-checks through Holds Service's `available-balance` endpoint "by convention," which
nothing enforces).

### Design

`TransactionPoster.postInTransaction` gains a synchronous, in-transaction call to a **new**
Holds Service endpoint, `GET /accounts/{accountRef}/held-balance`, returning just
`{accountRef, heldBalanceMinor}`. This is deliberately narrower than the existing
`GET /accounts/{accountRef}/available-balance` endpoint (used by clients for pre-transaction
spend-checks, kept unchanged) — the existing endpoint's `postedBalanceMinor` comes from Holds
Service's own asynchronously-synced cache, which can lag Ledger Service's live, just-locked
value inside a transaction. The held-balance-only endpoint avoids ever trusting a
potentially-stale posted balance for this check.

**Unknown-account behavior**: `GET /accounts/{accountRef}/held-balance` returns
`heldBalanceMinor: 0` (HTTP 200) for an account with no `AccountBalanceCache` row in Holds
Service — matching the exact convention already established by the existing
`available-balance` endpoint (an account never touched by Holds Service has legitimately held
nothing, not an error state).

**Where the check happens**, replacing the existing insufficient-funds check in
`TransactionPoster.postInTransaction`:

1. Idempotency pre-check (unchanged).
2. Resolve both accounts by ref (unchanged).
3. Lock both accounts via ordered `SELECT ... FOR UPDATE` (unchanged).
4. Status check — both `ACTIVE` (unchanged).
5. **New**: for the *debit* account only (a credit account is never at risk of overdraw), unless
   its ref starts with `fx-clearing-` (clearing accounts have no holds concept and are already
   exempt from the funds check entirely — see V3's `TransactionPoster.FX_CLEARING_ACCOUNT_REF_PREFIX`
   bypass), call a new `HoldsServiceClient.getHeldBalance(accountRef): long` on Ledger Service.
6. Compute `available = debitAccount.getBalanceMinor() - heldBalanceMinor`. If
   `request.amountMinor() > available`, throw the existing `InsufficientFundsException`
   (unchanged exception type and HTTP mapping — from the caller's perspective, "insufficient
   funds because of a hold" and "insufficient posted funds" are indistinguishable, matching how
   the rest of the platform already treats available-vs-posted).
7. Continue exactly as today (insert transaction + entries, update balances, outbox write).

**Failure mode**: if the call to Holds Service fails, times out, or the response can't be
parsed, `TransactionPoster` throws a new `HoldsServiceUnavailableException`, mapped to
`503 Service Unavailable` in `ApiExceptionHandler`. The whole DB transaction rolls back — no
partial state, matching this codebase's existing all-or-nothing discipline. This is a
**deliberate new coupling**: Ledger Service's write-path availability now depends on Holds
Service's availability. This is an intentional correctness-over-availability tradeoff, not a
regression, and must be documented explicitly in the README's "what this demonstrates" and
"known limitations" sections.

**Timeout**: the HTTP client to Holds Service (`HoldsServiceClient`, following the same
`RestClient`-based pattern as `LedgerTransactionClient`/`FxServiceClient`) uses an explicit,
short connect+read timeout — 2 seconds, configurable via
`holds.base-url`/`holds.held-balance-timeout-ms` — since the call happens while holding a
Postgres row lock on both accounts. A slow Holds Service directly extends lock hold time,
increasing contention risk under load; this tradeoff is also called out in the README.

**Interaction with V3's saga**: `CrossCurrencyTransferPoster.postLeg1`/`postLeg2` call
`TransactionService.postTransaction` internally (an in-process Java call, not HTTP), so they
automatically inherit this held-balance check for free — no code change needed there, but the
implementation plan must include a test proving a hold on the saga's source account correctly
blocks leg 1 from overdrawing it.

**No change** to Holds Service's own `POST /holds` creation path — it already does its own
atomic available-balance check inside its own transaction, using its own cache; unaffected by
this change.

---

## 2. FX Quote Expiry Enforcement

### Problem

`FxQuote` (Ledger Service's DTO for a locked quote) carries `expiresAt` and `stale`, but
`CrossCurrencyTransferService.transfer()` and `CrossCurrencyTransferPoster` never read either
field. A quote locked at saga-creation time is used to compute `destAmountMinor` once and that
rate is trusted indefinitely — even if leg 2 doesn't actually post until long after the quote's
60-second validity window, e.g. because the crash-recovery sweep picks up a stuck
`LEG1_POSTED` row well after the quote expired.

### Design

**Schema change**: add `expires_at TIMESTAMPTZ NOT NULL` to `pending_fx_transfers` (a new
migration, `V6__add_expires_at_to_pending_fx_transfers.sql` in `ledger-service`, since V1-V5 are
already applied). `CrossCurrencyTransferService.transfer()` sets this from the locked quote's
`expiresAt` when creating the `PendingFxTransfer` row — captured once, alongside `rateUsed`,
never re-fetched.

**Enforcement point**: `CrossCurrencyTransferPoster.postLeg2` checks
`Instant.now().isAfter(transfer.getExpiresAt())` before calling
`transactionService.postTransaction(...)` for leg 2. If expired, it throws a new
`FxQuoteExpiredException` instead of attempting to post. This flows into the **existing**
catch-and-compensate logic in both `CrossCurrencyTransferService.transfer()`'s try/catch and
`FxTransferRecoverySweep`'s `LEG1_POSTED` handling (both already catch a generic `Exception`
from `postLeg2` and call `compensate()`) — no new control-flow branch needed, just a new,
specific failure reason that's already handled correctly by construction. The failure message
(already threaded through to `PendingFxTransferResponse.errorMessage` per V3's Task 9 fix)
should be a real diagnostic ("FX quote expired before leg 2 could be posted"), not a generic
one.

**No quote-identity re-verification needed**: `PendingFxTransfer.rateUsed` is set once at
creation and never re-read from FX Service afterward — there is no code path where a different
quote's rate could be silently substituted. The expiry check is the complete fix; no additional
call back to FX Service to confirm `quoteId` is needed.

---

## 3. Observability

### Design

**Libraries**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus` added to all 5
services (already present in `ledger-service`/`transaction-processor`; newly added to
`holds-service`, `api-gateway`, `fx-service`). Each service exposes `/actuator/prometheus`.

**Infrastructure**: two new Docker Compose services — `prometheus` (scrapes all 5 app services'
`/actuator/prometheus` endpoints every ~15s, config via a committed `prometheus.yml`) and
`grafana` (pre-provisioned with a Prometheus datasource and one dashboard JSON, committed to the
repo, so `docker compose up` yields a working, populated dashboard without manual setup).
Container count grows from 12 to 14.

**The 7 metrics**, all additive instrumentation (no existing method signature or behavior
changes):

| Metric | Type | Instrumentation point |
|---|---|---|
| Transaction latency | `Timer` | Wraps `TransactionPoster.postInTransaction` |
| Failed transactions | `Counter`, tagged by exception class | Incremented in `TransactionService`'s existing catch paths (insufficient funds, account not active, idempotency conflict, and the new `HoldsServiceUnavailableException`) |
| Idempotency replays | `Counter` | Incremented wherever `replay=true` is already computed — `TransactionResponse`, `HoldResponse`, `PendingFxTransferResponse` all already carry this boolean |
| RabbitMQ redeliveries | `Counter` | `OutboxEventConsumer` (Transaction Processor) and `LedgerTransactionPostedConsumer` (Holds Service) — incremented when their dedup gate reports "already processed" |
| Reconciliation mismatches | `Gauge`, set per scheduled run | `ReconciliationService` — set from the already-computed `entriesImbalanceCount`/`outboxMissingCount`/`outboxStuckCount` after each run |
| Saga compensation count | `Counter` | `CrossCurrencyTransferPoster.compensate()` — incremented on entry, tagged by whether it's a fresh compensation or a sweep-driven replay |
| FX quote failures | `Counter` | `RateService.lockQuote`'s `RateNotAvailableException` path (FX Service side) and `FxServiceClient` call failures (Ledger Service side, e.g. FX Service unreachable) |

**Naming convention**: metrics follow Micrometer's dot-separated convention scoped by domain,
e.g. `ledger.transaction.latency`, `ledger.transaction.failed`, `ledger.idempotency.replay`,
`processor.rabbitmq.redelivery`, `ledger.reconciliation.mismatch`, `ledger.fx.compensation`,
`fx.quote.failure` — each tagged with relevant dimensions (exception type, service name) where
Prometheus/Grafana benefit from filtering.

---

## Explicitly Out of Scope

- Distributed tracing (correlation IDs across services) — a real, separately-scoped gap, not
  addressed here.
- Alerting rules (Prometheus Alertmanager) — dashboards only, no paging/alerting configured.
- Historical metrics retention/backup — default Prometheus retention is fine for a demo project.
- Any change to Holds Service's existing `available-balance` endpoint or its client-facing
  contract — only a new, additive endpoint is introduced.
- V4 (Fees Service) and V5 (Gateway Simulator) — V5 follows this work; V4 is deferred
  indefinitely per explicit decision.
