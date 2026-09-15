# Admin API Additions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add account/transaction listing and search, transaction reversal (via a new compensating transaction), reconciliation run history, hold listing, and the platform's first role-differentiated auth model (a new `admin` Keycloak role), so the two planned frontends (end-user UI, admin console) have a stable backend contract to build against.

**Architecture:** Additive endpoints on Ledger Service and Holds Service, reusing existing services/repositories wherever possible (the reversal endpoint reuses `TransactionService.postTransaction` end-to-end rather than reimplementing any posting logic). One new Ledger Service migration (reversal tracking). One new Keycloak realm role/demo user, and the API Gateway's first role-gated (not just authenticated-vs-not) authorization rule.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, Flyway, Spring Cloud Gateway, Spring Security OAuth2 Resource Server, Keycloak, Testcontainers, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-16-admin-api-additions-design.md`

## Global Constraints

- No pagination on any new list endpoint — plain arrays, matching the existing `GET /wallets/{groupId}/accounts` convention (spec, "Non-Goals").
- The reversal endpoint must reuse `TransactionService.postTransaction`, not reimplement posting logic (spec, Section 1, `POST /transactions/{id}/reverse`).
- Reversal idempotency key is deterministic: `admin-reversal-{originalTransactionId}` (spec, same section).
- At most one reversal per original transaction, enforced at the database level via a partial unique index, not just application code (spec, "Schema change").
- Only list/search endpoints, the reversal endpoint, and reconciliation-history are admin-gated; single-resource lookups (`GET /accounts/{ref}`, `GET /transactions/{id}`) remain open to any authenticated caller (spec, Section 2).
- Every existing endpoint's authorization is unchanged (spec, Section 2).

---

### Task 1: Ledger Service — `reversal_of_transaction_id` migration and `REVERSED` status wiring

**Files:**
- Create: `ledger-service/src/main/resources/db/migration/V8__add_reversal_tracking_to_transactions.sql`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/domain/Transaction.java`
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/SchemaMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Transaction.getReversalOfTransactionId(): UUID` (nullable), `Transaction.markReversed()` (transitions status to `REVERSED`) — Task 4's reversal service uses both. The DB-level uniqueness constraint that Task 4's tests rely on to prove "only one reversal per original" is enforced.

- [ ] **Step 1: Write the failing test**

Read `SchemaMigrationIntegrationTest.java` in full first to match its existing style, then add:

```java
@Test
void transactionsHasReversalOfTransactionIdWithPartialUniqueIndex() {
    UUID original = UUID.randomUUID();
    UUID reversalA = UUID.randomUUID();
    UUID reversalB = UUID.randomUUID();

    jdbcTemplate.update(
            "INSERT INTO transactions (id, idempotency_key, status, transaction_type, request_payload_hash, created_at) " +
                    "VALUES (?, 'idem-original', 'POSTED', 'TRANSFER', repeat('a', 64), now())", original);
    jdbcTemplate.update(
            "INSERT INTO transactions (id, idempotency_key, status, transaction_type, request_payload_hash, created_at, reversal_of_transaction_id) " +
                    "VALUES (?, 'idem-reversal-a', 'POSTED', 'REVERSAL', repeat('a', 64), now(), ?)", reversalA, original);

    assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO transactions (id, idempotency_key, status, transaction_type, request_payload_hash, created_at, reversal_of_transaction_id) " +
                    "VALUES (?, 'idem-reversal-b', 'POSTED', 'REVERSAL', repeat('a', 64), now(), ?)", reversalB, original))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
}
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` and `import java.util.UUID;` if not already present.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=SchemaMigrationIntegrationTest#transactionsHasReversalOfTransactionIdWithPartialUniqueIndex -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: FAIL — the column doesn't exist yet, so the first `INSERT` itself errors.

- [ ] **Step 3: Write the migration**

Confirm `V7__seed_external_clearing_account.sql` is still the highest existing migration in `ledger-service/src/main/resources/db/migration/` before naming this file `V8`.

```sql
ALTER TABLE transactions ADD COLUMN reversal_of_transaction_id UUID REFERENCES transactions(id);
CREATE UNIQUE INDEX uq_transactions_reversal_of_transaction_id
    ON transactions (reversal_of_transaction_id)
    WHERE reversal_of_transaction_id IS NOT NULL;
```

- [ ] **Step 4: Run the test to verify it passes**

Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Add the field and a `markReversed()` mutator to `Transaction`**

