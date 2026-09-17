# Ledger — Fault-Tolerant Transaction Processing System (V5)

A double-entry ledger built to guarantee correctness — no duplicate or lost transactions —
even under concurrent load or mid-process failures. V2 added a Holds Service, an API
Gateway as the single client-facing entry point, and OAuth2/JWT authentication via
Keycloak. V3 adds multi-currency accounts and an FX Service, enabling cross-currency
transfers via a saga with automatic compensation. V5 adds a Gateway Simulator modeling
an external payment rail — inbound deposits via webhook and outbound withdrawals with
saga-style compensation — the platform's first component that has to survive genuinely
hostile, non-cooperating input rather than internal, reliable-eventually traffic. (V4/Fees
Service is permanently out of scope, per an explicit platform-wide scoping decision.)

## What this demonstrates

- **Idempotent transaction API**: client-supplied `Idempotency-Key` header, safe to retry
  under any failure mode, backed by a DB unique constraint (not just an application-level
  check).
- **Transactional outbox pattern**: every posted transaction's async event is written in the
  same DB transaction as the ledger entries it represents — atomic by construction, not by
  convention.
- **Embedded Debezium CDC**: the Transaction Processor tails Ledger Service's Postgres WAL
  directly (no Kafka) to relay outbox events to RabbitMQ.
- **Exactly-once-in-effect consumption**: RabbitMQ delivers at-least-once; a dedup gate keyed
  on the outbox event's UUID makes redelivery safe.
- **Reconciliation job**: a scheduled, read-only correctness auditor cross-checking Ledger's
  outbox against the Processor's consumption state.
- **Toxiproxy-based chaos test suite**: 5 scripted failure scenarios (broker outage, source-DB
  crash, consumer crash, duplicate delivery, mid-transaction network partition), each
  asserting the system recovers with no duplicate or lost transaction.
- **OAuth2/JWT authentication via Keycloak**: client-credentials grant for machine clients
  (the chaos suite, the smoke test) and password grant for demo users (`alice`, `bob`),
  enforced at the API Gateway as a resource server.
- **Hold authorize/capture/release**: Holds Service places a hold that atomically locks
  funds out of an account's *available* balance (not its posted balance), then either
  captures it into a real ledger transaction or releases it back.
- **Cross-currency transfers via a two-legged saga with compensation**: a transfer between
  accounts in different currencies locks an FX quote, posts a debit leg in the source
  currency and a credit leg in the destination currency against platform-internal clearing
  accounts, and automatically reverses the first leg (compensates) if the second leg fails.
  A crash-recovery sweep resolves any saga left mid-flight by a service crash.
- **External rate integration** (Frankfurter, free ECB-backed API) with staleness detection
  and last-known-good fallback: rates are synced hourly and cached, so a temporary outage of
  the upstream rate provider doesn't stop quotes from being served.
- **Cross-service atomicity for hold-aware transaction posting, with an explicit fail-closed
  availability tradeoff**: `POST /transactions` now checks Holds Service's held balance
  synchronously before posting, closing the previously-documented overdraw gap; if Holds
  Service is unreachable the transaction is rejected rather than risking an overdraw.
- **Prometheus + Grafana observability across all 6 services**: transaction latency,
  failure/replay/redelivery counters, reconciliation-mismatch gauges, saga-compensation and
  FX-quote-failure counters.
- **External payment-rail simulation**: webhook deduplication at three layers, saga-style
  compensation on withdrawal failure/timeout, and out-of-order-confirmation handling via
  retryable rejection rather than a placeholder-state machine.
- **Admin-gated read/reversal API surface**: seven new endpoints (transaction/account/hold
  lookups, reconciliation-run history, and manual transaction reversal) gated behind an
  `admin` Keycloak realm role, enforced by a custom `realm_access.roles`-aware JWT authorities
  converter at the API Gateway.
- **A browser-based frontend**: a React SPA (`web/`, served by nginx in production) providing
  both an end-user banking UI (`/app/*`) and a role-gated admin console (`/admin/*`), the
  platform's first client-facing surface beyond `curl`/scripts. Logs in against Keycloak via
  a new public, PKCE-only OAuth2 client (`ledger-web`) — no shared secret, unlike every other
  existing Keycloak client in this realm.

## Architecture

Six Spring Boot microservices, database-per-service, fronted by an API Gateway:

