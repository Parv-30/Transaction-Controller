# Ledger — Fault-Tolerant Transaction Processing System (V3)

A double-entry ledger built to guarantee correctness — no duplicate or lost transactions —
even under concurrent load or mid-process failures. V2 added a Holds Service, an API
Gateway as the single client-facing entry point, and OAuth2/JWT authentication via
Keycloak. V3 adds multi-currency accounts and an FX Service, enabling cross-currency
transfers via a saga with automatic compensation.

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

## Architecture

Five Spring Boot microservices, database-per-service, fronted by an API Gateway:

- **API Gateway** (`:8080`) — the platform's single client-facing entry point. Routes
  `/transactions/**`, `/reconciliation/**`, `/accounts/**`, `/wallets/**`, and
  `/transfers/**` to Ledger Service; `/holds/**` and `/accounts/*/available-balance` to
  Holds Service (the more specific `available-balance` route is declared before the general
  `/accounts/**` route, so it still reaches Holds Service rather than Ledger Service); and
  `/rates/**` / `/conversions/**` to FX Service. Validates JWTs as an OAuth2 resource
  server; no unauthenticated request reaches a downstream service.
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
- **Keycloak** (`:8180`, `ledger` realm) — the auth provider. Issues JWTs for the
  client-credentials and password grants above; the API Gateway validates tokens against
  it.

See [docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md](docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md)
for the full platform design (V1-V5) and
[docs/superpowers/plans/2026-09-02-ledger-v1-implementation.md](docs/superpowers/plans/2026-09-02-ledger-v1-implementation.md) /
[docs/superpowers/plans/2026-09-04-ledger-v2-implementation.md](docs/superpowers/plans/2026-09-04-ledger-v2-implementation.md)
for how V1 and V2 were built.

## Running locally

```bash
make up            # builds and starts all containers, then provisions Toxiproxy + CDC
make smoke-test     # posts a transaction and a hold end-to-end and verifies reconciliation is clean
make chaos-test     # runs all 5 chaos scenarios
make down           # tears down and removes volumes
```

`make up` runs `docker compose up -d --build` (now bringing up 12 containers: the V1 six
plus `holds-db`, `holds-service`, `keycloak`, `api-gateway`, `fx-db`, and `fx-service`)
followed by `scripts/provision.sh`, which configures the Toxiproxy proxies and the Debezium
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
TOKEN=$(bash scripts/get-token.sh alice)    # password grant, demo user "alice"
TOKEN=$(bash scripts/get-token.sh bob)      # password grant, demo user "bob"
```

Example authenticated call — create a hold through the gateway:

```bash
curl -X POST http://localhost:8080/holds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: <client-generated-uuid>" \
  -d '{"accountRef":"smoke-a","destinationAccountRef":"smoke-b","amountMinor":500,"currency":"USD","expiresInSeconds":3600}'
```

## Running tests

```bash
mvn clean verify    # unit + Testcontainers integration tests across all five modules
```

## API

All endpoints below are reached through the **API Gateway** at `http://localhost:8080` and
require an `Authorization: Bearer <token>` header (see "Getting a token" above). Ledger
Service's own port (`:8090`), Holds Service's own port (`:8082`), and FX Service's own port
(`:8083`) are internal — reachable directly on the host for local debugging, but not the
intended client path.

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

## Known limitations

**Carried over from V1:**

- Single-instance Transaction Processor only — the embedded Debezium engine holds an
  exclusive Postgres replication slot. Horizontal scaling is out of scope for V1/V2.
- No fees or external payment simulation yet — those are V4/V5 of the full platform spec.
- Write-path only for the ledger itself — there is no `GET /transactions/{id}`. Accounts are
  seeded directly via SQL (see `scripts/smoke-test.sh` and the `chaos/` scripts) rather than
  through an API, since account provisioning is out of scope for V1/V2.

**New in V2:**

- **Overdraw gap**: a direct `POST /transactions` call bypassing Holds Service can still
  overdraw an account that has active holds against it — Ledger Service's posted balance and
  Holds Service's available-balance cache are separate, asynchronously-synced views, and
  nothing at the transaction-posting path itself consults outstanding holds. Clients are
  expected to use `GET /accounts/{accountRef}/available-balance` for spend-checks by
  convention; this is a documented, accepted limitation rather than a bug.
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
- **Quote staleness is detectable but not enforced**: `FxQuote` carries `expiresAt` and a
  `stale` flag, but the cross-currency transfer saga does not check either — a locked quote
  is used to post both legs even if it has gone stale by the time the second leg runs.
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