Read the full current `Transaction.java` file first (shown in this plan's own research, but verify against the real file before editing — it may have drifted). Add:

```java
@Column(name = "reversal_of_transaction_id")
private UUID reversalOfTransactionId;
```

Add a getter `public UUID getReversalOfTransactionId() { return reversalOfTransactionId; }`.

Add a setter-style mutator used only by the reversal-creation path (Task 4):
```java
public void setReversalOfTransactionId(UUID reversalOfTransactionId) {
    this.reversalOfTransactionId = reversalOfTransactionId;
}
```

Add a mutator used to mark the *original* transaction as reversed once its reversal has posted:
```java
public void markReversed() {
    this.status = TransactionStatus.REVERSED;
}
```

(`TransactionStatus.REVERSED` already exists in `TransactionStatus.java` — confirmed unused elsewhere in the codebase today; this task is what starts using it.)

- [ ] **Step 6: Run the full module suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, including the new test. Confirm the migration log shows `V8` applying cleanly on top of `V1`-`V7`.

- [ ] **Step 7: Commit**

```bash
git add ledger-service/src/main/resources/db/migration/V8__add_reversal_tracking_to_transactions.sql \
        ledger-service/src/main/java/com/ledger/ledgerservice/domain/Transaction.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/SchemaMigrationIntegrationTest.java
git commit -m "feat(ledger-service): add reversal_of_transaction_id with a DB-enforced one-reversal-per-original constraint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Ledger Service — `GET /accounts` and `GET /accounts/{accountRef}`

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/repository/AccountRepository.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountService.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/AccountController.java`
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/service/AccountServiceIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET /accounts?accountRef=&status=`, `GET /accounts/{accountRef}` — Task 6's API Gateway routing and Task 8's smoke-test extension use these paths directly.

- [ ] **Step 1: Write the failing tests**

Read `AccountServiceIntegrationTest.java` in full first to match its real seeding/assertion helpers (do not assume method names from this plan's description — verify against the actual file, which this plan's own earlier V5 effort already established has no generic `createAccount(...)` helper; check the current state directly since it may differ).

```java
@Test
void listAccountsWithNoFiltersReturnsAllAccounts() {
    accountService.createAccount(new CreateAccountRequest("list-test-a", "USD", null));
    accountService.createAccount(new CreateAccountRequest("list-test-b", "USD", null));

    List<AccountResponse> results = accountService.listAccounts(null, null);

    assertThat(results).extracting(AccountResponse::accountRef)
            .contains("list-test-a", "list-test-b");
}

@Test
void listAccountsFiltersByAccountRefSubstringCaseInsensitively() {
    accountService.createAccount(new CreateAccountRequest("filter-target-1", "USD", null));
    accountService.createAccount(new CreateAccountRequest("unrelated-2", "USD", null));

    List<AccountResponse> results = accountService.listAccounts("FILTER-TARGET", null);

    assertThat(results).extracting(AccountResponse::accountRef).containsExactly("filter-target-1");
}

@Test
void listAccountsFiltersByStatus() {
    accountService.createAccount(new CreateAccountRequest("status-filter-active", "USD", null));

    List<AccountResponse> activeResults = accountService.listAccounts(null, "ACTIVE");
    List<AccountResponse> closedResults = accountService.listAccounts(null, "CLOSED");

    assertThat(activeResults).extracting(AccountResponse::accountRef).contains("status-filter-active");
    assertThat(closedResults).extracting(AccountResponse::accountRef).doesNotContain("status-filter-active");
}

@Test
void getAccountByRefReturnsTheAccount() {
    accountService.createAccount(new CreateAccountRequest("get-by-ref-test", "EUR", null));

    AccountResponse response = accountService.getAccount("get-by-ref-test");

    assertThat(response.accountRef()).isEqualTo("get-by-ref-test");
    assertThat(response.currency()).isEqualTo("EUR");
}

@Test
void getAccountByRefThrowsWhenNotFound() {
    assertThatThrownBy(() -> accountService.getAccount("does-not-exist-ref"))
            .isInstanceOf(AccountNotFoundException.class);
}
```

Adjust these to the real `CreateAccountRequest` constructor/`AccountResponse` field names if they've drifted (verify against the real files first, per this codebase's established practice) before finalizing.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ledger-service -am test -Dtest=AccountServiceIntegrationTest -Dapi.version=1.44`
Expected: compile failure (the methods don't exist yet).

- [ ] **Step 3: Add repository query methods**

In `AccountRepository.java`, add:
```java
List<Account> findByAccountRefContainingIgnoreCaseAndStatus(String accountRefPart, AccountStatus status);
List<Account> findByAccountRefContainingIgnoreCase(String accountRefPart);
List<Account> findByStatus(AccountStatus status);
```
(Spring Data derives all three from method-name conventions; no `@Query` needed. `List<Account> findAll()` already exists via `JpaRepository`.)

- [ ] **Step 4: Add `AccountService.listAccounts` and `getAccount`**

```java
public List<AccountResponse> listAccounts(String accountRefFilter, String statusFilter) {
    List<Account> accounts;
    if (accountRefFilter != null && statusFilter != null) {
        accounts = accountRepository.findByAccountRefContainingIgnoreCaseAndStatus(
                accountRefFilter, AccountStatus.valueOf(statusFilter));
    } else if (accountRefFilter != null) {
        accounts = accountRepository.findByAccountRefContainingIgnoreCase(accountRefFilter);
    } else if (statusFilter != null) {
        accounts = accountRepository.findByStatus(AccountStatus.valueOf(statusFilter));
    } else {
        accounts = accountRepository.findAll();
    }
    return accounts.stream().map(this::toResponse).toList();
}

public AccountResponse getAccount(String accountRef) {
    Account account = accountRepository.findByAccountRef(accountRef)
            .orElseThrow(() -> new AccountNotFoundException(accountRef));
    return toResponse(account);
}
```

Read the existing `createAccount` method to find its exact `Account` → `AccountResponse` mapping logic (it must already build an `AccountResponse` somewhere, even if inline) and factor it into a shared private `toResponse(Account)` method used by all three (`createAccount`, `listAccounts`, `getAccount`) rather than duplicating the mapping — this is a real, minor refactor of existing code, not new logic; do it carefully so `createAccount`'s existing behavior/tests are unaffected.

`AccountNotFoundException`'s constructor signature — verify it against the real exception class before assuming it takes a single `String accountRef` argument.

- [ ] **Step 5: Add the controller routes**

```java
@GetMapping("/accounts")
public ResponseEntity<List<AccountResponse>> list(
        @RequestParam(value = "accountRef", required = false) String accountRef,
        @RequestParam(value = "status", required = false) String status) {
    return ResponseEntity.ok(accountService.listAccounts(accountRef, status));
}

@GetMapping("/accounts/{accountRef}")
public ResponseEntity<AccountResponse> get(@PathVariable("accountRef") String accountRef) {
    return ResponseEntity.ok(accountService.getAccount(accountRef));
}
```
(`@PathVariable("accountRef")` needs the explicit name argument per this codebase's established convention — no module has the `-parameters` compiler flag.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, including all 5 new tests and no regression to `createAccount`'s existing tests (confirming the `toResponse` refactor didn't break anything).

- [ ] **Step 7: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/repository/AccountRepository.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountService.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/AccountController.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/AccountServiceIntegrationTest.java
git commit -m "feat(ledger-service): add GET /accounts (list/search) and GET /accounts/{accountRef}

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Ledger Service — `GET /transactions` and `GET /transactions/{id}`

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/TransactionSummaryResponse.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/TransactionDetailResponse.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/EntryResponse.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionNotFoundException.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/repository/TransactionRepository.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/TransactionQueryController.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java`
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `EntryRepository.findByTransactionId(UUID)` (already exists).
- Produces: `GET /transactions?accountRef=&status=&since=&until=`, `GET /transactions/{id}` — Task 4's reversal endpoint and Task 6/8 depend on these paths and DTO shapes.

- [ ] **Step 1: Write the failing tests**

Read `TransactionServiceIntegrationTest.java`'s existing `@BeforeEach` seeding (`acct-a`/`acct-b`) before writing these, and reuse those seeded accounts where the test doesn't need its own dedicated ones:

```java
@Test
void listTransactionsWithNoFiltersReturnsAllTransactions() {
    transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 100L, "USD", "list test"), "list-test-key-1");

    List<TransactionSummaryResponse> results = transactionService.listTransactions(null, null, null, null);

    assertThat(results).extracting(TransactionSummaryResponse::debitAccountRef).contains("acct-a");
}

@Test
void listTransactionsFiltersByAccountRefOnEitherSide() {
    TransactionResponse posted = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 200L, "USD", "filter test"), "filter-test-key-1");

    List<TransactionSummaryResponse> debitSideResults =
            transactionService.listTransactions("acct-a", null, null, null);
    List<TransactionSummaryResponse> creditSideResults =
            transactionService.listTransactions("acct-b", null, null, null);

    assertThat(debitSideResults).extracting(TransactionSummaryResponse::transactionId)
            .contains(posted.transactionId());
    assertThat(creditSideResults).extracting(TransactionSummaryResponse::transactionId)
            .contains(posted.transactionId());
}