- **API Gateway** (`:8080`) — the platform's single client-facing entry point. Routes
  `/transactions/**`, `/reconciliation/**`, `/accounts/**`, `/wallets/**`, and
  `/transfers/**` to Ledger Service; `/holds/**` and `/accounts/*/available-balance` to
  Holds Service (the more specific `available-balance` route is declared before the general
  `/accounts/**` route, so it still reaches Holds Service rather than Ledger Service);
  `/rates/**` / `/conversions/**` to FX Service; and `/webhooks/**`, `/simulator/**`,
  `/external-deposits/**`, `/external-withdrawals/**` to Gateway Simulator. Validates JWTs
  as an OAuth2 resource server; no unauthenticated request reaches a downstream service.
  `GET /accounts`, `GET /accounts/{accountRef}`, `GET /transactions`,
  `GET /transactions/{id}`, `POST /transactions/{id}/reverse`, `GET /reconciliation/runs`,
  and `GET /holds` additionally require the `admin` realm role — see "Admin API" below.
- **Ledger Service** (`:8090`) — owns accounts, transactions, entries, and the outbox. No
  longer directly client-facing in V2+ — all client traffic goes through the gateway. In V3
  it also owns multi-currency accounts (grouped into wallets via `accountGroupId`) and the
  cross-currency transfer saga.
- **Transaction Processor** (`:8081`) — embeds Debezium, publishes to RabbitMQ, consumes with
  dedup, exposes processed-event status for reconciliation.
- **Holds Service** (`:8082`) — places, captures, and releases holds against an account's
  available balance; maintains its own balance cache synced asynchronously from
  `ledger.transaction.posted` events on RabbitMQ.
- **FX Service** (`:8083`) — syncs exchange rates from Frankfurter (a free, ECB-backed,
  no-API-key-required rate source) on an hourly schedule, and serves rate lookups and
  conversion quotes used by Ledger Service's cross-currency transfer saga.
- **Gateway Simulator** (`:8084`) — models an external payment rail, the platform's only
  component simulating a real trust boundary and its inherent unreliability (duplicate
  deliveries, out-of-order confirmations, requests that never resolve). Handles two flows:
  - **Inbound deposits**: `POST /webhooks/deposits` is the simulated trust-boundary
    endpoint — it upserts a `webhook_dedup` row keyed by `externalReference` to detect
    redelivery, then (on first delivery only) calls `POST /transactions` on the API
    Gateway, crediting the target account and debiting an `external-clearing-{currency}`
    account, with a deterministic `Idempotency-Key` as a second line of defense.
    `POST /simulator/deposits` is a control-plane convenience that originates a deposit
    in-process for demos/chaos scripts without hand-crafting a webhook payload.
  - **Outbound withdrawals**: a `WITHDRAWAL_EXTERNAL` transaction posted through the normal
    `POST /transactions` path is picked up asynchronously off the existing
    `ledger.transaction.posted` RabbitMQ topic; Gateway Simulator records it `SUBMITTED`
    and waits for resolution, which always arrives later via
    `POST /simulator/withdrawals/{id}/confirm` (standing in for the rail's own async
    callback) or via a scheduled sweep that marks a stuck `SUBMITTED` row `TIMED_OUT` after
    a configurable timeout. Both a `FAILED` confirmation and a `TIMED_OUT` sweep trigger the
    same saga-style compensating reversal — a second `POST /transactions` call debiting the
    clearing account and crediting the original account back, with its own deterministic
    idempotency key. A confirm call that arrives before the `SUBMITTED` row exists (a race
    with the transaction event still in flight) gets a retryable `409` rather than a new
    placeholder-state machine.

  Like `TransactionPoster`'s existing synchronous Holds Service check, both
  `DepositService.handleWebhook` and `WithdrawalResolutionService.reverse` make a blocking
  `POST /transactions` call to the API Gateway while holding a local DB transaction open on
  Gateway Simulator's own database — the same deliberate lock-duration-vs-atomicity tradeoff
  already documented for the Holds Service check below, applied a second time at this new
  trust boundary.
- **Keycloak** (`:8180`, `ledger` realm) — the auth provider. Issues JWTs for the
  client-credentials and password grants above; the API Gateway validates tokens against
  it. A `realm_access.roles` claim (e.g. `["admin","user"]`) carries realm roles; the demo
  user `admin` (password grant, see "Getting a token") has the `admin` role, while `alice`
  and `bob` do not.
