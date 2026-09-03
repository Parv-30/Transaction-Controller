# Ledger — Fault-Tolerant Transaction Processing Platform: Full Architecture Spec

## Context

This is a greenfield project (`D:\Ledger` is currently empty). The goal is a resume-grade
portfolio project that is also genuinely production-style in rigor, and doubles as a deep
learning vehicle for Java/Spring/Postgres/RabbitMQ/Docker. The original pitch was a
double-entry ledger with idempotent APIs, a transactional-outbox event pipeline, and
chaos-tested correctness guarantees (no duplicate/lost transactions).

Through design discussion this expanded from "a ledger" into a **full ledger platform**
built as **microservices** (not a monolith), because the user wants both a strong resume
story and hands-on practice with distributed-systems patterns and the target stack. To keep
this achievable, the platform is built in five ordered **versions**, each fully working and
demoable before the next begins — V1 alone (core double-entry ledger + fault-tolerant async
processing) is already a complete, postable resume artifact; V2–V5 layer on holds,
multi-currency/FX, fees, and an external-payment-gateway simulation.

This document is the complete architecture — all five versions — agreed before any code is
written. Implementation proceeds version by version, each via its own detailed implementation
plan (written separately, via the writing-plans flow) once this spec is approved.

---

## Platform-Wide Decisions (apply to every version)

- **Architecture style**: microservices, not a monolith. Each service is independently
  deployable and owns its own responsibility boundary.
- **Data ownership**: database-per-service. Every service gets its own Postgres
  database/instance in Docker Compose; no service ever queries another service's tables
  directly. Cross-service data access is always via REST (sync) or RabbitMQ events (async).
- **Synchronous inter-service calls**: plain REST/HTTP.
- **Asynchronous communication**: RabbitMQ, fed via the transactional outbox pattern in every
  service that publishes events (not just the Ledger Service) — a service commits its own DB
  write and an `outbox_events` row in the same local transaction, then a relay publishes to
  RabbitMQ. This is the one architectural pattern repeated consistently across all six
  services.
- **API Gateway**: Spring Cloud Gateway is the single entry point, routing `/accounts/**`,
  `/transactions/**`, `/holds/**`, `/rates/**`, `/conversions/**`, `/fee-rules/**`,
  `/webhooks/**`, etc. to the owning service. Present from V1 onward; its routing table grows
  with each version.
- **Auth**: V1 ships with simple API-key auth at the gateway (fast to build, still real).
  V2 upgrades this to full JWT/OAuth2 (e.g. Keycloak) — auth is a clean, separable concern
  deliberately decoupled from the fault-tolerance story so V1 isn't blocked on it.
- **Orchestration**: Docker Compose only, no Kubernetes. One `docker-compose.yml` at the repo
  root, growing incrementally per version (new service + DB containers added, nothing
  removed).
- **Repo structure**: single Git monorepo, Maven multi-module. Each service is a top-level
  module (`ledger-service/`, `transaction-processor/`, `holds-service/`, `fx-service/`,
  `fees-service/`, `gateway-simulator/`, `api-gateway/`), plus root-level `docker-compose.yml`
  and a `chaos/` directory for the chaos-test suite.
- **Build tool**: Maven.
- **Testing**: Testcontainers for all integration tests (real Postgres/RabbitMQ in ephemeral
  containers, not H2/mocks) — correctness claims about row-locking and concurrency require
  testing against real Postgres semantics.
- **Money representation**: integer minor units (e.g. cents) or `NUMERIC` with fixed scale —
  never floating point — consistently across all services.

---

## Version Roadmap

| Version | Adds | Resume story |
|---|---|---|
| V1 | Ledger Service + Transaction Processor | Core double-entry ledger, idempotent transaction API, transactional outbox, embedded Debezium CDC → RabbitMQ, exactly-once-in-effect processing, reconciliation job, Toxiproxy chaos-test suite. **This alone is a complete, postable artifact.** |
| V2 | Holds Service + full JWT/OAuth2 auth | Authorize/capture/release fund holds; upgraded auth story. |
| V3 | FX Service + multi-currency accounts | Cross-currency transfers, rate locking, saga-style two-leg transaction compensation. |
| V4 | Fees Service | Event-driven, idempotent fee application as ordinary double-entry transactions. |
| V5 | Gateway Simulator | Simulated external payment rail — async webhooks, duplicate/out-of-order delivery handling, outbound confirmation timeouts. |