@Test
void listTransactionsFiltersByStatus() {
    transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 50L, "USD", "status filter test"), "status-filter-key-1");

    List<TransactionSummaryResponse> postedResults = transactionService.listTransactions(null, "POSTED", null, null);
    List<TransactionSummaryResponse> failedResults = transactionService.listTransactions(null, "FAILED", null, null);

    assertThat(postedResults).isNotEmpty();
    assertThat(failedResults).isEmpty();
}

@Test
void listTransactionsFiltersBySinceAndUntil() {
    TransactionResponse posted = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 75L, "USD", "date filter test"), "date-filter-key-1");
    Instant beforePosting = Instant.now().minusSeconds(60);
    Instant afterPosting = Instant.now().plusSeconds(60);

    List<TransactionSummaryResponse> inRangeResults =
            transactionService.listTransactions(null, null, beforePosting, afterPosting);
    List<TransactionSummaryResponse> outOfRangeResults =
            transactionService.listTransactions(null, null, afterPosting, null);

    assertThat(inRangeResults).extracting(TransactionSummaryResponse::transactionId)
            .contains(posted.transactionId());
    assertThat(outOfRangeResults).extracting(TransactionSummaryResponse::transactionId)
            .doesNotContain(posted.transactionId());
}

@Test
void getTransactionReturnsDetailWithEntries() {
    TransactionResponse posted = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 300L, "USD", "detail test"), "detail-test-key-1");

    TransactionDetailResponse detail = transactionService.getTransaction(posted.transactionId());

    assertThat(detail.transactionId()).isEqualTo(posted.transactionId());
    assertThat(detail.entries()).hasSize(2);
    assertThat(detail.entries()).extracting(EntryResponse::direction).containsExactlyInAnyOrder("DEBIT", "CREDIT");
}

@Test
void getTransactionThrowsWhenNotFound() {
    assertThatThrownBy(() -> transactionService.getTransaction(UUID.randomUUID()))
            .isInstanceOf(TransactionNotFoundException.class);
}
```

Add `import java.time.Instant;` and `import java.util.UUID;` if not already present.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: compile failure.

- [ ] **Step 3: Write the 3 new DTOs and the exception**

```java
package com.ledger.ledgerservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TransactionSummaryResponse(UUID transactionId, String status, String transactionType,
                                          String debitAccountRef, String creditAccountRef,
                                          long amountMinor, String currency, String description,
                                          Instant createdAt, UUID reversalOfTransactionId) {
}
```

```java
package com.ledger.ledgerservice.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionDetailResponse(UUID transactionId, String status, String transactionType,
                                         String description, Instant createdAt,
                                         UUID reversalOfTransactionId, List<EntryResponse> entries) {
}
```

```java
package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record EntryResponse(UUID accountId, String accountRef, String direction,
                             long amountMinor, String currency) {
}
```

```java
package com.ledger.ledgerservice.service;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found: " + transactionId);
    }
}
```

- [ ] **Step 4: Add a repository query method for the account-ref filter**

`TransactionRepository` needs a way to find transactions where a given account appears in either entry. Since `Transaction` has no direct account-ref columns (only `Entry` does), add a JPQL query joining through `Entry` and `Account`:

```java
@Query("SELECT DISTINCT t FROM Transaction t JOIN Entry e ON e.transactionId = t.id " +
       "JOIN Account a ON a.id = e.accountId WHERE a.accountRef = :accountRef")
List<Transaction> findByAccountRef(@Param("accountRef") String accountRef);
```

Add `import org.springframework.data.jpa.repository.Query;` and `import org.springframework.data.repository.query.Param;`. Verify this JPQL compiles against the real entity mappings (Entry's `transactionId`/`accountId` are plain UUID columns, not `@ManyToOne` relations, per the existing `Entry.java` structure — confirm this before assuming the join syntax above works as written; if `Entry` has no JPA relationship annotations to `Transaction`/`Account`, this cross-entity `JOIN ... ON` form is still valid JPQL as an explicit ad-hoc join condition, but verify against the actual entity definitions and adjust if Hibernate rejects the syntax, e.g. falling back to a native `@Query(nativeQuery = true, value = "...")` on the `transactions`/`entries`/`accounts` tables directly if the JPQL join proves awkward).

- [ ] **Step 5: Add `TransactionService.listTransactions` and `getTransaction`**

```java
public List<TransactionSummaryResponse> listTransactions(String accountRefFilter, String statusFilter,
                                                            Instant since, Instant until) {
    List<Transaction> transactions;
    if (accountRefFilter != null) {
        transactions = transactionRepository.findByAccountRef(accountRefFilter);
    } else {
        transactions = transactionRepository.findAll();
    }
    return transactions.stream()
            .filter(t -> statusFilter == null || t.getStatus().name().equals(statusFilter))
            .filter(t -> since == null || !t.getCreatedAt().isBefore(since))
            .filter(t -> until == null || !t.getCreatedAt().isAfter(until))
            .map(this::toSummary)
            .toList();
}