- **Web** (`:8085`) — a Vite + React + TypeScript SPA, served by nginx in production. The
  public landing page (`/`) starts the Keycloak Authorization Code + PKCE flow via the new
  `ledger-web` public client; a caller with the `admin` realm role lands on `/admin`, every
  other authenticated caller lands on `/app`. Every request goes through the API Gateway,
  same as every other client. `GET /holds` and `GET /transactions` are no longer unconditionally
  admin-gated: a non-admin, authenticated caller may list either scoped to a specific
  `accountRef` they name (never the unscoped full list), enforced inside Holds Service and
  Ledger Service respectively via a new, minimal JWT-role-decoding path
  (`JwtRoleReader`/`CallerContext` in each service) — the first time either service has needed
  to know the caller's identity rather than trusting the gateway's authorization decision
  alone.
- **Prometheus** (`:9090`) — scrapes `/actuator/prometheus` from all 6 Spring Boot services
  and stores the resulting metrics.
- **Grafana** (`:3000`) — dashboards over Prometheus's data; anonymous viewer access is
  enabled for local/demo convenience (see "Known limitations").

**Admin-role authorization at the gateway**: Spring Security's default JWT authorities
converter only reads a flat `scope`/`scp` claim — it does not understand Keycloak's nested
`realm_access.roles` claim. `KeycloakRealmRoleConverter`
(`api-gateway/src/main/java/com/ledger/apigateway/KeycloakRealmRoleConverter.java`) reads
that nested claim directly and maps each role to a `ROLE_*` Spring Security authority,
wrapped in a `ReactiveJwtAuthenticationConverterAdapter` for this WebFlux gateway's
reactive security filter chain. `SecurityConfig` then gates `GET /accounts`,
`GET /accounts/{accountRef}`, `GET /transactions`, `GET /transactions/{id}`,
`POST /transactions/{id}/reverse`, `GET /reconciliation/runs`, and `GET /holds` behind
`hasAuthority("ROLE_admin")`, with explicit `HttpMethod.GET` qualifiers so the
otherwise-identical `POST /accounts` (account creation) and `POST /holds` (hold creation)
paths remain open to any authenticated user, unchanged.

`POST /transactions` now atomically checks Holds Service's held balance before posting,
closing the overdraw gap previously documented under "Known limitations": Ledger Service
calls Holds Service synchronously as part of the transaction-posting path and rejects the
transaction if the held amount would be exceeded. This introduces a deliberate fail-closed
tradeoff — Ledger Service's write availability for `POST /transactions` now depends on
Holds Service being reachable; if Holds Service is down, transactions are rejected rather
than risking a silent overdraw. See "Known limitations" for the full tradeoff writeup.

See [docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md](docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md)
for the full platform design (V1-V5) and
[docs/superpowers/plans/2026-09-02-ledger-v1-implementation.md](docs/superpowers/plans/2026-09-02-ledger-v1-implementation.md) /
[docs/superpowers/plans/2026-09-04-ledger-v2-implementation.md](docs/superpowers/plans/2026-09-04-ledger-v2-implementation.md)
for how V1 and V2 were built.

## Running locally

```bash
make up            # builds and starts all containers, then provisions Toxiproxy + CDC
make smoke-test     # posts a transaction and a hold end-to-end and verifies reconciliation is clean
make chaos-test     # runs chaos scenarios 01-05; run 06-09 directly, see below
make down           # tears down and removes volumes
```

`scripts/smoke-test.sh` also exercises a full Gateway Simulator round trip: a simulated
deposit credited end-to-end, then a `WITHDRAWAL_EXTERNAL` transaction failed via the confirm
endpoint and verified to reverse back to the correct balance.

`make chaos-test` currently wires up only the original 5 scenarios; the 4 added since (V3's
`06_fx_saga_crash_mid_leg.sh` and V5's `07_duplicate_webhook.sh`,
`08_withdrawal_timeout_sweep.sh`, `09_out_of_order_confirmation.sh`) are run directly:

```bash
for s in chaos/scenarios/*.sh; do bash "$s" || break; done
```

