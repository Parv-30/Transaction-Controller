# Ledger — Fault-Tolerant Transaction Processing System (V2)

A double-entry ledger built to guarantee correctness — no duplicate or lost transactions —
even under concurrent load or mid-process failures. V2 adds a Holds Service, an API
Gateway as the single client-facing entry point, and OAuth2/JWT authentication via
Keycloak.

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

## Architecture

Four Spring Boot microservices, database-per-service, fronted by an API Gateway:

- **API Gateway** (`:8080`) — the platform's single client-facing entry point. Routes
  `/transactions/**` and `/reconciliation/**` to Ledger Service, and `/holds/**` and
  `/accounts/*/available-balance` to Holds Service. Validates JWTs as an OAuth2 resource
  server; no unauthenticated request reaches a downstream service.
- **Ledger Service** (`:8090`) — owns accounts, transactions, entries, and the outbox. No
  longer directly client-facing in V2 — all client traffic goes through the gateway.
- **Transaction Processor** (`:8081`) — embeds Debezium, publishes to RabbitMQ, consumes with
  dedup, exposes processed-event status for reconciliation.
- **Holds Service** (`:8082`) — places, captures, and releases holds against an account's
  available balance; maintains its own balance cache synced asynchronously from
  `ledger.transaction.posted` events on RabbitMQ.
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

`make up` runs `docker compose up -d --build` (now bringing up 10 containers: the V1 six
plus `holds-db`, `holds-service`, `keycloak`, and `api-gateway`) followed by
`scripts/provision.sh`, which configures the Toxiproxy proxies and the Debezium CDC
grant/publication that the stack needs to actually work (see `scripts/provision.sh` for why
these can't be baked into the compose file or Postgres init scripts) — this part of the
flow is unchanged from V1. `make smoke-test` (`scripts/smoke-test.sh`) assumes provisioning
has already happened and only does verification — it now also fetches a Keycloak token and
exercises the Holds flow through the gateway — if you bring the stack up some other way, run
`bash scripts/provision.sh` once first.

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
mvn clean verify    # unit + Testcontainers integration tests across all four modules
```

## API

All endpoints below are reached through the **API Gateway** at `http://localhost:8080` and
require an `Authorization: Bearer <token>` header (see "Getting a token" above). Ledger
Service's own port (`:8090`) and Holds Service's own port (`:8082`) are internal — reachable
directly on the host for local debugging, but not the intended client path.

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

## Known limitations

**Carried over from V1:**

- Single-instance Transaction Processor only — the embedded Debezium engine holds an
  exclusive Postgres replication slot. Horizontal scaling is out of scope for V1/V2.
- No multi-currency, fees, or external payment simulation yet — those are V3 through V5 of
  the full platform spec.
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