public TransactionDetailResponse getTransaction(UUID transactionId) {
    Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    List<Entry> entries = entryRepository.findByTransactionId(transactionId);
    List<EntryResponse> entryResponses = entries.stream().map(this::toEntryResponse).toList();
    return new TransactionDetailResponse(transaction.getId(), transaction.getStatus().name(),
            transaction.getTransactionType(), transaction.getDescription(), transaction.getCreatedAt(),
            transaction.getReversalOfTransactionId(), entryResponses);
}

private TransactionSummaryResponse toSummary(Transaction transaction) {
    List<Entry> entries = entryRepository.findByTransactionId(transaction.getId());
    Entry debitEntry = entries.stream().filter(e -> e.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
    Entry creditEntry = entries.stream().filter(e -> e.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
    String debitAccountRef = accountRepository.findById(debitEntry.getAccountId()).orElseThrow().getAccountRef();
    String creditAccountRef = accountRepository.findById(creditEntry.getAccountId()).orElseThrow().getAccountRef();
    return new TransactionSummaryResponse(transaction.getId(), transaction.getStatus().name(),
            transaction.getTransactionType(), debitAccountRef, creditAccountRef,
            debitEntry.getAmountMinor(), debitEntry.getCurrency(), transaction.getDescription(),
            transaction.getCreatedAt(), transaction.getReversalOfTransactionId());
}

private EntryResponse toEntryResponse(Entry entry) {
    String accountRef = accountRepository.findById(entry.getAccountId()).orElseThrow().getAccountRef();
    return new EntryResponse(entry.getAccountId(), accountRef, entry.getDirection().name(),
            entry.getAmountMinor(), entry.getCurrency());
}
```

`TransactionService` needs new constructor dependencies (`EntryRepository`, `AccountRepository`) if it doesn't already have them — check the real current constructor first and add only what's missing. Verify `Entry.getDirection()`/`getAmountMinor()`/`getCurrency()`/`getAccountId()` and `Direction.DEBIT`/`CREDIT` against the real `Entry.java`/`Direction.java` before finalizing this code (this plan's draft assumes standard getter names; confirm rather than guess).

- [ ] **Step 6: Add the controller and wire the exception handler**

```java
package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.TransactionDetailResponse;
import com.ledger.ledgerservice.api.dto.TransactionSummaryResponse;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class TransactionQueryController {

    private final TransactionService transactionService;

    public TransactionQueryController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<TransactionSummaryResponse>> list(
            @RequestParam(value = "accountRef", required = false) String accountRef,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(value = "until", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
        return ResponseEntity.ok(transactionService.listTransactions(accountRef, status, since, until));
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<TransactionDetailResponse> get(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(transactionService.getTransaction(id));
    }
}
```

Add to `ApiExceptionHandler.java`: `TransactionNotFoundException` → `404 NOT_FOUND`, following the exact pattern of the existing `AccountNotFoundException` handler.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, including all 6 new tests.

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/TransactionSummaryResponse.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/TransactionDetailResponse.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/EntryResponse.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionNotFoundException.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/repository/TransactionRepository.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/TransactionQueryController.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java
git commit -m "feat(ledger-service): add GET /transactions (list/search) and GET /transactions/{id}

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Ledger Service — `POST /transactions/{id}/reverse`

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionAlreadyReversedException.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/service/CannotReverseAReversalException.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/TransactionQueryController.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java`
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `TransactionService.postTransaction`, `getTransaction` (Task 3), `Transaction.markReversed()`/`setReversalOfTransactionId()` (Task 1).
- Produces: `POST /transactions/{id}/reverse` — Task 6/8 depend on this path.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void reversingATransactionPostsACompensatingTransactionWithSwappedAccounts() {
    TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 400L, "USD", "reversal source"), "reverse-test-key-1");

    TransactionSummaryResponse reversal = transactionService.reverseTransaction(original.transactionId());

    assertThat(reversal.debitAccountRef()).isEqualTo("acct-b");
    assertThat(reversal.creditAccountRef()).isEqualTo("acct-a");
    assertThat(reversal.amountMinor()).isEqualTo(400L);
    assertThat(reversal.currency()).isEqualTo("USD");
    assertThat(reversal.reversalOfTransactionId()).isEqualTo(original.transactionId());
    assertThat(reversal.transactionType()).isEqualTo("REVERSAL");

    TransactionDetailResponse originalDetail = transactionService.getTransaction(original.transactionId());
    assertThat(originalDetail.status()).isEqualTo("REVERSED");
}

@Test
void reversingAnAlreadyReversedTransactionThrowsConflict() {
    TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 150L, "USD", "double reversal test"), "double-reverse-key-1");
    transactionService.reverseTransaction(original.transactionId());

    assertThatThrownBy(() -> transactionService.reverseTransaction(original.transactionId()))
            .isInstanceOf(TransactionAlreadyReversedException.class);
}

@Test
void reversingAReversalThrowsConflict() {
    TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 250L, "USD", "chain reversal test"), "chain-reverse-key-1");
    TransactionSummaryResponse reversal = transactionService.reverseTransaction(original.transactionId());

    assertThatThrownBy(() -> transactionService.reverseTransaction(reversal.transactionId()))
            .isInstanceOf(CannotReverseAReversalException.class);
}