Gateway Simulator is reachable directly at `http://localhost:8084` for local debugging, or
through the gateway's `/webhooks/**`, `/simulator/**`, `/external-deposits/**`, and
`/external-withdrawals/**` routes like every other client-facing path.

Grafana is reachable at `http://localhost:3000` with anonymous viewer access — no login is
needed to view the platform dashboard locally. Prometheus's own UI is reachable at
`http://localhost:9090` for ad-hoc queries.

`make up` runs `docker compose up -d --build` (now bringing up 16 containers: the V1 six
plus `holds-db`, `holds-service`, `keycloak`, `api-gateway`, `fx-db`, `fx-service`,
`prometheus`, `grafana`, `gateway-sim-db`, and `gateway-simulator`) followed by
`scripts/provision.sh`, which configures the Toxiproxy proxies and the Debezium
CDC grant/publication that the stack needs to actually work (see `scripts/provision.sh` for
why these can't be baked into the compose file or Postgres init scripts) — this part of the
flow is unchanged from V1. `provision.sh` now also calls `scripts/seed-clearing-accounts.sh`,
which creates the `fx-clearing-USD`/`EUR`/`GBP` platform-internal netting accounts that the
cross-currency transfer saga posts its legs against — these are not created via the public
`POST /accounts` API (see "Known limitations"). `make smoke-test` (`scripts/smoke-test.sh`)
assumes provisioning has already happened and only does verification — it now also fetches a
Keycloak token and exercises the Holds flow through the gateway — if you bring the stack up
some other way, run `bash scripts/provision.sh` once first.

### Getting a token

All client-facing traffic goes through the API Gateway (`:8080`) and requires a bearer
token issued by Keycloak (`:8180`, `ledger` realm). `scripts/get-token.sh` fetches one for
manual testing:

```bash
TOKEN=$(bash scripts/get-token.sh client)   # client-credentials grant (chaos-suite-client)
TOKEN=$(bash scripts/get-token.sh alice)    # password grant, demo user "alice" (no admin role)
TOKEN=$(bash scripts/get-token.sh bob)      # password grant, demo user "bob" (no admin role)
TOKEN=$(bash scripts/get-token.sh admin)    # password grant, demo user "admin" (has the admin realm role)
```

Example authenticated call — create a hold through the gateway:

```bash
curl -X POST http://localhost:8080/holds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: <client-generated-uuid>" \
  -d '{"accountRef":"smoke-a","destinationAccountRef":"smoke-b","amountMinor":500,"currency":"USD","expiresInSeconds":3600}'
```

### Running the web frontend

`make up` now also builds and starts `web` (`http://localhost:8085`), nginx-served in
production. For local frontend development with hot reload instead:

```bash
cd web
npm install
npm run dev   # http://localhost:5173, proxied against the already-running API Gateway/Keycloak
```

Log in with any of the demo users (`alice`, `bob`, `admin` — see "Getting a token" above for
their passwords); the web app drives the same Keycloak Authorization Code + PKCE flow a real
browser client would use, distinct from the password-grant flow `scripts/get-token.sh` uses
for scripting/testing.

## Running tests

```bash
mvn clean verify    # unit + Testcontainers integration tests across all six modules
```

## API

All endpoints below are reached through the **API Gateway** at `http://localhost:8080` and
require an `Authorization: Bearer <token>` header (see "Getting a token" above). Ledger
Service's own port (`:8090`), Holds Service's own port (`:8082`), FX Service's own port
(`:8083`), and Gateway Simulator's own port (`:8084`) are internal — reachable directly on
the host for local debugging, but not the intended client path.

The new V3 endpoints are split across two services. `POST /accounts`,
`GET /wallets/{groupId}/accounts`, and `POST /transfers/cross-currency` are all owned and
served by **Ledger Service** (routed through the gateway like everything else above).
`GET /rates/{base}/{quote}` and `POST /conversions/quote` are owned and served by **FX
Service** directly — the gateway routes `/rates/**` and `/conversions/**` straight to FX
Service, not to Ledger Service.

`POST /transactions`

```bash
curl -X POST http://localhost:8080/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: <client-generated-uuid>" \
  -d '{"debitAccountRef":"acct-a","creditAccountRef":"acct-b","amountMinor":500,"currency":"USD","description":"transfer"}'
```

`POST /reconciliation/runs` — triggers an on-demand reconciliation pass and returns a summary
of any findings.

`POST /holds` — authorizes a hold, atomically locking funds out of the source account's
*available* balance.

```bash
curl -X POST http://localhost:8080/holds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: <client-generated-uuid>" \
  -d '{"accountRef":"acct-a","destinationAccountRef":"acct-b","amountMinor":500,"currency":"USD","expiresInSeconds":3600}'
```

`POST /holds/{id}/capture` — captures a held amount, posting a real ledger transaction.

`POST /holds/{id}/release` — releases a hold without posting a transaction, restoring the
account's available balance.

`GET /holds/{id}` — fetches a hold's current status.

`GET /accounts/{accountRef}/available-balance` — returns an account's balance minus any
active holds against it. Clients are expected to spend-check against this endpoint before
posting a transaction directly (see "Known limitations" below).

`POST /accounts` — creates a multi-currency account (a "wallet member"). Optionally joins an
existing wallet by passing an `accountGroupId` shared with another account; omitting it
starts a new wallet. Rejects any `accountRef` starting with `fx-clearing-` (reserved for the
platform's internal netting accounts — see "Known limitations").

```bash
curl -X POST http://localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accountRef":"alice-usd","currency":"USD"}'
```

`GET /wallets/{groupId}/accounts` — lists every account belonging to a wallet
(`accountGroupId`), one per currency.

`POST /transfers/cross-currency` — runs the two-legged cross-currency transfer saga: locks
an FX quote, debits the source account, credits the destination account in its own
currency via the platform's clearing accounts, and automatically compensates (reverses the
debit leg) if the credit leg fails. Returns `200` with status `COMPLETED` on success; any
other terminal status (e.g. `COMPENSATED`) is returned as `422`. Unlike `/transactions` and
`/holds`, the idempotency key here is a body field (`idempotencyKey`), not an
`Idempotency-Key` header.

```bash
curl -X POST http://localhost:8080/transfers/cross-currency \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccountRef":"alice-usd","destAccountRef":"alice-eur","sourceAmountMinor":10000,"idempotencyKey":"<client-generated-uuid>"}'
```

`GET /rates/{base}/{quote}` — served by **FX Service**. Returns the current exchange rate
between two currencies; an optional `?at=<ISO-8601 timestamp>` query parameter looks up the
historical rate as of that time instead.

`POST /conversions/quote` — served by **FX Service**. Returns a locked conversion quote
(amount, rate, and an `expiresAt`) for a given source amount/currency and destination
currency, for use by a caller composing its own transfer logic.

The new V5 endpoints are all owned and served by **Gateway Simulator**.

`POST /simulator/deposits` — control-plane trigger for demos/chaos scripts: originates a
simulated inbound deposit in-process (no separate webhook round-trip) and credits the given
account.

```bash
curl -X POST http://localhost:8080/simulator/deposits \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"accountRef":"alice-usd","amountMinor":5000,"currency":"USD"}'
```

`POST /webhooks/deposits` — the actual simulated trust-boundary endpoint modeling an
inbound webhook from an external payment rail. Deduplicates redelivery of the same
`externalReference` before crediting the target account via a downstream
`POST /transactions` call. See "Known limitations" for the webhook-auth simplification.

`GET /external-deposits/{externalReference}` — fetches a simulated deposit's current
status (`RECEIVED`, `CREDITED`, or `REJECTED`) for polling/demos.

`POST /simulator/withdrawals/{id}/confirm` — control-plane endpoint resolving a pending
`WITHDRAWAL_EXTERNAL` withdrawal, standing in for the rail's own async callback.
`{"outcome":"CONFIRMED"}` finalizes it with no further ledger action;
`{"outcome":"FAILED"}` triggers a compensating reversal transaction. Returns `409` if the
withdrawal hasn't reached `SUBMITTED` status yet (a race with the triggering transaction
event still in flight) — the caller is expected to retry after a short delay. `{id}` may be
either Gateway Simulator's own internal withdrawal id or the original transaction's id
(`POST /simulator/withdrawals/by-transaction/{transactionId}/confirm`).

