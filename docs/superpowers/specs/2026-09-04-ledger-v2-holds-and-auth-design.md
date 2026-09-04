# Ledger V2 — Holds Service + Auth Upgrade: Design Spec

## Context

V1 shipped the core double-entry ledger: an idempotent transaction API, a transactional
outbox relayed via embedded Debezium CDC, exactly-once-in-effect RabbitMQ consumption, a
reconciliation job, and a 5-scenario chaos test suite — all verified against a live Docker
Compose stack. V1 deliberately deferred two things to keep scope tight: real authentication
(shipped with a placeholder-simple API-key story that was never actually built, since V1 had
no gateway to host it) and any notion of "held" funds (a hold/authorize-capture-release flow
common to card payments).

V2 builds both, together, because they're naturally coupled: Holds Service is the first new
service V2 adds, and it's also the first service that needs to sit behind a real gateway with
real routing — so this is the natural point to introduce the gateway and wire genuine
JWT/OAuth2 auth into it at the same time, rather than building the gateway once for routing
and touching its auth layer again later.

This spec covers V2 in full: the API Gateway (built for the first time — V1 had no gateway),
Keycloak-backed OAuth2 authentication, and the Holds Service. It supersedes the "V2" section
of `docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md`, filling in the
implementation-level decisions that document explicitly deferred ("the exact IdP choice and
container footprint are finalized in V2's own implementation plan when we get there").

---

## Platform-Wide Decisions Carried Forward from V1

- Database-per-service, no service reaches into another's tables.
- REST for synchronous inter-service calls, RabbitMQ for async events.
- Money in integer minor units, never floating point.
- Maven multi-module monorepo, Testcontainers for integration tests, Docker Compose only.
- Every service that publishes async events uses the transactional outbox pattern.

## New V2-Wide Decisions

- **API Gateway**: Spring Cloud Gateway, introduced for the first time in V2 (V1 had none —
  Ledger Service and Transaction Processor were called directly on `:8080`/`:8081`).
- **Auth**: Keycloak, self-hosted in Docker Compose, replaces V1's never-built API-key
  placeholder. The gateway is the only component that validates tokens; backend services
  trust requests that arrive through it.
- **Outbox relay pattern for new services**: a lightweight `@Scheduled` polling publisher,
  not embedded Debezium. V1 already proved the heavier CDC pattern once for the core ledger;
  repeating full logical-replication setup (replication slot, publication, pgoutput) for
  every secondary service adds infrastructure cost without new learning value. Holds
  Service's `outbox_events` table gains a `published_at` column (V1's outbox table
  deliberately did not have one, since Transaction Processor tracked publish state in its
  own `processed_events` table instead — Holds Service has no separate processor, so the
  poller marks its own rows published directly).

---

## Component 1: API Gateway

**New Maven module**: `api-gateway`, Spring Cloud Gateway.

**Routing** (path-prefix based):
- `/transactions/**`, `/reconciliation/**` → Ledger Service
- `/holds/**`, `/accounts/*/available-balance` → Holds Service
- Transaction Processor gets no client-facing route — it's internal-only, called by Ledger
  Service's reconciliation job over REST and driven by RabbitMQ; nothing external needs to
  reach it directly.

**Auth enforcement**: the gateway is configured as an OAuth2 Resource Server
(`spring-boot-starter-oauth2-resource-server`), validating every incoming request's JWT
against Keycloak's JWKS endpoint (signature + expiry, no shared secret, no manual token
parsing). A request without a valid bearer token is rejected at the gateway; it never
reaches a backend service. Backend services (Ledger, Holds) do not independently validate
tokens — they trust the network boundary the gateway enforces, consistent with V1's
already-established pattern of Ledger Service being unaware of concerns (like holds) that
live in front of or beside it.

---

## Component 2: Keycloak (Auth)

**New container**: `keycloak`, added to `docker-compose.yml`.

**Realm**: a single realm named `ledger`, imported from a JSON file at container startup
(`keycloak-realm/ledger-realm.json`, committed to the repo) rather than configured through
the admin UI — this keeps the auth configuration reproducible, diffable, and code-reviewable
like everything else in this system.