@Test
void reversingTheSameTransactionTwiceConcurrentlyViaRetryProducesExactlyOneReversal() {
    TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
            "acct-a", "acct-b", 350L, "USD", "idempotent reversal test"), "idempotent-reverse-key-1");

    TransactionSummaryResponse firstAttempt = transactionService.reverseTransaction(original.transactionId());
    // A caller retrying after a lost response (e.g. a timeout) would call reverseTransaction
    // again for the same original id -- but by then the original is already REVERSED, so this
    // should throw TransactionAlreadyReversedException rather than silently succeeding twice.
    // This IS the correct behavior (not a bug): the deterministic idempotency key on the
    // underlying postTransaction call protects against a race where two reversal requests
    // are in flight simultaneously before either has committed; once one has fully committed
    // and the original is marked REVERSED, a second top-level call correctly rejects via the
    // already-reversed check, which is a stronger and simpler guarantee for this admin-only
    // endpoint than allowing a silent replay.
    assertThatThrownBy(() -> transactionService.reverseTransaction(original.transactionId()))
            .isInstanceOf(TransactionAlreadyReversedException.class);

    List<TransactionSummaryResponse> allReversalsOfOriginal =
            transactionService.listTransactions(null, null, null, null).stream()
                    .filter(t -> original.transactionId().equals(t.reversalOfTransactionId()))
                    .toList();
    assertThat(allReversalsOfOriginal).hasSize(1);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: compile failure.

- [ ] **Step 3: Write the 2 new exceptions**

```java
package com.ledger.ledgerservice.service;

import java.util.UUID;

public class TransactionAlreadyReversedException extends RuntimeException {
    public TransactionAlreadyReversedException(UUID transactionId) {
        super("Transaction already reversed: " + transactionId);
    }
}
```

```java
package com.ledger.ledgerservice.service;

import java.util.UUID;

public class CannotReverseAReversalException extends RuntimeException {
    public CannotReverseAReversalException(UUID transactionId) {
        super("Cannot reverse a transaction that is itself a reversal: " + transactionId);
    }
}
```

- [ ] **Step 4: Add `TransactionService.reverseTransaction`**

```java
@Transactional
public TransactionSummaryResponse reverseTransaction(UUID transactionId) {
    Transaction original = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    if (original.getReversalOfTransactionId() != null) {
        throw new CannotReverseAReversalException(transactionId);
    }
    if (original.getStatus() == TransactionStatus.REVERSED) {
        throw new TransactionAlreadyReversedException(transactionId);
    }

    List<Entry> entries = entryRepository.findByTransactionId(transactionId);
    Entry originalDebitEntry = entries.stream().filter(e -> e.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
    Entry originalCreditEntry = entries.stream().filter(e -> e.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
    String originalDebitAccountRef = accountRepository.findById(originalDebitEntry.getAccountId()).orElseThrow().getAccountRef();
    String originalCreditAccountRef = accountRepository.findById(originalCreditEntry.getAccountId()).orElseThrow().getAccountRef();

    CreateTransactionRequest reversalRequest = new CreateTransactionRequest(
            originalCreditAccountRef, originalDebitAccountRef, originalDebitEntry.getAmountMinor(),
            originalDebitEntry.getCurrency(), "Reversal of transaction " + transactionId, "REVERSAL");
    String reversalIdempotencyKey = "admin-reversal-" + transactionId;

    TransactionResponse reversalResponse = postTransaction(reversalRequest, reversalIdempotencyKey);

    Transaction reversalTransaction = transactionRepository.findById(reversalResponse.transactionId()).orElseThrow();
    reversalTransaction.setReversalOfTransactionId(transactionId);
    transactionRepository.save(reversalTransaction);

    original.markReversed();
    transactionRepository.save(original);

    return toSummary(reversalTransaction);
}
```

Add `@Transactional` import if not already present. Note this method calls `this.postTransaction(...)` — re-check this against the self-invocation bug this codebase has hit repeatedly: `postTransaction` is itself deliberately NOT `@Transactional` (per its own Javadoc, already read during this plan's research), so `reverseTransaction` being `@Transactional` and calling the non-transactional `postTransaction` does not hit the self-invocation bypass pattern (that bug only applies when a same-class call skips an `@Transactional` annotation on the *callee*; here the callee has no such annotation to skip, and `reverseTransaction`'s own `@Transactional` boundary wraps everything including the nested `TransactionPoster.postInTransaction` call transitively, which is fine since Spring transaction propagation defaults to `REQUIRED` — joining the already-open transaction, not silently running outside one). Still, double-check this reasoning holds by reading `postTransaction`'s actual current implementation one more time before finalizing, since Task 3 may have already required touching it and this task builds on that same file.

- [ ] **Step 5: Add the controller route and wire both exceptions**

```java
@PostMapping("/transactions/{id}/reverse")
public ResponseEntity<TransactionSummaryResponse> reverse(@PathVariable("id") UUID id) {
    return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.reverseTransaction(id));
}
```
Add `import org.springframework.http.HttpStatus;` if not already present in `TransactionQueryController.java`.

Add to `ApiExceptionHandler.java`: `TransactionAlreadyReversedException` → `409 CONFLICT`, `CannotReverseAReversalException` → `409 CONFLICT`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, including all 4 new tests, and no regression anywhere else in the module (this task touches `TransactionService.java`, which every existing transaction-posting test also exercises).

