# V5 — Gateway Simulator: Design Spec

## Context

This is the fifth and final planned version of the Ledger platform (V4/Fees Service remains
explicitly and indefinitely skipped, per an earlier scoping decision). V1-V3 built the core
double-entry ledger, transactional-outbox event pipeline, Holds Service, and multi-currency FX
transfers; a subsequent hardening pass closed a hold/transaction atomicity gap, enforced FX quote
expiry, and added Prometheus/Grafana observability across all five existing services.

V5 adds a **Gateway Simulator** — the platform's model of an external payment rail. It is the
only component in the whole system that simulates a real trust boundary and that boundary's
inherent unreliability: duplicate deliveries, out-of-order confirmations, and requests that never
resolve within a reasonable window. Everything else in the platform is internal, synchronous or
async-but-reliable-eventually; this is the first component that has to survive genuinely hostile,
non-cooperating input.

The original platform-wide architecture spec
(`docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md`, section "V5 — Gateway
Simulator") already sketched this component's shape. This document supersedes that sketch with a
fully resolved design — every ambiguity the original left open (exact trigger mechanisms,
clearing-account treatment, timeout values, auth boundary, chaos scope) has been decided below.

## Goals

- Simulate **inbound** deposits: an external party "sends money" that credits a ledger account.
- Simulate **outbound** withdrawals: a ledger account debits, funds are "submitted" to an external
  rail, and an async confirmation or failure arrives later — with automatic compensating reversal
  on failure or timeout.
- Demonstrate correct handling of the specific failure modes a real payment integration must
  survive: duplicate webhook delivery, out-of-order confirmation, and a rail that never calls back.
- Extend the platform's existing correctness-proof mechanism (the Toxiproxy-driven chaos suite) to
  cover these new failure modes, consistent with how V1 and V3 were chaos-tested.

## Non-Goals

- No real external payment rail integration of any kind — everything is simulated in-process.
- No webhook-signature/HMAC verification scheme. A real payment rail's webhook would authenticate
  itself independently of your application's own auth (e.g. a shared secret or signed payload,
  not a bearer token) — modeling that is out of scope. `POST /webhooks/deposits` sits behind the
  same OAuth2 resource-server auth as every other gateway route, and this simplification is
  documented in the README as a deliberate simplification, not an oversight.
- No configurable/pluggable rail behavior beyond what chaos tests and demos need (i.e., no
  admin UI or rule engine for simulated outcomes — the control-plane endpoints described below are
  sufficient).
- No changes to Ledger Service's schema or transaction-posting logic beyond: (a) adding
  `WITHDRAWAL_EXTERNAL` as a value written into the existing free-form `transactions.transaction_type`
  column (no migration needed — this column has never had a CHECK constraint), and (b) generalizing
  the existing single-prefix clearing-account exemption in `TransactionPoster` to cover a second
  prefix.

## Architecture

### New service: `gateway-simulator`

A new Spring Boot module, `gateway-simulator/`, following the exact scaffolding, module layout,
and Maven-reactor-membership pattern already established by `holds-service` and `fx-service`:
`api/`, `domain/`, `repository/`, `service/`, `messaging/`, `config/`,
`resources/db/migration/`. It owns its own database, `gateway_sim_db`, per the platform's
database-per-service rule — no other service ever queries its tables directly.

### Schema (`gateway_sim_db`)

```sql
CREATE TABLE external_deposits (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_reference  VARCHAR(255) NOT NULL UNIQUE,
    account_ref         VARCHAR(128) NOT NULL,
    amount_minor        BIGINT NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'RECEIVED'
                          CHECK (status IN ('RECEIVED','CREDITED','REJECTED')),
    transaction_id      UUID,
    raw_payload         JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE external_withdrawals (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_transaction_id   UUID NOT NULL UNIQUE,
    account_ref             VARCHAR(128) NOT NULL,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency                CHAR(3) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED'
                              CHECK (status IN ('SUBMITTED','CONFIRMED','FAILED','TIMED_OUT','REVERSED')),
    submitted_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,
    reversal_transaction_id UUID
);
CREATE INDEX idx_external_withdrawals_status_submitted_at
    ON external_withdrawals(status, submitted_at);

CREATE TABLE webhook_dedup (
    external_reference  VARCHAR(255) PRIMARY KEY,
    webhook_count       INT NOT NULL DEFAULT 1,
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- outbox_events and processed_events follow the exact shape already used by
-- holds-service and fx-service (see their V1 migrations for the canonical columns).
```