Each version's services communicate with prior versions' services only via REST/events per
the platform-wide rule — nothing here breaks the database-per-service boundary.

---

## V1 — Ledger Service + Transaction Processor

**Scope**: the foundational two services. Everything below is fully designed and
implementable as-is.

### Postgres Schemas

**Ledger Service DB (`ledger_db`)**

```sql
CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_ref     VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(256),
    currency        CHAR(3) NOT NULL DEFAULT 'USD',   -- single-currency in V1; extended in V3
    balance_minor   BIGINT NOT NULL DEFAULT 0,        -- integer minor units, never float
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key   VARCHAR(255) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','FAILED','REVERSED')),
    transaction_type  VARCHAR(32) NOT NULL DEFAULT 'TRANSFER',  -- V4 adds 'FEE', V5 adds 'WITHDRAWAL_EXTERNAL'
    description       TEXT,
    request_payload_hash CHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transactions_idem_key UNIQUE (idempotency_key)
);

CREATE TABLE entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_transaction_id ON entries(transaction_id);
CREATE INDEX idx_entries_account_id ON entries(account_id);

CREATE TABLE outbox (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type    VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION',
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION_POSTED',
    payload           JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No "published" column: publishing state lives in the Processor's own DB
    -- (an UPDATE here would generate a spurious WAL event Debezium would re-capture).
    -- Ledger's outbox stays append-only; cross-checked against Processor's
    -- processed_events via REST in the reconciliation job.
);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox(aggregate_id);

CREATE TABLE reconciliation_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    transactions_checked     INT NOT NULL DEFAULT 0,
    entries_imbalance_count  INT NOT NULL DEFAULT 0,
    outbox_missing_count     INT NOT NULL DEFAULT 0,
    outbox_stuck_count       INT NOT NULL DEFAULT 0,
    summary         JSONB
);

CREATE TABLE reconciliation_findings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL REFERENCES reconciliation_runs(id),
    finding_type        VARCHAR(32) NOT NULL CHECK (finding_type IN
                          ('ENTRIES_NOT_ZERO','OUTBOX_MISSING','OUTBOX_STUCK_UNPUBLISHED')),
    transaction_id      UUID,
    outbox_id           UUID,
    detail              JSONB NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Defense in depth: add a deferred `CONSTRAINT TRIGGER` on `entries` that recomputes the
debit/credit sum per `transaction_id` at commit and raises if nonzero — makes the zero-sum
invariant actually impossible to violate, not just application-enforced.

Postgres must run with `wal_level=logical`, `max_replication_slots>=4`,
`max_wal_senders>=4`, and a `PUBLICATION ledger_outbox_pub FOR TABLE outbox;` for Debezium.

**Transaction Processor DB (`processor_db`)**

```sql
CREATE TABLE processed_events (
    outbox_event_id   UUID PRIMARY KEY,           -- = ledger.outbox.id, the dedup key
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(64) NOT NULL,
    captured_at       TIMESTAMPTZ NOT NULL,
    published_at      TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    status            VARCHAR(24) NOT NULL DEFAULT 'CAPTURED'
                        CHECK (status IN ('CAPTURED','PUBLISHED','CONSUMED','DUPLICATE_IGNORED','PUBLISH_FAILED')),
    delivery_count    INT NOT NULL DEFAULT 1,
    payload           JSONB,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_processed_events_aggregate ON processed_events(aggregate_id);
CREATE INDEX idx_processed_events_status ON processed_events(status);

CREATE TABLE cdc_progress (
    id                      SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_lsn                VARCHAR(64),
    last_event_captured_at  TIMESTAMPTZ,
    last_heartbeat_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`status` semantics: `CAPTURED` (Debezium handed the record to the publisher; row written
*before* the publish attempt so a crash mid-publish leaves visible evidence) →
`PUBLISHED` (RabbitMQ publisher-confirm received) → `CONSUMED` (the true dedup gate, set by
the consumer after its business effect is applied). `DUPLICATE_IGNORED` marks a redelivery
observed after `CONSUMED` was already set. `PUBLISH_FAILED` is retried by a scheduled sweep.

### POST /transactions — End-to-End Flow

1. **Idempotency pre-check**: hash the normalized request body; look up
   `transactions WHERE idempotency_key = ?`. Same key + same hash → return the original
   result as a replay (200, `X-Idempotent-Replay: true`). Same key + different hash → 409.
   Not found → proceed. (Race safety net is the `UNIQUE(idempotency_key)` constraint, not
   this read — a losing concurrent insert is caught and turned into a replay lookup.)
2. Resolve `debitAccountRef`/`creditAccountRef` → account IDs (404 if missing).
3. Begin a DB transaction (`READ COMMITTED` + explicit row locks; `SERIALIZABLE` not needed).
4. **Lock both accounts in a fixed order** — `SELECT ... WHERE id IN (:a,:b) ORDER BY id FOR UPDATE`
   (ordered by account UUID, not debit/credit role) — this is what prevents deadlocks across
   any concurrent transfers touching overlapping account pairs. Check both `ACTIVE`, check
   sufficient balance; abort (422) otherwise.
5. Insert the `transactions` row (catch a unique-violation on `idempotency_key` as a lost
   race → rollback, re-fetch, return as replay rather than a hard error).
6. Insert the debit + credit `entries` rows.
7. Update both account balances using the already-locked rows.
8. Insert the `outbox` row — **same DB transaction** as steps 5–7.
9. Commit. Steps 4–8 are one atomic unit: entries, balances, and the outbox row all persist
   together or not at all. This atomicity is the load-bearing guarantee for everything else.
10. Return 201 to the client. Everything from here is async and invisible to the caller.
11. The embedded Debezium Engine (running inside Transaction Processor) tails
    `ledger_outbox_pub` via a logical replication slot and receives the new `outbox` row on
    the next WAL flush.
12. Processor writes a `CAPTURED` row to its own `processed_events` (keyed by `outbox.id`)
    **before** attempting to publish — a write-ahead pattern so a crash between capture and
    publish leaves recoverable evidence instead of silently losing the event.
13. Publish to RabbitMQ via Spring AMQP with **publisher confirms**. Confirm ack →
    `status='PUBLISHED'`. Nack/timeout → `status='PUBLISH_FAILED'`, retried by a scheduled
    sweep.
14. Debezium's offset commit only happens once the publish path is resolved — a Processor
    crash before that point causes Debezium to redeliver the same WAL record on restart
    (at-least-once by design), which is exactly why the dedup step below is mandatory.
15. Consumer (`@RabbitListener`, manual ack): looks up `processed_events` by
    `outbox_event_id`. If already `CONSUMED` → redelivery, ack and no-op (do not overwrite).
    If `PUBLISHED` → do the business work, then
    `UPDATE processed_events SET status='CONSUMED' WHERE outbox_event_id=? AND status<>'CONSUMED'`
    (atomic conditional update, not read-then-write) → ack only after the DB commit.
16. The reconciliation job periodically cross-checks Ledger's `outbox` against Processor's
    `processed_events` via REST (see below).

**Single-instance constraint** (explicit, accepted gap for V1): the embedded Debezium engine
holds an exclusive Postgres replication slot and a local file-based offset store. Only one
Transaction Processor instance may run. Scaling this is out of scope for V1 (would require
Debezium Server + Kafka, or leader election, or Kafka Connect distributed mode).

### Spring Boot Module Structure

```
ledger-service/
  api/            TransactionController, AccountController, ReconciliationController, dto/, error/
  domain/         Account, Transaction, Entry, OutboxEvent, Direction, TransactionStatus (JPA entities)
  repository/     AccountRepository (custom FOR UPDATE query), TransactionRepository, EntryRepository, OutboxRepository
  service/        TransactionService (the @Transactional orchestrator), IdempotencyService,
                  AccountLockingService (ordered dual lock), LedgerEventFactory
  reconciliation/ ReconciliationJob (@Scheduled), ReconciliationService, ProcessorReconciliationClient, ReconciliationRepository
  config/         DataSourceConfig, SchedulingConfig, WebClientConfig
  resources/db/migration/  Flyway: V1__init_schema.sql, V2__constraint_trigger_zero_sum.sql

transaction-processor/
  cdc/            DebeziumEngineConfig, OutboxChangeConsumer (ChangeConsumer impl), DebeziumEngineLifecycle (SmartLifecycle), OutboxEventPayloadMapper
  messaging/      RabbitPublisherConfig (publisher confirms), OutboxEventPublisher, OutboxEventConsumer (dedup gate), MessagingConstants
  domain/         ProcessedEvent, ProcessedEventStatus, CdcProgress
  repository/     ProcessedEventRepository, CdcProgressRepository
  api/            ProcessedEventController (GET /processed-events, batch-status — consumed by Ledger's reconciliation)
  service/        PublishRetryService (@Scheduled sweep over PUBLISH_FAILED)
  config/         DataSourceConfig, OffsetStorageConfig
  resources/db/migration/  Flyway: V1__init_schema.sql
```

Both modules get `unit/` and `integration/` (Testcontainers) test packages.

### Reconciliation Job

Scheduled (e.g. every 5 minutes), read-only, writes only to
`reconciliation_runs`/`reconciliation_findings`.

- **Check A — entries sum to zero per transaction**:
  `GROUP BY transaction_id HAVING SUM(debit) - SUM(credit) <> 0` over `entries`. Should never
  fire given the atomic-write design + optional constraint trigger; its presence is the proof
  the invariant holds, not the primary enforcement.
- **Check B — every committed transaction has an outbox row**: `LEFT JOIN outbox` on
  `aggregate_id`, flag `transactions` with no match older than a grace window. Structurally
  should be impossible given the same-DB-transaction write; a hit is treated as
  severity-critical.
- **Check C — outbox rows stuck unpublished past a threshold** (the one check needing
  cross-service data, since "published" state lives in the Processor): Ledger selects outbox
  rows older than a threshold, batches their IDs to the Processor's
  `POST /processed-events/batch-status`, and flags any not yet `PUBLISHED`/`CONSUMED` as
  `OUTBOX_STUCK_UNPUBLISHED`. If the REST call itself fails (e.g. Processor unreachable), the
  run is marked `FAILED` with the reason recorded — never silently treated as "no findings."

### Chaos Test Suite (Toxiproxy, ~5 scenarios)

Toxiproxy sits in Docker Compose with named proxies in front of `ledger-postgres` (app
connection and Debezium's CDC connection routed through separate proxy ports so they can be
faulted independently) and `rabbitmq`. Driven via the Toxiproxy HTTP API from a
`make chaos-test` target running scripts under `chaos/scenarios/`. Each scenario: seed a known
transaction, induce the fault at the right moment, wait/retry, assert via direct SQL + the
reconciliation report + the Processor's `processed_events` API; non-zero exit on failure.

1. **RabbitMQ down mid-publish**: drop the RabbitMQ connection (Toxiproxy `timeout` toxic, or
   `docker compose stop rabbitmq`) right after the outbox row is written. Verifies the event
   isn't lost — `processed_events` must reach `CONSUMED` once connectivity returns, and
   reconciliation must show zero stuck findings.
2. **Ledger DB crash/restart after commit, before Debezium sees it**: hard-restart
   `ledger-postgres` immediately after a 201 response (optionally delay the CDC connection via
   a `latency` toxic to land the restart before Debezium has polled). Verifies WAL durability
   + replication slot retention survive a source-DB crash — the outbox row must still exist
   post-restart and still get delivered.
3. **Transaction Processor crash mid-consume**: kill the Processor container at a tuned delay
   after publish (with a `latency` toxic on the RabbitMQ proxy to make the timing reliable).
   Verifies RabbitMQ redelivery + the dedup gate cooperate correctly regardless of exactly
   which side of the ack the crash landed on.
4. **Duplicate RabbitMQ delivery**: directly republish the same message body via the RabbitMQ
   management API (isolates dedup logic from crash-timing flakiness); fire several duplicates
   rapidly to stress race conditions in the dedup check itself. Verifies exactly one
   `CONSUMED` transition and correct final account balances.
5. **Network partition between Ledger Service and Postgres mid-`SELECT FOR UPDATE`**: a
   `latency` toxic followed by a hard `timeout` on the app-DB proxy, timed to land inside the
   open transaction. Verifies the whole multi-statement transaction rolls back atomically (no
   torn writes), the client gets a clear error, and the same `Idempotency-Key` safely retried
   afterward produces exactly one successful transfer.

### Docker Compose (V1)

Containers: `ledger-postgres` (logical replication enabled), `processor-postgres`,
`rabbitmq`, `toxiproxy`, `ledger-service`, `transaction-processor`. Named volumes for both
Postgres data directories and for Debezium's file-based offset store (durability across
restarts). Explicitly **not** included yet: API gateway, holds/fx/fees/gateway-sim services,
any Kafka broker (the embedded-Debezium approach avoids Kafka entirely).

### Testing Strategy (beyond chaos)

- **Unit**: `TransactionService` with mocked repos — exactly one debit + one credit entry
  built with equal amounts; insufficient-funds path never mutates; lock ordering is
  independently unit-testable via `AccountLockingService`; `IdempotencyService`'s
  replay/conflict decision logic tested as pure functions.
- **Integration (Testcontainers)**: real concurrent `SELECT FOR UPDATE` test (two threads
  transferring between the same account pair — assert no deadlock, correct final balances
  regardless of interleaving); Flyway migrations + the constraint trigger validated against a
  real container; outbox atomicity verified by forcing a failure between the balance update
  and the outbox insert and asserting full rollback. Processor-side: Testcontainers Postgres
  (with logical replication) + Testcontainers RabbitMQ, end-to-end through the real embedded
  Debezium engine; a dedicated dedup test publishing the same message twice; an offset
  persistence test (stop/restart the engine, assert no redelivery of already-flushed events).
- **Idempotency-specific**: same key+body fired twice → byte-identical replay, only one DB
  row; same key fired from N concurrent threads → exactly one winner, rest resolve via
  unique-violation-catch-and-replay, balance mutated exactly once; same key + different body →
  409, original untouched.

**Critical files**: `ledger-service/.../db/migration/V1__init_schema.sql`,
`ledger-service/.../service/TransactionService.java`,
`transaction-processor/.../cdc/{DebeziumEngineConfig,OutboxChangeConsumer}.java`,
`transaction-processor/.../messaging/OutboxEventConsumer.java` (the dedup gate),
`docker-compose.yml` and `chaos/scenarios/*.sh`.

---

## V2 — Holds Service (+ auth upgrade to JWT/OAuth2)

**Boundary**: owns hold lifecycle only (create/capture/release). Never writes to Ledger's
tables. Reduces *available* balance via its own locally-tracked `held_balance`, always
re-validated against Ledger's authoritative balance at capture time.

**Schema (`holds_db`)**: `holds` (id, account_id, amount, currency, status
ACTIVE/CAPTURED/RELEASED/EXPIRED, idempotency_key, expires_at, captured_amount,
created_transaction_id, `version` for optimistic locking); `account_balance_cache`
(account_id, posted_balance, held_balance — a fast-path read-model, not source of truth);
`outbox_events` (same pattern as V1); `processed_events` (dedup for consumed events).

**API**: `POST /holds` (Idempotency-Key), `POST /holds/{id}/capture` (supports partial
capture), `POST /holds/{id}/release`, `GET /holds/{id}`,
`GET /accounts/{accountId}/available-balance`. Calls out to Ledger's `GET /accounts/{id}`
(authoritative balance check) and Transaction Processor's `POST /transactions` (on capture,
with `Idempotency-Key = hold-capture-{holdId}`).