- [ ] **Step 7: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionAlreadyReversedException.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/CannotReverseAReversalException.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/TransactionQueryController.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java
git commit -m "feat(ledger-service): add POST /transactions/{id}/reverse

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Ledger Service — `GET /reconciliation/runs`

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/ReconciliationRunResponse.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/repository/ReconciliationRunRepository.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/reconciliation/ReconciliationController.java` (or wherever `POST /runs` currently lives — verify the real file/package first, since this plan's own research only confirmed the endpoint's path, not its exact file location)
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/reconciliation/ReconciliationServiceIntegrationTest.java` (verify the real test file name/package first)

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET /reconciliation/runs` — Task 6/8 depend on this path.

- [ ] **Step 1: Locate the existing reconciliation controller/service**

Find the file containing `@PostMapping("/runs")` (`grep -rn "PostMapping(\"/runs\")" ledger-service/src/main/java`) — read it in full, along with its backing service class and `ReconciliationRunRepository.java`, before writing anything. This plan's own research confirmed `ReconciliationRun`'s entity shape but not its controller's exact file path or package — verify both now.

- [ ] **Step 2: Write the failing test**

Read the real reconciliation test file's existing structure first, then add:

```java
@Test
void listRunsReturnsRunsNewestFirst() {
    reconciliationService.runReconciliation();
    reconciliationService.runReconciliation();

    List<ReconciliationRunResponse> runs = reconciliationService.listRuns();

    assertThat(runs.size()).isGreaterThanOrEqualTo(2);
    for (int i = 0; i < runs.size() - 1; i++) {
        assertThat(runs.get(i).startedAt()).isAfterOrEqualTo(runs.get(i + 1).startedAt());
    }
}
```

(Verify the real reconciliation-triggering method name — this plan's research found the entity but not the exact service method that creates/completes a `ReconciliationRun`; confirm it's called `runReconciliation()` or adjust to the real name before finalizing this test.)

- [ ] **Step 3: Run test to verify it fails**

Run the equivalent of `mvn -pl ledger-service -am test -Dtest=<real-test-class-name> -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`).
Expected: compile failure.

- [ ] **Step 4: Write the DTO**

```java
package com.ledger.ledgerservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunResponse(UUID runId, String status, Instant startedAt, Instant finishedAt,
                                         int transactionsChecked, int entriesImbalanceCount,
                                         int outboxMissingCount, int outboxStuckCount) {
}
```

- [ ] **Step 5: Add a repository query method and a service method**

In `ReconciliationRunRepository.java`, add:
```java
List<ReconciliationRun> findAllByOrderByStartedAtDesc();
```

In the reconciliation service class (real name/location confirmed in Step 1), add:
```java
public List<ReconciliationRunResponse> listRuns() {
    return reconciliationRunRepository.findAllByOrderByStartedAtDesc().stream()
            .map(run -> new ReconciliationRunResponse(run.getId(), run.getStatus().name(),
                    run.getStartedAt(), run.getFinishedAt(), run.getTransactionsChecked(),
                    run.getEntriesImbalanceCount(), run.getOutboxMissingCount(), run.getOutboxStuckCount()))
            .toList();
}
```

- [ ] **Step 6: Add the controller route**

```java
@GetMapping("/reconciliation/runs")
public ResponseEntity<List<ReconciliationRunResponse>> listRuns() {
    return ResponseEntity.ok(reconciliationService.listRuns());
}
```
(Add to whichever controller class Step 1 identified as owning `POST /runs` — verify the real route prefix; this plan assumes `POST /runs` maps to a controller with no class-level `@RequestMapping` prefix or one that already resolves to `/runs`, so `/reconciliation/runs` is a new, unprefixed full path on that same controller — confirm this is consistent with how `/runs` itself is currently exposed before adding the new route, adjusting the path literal if the real controller has a class-level prefix this plan's research didn't capture.)

- [ ] **Step 7: Run tests to verify they pass**

Run the full module suite: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 8: Commit**

```bash
git add -A ledger-service/
git commit -m "feat(ledger-service): add GET /reconciliation/runs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

(Using `git add -A ledger-service/` here rather than an explicit file list since this task's exact file set depends on Step 1's discovery of the real controller/service file names.)

---

### Task 6: Holds Service — `GET /holds`

**Files:**
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/repository/HoldRepository.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/api/HoldController.java`
- Test: `holds-service/src/test/java/com/ledger/holdsservice/service/HoldServiceIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `GET /holds?accountRef=&status=` — Task 8's API Gateway routing depends on this path.

- [ ] **Step 1: Write the failing tests**

Read `HoldServiceIntegrationTest.java`'s existing seeding conventions first, then add:

```java
@Test
void listHoldsWithNoFiltersReturnsAllHolds() {
    holdService.createHold(new CreateHoldRequest("list-holds-a", "list-holds-b", 100L, "USD",
            Instant.now().plusSeconds(3600)), "list-holds-key-1");

    List<HoldResponse> results = holdService.listHolds(null, null);

    assertThat(results).extracting(HoldResponse::accountRef).contains("list-holds-a");
}

@Test
void listHoldsFiltersByAccountRefOnEitherSide() {
    holdService.createHold(new CreateHoldRequest("filter-holds-source", "filter-holds-dest", 50L, "USD",
            Instant.now().plusSeconds(3600)), "filter-holds-key-1");

    List<HoldResponse> sourceResults = holdService.listHolds("filter-holds-source", null);
    List<HoldResponse> destResults = holdService.listHolds("filter-holds-dest", null);

    assertThat(sourceResults).extracting(HoldResponse::accountRef).contains("filter-holds-source");
    assertThat(destResults).extracting(HoldResponse::destinationAccountRef).contains("filter-holds-dest");
}

@Test
void listHoldsFiltersByStatus() {
    holdService.createHold(new CreateHoldRequest("status-holds-a", "status-holds-b", 25L, "USD",
            Instant.now().plusSeconds(3600)), "status-holds-key-1");

    List<HoldResponse> activeResults = holdService.listHolds(null, "ACTIVE");
    List<HoldResponse> releasedResults = holdService.listHolds(null, "RELEASED");

    assertThat(activeResults).extracting(HoldResponse::accountRef).contains("status-holds-a");
    assertThat(releasedResults).extracting(HoldResponse::accountRef).doesNotContain("status-holds-a");
}
```

Verify the real `CreateHoldRequest` constructor argument order/names before finalizing — this plan's draft assumes `(accountRef, destinationAccountRef, amountMinor, currency, expiresAt)` based on this same session's earlier V5 work referencing `HoldController`'s create route, but confirm against the actual DTO file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl holds-service -am test -Dtest=HoldServiceIntegrationTest -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: compile failure.

- [ ] **Step 3: Add repository query methods**

