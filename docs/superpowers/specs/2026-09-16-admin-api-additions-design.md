# Admin API Additions: Design Spec

## Context

The platform (V1-V3, a hardening pass, and V5/Gateway Simulator) currently exposes only
single-resource, caller-scoped endpoints: `GET /accounts/{ref}/available-balance`,
`POST /transactions`, `GET /holds/{id}`, and similar. There is no way to list or search
accounts, list or search transactions, view reconciliation run history, or reverse a
posted transaction. Every authenticated caller — human or service — is treated identically;
there is no role distinction anywhere in the auth model.

This is the first of two planned follow-on efforts: a full **end-user banking UI** and an
**admin console** are both planned next, and both need a stable backend contract before
frontend work starts. This spec covers only the backend additions those two frontends will
depend on — no frontend code is written as part of this spec's implementation.

## Goals

- List and search accounts across the whole platform (not just within one wallet group).
- List and search transactions, and view a single transaction's full detail (including its
  entries).
- Reverse a posted transaction via a new compensating transaction, preserving the zero-sum
  double-entry invariant and full audit history — never mutating or deleting the original.
- View reconciliation run history (today only `POST /runs` triggers a run; there's no way to
  see past runs).
- List and search holds across all accounts (today only single-hold lookup by id exists).
- Introduce a real `admin` role in the platform's auth model, and gate the above
  admin-shaped endpoints behind it, while leaving every existing endpoint's authorization
  unchanged.

## Non-Goals

- No frontend work of any kind (covered by the next, separate brainstorm).
- No pagination. Every new list endpoint returns a plain array, matching this codebase's
  existing convention (`GET /wallets/{groupId}/accounts` is already unpaginated) — appropriate
  at this platform's data scale, and consistent rather than introducing a novel
  `Page<T>`/`Pageable` pattern nothing else uses.
- No changes to Holds Service's or FX Service's own transaction-posting logic.
- No changes to how holds are released or captured — those endpoints already work for any
  caller regardless of role, since holds were never scoped to their creator; only *listing*
  holds is new here.
- No audit-logging subsystem (who reversed what, when) beyond what the new transaction's own
  `created_at` and its `reversal_of_transaction_id` linkage already provide.

## Section 1 — New Ledger Service Endpoints

### `GET /accounts`

Query params (all optional, combinable):
- `accountRef` — substring match (case-insensitive) against `account_ref`.
- `status` — exact match against `AccountStatus` (`ACTIVE`, `FROZEN`, `CLOSED`).

Returns `List<AccountResponse>` (existing DTO, unchanged):
```java
public record AccountResponse(String accountRef, String currency, long balanceMinor,
                               String status, UUID accountGroupId) {}
```

No results is `200` with an empty list, not `404` — consistent with `GET /wallets/{groupId}/accounts`'s
existing behavior for an empty wallet.

### `GET /accounts/{accountRef}`

Single-account lookup. Returns `AccountResponse`. `404` (`AccountNotFoundException`, already
exists) if not found. This closes a real, pre-existing gap: today the only way to read an
account's own fields is via `available-balance`/`held-balance`, and neither of those returns
`status`, `currency`, or `accountGroupId`.

### `GET /transactions`

Query params (all optional, combinable):
- `accountRef` — matches if this account appears as either the debit or credit side of any
  entry on the transaction (a join through `entries` → `accounts`).