**Events**: publishes `hold.created`/`hold.captured`/`hold.released` via its own outbox;
consumes `ledger.transaction.posted` to keep `account_balance_cache.posted_balance` fresh
(deduped via `processed_events`).

**Integration with Ledger**: placing a hold never moves ledger entries — only capture does,
via the existing idempotent `POST /transactions`. Ledger Service remains completely unaware
holds exist.

**Correctness**:
- Hold-vs-hold oversubscription prevented via `SELECT ... FOR UPDATE` on the
  `account_balance_cache` row inside the hold-creation transaction, checking
  `amount <= posted_balance - held_balance` atomically.
- **Known accepted gap**: a direct `POST /transactions` call that bypasses Holds Service
  entirely can still overdraw an account with active holds, since Ledger has no concept of
  "held" funds and the two services don't share a lock. Documented rather than solved with
  cross-service locking — clients must route spend-checks through Holds Service's
  `available-balance` endpoint by convention.
- Capture race prevented via the `version` optimistic-lock column + the capture endpoint's
  idempotency key.
- A scheduled sweep expires holds past `expires_at`, decrementing `held_balance` under lock
  and emitting `hold.released`.
- Cache staleness mitigated by re-fetching the authoritative Ledger balance synchronously for
  high-value holds rather than trusting the cache unconditionally.