In `HoldRepository.java`, add:
```java
List<Hold> findByAccountRefOrDestinationAccountRef(String accountRef, String destinationAccountRef);
List<Hold> findByAccountRefOrDestinationAccountRefAndStatus(String accountRef, String destinationAccountRef, HoldStatus status);
List<Hold> findByStatus(HoldStatus status);
```

(Verify Spring Data correctly derives the second method's precedence — `findByXOrYAndZ` parses as `(X) OR (Y AND Z)` under Spring Data's left-to-right `Or`/`And` grouping rules, NOT `(X OR Y) AND Z` as might be intuitively expected; if this method needs the "account matches either side, AND status matches" semantics, write it instead as `findByStatusAndAccountRefOrStatusAndDestinationAccountRef(HoldStatus, String, HoldStatus, String)` calling it twice with the same status, or add an explicit `@Query` with proper parenthesization — do not trust the derived-method-name shortcut here without checking Spring Data's actual precedence rules or just using an explicit `@Query("SELECT h FROM Hold h WHERE (h.accountRef = :ref OR h.destinationAccountRef = :ref) AND (:status IS NULL OR h.status = :status)")` single method instead, which is likely simpler and avoids the ambiguity entirely).

- [ ] **Step 4: Add `HoldService.listHolds`**

```java
public List<HoldResponse> listHolds(String accountRefFilter, String statusFilter) {
    List<Hold> holds;
    HoldStatus status = statusFilter != null ? HoldStatus.valueOf(statusFilter) : null;
    if (accountRefFilter != null && status != null) {
        holds = holdRepository.findByAccountRefOrDestinationAccountRefAndStatus(accountRefFilter, accountRefFilter, status);
    } else if (accountRefFilter != null) {
        holds = holdRepository.findByAccountRefOrDestinationAccountRef(accountRefFilter, accountRefFilter);
    } else if (status != null) {
        holds = holdRepository.findByStatus(status);
    } else {
        holds = holdRepository.findAll();
    }
    return holds.stream().map(this::toResponse).toList();
}
```

(Adjust to whichever query-method shape Step 3 actually settled on.) Find `HoldService`'s existing private `Hold` → `HoldResponse` mapping (it must already exist, even if inline in `createHold`/`getHold`) and reuse it as a shared `toResponse(Hold)` method rather than duplicating the mapping logic.

- [ ] **Step 5: Add the controller route**

```java
@GetMapping("/holds")
public ResponseEntity<List<HoldResponse>> list(
        @RequestParam(value = "accountRef", required = false) String accountRef,
        @RequestParam(value = "status", required = false) String status) {
    return ResponseEntity.ok(holdService.listHolds(accountRef, status));
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl holds-service -am test -Dapi.version=1.44`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors, including all 3 new tests.

- [ ] **Step 7: Commit**

```bash
git add holds-service/src/main/java/com/ledger/holdsservice/repository/HoldRepository.java \
        holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java \
        holds-service/src/main/java/com/ledger/holdsservice/api/HoldController.java \
        holds-service/src/test/java/com/ledger/holdsservice/service/HoldServiceIntegrationTest.java
git commit -m "feat(holds-service): add GET /holds (list/search)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Keycloak — add the `admin` realm role and demo user

**Files:**
- Modify: `keycloak-realm/ledger-realm.json`

**Interfaces:**
- Consumes: nothing.
- Produces: an `admin` realm role and a demo `admin`/`admin-password` user with both `user` and `admin` roles — Task 8's API Gateway security config and its own smoke-test extension depend on this user existing.

- [ ] **Step 1: Read the current realm file in full**

Read `keycloak-realm/ledger-realm.json` completely — this is a single JSON file, not multiple files, and this task edits it directly (no test-first cycle applies to a static config file; verification happens by bringing up Keycloak and checking the role/user exist, in Step 3).

- [ ] **Step 2: Add the role and user**

In the `"roles": { "realm": [ ... ] }` array, add an entry for `admin` matching the exact shape of the existing `user` role entry (read it first to copy the shape exactly — likely just `{"name": "admin"}` or similar, possibly with a `description` field like the existing `user` role has, if it has one).

In the `"users": [ ... ]` array, add a new user entry copying `alice`'s or `bob`'s exact shape:
```json
{
  "username": "admin",
  "enabled": true,
  "email": "admin@example.com",
  "credentials": [
    {"type": "password", "value": "admin-password", "temporary": false}
  ],
  "realmRoles": ["user", "admin"]
}
```
(Match the real file's exact field set and formatting — this plan's research already confirmed `alice`/`bob`'s shape includes `username`, `email`, `credentials`, `realmRoles`; verify there isn't also an `id` field or other required field this draft is missing before finalizing.)

- [ ] **Step 3: Verify live**

Bring up just the `keycloak` container (`docker compose up -d keycloak`, with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`), wait for it to report healthy, then fetch a token for the new admin user via the same mechanism `scripts/get-token.sh` already uses for `alice`/`bob` (read that script first to find the real password-grant curl shape), and decode the resulting JWT (e.g. `echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool` or a Node one-liner if Python is unavailable per this environment's earlier-established limitation) to confirm `realm_access.roles` contains both `"user"` and `"admin"`.

Tear down: `docker compose down -v` (or just `docker compose stop keycloak` if other services weren't brought up for this isolated check — prefer the narrowest teardown that leaves no stray state).

- [ ] **Step 4: Commit**

```bash
git add keycloak-realm/ledger-realm.json
git commit -m "feat(auth): add admin realm role and demo admin user to Keycloak realm import

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: API Gateway — role-gated routing, README, and full acceptance verification

**Files:**
- Modify: `api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java`
- Modify: `api-gateway/src/main/resources/application.yml`
- Modify: `scripts/smoke-test.sh`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-7.
- Produces: the final, complete, admin-gated routing — the last task of this plan.

- [ ] **Step 1: Determine the real JWT-to-authority mapping before writing the security rule**

Read `api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java` and `api-gateway/src/main/resources/application.yml` (specifically any `spring.security.oauth2.resourceserver.jwt` config) in full. Spring Security's default `JwtAuthenticationConverter` does NOT automatically map Keycloak's `realm_access.roles` claim into Spring Security `GrantedAuthority` objects — by default it only reads a flat `scope`/`scp` claim, and `JwtGrantedAuthoritiesConverter.setAuthoritiesClaimName(...)` does not support a dotted/nested path like `"realm_access.roles"` (it expects a flat top-level claim name) — Keycloak's roles live nested one level down, under the `realm_access` claim's own `roles` array. Confirm whether this codebase already has a custom `JwtAuthenticationConverter`/authorities-converter bean anywhere (`grep -rn "JwtAuthenticationConverter\|GrantedAuthoritiesConverter" api-gateway/src/main/java`) before adding one — if none exists, write a custom converter that reads the real nested structure:

```java
package com.ledger.apigateway;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class KeycloakRealmRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        Collection<GrantedAuthority> authorities;
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .map(GrantedAuthority.class::cast)
                    .toList();
        } else {
            authorities = List.of();
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
```

Since this is a Spring Cloud Gateway (WebFlux/reactive) application, not Spring MVC, this synchronous `Converter<Jwt, AbstractAuthenticationToken>` must be wrapped in a `ReactiveJwtAuthenticationConverterAdapter` when wiring it into `oauth2ResourceServer(...)`:

```java
.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
        jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(new KeycloakRealmRoleConverter()))))