- `status` — exact match against `TransactionStatus` (`POSTED`, `FAILED`, `REVERSED` — see
  Section 1's schema change below for the new `REVERSED` value).
- `since` / `until` — ISO-8601 instants, inclusive lower/upper bound on `created_at`. Either
  may be supplied alone.

Returns `List<TransactionSummaryResponse>`, a new DTO:
```java
public record TransactionSummaryResponse(UUID transactionId, String status,
                                          String transactionType, String debitAccountRef,
                                          String creditAccountRef, long amountMinor,
                                          String currency, String description,
                                          Instant createdAt, UUID reversalOfTransactionId) {}
```
`debitAccountRef`/`creditAccountRef`/`amountMinor`/`currency` are derived from the
transaction's two `entries` rows (one `DEBIT`, one `CREDIT`) joined to `accounts` for the
ref — `Transaction` itself has no direct account-ref columns, only `Entry` does.
`reversalOfTransactionId` is `null` unless this transaction is itself a reversal (see below).

### `GET /transactions/{id}`

Single-transaction detail. Returns a new `TransactionDetailResponse`:
```java
public record TransactionDetailResponse(UUID transactionId, String status,
                                         String transactionType, String description,
                                         Instant createdAt, UUID reversalOfTransactionId,
                                         List<EntryResponse> entries) {}

public record EntryResponse(UUID accountId, String accountRef, String direction,
                             long amountMinor, String currency) {}
```
`404` (new `TransactionNotFoundException`) if not found.

### `POST /transactions/{id}/reverse`

Posts a new, ordinary transaction: debit and credit swapped from the original (the original's
credit account is debited, the original's debit account is credited), same `amountMinor` and
`currency`, `transactionType = "REVERSAL"`, `description = "Reversal of transaction {id}"`.
Goes through the exact same `TransactionPoster`/`TransactionService` path as any other
transaction — same held-balance check (the account being debited in the reversal is
subject to the same fail-closed Holds Service check as any other debit; this is intentional,
not a special case, per the existing invariant that no debit ever bypasses that check except
the two explicitly-exempted clearing-account prefixes), same outbox/CDC/reconciliation
behavior.

Idempotency key is deterministic: `admin-reversal-{originalTransactionId}`, so a retried or
double-clicked reversal request safely replays rather than double-reversing (uses the
existing idempotency-replay mechanism, no new logic needed there).

Validation before posting:
- `404` if the original transaction doesn't exist.
- `409` (new `TransactionAlreadyReversedException`) if the original already has a reversal
  (i.e., some other transaction's `reversal_of_transaction_id` already points at it).
- `409` (new `CannotReverseAReversalException`) if the original transaction is itself a
  reversal (`reversal_of_transaction_id IS NOT NULL`) — reversals cannot be chained.

Returns the newly-created `TransactionSummaryResponse` (same shape as the list endpoint) with
`201`.

**Schema change**: `transactions` gains a nullable `reversal_of_transaction_id UUID`
column (new migration, `V8__add_reversal_tracking_to_transactions.sql` — confirmed `V7` is
the current highest ledger-service migration), plus a partial unique index
`ON transactions (reversal_of_transaction_id) WHERE reversal_of_transaction_id IS NOT NULL`
enforcing "at most one reversal per original" at the database level, not just in application
code (mirroring this codebase's established practice of using DB constraints as the real
enforcement mechanism, not a Java-side check that a race could slip past). `TransactionStatus`
gains a `REVERSED` value (the *original* transaction's status is updated to `REVERSED` once
its reversal posts successfully, so `GET /transactions/{id}` on the original immediately
reflects that it's been reversed without needing to search for whether some other row points
at it).

### `GET /reconciliation/runs`

No query params (run history at this platform's scale is small; add filters later if ever
needed). Returns `List<ReconciliationRunResponse>`, newest-first by `started_at`:
```java
public record ReconciliationRunResponse(UUID runId, String status, Instant startedAt,
                                         Instant finishedAt, int transactionsChecked,
                                         int entriesImbalanceCount, int outboxMissingCount,
                                         int outboxStuckCount) {}
```
(Omits the raw `summary` JSONB blob from the list response — that field exists for the
`FAILED` case's internal failure-reason string, which isn't meaningful to surface in a list
view; `GET /reconciliation/runs/{id}` is not being added since nothing in this spec's goals
needs single-run detail beyond what the list already carries.)

## Section 2 — Auth Model Changes

- New Keycloak realm role: `admin`, added to `keycloak-realm/ledger-realm.json`.
- New demo user in the same realm-import file: username `admin`, password `admin-password`,
  `realmRoles: ["user", "admin"]` (has both roles, so the same account can exercise ordinary
  endpoints too if useful for testing/demos).
- `api-gateway`'s `SecurityConfig.java` gains a role-gated rule for the admin-shaped routes,
  ordered before the existing `anyExchange().authenticated()` catch-all (same DSL-ordering
  principle already used for the `/actuator/**` carve-out — a more specific matcher must be
  registered before a broader one, or it's dead code):
  ```java
  .pathMatchers(HttpMethod.GET, "/accounts").hasAuthority("ROLE_admin")
  .pathMatchers("/transactions/*/reverse").hasAuthority("ROLE_admin")
  .pathMatchers("/reconciliation/runs").hasAuthority("ROLE_admin")
  .pathMatchers("/holds").hasAuthority("ROLE_admin")
  .pathMatchers("/actuator/**").permitAll()
  .anyExchange().authenticated()
  ```
  (Exact Spring Security role-prefix convention — `ROLE_admin` vs. `admin` — confirmed at
  implementation time against how the JWT's realm-role claim is actually mapped into Spring
  Security authorities in this codebase's existing OAuth2 resource-server config; the plan's
  own task will verify this against the real JWT structure rather than assume.)
- `GET /accounts/{accountRef}` (single lookup) and `GET /transactions/{id}` (single lookup)
  are deliberately **not** admin-gated — they're read-only single-resource lookups, no more
  sensitive than the existing ungated `GET /holds/{id}`, and the future end-user UI may
  reasonably want an authenticated user to look up transaction detail for their own
  transactions. Only the *list/search* endpoints, the *reversal* endpoint, and the
  reconciliation-history endpoint are admin-only.
- Every existing endpoint's authorization is completely unchanged.

## Section 3 — Holds Service Addition

### `GET /holds`

Query params (optional, combinable): `accountRef` (matches either `account_ref` or
`destination_account_ref`), `status` (exact match against `HoldStatus`). Returns
`List<HoldResponse>` (existing DTO, unchanged). Admin-gated per Section 2.

## Testing Strategy

- Standard Testcontainers integration tests per new endpoint, following this codebase's
  established pattern (real Postgres, real Flyway migration, real repository queries — no
  mocking the persistence layer).
- The reversal endpoint gets the heaviest test coverage: successful reversal restores
  balances correctly and posts with the right debit/credit swap; a second reversal attempt on
  an already-reversed transaction returns `409`; attempting to reverse a reversal returns
  `409`; the deterministic idempotency key means a duplicate `POST` (simulating a
  double-click or retry) replays rather than double-reversing — assert exactly one reversal
  transaction exists in the database afterward.
- `scripts/smoke-test.sh` gains a role-boundary check: a `user`-role token attempting
  `POST /transactions/{id}/reverse` gets `403`; an `admin`-role token succeeds. This is the
  platform's first role-differentiated authorization behavior, so it's worth a permanent
  smoke-test assertion, not just a unit test, to catch any future `SecurityConfig` regression
  that accidentally widens or narrows the gate.

## Verification

- Full Maven reactor build green.
- Full Docker Compose stack up, `scripts/provision.sh`, extended `smoke-test.sh` (including
  the new role-boundary check) green.
- All 9 existing chaos scenarios still green (this spec touches `TransactionPoster`'s call
  path only additively — a new endpoint calling existing posting logic — so a regression here
  would likely also surface as a chaos-scenario failure).
- Manual verification through the gateway: as `admin`, list accounts, list transactions,
  reverse one, confirm the original shows `REVERSED` and the new transaction correctly
  restores the affected balances; as `user`, confirm the reversal endpoint is rejected with
  `403`.
- README updated documenting the new endpoints and the `admin` role/demo user.