**Docker Compose changes**: new `holds-service` + `holds-db` containers; gateway routes
`/holds/**` and `/accounts/*/available-balance`. Auth upgrade: gateway swaps API-key
validation for JWT/OAuth2 (e.g. Keycloak), which likely adds a `keycloak` (or equivalent)
container plus a client-credentials/user-token setup — the exact IdP choice and container
footprint are finalized in V2's own implementation plan when we get there.

---

## V3 — FX Service + Multi-Currency Accounts

**Boundary**: FX Service owns currency rate data and conversion computation only — no funds,
no transaction posting, no account ownership. Ledger Service is extended (schema migration,
still the same service) to support multi-currency: accounts become inherently
single-currency, with multi-currency "wallets" modeled as several account rows sharing an
`account_group_id`. Every ledger entry keeps its single-currency invariant; cross-currency
transfers become **two linked single-currency transactions**, never one entry with mixed
currencies.

**Schema (`fx_db`)**: `currencies` (ISO 4217 code, minor units); `fx_rates`
(base/quote/rate/effective_at, unique per pair+timestamp); `fx_conversion_records`
(source_transaction_id, dest_transaction_id, rate_used, rate_id, amounts, idempotency_key) —
the reconciling link between the two ledger-side legs. `outbox_events` per the platform
pattern.