### Inbound flow: simulated deposit

`POST /simulator/deposits {accountRef, amountMinor, currency}` is the control-plane trigger used
by demos and chaos scripts. It generates a UUID `externalReference` and immediately invokes the
real inbound path described next (in-process call, not a second HTTP round-trip) — this endpoint
exists purely to give a demo or test script an easy way to originate a deposit without
hand-crafting a webhook payload.

`POST /webhooks/deposits {externalReference, accountRef, amountMinor, currency}` is the actual
trust-boundary endpoint — the one place in the whole platform modeling traffic arriving from an
external, non-cooperating party:

1. Upsert `webhook_dedup` keyed by `externalReference` (`INSERT ... ON CONFLICT (external_reference)
   DO UPDATE SET webhook_count = webhook_dedup.webhook_count + 1, last_seen_at = now()`), and read
   back whether this was the first insert or an existing-row update via the row's `webhook_count`
   value post-upsert. `webhook_count > 1` means this is a **redelivery** — increment the
   `gateway_sim.webhook.duplicate` counter, then short-circuit: look up the already-recorded
   `external_deposits` row by `external_reference` and return its existing status. No further
   processing.
2. First delivery: insert `external_deposits` row with `status='RECEIVED'`.
3. Call `POST /transactions` on API Gateway with `Idempotency-Key = external-deposit-{externalReference}`,
   crediting `accountRef` and debiting the `external-clearing-{currency}` account.
4. On success (`201`), update `external_deposits.status='CREDITED'` with the returned
   `transaction_id`. On a definitive rejection from Ledger (e.g. `404` unknown account), set
   `status='REJECTED'` and increment `gateway_sim.deposit.rejected`.

`GET /external-deposits/{externalReference}` exposes the current row for polling/demos.