```bash
curl -X POST http://localhost:8080/simulator/withdrawals/{id}/confirm \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"outcome":"FAILED"}'
```

`GET /external-withdrawals/{id}` — fetches a simulated withdrawal's current status
(`SUBMITTED`, `CONFIRMED`, `FAILED`, `TIMED_OUT`, or `REVERSED`) for polling/demos.

An external withdrawal itself is triggered with no dedicated initiation endpoint — post an
ordinary transaction through the existing `POST /transactions` with
`"transactionType":"WITHDRAWAL_EXTERNAL"`, debiting the customer's account and crediting
`external-clearing-{currency}`; Gateway Simulator picks it up asynchronously off the
`ledger.transaction.posted` event stream.

### Admin API

The following seven endpoints require a bearer token whose Keycloak `realm_access.roles`
claim includes `admin` (the demo `admin` user, or any client/user provisioned with that
realm role) — a token without it gets `403` before the request ever reaches the owning
service. All are owned and served by **Ledger Service** except `GET /holds`, which is
served by **Holds Service**; both are reached through the gateway like every other
endpoint above.

```bash
ADMIN_TOKEN=$(bash scripts/get-token.sh admin)
```

`GET /accounts` — lists all accounts.

`GET /accounts/{accountRef}` — fetches a single account by its reference.