**Ledger Service migration**: `accounts.currency` (already present from V1, now meaningfully
varies), `accounts.account_group_id` for grouping a wallet's per-currency sub-accounts.

**API**: `GET /rates/{base}/{quote}` (latest + historical via `?at=`),
`POST /conversions/quote` (locks a rate for a short TTL — the fix for rate-changed-mid-
transaction), `POST /conversions` (records a completed conversion, idempotent).

**Integration**: Transaction Processor is extended with a new orchestration flow for
cross-currency transfers: (1) quote-lock a rate from FX Service, (2) post leg 1 (debit source
account, credit an FX clearing account in source currency) with a derived idempotency key,
(3) post leg 2 (debit clearing in dest currency, credit dest account), (4) record the
conversion linking both transaction IDs. If leg 2 fails after leg 1 succeeds, a compensating
reversal is posted for leg 1 — saga-style compensation, tracked via a new
`pending_fx_transfers` state table (`LEG1_POSTED → LEG2_POSTED → COMPLETED`, or
`COMPENSATING → COMPENSATED`) in Transaction Processor, with a periodic sweep (extending V1's
reconciliation job) that resolves stuck `LEG1_POSTED` rows past a timeout.

**Correctness**: rate-changed-mid-transaction solved by the quote-lock + immutable
`rate_used` record; two-legged atomicity is the hardest new failure mode (no true cross-DB
transaction) and is explicitly accepted as eventual-consistency-with-compensation rather than
attempted as atomic; stale/missing rate pairs return 422 rather than silently using an old
rate.