Duplicate delivery of the same `externalReference` is defended at three layers, per the original
platform spec: the `webhook_dedup` unique-key upsert (this service's own first line of defense),
the deterministic `Idempotency-Key` on the downstream `POST /transactions` call (Ledger's own
idempotency layer as the real backstop even if this service's own dedup check were somehow
bypassed), and V1's general idempotency-conflict handling.

### Outbound flow: external withdrawal

1. A client posts an ordinary transaction via the existing `POST /transactions`, with
   `transaction_type='WITHDRAWAL_EXTERNAL'`, debiting the customer's account and crediting
   `external-clearing-{currency}`. This is unchanged from how every other transaction type is
   posted — Gateway Simulator does not expose its own withdrawal-initiation endpoint.
2. Gateway Simulator consumes this event by subscribing to the existing `ledger.transaction.posted`
   RabbitMQ topic — a new queue bound to the exchange that already exists for Holds Service's own
   consumer — filtering to `transaction_type='WITHDRAWAL_EXTERNAL'`. Redelivery-dedup uses
   `processed_events` keyed by the outbox event ID, identical in shape to every other consumer in
   this codebase (Holds Service's `ledgerTransactionPosted` consumer, Transaction Processor's own
   dedup gate).
3. On first delivery: insert `external_withdrawals` (`status='SUBMITTED'`, `submitted_at=now()`),
   then invoke the simulated rail — a trivial internal stub (e.g. a log line) with no real external
   call and no outcome of its own. Resolution always arrives later via the confirm endpoint below,
   never synchronously from this step, so the async-confirmation code path is always exercised
   uniformly rather than sometimes short-circuited.
4. `POST /simulator/withdrawals/{id}/confirm {outcome: CONFIRMED|FAILED}` is the control-plane
   endpoint used by demos and chaos scripts to resolve a pending withdrawal (standing in for the
   rail's own async callback):
   - If no `SUBMITTED` row exists yet for `{id}` (a race between this confirm call and step 2/3
     still being in flight), return `409 Conflict`. The caller is expected to retry after a short
     delay — this mirrors how every redelivery-tolerant consumer in this codebase already handles
     transient not-yet-ready state, and avoids introducing a new placeholder-row state machine
     that no other part of the platform uses.
   - `outcome=CONFIRMED` → `status='CONFIRMED'`, `resolved_at=now()`. No further ledger action —
     the debit already took effect at submission time in step 1.
   - `outcome=FAILED` → `status='FAILED'`, `resolved_at=now()`, then proceed to the reversal path
     below.
5. **Reversal path** (triggered by both `FAILED` and `TIMED_OUT`, see next): Gateway Simulator
   posts a reversing transaction via `POST /transactions` — debit `external-clearing-{currency}`,
   credit the original `account_ref` for `amount_minor` — with
   `Idempotency-Key = external-withdrawal-reversal-{sourceTransactionId}` (deterministic, so a
   retry of the reversal itself is safely idempotent). On success, set
   `reversal_transaction_id` and `status='REVERSED'`.
6. A `@Scheduled` sweep (same shape as Holds Service's expiry sweep and the FX recovery sweep)
   runs periodically, selects `external_withdrawals` rows where `status='SUBMITTED'` and
   `submitted_at < now() - withdrawal_timeout`, marks each `TIMED_OUT`, increments
   `gateway_sim.withdrawal.timeout`, and triggers the same reversal path as step 5.

`GET /external-withdrawals/{id}` exposes the current row for polling/demos.

`withdrawal_timeout` defaults to **60 seconds** and is externalized as
`gateway-sim.withdrawal-timeout-seconds` in `application.yml`, matching how
`holds.held-balance-timeout-ms` and the FX quote TTL are already configured elsewhere in this
codebase. A short default suits a demo/chaos-test platform; a real payment rail's settlement
window would be much longer, and the README calls this out as a deliberate simplification.

### Clearing-account exemption (Ledger Service change)

`TransactionPoster`'s existing hardcoded single-prefix check
(`debitAccount.getAccountRef().startsWith(FX_CLEARING_ACCOUNT_REF_PREFIX)`, which currently exempts
`fx-clearing-` accounts from the synchronous Holds Service held-balance check) is generalized to a
small, explicit set of clearing prefixes: `fx-clearing-` and `external-clearing-`. The exact
mechanism (a `List<String> CLEARING_ACCOUNT_REF_PREFIXES` constant plus a
`isClearingAccount(String accountRef)` helper checking `.startsWith(...)` against each, versus two
separate named constants) is an implementation-plan-level detail, not a design-level one — either
reads cleanly; the implementer should pick whichever keeps `TransactionPoster` more readable given
its current structure.

This exemption is necessary because clearing accounts are internal suspense accounts, not real
customer accounts — a hold placed against one (which should never happen in practice, but nothing
currently prevents it) would otherwise block legitimate settlement/reversal transactions, a new
failure mode with no real-world analog. This directly extends the precedent V3 already established
for `fx-clearing-` accounts.

### API Gateway routing

New routes added to the existing Spring Cloud Gateway routing table: `/webhooks/**`,
`/simulator/**`, `/external-deposits/**`, `/external-withdrawals/**`, all proxied to
`gateway-simulator`. All of these sit behind the same OAuth2 resource-server auth as every other
route — no new security carve-out. `POST /webhooks/deposits` in particular would, in a real system,
authenticate via a rail-specific signature scheme rather than this platform's own bearer tokens;
this simplification is called out explicitly in the README's "known limitations" section rather
than silently glossed over.

### Docker Compose

New `gateway-simulator` + `gateway-sim-db` containers, wired following the exact pattern V3 used
for `fx-service`/`fx-db`: health-checked dependency on its own Postgres and on RabbitMQ, no
dependency edge running the other direction (avoiding the same kind of dependency-cycle risk the
hardening plan's Task 4 deliberately avoided when wiring Ledger Service to Holds Service).

### Observability

Extends the existing Micrometer/Prometheus/Grafana stack (from the hardening plan) with four new
counters, all on `gateway-simulator`:

| Metric | Type | Fires when |
|---|---|---|
| `gateway_sim.webhook.duplicate` | Counter | A `webhook_dedup` upsert observes `webhook_count > 1` |
| `gateway_sim.deposit.rejected` | Counter | A deposit's downstream `POST /transactions` call is definitively rejected |
| `gateway_sim.withdrawal.timeout` | Counter | The sweep marks a `SUBMITTED` row `TIMED_OUT` |
| `gateway_sim.withdrawal.reversal` | Counter | A reversal transaction is successfully posted (from either the `FAILED` or `TIMED_OUT` path) |

`gateway-simulator` is added as a new Prometheus scrape target (same `/actuator/prometheus`
pattern as the other five services) and gets its own panel(s) added to the existing Grafana
dashboard JSON.

## Chaos Testing

Three new scenarios extend the existing 6-scenario Toxiproxy-driven chaos suite in
`chaos/scenarios/`, following its established script conventions (seed known state, induce the
fault, wait/retry, assert via direct SQL plus the service's own status endpoints, non-zero exit on
failure):

1. **`07_duplicate_webhook.sh`** — fire `POST /webhooks/deposits` with the same `externalReference`
   N times in quick succession (directly, not via a network-fault toxic — isolates the dedup logic
   itself from crash-timing flakiness, matching how the existing scenario 4 tests RabbitMQ
   redelivery dedup). Asserts exactly one `CREDITED` deposit and exactly one resulting ledger
   transaction, with `gateway_sim.webhook.duplicate` incremented `N-1` times.
2. **`08_withdrawal_timeout_sweep.sh`** — post a `WITHDRAWAL_EXTERNAL` transaction, let Gateway
   Simulator submit it, and deliberately never call the confirm endpoint. Wait past the configured
   timeout (using a short test-profile override of `gateway-sim.withdrawal-timeout-seconds` to
   keep the scenario fast), then assert the sweep has marked it `TIMED_OUT`, posted a reversal, and
   the original account's balance is restored.
3. **`09_out_of_order_confirmation.sh`** — race a `POST /simulator/withdrawals/{id}/confirm` call
   against the withdrawal event still being in flight through RabbitMQ (a `latency` toxic on the
   Gateway Simulator's queue consumption, timed to land the confirm call before the `SUBMITTED` row
   exists). Asserts the confirm call receives `409`, and that a retry after the row exists
   succeeds and resolves correctly.

## Testing Strategy (beyond chaos)

- **Unit**: dedup-decision logic in the webhook handler (first-delivery vs. redelivery) as a pure
  function; the sweep's stuck-row selection query logic.
- **Integration (Testcontainers)**: real Postgres + RabbitMQ, exactly matching the pattern already
  used by `holds-service`/`fx-service`'s own integration suites — a real end-to-end deposit
  (webhook in, ledger transaction out, balance updated); a real end-to-end withdrawal
  (transaction posted → consumed → submitted → confirmed, and separately → failed → reversed);
  the sweep's timeout-and-reverse path against a real clock-independent test (insert a row with a
  backdated `submitted_at` rather than sleeping past a real timeout); the `409`-then-retry race
  behavior of the confirm endpoint.
- **Idempotency-specific**: same `externalReference` delivered N times → exactly one credit, N-1
  `gateway_sim.webhook.duplicate` increments; the reversal path's own idempotency key verified
  safe against a retried reversal attempt.

## Verification (end-to-end)

- Full Maven reactor build (`mvn clean verify`) green across all 6 modules (5 existing +
  `gateway-simulator`).
- `docker compose up` brings up the full stack (16 containers: 14 existing + `gateway-simulator` +
  `gateway-sim-db`) cleanly from scratch.
- `scripts/smoke-test.sh` extended with a deposit-then-withdrawal round trip through the gateway.
- All 9 chaos scenarios (6 existing + 3 new) green.
- Manual live walkthrough through the gateway: simulate a deposit, confirm the account balance
  increases by the expected amount; submit a withdrawal, fail it via the confirm endpoint, confirm
  the account balance is restored to its pre-withdrawal value.
- README updated: architecture section describes the inbound/outbound flows and the simulated
  trust boundary; "what this demonstrates" gains an entry for external-system integration patterns
  (webhook dedup, saga compensation on a third failure axis beyond FX); known limitations documents
  the webhook-auth simplification and the short/configurable timeout default; running-locally
  section updated to 16 containers.

## Open Items Resolved From the Original Spec

For traceability against the original platform-wide spec's V5 sketch, every ambiguity it left open
has been resolved in this document:

- **Deposit targeting**: direct `account_ref` in the webhook payload (not a separate external
  funding-source-to-account mapping).
- **Withdrawal trigger**: reactive, via consuming `ledger.transaction.posted` (not a dedicated
  synchronous withdrawal-initiation endpoint on Gateway Simulator itself).
- **Clearing-account holds exemption**: extended from the existing `fx-clearing-` precedent to
  also cover `external-clearing-`.
- **Out-of-order confirmation handling**: retryable rejection (`409` + caller retry), not a new
  placeholder-row state machine.
- **Withdrawal timeout**: 60-second configurable default, externalized in `application.yml`.
- **Chaos scope**: yes, 3 new Toxiproxy-driven scenarios extending the existing 6.
- **Webhook auth boundary**: `POST /webhooks/deposits` sits behind the same OAuth2 auth as every
  other route; the simplification versus real webhook-signature verification is documented, not
  silently absorbed.