**Clients and grants**:
- **Machine clients** (client-credentials grant): one confidential client per automated
  caller that needs to call the platform — at minimum `chaos-suite-client` and
  `smoke-test-client`, registered in the realm import with generated secrets stored the same
  way other V1 credentials are (env-var-overridable defaults in Docker Compose, not
  hardcoded into application code).
- **Human users** (Resource Owner Password Credentials grant): 2-3 seeded demo users
  (`alice`, `bob`) with a basic `user` role, created via the realm import's `users` section.
  No registration flow, no password reset, no browser-redirect login (authorization-code
  flow) — there is no UI anywhere in this system yet to redirect to, so building that flow
  now would be speculative. This is enough to demonstrate both grant types work end-to-end
  and gives the resume/interview story real breadth (client-credentials *and* password
  grant, not just one) without over-building for a consumer that doesn't exist.

**What V2 does NOT include**: authorization-code + PKCE flow, a login UI, user
self-registration, password reset, fine-grained per-endpoint role authorization beyond "has
a valid token." These are reasonable V3+ additions once a real frontend or more complex
authorization model exists to justify them.

---

## Component 3: Holds Service

**Boundary**: owns hold lifecycle only (create/authorize, capture, release). Never writes to
Ledger's tables. Tracks *available* balance via its own locally-cached `held_balance`,
re-validated against Ledger's authoritative balance at hold-creation time.

**Schema (`holds_db`)**:

Ledger Service's internal `accounts.id` (UUID) is distinct from its external-facing
`account_ref` (string) — `POST /transactions` addresses accounts by `account_ref`, not by
Ledger's internal UUID, which Holds Service never sees. Holds Service therefore identifies
accounts by `account_ref` (string) throughout its own schema and API, so capture can call
`POST /transactions` directly without an extra resolution round-trip.

Capturing a hold produces a Ledger transaction with two sides — a debit (the held account)
and a credit (a destination). `POST /holds` therefore takes both a source `accountRef` (the
account being held) and a `destinationAccountRef` (e.g. a merchant/payee account) up front,
mirroring how a real card hold authorizes a specific merchant charge; both are stored on the
hold row and used verbatim at capture time.