```

Before trusting this exact shape as final, decode a real token from Task 7's verification step (or re-fetch one via `scripts/get-token.sh admin`) and confirm `realm_access.roles` genuinely appears at this exact nesting in the actual issued JWT — Keycloak's standard token shape has been consistent across Keycloak versions for this specific claim, but this codebase's own token customization (client scopes/mappers) could in principle have altered it, so verify rather than assume before finalizing.

- [ ] **Step 2: Write the security rule**

Once the converter from Step 1 is wired into `oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(...)))`, add the role-gated path rules before the generic catch-all, in `SecurityConfig.java`:

```java
.authorizeExchange(exchanges -> exchanges
        .pathMatchers(HttpMethod.GET, "/accounts").hasAuthority("ROLE_admin")
        .pathMatchers(HttpMethod.POST, "/transactions/*/reverse").hasAuthority("ROLE_admin")
        .pathMatchers(HttpMethod.GET, "/reconciliation/runs").hasAuthority("ROLE_admin")
        .pathMatchers(HttpMethod.GET, "/holds").hasAuthority("ROLE_admin")
        .pathMatchers("/actuator/**").permitAll()
        .anyExchange().authenticated())
```

Note the `HttpMethod.GET` qualifier on `/accounts` is required to avoid also gating `POST /accounts` (account creation, which must remain open to any authenticated user, unchanged per the spec) — Spring Cloud Gateway's `pathMatchers(String...)` without an `HttpMethod` argument matches all methods on that path, which would incorrectly gate account creation too if omitted. Similarly `/holds` as a bare path matches only `GET /holds` in intent but `pathMatchers("/holds")` alone (no method qualifier) would ALSO catch `POST /holds` (hold creation) if such a path existed without a trailing segment — confirm this isn't an issue by checking hold creation's real route pattern is `POST /holds` (same literal path as the new `GET /holds`) and add the `HttpMethod.GET` qualifier here too to avoid accidentally gating hold creation behind the admin role.

- [ ] **Step 3: Run the role-boundary check live**

Bring up the full stack (`docker compose down -v` then `docker compose up -d --build`, with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`), run `scripts/provision.sh`, then manually verify via curl:
```bash
USER_TOKEN=$(bash scripts/get-token.sh alice)
ADMIN_TOKEN=$(bash scripts/get-token.sh admin)
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/transactions/00000000-0000-0000-0000-000000000000/reverse -H "Authorization: Bearer $USER_TOKEN"
# expect 403
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/transactions/00000000-0000-0000-0000-000000000000/reverse -H "Authorization: Bearer $ADMIN_TOKEN"
# expect 404 (not 403) -- the id doesn't exist, but an admin-role token should pass the
# authorization gate and reach the controller, which then legitimately 404s
```
(Check `scripts/get-token.sh`'s real invocation signature — this plan assumes it takes a username argument based on this same session's earlier usage of `bash scripts/get-token.sh client`/`alice` patterns; confirm before running.)

- [ ] **Step 4: Extend `scripts/smoke-test.sh` with the same role-boundary check**

Read the existing script's structure first, then add a section performing the same two curls as Step 3 as permanent assertions (403 for `alice`, non-403 for `admin`), following the script's existing `HTTP_CODE` extraction/assertion style.

- [ ] **Step 5: Run the full acceptance sequence**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
bash scripts/smoke-test.sh
for s in chaos/scenarios/*.sh; do bash "$s" || break; done
```
Expected: smoke test passes (including the new role-boundary check) and all 9 chaos scenarios still pass (this plan's changes are additive to `TransactionService`/`TransactionPoster`'s call surface, but Task 4's `reverseTransaction` reuses `postTransaction` rather than modifying its existing behavior, so no chaos-scenario regression is expected — confirm this holds true live, not just in theory).

Tear down: `docker compose down -v`.

- [ ] **Step 6: Update the README**

Document the 7 new endpoints, the `admin` role and demo user, and the reversal semantics (a new compensating transaction, never a mutation) in the appropriate sections (Architecture, API, Known limitations if anything new is worth noting — e.g. the lack of pagination at this platform's current scale).

- [ ] **Step 7: Run the full Maven reactor build one final time**

Run: `mvn clean verify -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`) from the repo root.
Expected: `BUILD SUCCESS` across all 6 modules.

- [ ] **Step 8: Commit**

```bash
git add api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java \
        api-gateway/src/main/resources/application.yml \
        scripts/smoke-test.sh README.md
git commit -m "feat(api-gateway): add role-gated routing for admin endpoints, extend smoke test, update README

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
