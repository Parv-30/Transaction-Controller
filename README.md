# Ledger — Fault-Tolerant Transaction Processing System (V1)

A double-entry ledger built to guarantee correctness — no duplicate or lost transactions —
even under concurrent load or mid-process failures.

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

## Architecture

Two Spring Boot microservices, database-per-service:

- **Ledger Service** (`:8080`) — owns accounts, transactions, entries, and the outbox.
- **Transaction Processor** (`:8081`) — embeds Debezium, publishes to RabbitMQ, consumes with
  dedup, exposes processed-event status for reconciliation.

See [docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md](docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md)
for the full platform design (V1-V5) and
[docs/superpowers/plans/2026-09-02-ledger-v1-implementation.md](docs/superpowers/plans/2026-09-02-ledger-v1-implementation.md)
for how V1 was built.

## Running locally

```bash
make up            # builds and starts all 6 containers
make smoke-test     # posts a transaction end-to-end and verifies reconciliation is clean
make chaos-test     # runs all 5 chaos scenarios
make down           # tears down and removes volumes
```

## Running tests

```bash
mvn clean verify    # unit + Testcontainers integration tests across both modules
```

## API

`POST /transactions` (Ledger Service, `:8080`)

```bash
curl -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: <client-generated-uuid>" \
  -d '{"debitAccountRef":"acct-a","creditAccountRef":"acct-b","amountMinor":500,"currency":"USD","description":"transfer"}'
```

`POST /reconciliation/runs` (Ledger Service, `:8080`) — triggers an on-demand reconciliation
pass and returns a summary of any findings.

## Known V1 limitations (by design)

- Single-instance Transaction Processor only — the embedded Debezium engine holds an
  exclusive Postgres replication slot. Horizontal scaling is out of scope for V1.
- No auth yet — API-key auth at the gateway arrives with V2; this is deliberately deferred so
  V1 could ship as a complete, focused artifact first.
- No holds, multi-currency, fees, or external payment simulation yet — those are V2 through
  V5 of the full platform spec.