```sql
CREATE TABLE holds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_ref             VARCHAR(128) NOT NULL,
    destination_account_ref VARCHAR(128) NOT NULL,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency                CHAR(3) NOT NULL,
    status                  VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
    idempotency_key         VARCHAR(255) NOT NULL UNIQUE,
    expires_at              TIMESTAMPTZ NOT NULL,
    captured_amount_minor   BIGINT NOT NULL DEFAULT 0,
    created_transaction_id  UUID,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_holds_account_status ON holds(account_ref, status);

CREATE TABLE account_balance_cache (
    account_ref     VARCHAR(128) PRIMARY KEY,
    posted_balance_minor  BIGINT NOT NULL DEFAULT 0,
    held_balance_minor    BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_holds_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**API**:
- `POST /holds` — body `{accountRef, destinationAccountRef, amountMinor, currency,
  expiresInSeconds}`, header `Idempotency-Key`. Returns `{holdId, status: ACTIVE, expiresAt}`.
- `POST /holds/{id}/capture` — body `{amountMinor}` (≤ remaining hold amount, partial
  capture supported), header `Idempotency-Key`. Calls Transaction Processor's
  `POST /transactions` with `debitAccountRef = hold.accountRef`,
  `creditAccountRef = hold.destinationAccountRef`, and
  `Idempotency-Key = hold-capture-{holdId}`; on success marks the hold `CAPTURED` (any
  uncaptured remainder is released in the same operation).
- `POST /holds/{id}/release` — releases the remaining hold amount. Idempotent: a no-op if
  the hold is already in a terminal state.
- `GET /holds/{id}` — status lookup.
- `GET /accounts/{accountRef}/available-balance` — `posted_balance_minor - held_balance_minor`.

**Integration with Ledger/Transaction Processor**: placing a hold never touches Ledger — it
only adjusts `held_balance_minor` locally. Only capture calls the real, idempotent
`POST /transactions`. Ledger Service remains completely unaware holds exist, matching V1's
established pattern of keeping the core ledger free of concerns that live beside it.

**Events**: publishes `hold.created`, `hold.captured`, `hold.released` via the
`outbox_events` table (written in the same DB transaction as the corresponding `holds` row
change). A `@Scheduled` polling publisher (not embedded Debezium — see "New V2-Wide
Decisions" above) reads unpublished rows periodically and publishes them to RabbitMQ,
marking `published_at` on success. Nothing in V2 consumes these events yet; they exist for
future consumers (a later version's Fees or Gateway Simulator service, or an audit/
notification service). Holds Service DOES consume one real event today —
`ledger.transaction.posted` — to keep `account_balance_cache.posted_balance_minor` fresh,
deduped via `processed_events` using the same pattern V1 established for Transaction
Processor's consumer.

**Correctness**:
- **Hold-vs-hold oversubscription**: `SELECT ... FOR UPDATE` on the single
  `account_balance_cache` row inside the hold-creation transaction, checking
  `amount_minor <= posted_balance_minor - held_balance_minor` atomically before inserting
  the hold and incrementing `held_balance_minor` — the same ordered-locking discipline as
  V1's `AccountRepository.lockAccountsForUpdate`, simplified to a single row since only one
  account is involved per hold.
- **Known accepted gap** (unchanged from the platform spec, deliberately not solved in V2):
  a direct `POST /transactions` call that bypasses Holds Service can still overdraw an
  account with active holds, since Ledger Service has no concept of "held" funds and the two
  services share no lock. This is documented, not enforced — solving it would require either
  making Ledger Service hold-aware (a larger, cross-cutting change) or synchronous
  cross-service locking (reintroducing the tight coupling this platform's architecture
  avoids elsewhere). Clients are expected to route spend-checks through Holds Service's
  `available-balance` endpoint by convention.
- **Capture race**: guarded by the `version` optimistic-lock column plus the capture
  endpoint's idempotency key — a losing concurrent capture attempt fails the version check
  and is expected to retry or inspect the hold's resulting state via `GET /holds/{id}`.
- **Expiry**: a `@Scheduled` sweep finds `ACTIVE` holds past `expires_at`, transitions them
  to `EXPIRED` under row lock, decrements `held_balance_minor` by the hold's remaining
  amount, and emits `hold.released`. The sweep strictly expires — it never attempts to
  detect or correct balance drift from other causes; that would be a distinct
  reconciliation-style job, out of scope for V2 (V1's reconciliation job is the platform's
  only such auditor today, and it audits Ledger/Processor, not Holds).
- **Cache staleness**: if a hold's `amount_minor` is within 10% of the cached available
  balance (`posted_balance_minor - held_balance_minor`), re-fetch the authoritative posted
  balance from Ledger Service synchronously (`GET /accounts/{id}`) before deciding, rather
  than trusting `account_balance_cache` unconditionally. Below that margin, the cache is
  trusted as-is — re-fetching on every hold would defeat the purpose of caching, while a
  hold close to the account's apparent limit is exactly the case where staleness is most
  likely to matter.

**Testing**: ordinary Testcontainers-backed unit/integration tests (matching V1's rigor) —
no new Toxiproxy chaos scenarios in V2. Coverage should include: the hold-vs-hold locking
race under concurrent load (mirroring V1's `AccountRepositoryLockingIntegrationTest`
pattern), capture idempotency (including the optimistic-lock race), the expiry sweep, and
the polling outbox publisher actually delivering to RabbitMQ.

**Docker Compose changes**: new `holds-service` + `holds-db` containers, plus `keycloak` and
`api-gateway` from Components 1-2 above.

---

## Verification

- `docker compose up` brings up the full V2 stack (V1's 6 containers + `holds-db`,
  `holds-service`, `keycloak`, `api-gateway`) from a clean state.
- Each service's Testcontainers-backed test suite passes (`mvn test` across the reactor).
- A manual/scripted smoke test through the gateway: obtain a token via client-credentials,
  create a hold, attempt a second hold that would oversubscribe and confirm rejection,
  capture the first hold, confirm the resulting Ledger transaction and balance are correct.
  Repeat token acquisition via the password grant for a seeded demo user, confirming the
  same flow works identically.
- V1's existing chaos suite and reconciliation job continue to pass unmodified, confirming
  V2's additions didn't regress V1's fault-tolerance guarantees.

## Next Step

Once this spec is approved, invoke the writing-plans flow to turn it into a step-by-step V2
implementation plan, following the same TDD/task-review discipline used for V1.