**Docker Compose changes**: new `fx-service` + `fx-db` containers; a Flyway migration applied
to the existing `ledger-postgres`; gateway routes `/rates/**` and `/conversions/**`.

---

## V4 — Fees Service

**Boundary**: owns fee rule configuration and the fee-applies decision only — never writes
ledger entries directly; always triggers a real double-entry transaction through the existing
`POST /transactions`, exactly like any other client would.

**Schema (`fees_db`)**: `fee_rules` (applies_to_type, calculation_type
FLAT/PERCENTAGE/TIERED, percentage in basis points to avoid float rounding, fee_account_id,
effective window, priority); `fee_applications` (source_transaction_id, fee_rule_id,
fee_transaction_id, status, idempotency_key, with a **hard DB unique constraint on
`(source_transaction_id, fee_rule_id)`** preventing double-application at the schema level);
`processed_events`; `outbox_events`.

**API**: admin CRUD on `/fee-rules`, `GET /fee-applications/{sourceTransactionId}` for audit,
`POST /fee-applications/simulate` for client-side fee preview. No external trigger endpoint —
fee application is event-driven only, so it can never be bypassed by calling Fees Service out
of band.

**Integration**: purely event-driven. Consumes `ledger.transaction.posted`, evaluates
`fee_rules`, and on a match calls `POST /transactions` with a deterministic
`Idempotency-Key = fee-{sourceTransactionId}-{feeRuleId}`. Ledger Service stays completely
fee-agnostic — a fee is just another transaction.