`GET /transactions` — lists all transactions.

`GET /transactions/{id}` — fetches a single transaction by id.

`POST /transactions/{id}/reverse` — reverses a previously posted transaction. This posts a
**new, compensating transaction** with the debit/credit legs swapped — it never mutates or
deletes the original transaction or its ledger entries, preserving the ledger's
append-only/audit-trail property. Returns `404` if `{id}` doesn't identify a real
transaction (this is what proves the request passed the admin-role gate and actually
reached the controller, rather than being rejected at the gateway).

```bash
curl -X POST http://localhost:8080/transactions/{id}/reverse \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

`GET /reconciliation/runs` — lists past reconciliation run results (as opposed to
`POST /reconciliation/runs`, unchanged and open to any authenticated user, which triggers a
new run).

`GET /holds` — served by **Holds Service**. Lists all holds.

## Known limitations

**Carried over from V1:**

- Single-instance Transaction Processor only — the embedded Debezium engine holds an
  exclusive Postgres replication slot. Horizontal scaling is out of scope for V1/V2.
- No fee simulation — Fees Service (V4 of the full platform spec) is permanently out of
  scope, per an explicit scoping decision, not merely "not yet built." External payment
  simulation (V5) is now implemented — see "New in V5" below.
- Accounts used in the chaos suite and smoke test are still seeded directly via SQL rather
  than through `POST /accounts`, matching those scripts' existing style.

**New in V2:**

- **Overdraw gap — closed for the direct `POST /transactions` path**: a direct
  `POST /transactions` call used to be able to overdraw an account that had active holds
  against it, because Ledger Service's posted balance and Holds Service's available-balance
  cache were separate, asynchronously-synced views that the transaction-posting path never
  consulted. This hardening work closes that gap: `POST /transactions` now synchronously
  checks Holds Service's held balance before posting, and a transaction that would overdraw
  the account given its outstanding holds is rejected (`422`). The new limitation this
  introduces is a cross-service coupling: Ledger Service's write availability for
  `POST /transactions` now depends on Holds Service being reachable, and the failure mode is
  deliberately fail-closed — if Holds Service can't be reached, the transaction is rejected
  rather than risking a silent overdraw. As a secondary effect, the held-balance check runs
  while `SELECT ... FOR UPDATE` row locks are held on both accounts; sustained Holds Service
  slowness (not just outages) will increase lock contention on hot accounts under load,
  though a bounded timeout (`holds.held-balance-timeout-ms`) caps the worst-case lock duration.
  `GET /accounts/{accountRef}/available-balance` remains available for clients that want to
  pre-check before attempting a transfer.
- **Auth scope**: Keycloak in V2 covers the client-credentials grant (machine clients) and
  the password grant (demo users `alice`/`bob`) only. There is no browser login /
  authorization-code flow and no user self-registration.

**New in V3:**

- **No dead-letter/manual-intervention path**: if a cross-currency transfer's compensation
  leg itself keeps failing, its `pending_fx_transfers` row can stay `COMPENSATING`
  indefinitely — the crash-recovery sweep keeps retrying it, but there is no escalation path
  or operator alert. This is a documented, accepted gap per the V3 design spec, not a bug.
- **Limited currency coverage**: only USD, EUR, and GBP are seeded/configured. Frankfurter
  itself covers more currencies, but this project does no dynamic currency administration —
  adding a currency requires a code/config change.
- **Quote expiry — fixed, quote expiry is now enforced before leg 2**: `FxQuote` carries an
  `expiresAt` timestamp; the cross-currency transfer saga now checks it immediately before
  posting the second leg and fails the transfer (triggering compensation of the first leg)
  if the locked quote has expired in the interim, rather than posting both legs against a
  stale rate.
- **Sub-minor-unit rounding is undocumented/untested**: conversion amounts are computed via
  `BigDecimal` and truncated with `longValue()`. For exchange rates that don't divide exactly
  into whole minor units, the truncation remainder is silently absorbed by the clearing
  account rather than being tracked or reconciled explicitly.
- **Clearing accounts run by convention, not by mechanism**: `fx-clearing-USD`/`EUR`/`GBP`
  are platform-internal netting accounts seeded by a Flyway migration
  (`V4__seed_fx_clearing_accounts.sql`) and are allowed to go negative because
  `TransactionPoster` grants any `fx-clearing-*` ref an unlimited-overdraft exemption. They
  are not real funded accounts, and `POST /accounts` rejects any client-supplied ref with
  that prefix specifically to prevent a client from creating one and exploiting the
  overdraft exemption to mint money.
- **Grafana anonymous-viewer access**: Grafana is configured for anonymous viewer access
  with no login required, which is appropriate only for local/demo use — it is not a
  production-safe configuration and would need real authentication before being exposed
  beyond a local machine.

**New in V5:**

- **`POST /webhooks/deposits` uses this platform's own OAuth2 auth, not a real
  webhook-signature scheme**: a real payment rail would authenticate its webhook calls
  independently of your application's own auth (e.g. a shared secret or a signed payload),
  not with a bearer token issued by your own Keycloak realm. Modeling that is explicitly out
  of scope per the V5 design spec — this is a deliberate simplification of the simulated
  trust boundary, not an oversight.
- **Short withdrawal timeout**: the window Gateway Simulator waits for a withdrawal
  confirmation before its sweep marks it `TIMED_OUT` and reverses it defaults to 60 seconds
  (`gateway-sim.withdrawal-timeout-seconds`, configurable). A real payment rail's settlement
  window would typically be much longer; 60 seconds is appropriate for a demo/chaos-test
  platform where scenarios need to complete quickly, not for a production integration.

**New admin API additions:**

- **No pagination**: `GET /accounts`, `GET /transactions`, and `GET /holds` return every
  row unconditionally. Acceptable at this platform's current demo scale; a real deployment
  would need cursor- or offset-based pagination before these could be exposed against a
  production-sized dataset.
- **Coarse-grained role model**: authorization is a single flat `admin` realm role with no
  finer-grained scopes (e.g. read-only auditor vs. an operator who can reverse
  transactions) and no per-resource ownership checks — any `admin`-role token can read or
  reverse anything
  (`GET /holds` and `GET /transactions` are now the first exception: a non-admin caller may
  read their own named account's holds/transactions, though "own" here means only "the
  account ref they supply," since this platform still has no formal link between a Keycloak
  identity and a ledger `accountRef`).
- **No audit log for admin actions**: `POST /transactions/{id}/reverse` is recorded only as
  an ordinary compensating transaction; there is no separate record of who (which token
  subject) triggered a reversal or when the read-only admin endpoints were queried.

**New in the web frontend:**

- **No real user-to-account linkage**: every end-user page hardcodes `alice-usd` as "the
  current user's account" rather than deriving it from the logged-in identity, since this
  platform has no such mapping today (see the design spec's Section 4). A real multi-account
  frontend would need this linkage built first.
- **Vite build-time config, not runtime config**: `VITE_KEYCLOAK_BASE_URL`/`VITE_API_BASE_URL`
  are baked into the static JS bundle at Docker build time (`web/Dockerfile`'s build args),
  not read from a runtime environment variable like every other service in this platform —
  an nginx-served static bundle has no server-side process to read `environment:` from.
  Changing either value requires a rebuild of the `web` image, not just a container restart.
- **No E2E test suite**: Vitest + React Testing Library cover the API client, auth/role-routing
  logic, and the transfer/reversal confirmation flows; no browser-driven end-to-end suite
  exists yet (Playwright, per the design spec's Non-Goals, is a plausible later addition).