**Correctness (idempotent fee application, three layers of defense)**: the DB unique
constraint on `fee_applications`, the deterministic idempotency key on the downstream
`POST /transactions` call (so a crash-and-redeliver of the triggering event safely no-ops via
V1's own idempotency layer), and dedup of the consumed `ledger.transaction.posted` event
itself via `processed_events`. **Fee-of-a-fee recursion** is prevented by tagging fee
transactions with `transaction_type='FEE'` and having the consumer filter those out before
rule evaluation — requires `transactions.transaction_type` (present since V1) to be reliably
set.

**Docker Compose changes**: new `fees-service` + `fees-db` containers; gateway routes
`/fee-rules/**` and `/fee-applications/**`.

---

## V5 — Gateway Simulator

**Boundary**: simulates an external payment rail — the only component modeling a real trust
boundary and its unreliability (duplicate/delayed/out-of-order delivery). Owns simulated
external-account state and inbound/outbound instruction records only; never owns Ledger
balances.

**Schema (`gateway_sim_db`)**: `external_deposits` (external_reference unique, account_id,
amount, status, transaction_id, raw webhook payload); `external_withdrawals`
(source_transaction_id, status SUBMITTED/CONFIRMED/FAILED/TIMED_OUT); `webhook_dedup`
(external_reference PK, webhook_count — tracks redelivery for chaos-test visibility);
`processed_events`; `outbox_events`.

**API**: `POST /webhooks/deposits` (the simulated inbound callback — the one endpoint in the
whole platform modeling traffic crossing the trust boundary), `POST /simulator/deposits` and
`POST /simulator/withdrawals/{id}/confirm` (control-plane endpoints to trigger simulated
events with configurable delay/duplication/reordering, used by chaos tests and demos),
`GET /external-deposits/{ref}` / `GET /external-withdrawals/{id}`.

**Integration**: **inbound** — webhook dedups via `webhook_dedup` (upsert +
`webhook_count` check), then posts the internal credit via `POST /transactions` with
`Idempotency-Key = external-deposit-{externalReference}`. **Outbound** — a withdrawal is a
normal Ledger transaction first (debit account, credit an external-clearing account);
Gateway Simulator picks it up via `ledger.transaction.posted` (filtered to
`transaction_type='WITHDRAWAL_EXTERNAL'`, a new type added in V5), submits it to the
simulated rail, and later receives an async confirm/fail. On failure, it triggers a
compensating reversal — the same structural pattern as V3's FX leg-failure compensation.

**Correctness**: duplicate webhook delivery handled at three layers (the
`webhook_dedup` unique constraint, the deterministic downstream idempotency key, and V1's
idempotency layer as final backstop); out-of-order confirmation (arriving before the
`SUBMITTED` row exists) handled via a retryable-rejection or placeholder-state pattern; a
scheduled timeout sweep (same shape as V2's hold-expiry sweep) marks stuck `SUBMITTED`
withdrawals `TIMED_OUT` and triggers reversal so the system never waits forever on a rail that
never calls back.

**Docker Compose changes**: new `gateway-simulator` + `gateway-sim-db` containers; gateway
routes `/webhooks/**`, `/simulator/**`, `/external-deposits/**`, `/external-withdrawals/**`.
Extends V1's existing Toxiproxy setup (rather than introducing new chaos tooling) with
scenarios specific to V5: duplicate webhook, delayed-past-timeout webhook, out-of-order
webhook relative to submission.

---

## Verification (end-to-end, applies once each version is built)

- `docker compose up` brings up that version's full stack from a clean state.
- Each service's own unit + Testcontainers integration suite passes
  (`mvn test` per module / `mvn test` at the root for the whole reactor).
- V1's `make chaos-test` suite passes all 5 scenarios (extended with new scenarios in V5).
- Manual/scripted end-to-end smoke test through the API Gateway exercising that version's new
  endpoints against the previous version's existing ones (e.g. in V2: create a hold, attempt
  an over-limit second hold and confirm rejection, capture, confirm the resulting ledger
  transaction and balance are correct).
- The reconciliation job's report shows zero findings after a clean run.

## Next Step

Once this spec is approved, implementation proceeds version by version. For V1, invoke the
writing-plans flow to turn the V1 section above into a step-by-step implementation plan
(module scaffolding → schema/migrations → TransactionService → Debezium/RabbitMQ wiring →
reconciliation job → chaos-test suite → verification), executed before V2 planning begins.
