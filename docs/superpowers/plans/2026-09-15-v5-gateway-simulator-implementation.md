# V5 (Gateway Simulator) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Gateway Simulator microservice modeling an external payment rail — simulated inbound deposits (webhook → ledger credit) and outbound withdrawals (ledger debit → simulated submission → async confirm/fail/timeout → compensating reversal) — with full observability and chaos-test coverage, completing the platform's originally-planned 5-version roadmap (V4/Fees Service remains skipped).

**Architecture:** New Spring Boot module `gateway-simulator` (own `gateway_sim_db`), following the exact scaffolding, outbox, and RabbitMQ-consumer patterns already established by `holds-service`/`fx-service`. Requires one small, contained upstream change to `ledger-service` first: `CreateTransactionRequest` currently has no field for `transactionType` (it's hardcoded to `"TRANSFER"` in `TransactionPoster`), so Gateway Simulator has no way to trigger a `WITHDRAWAL_EXTERNAL`-typed transaction, and the outbox payload has no `transactionType` field for a consumer to filter on.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, Flyway, Spring AMQP (RabbitMQ), RestClient, Testcontainers, JUnit 5, AssertJ, Micrometer + Prometheus + Grafana (existing stack).

**Spec:** `docs/superpowers/specs/2026-09-15-v5-gateway-simulator-design.md`

## Global Constraints

- Money in integer minor units, never floating point (platform-wide rule, unchanged).
- Database-per-service: `gateway_sim_db` is a new, independent Postgres database; no service queries another's tables directly.
- Every new outbound service-to-service reference (`external-clearing-{currency}`) must be exempted from the Holds Service held-balance check the same way `fx-clearing-` already is (spec, "Clearing-account exemption").
- `webhook_dedup`, `processed_events`, `outbox_events` schemas follow the exact column shapes already used by `holds-service`/`fx-service` (spec, "Schema").
- Withdrawal timeout defaults to 60 seconds, externalized as `gateway-sim.withdrawal-timeout-seconds` in `application.yml` (spec, "Outbound flow", step 6).
- `POST /webhooks/deposits` and all other Gateway Simulator routes sit behind the API Gateway's existing OAuth2 resource-server auth — no new security carve-out (spec, "API Gateway routing").
- Out-of-order withdrawal confirmation returns `409` for retry — no placeholder-row state machine (spec, "Outbound flow", step 4).
- New chaos scenarios are numbered 07-09, following the existing `chaos/scenarios/NN_description.sh` convention exactly (spec, "Chaos Testing").
- New Micrometer metric names: `gateway_sim.webhook.duplicate`, `gateway_sim.deposit.rejected`, `gateway_sim.withdrawal.timeout`, `gateway_sim.withdrawal.reversal` (spec, "Observability").

---

### Task 1: Ledger Service — add `transactionType` to the transaction-posting path

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/CreateTransactionRequest.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java`
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `CreateTransactionRequest.transactionType()` (nullable `String`, defaults to `"TRANSFER"` server-side when absent or blank) — later tasks' Gateway Simulator code posts `WITHDRAWAL_EXTERNAL` through this field. The outbox JSON payload gains a `"transactionType"` key that Task 5's consumer reads.

- [ ] **Step 1: Write the failing test — request without transactionType still defaults to TRANSFER**

Add to `TransactionServiceIntegrationTest.java` (same file, alongside existing tests — read the existing test class first to match its exact helper-method names for creating accounts and posting requests before writing this):

```java
@Test
void postingWithoutTransactionTypeDefaultsToTransfer() {
    createAccount("txtype-default-a", 10_000);
    createAccount("txtype-default-b", 0);

    CreateTransactionRequest request = new CreateTransactionRequest(
            "txtype-default-a", "txtype-default-b", 500L, "USD", "no type specified");
    TransactionResponse response = transactionService.postTransaction(request, "txtype-default-key-1");

    Transaction saved = transactionRepository.findById(response.transactionId()).orElseThrow();
    assertThat(saved.getTransactionType()).isEqualTo("TRANSFER");
}

@Test
void postingWithExplicitTransactionTypePersistsIt() {
    createAccount("txtype-explicit-a", 10_000);
    createAccount("txtype-explicit-b", 0);

    CreateTransactionRequest request = new CreateTransactionRequest(
            "txtype-explicit-a", "txtype-explicit-b", 500L, "USD", "withdrawal", "WITHDRAWAL_EXTERNAL");
    TransactionResponse response = transactionService.postTransaction(request, "txtype-explicit-key-1");

    Transaction saved = transactionRepository.findById(response.transactionId()).orElseThrow();
    assertThat(saved.getTransactionType()).isEqualTo("WITHDRAWAL_EXTERNAL");
}
```

(Use whatever this test class's existing helper for creating an account and reading `transactionRepository`/`transactionService` beans is actually called — read the file first; do not assume a `createAccount` helper exists verbatim if it doesn't, adapt to the real one.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest#postingWithoutTransactionTypeDefaultsToTransfer+postingWithExplicitTransactionTypePersistsIt -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: compile error (the 6-arg constructor doesn't exist yet on `CreateTransactionRequest`) or, once you add the field, a runtime assertion failure showing `"TRANSFER"` hardcoded regardless of input.

- [ ] **Step 3: Add `transactionType` to `CreateTransactionRequest`**

```java
package com.ledger.ledgerservice.api.dto;

public record CreateTransactionRequest(
        String debitAccountRef,
        String creditAccountRef,
        long amountMinor,
        String currency,
        String description,
        String transactionType
) {
    public CreateTransactionRequest {
        if (transactionType == null || transactionType.isBlank()) {
            transactionType = "TRANSFER";
        }
    }

    public CreateTransactionRequest(String debitAccountRef, String creditAccountRef, long amountMinor,
                                     String currency, String description) {
        this(debitAccountRef, creditAccountRef, amountMinor, currency, description, "TRANSFER");
    }
}
```

This adds a compact canonical constructor that normalizes a null/blank `transactionType` to `"TRANSFER"`, plus a backward-compatible 5-arg constructor so every existing caller (production code, and every existing test across `ledger-service`) that constructs this record with 5 args keeps compiling unchanged.

- [ ] **Step 4: Use `request.transactionType()` in `TransactionPoster`**

In `TransactionPoster.java`, find the line (around line 143-144):
```java
Transaction transaction = new Transaction(transactionId, idempotencyKey, TransactionStatus.POSTED,
        "TRANSFER", request.description(), requestHash);
```
Replace `"TRANSFER"` with `request.transactionType()`.

- [ ] **Step 5: Add `transactionType` to the outbox JSON payload**

In `TransactionPoster.java`'s `buildOutboxPayload` method, add a `"transactionType"` entry to the `Map.of(...)` call, using `transaction.getTransactionType()` (the just-persisted entity's own field, not `request.transactionType()`, so the payload always reflects what was actually saved). `Map.of` has a 10-key-pair limit (5 entries) — check the current entry count first; if it's already at or near that limit, switch the payload construction from `Map.of(...)` to `Map.ofEntries(entry(...), entry(...), ...)` instead, which has no such limit, updating all existing entries to the `entry(...)` form for consistency rather than mixing styles.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44` (full module suite, not just the 2 new tests — a change to a widely-used record's constructor risks breaking existing call sites that Step 3's backward-compatible 5-arg constructor is meant to protect, so confirm nothing broke).
Expected: BUILD SUCCESS, 0 failures, 0 errors, same test count as before plus 2.

- [ ] **Step 7: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/CreateTransactionRequest.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java
git commit -m "feat(ledger-service): add transactionType to CreateTransactionRequest and outbox payload

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Ledger Service — generalize the clearing-account holds exemption

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java`
- Test: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `external-clearing-{currency}` accounts (e.g. `external-clearing-USD`) are now exempt from the Holds Service held-balance check — Task 8 and Task 9's Gateway Simulator code relies on this to post deposit/withdrawal/reversal transactions without needing an active Holds Service round-trip against a suspense account.

- [ ] **Step 1: Write the failing test**

Read the existing test in `TransactionServiceIntegrationTest.java` that proves `fx-clearing-` accounts bypass the Holds Service call (it stubs `heldBalance` to `Long.MAX_VALUE/2` and asserts the transfer still succeeds) — find its exact name and structure first, then add an analogous test for the new prefix:

```java
@Test
void externalClearingAccountsSkipTheHeldBalanceCheckEntirely() {
    stubbedHeldBalance.set(Long.MAX_VALUE / 2);
    createAccount("external-clearing-USD", 1_000_000L);
    createAccount("clearing-check-dest", 0);

    CreateTransactionRequest request = new CreateTransactionRequest(
            "external-clearing-USD", "clearing-check-dest", 5000L, "USD", "reversal test", "WITHDRAWAL_EXTERNAL");
    TransactionResponse response = transactionService.postTransaction(request, "clearing-check-key-1");

    assertThat(response.transactionId()).isNotNull();
    assertThat(getAccountBalance("clearing-check-dest")).isEqualTo(5000L);
}
```

(Match this to the exact field/method names the existing `fx-clearing-` test in this file already uses for `stubbedHeldBalance` and balance lookups — do not invent new ones if equivalents already exist.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest#externalClearingAccountsSkipTheHeldBalanceCheckEntirely -Dapi.version=1.44`
Expected: FAIL — with `stubbedHeldBalance` set to `Long.MAX_VALUE/2` and no exemption yet, `getHeldBalance` is still called and returns that huge value, but since the test posts a small amount well within even a huge held-balance subtraction going negative... re-check this: actually with `Long.MAX_VALUE/2` held, `availableBalanceMinor` goes deeply negative, so the request should FAIL with `InsufficientFundsException` (422) prior to the fix, not merely "call was made." Confirm the test currently gets a 422/exception rather than a successful response, proving the exemption doesn't yet apply to this prefix.

- [ ] **Step 3: Generalize the exemption**

In `TransactionPoster.java`, find:
```java
public static final String FX_CLEARING_ACCOUNT_REF_PREFIX = "fx-clearing-";
```
and the check at (around) line 133:
```java
boolean debitAccountAllowsNegativeBalance =
        debitAccount.getAccountRef().startsWith(FX_CLEARING_ACCOUNT_REF_PREFIX);
```

Replace both with:
```java
private static final List<String> CLEARING_ACCOUNT_REF_PREFIXES =
        List.of("fx-clearing-", "external-clearing-");

public static boolean isClearingAccount(String accountRef) {
    return CLEARING_ACCOUNT_REF_PREFIXES.stream().anyMatch(accountRef::startsWith);
}
```
and update the call site to:
```java
boolean debitAccountAllowsNegativeBalance = isClearingAccount(debitAccount.getAccountRef());
```

Check whether `FX_CLEARING_ACCOUNT_REF_PREFIX` is referenced anywhere else in the codebase before removing it (`grep -rn "FX_CLEARING_ACCOUNT_REF_PREFIX" --include="*.java" .` from repo root) — if other files reference the old constant name directly, either keep it as a `public static final String FX_CLEARING_ACCOUNT_REF_PREFIX = "fx-clearing-";` alongside the new list (both can coexist, referencing the same literal), or update those call sites to use `isClearingAccount(...)` instead, whichever keeps the change smaller. Add `import java.util.List;` if not already present.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: BUILD SUCCESS, including both the new test and the pre-existing `fx-clearing-` exemption test (unaffected, since `fx-clearing-` remains in the prefix list).

- [ ] **Step 5: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java
git commit -m "feat(ledger-service): generalize clearing-account holds exemption to cover external-clearing-

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Scaffold the `gateway-simulator` Maven module

**Files:**
- Create: `gateway-simulator/pom.xml`
- Create: `gateway-simulator/Dockerfile`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/GatewaySimulatorApplication.java`
- Create: `gateway-simulator/src/main/resources/application.yml`
- Modify: `pom.xml` (root reactor)

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable, empty Spring Boot module joining the Maven reactor, on port `8084` (next free port after `fx-service`'s `8083`), with `@EnableScheduling` (needed by Task 10's sweep) already turned on so later tasks don't need to touch this file again.

- [ ] **Step 1: Add the module to the root reactor**

In `pom.xml`, inside `<modules>`, add:
```xml
<module>gateway-simulator</module>
```
after `<module>fx-service</module>`.

- [ ] **Step 2: Create `gateway-simulator/pom.xml`**

Copy `fx-service/pom.xml` verbatim as a starting point (same parent, same dependency set: web, data-jpa, actuator, micrometer-registry-prometheus, spring-boot-starter-test, flyway-core, flyway-database-postgresql, postgresql runtime, testcontainers junit-jupiter, testcontainers postgresql), with these changes:
- `<artifactId>gateway-simulator</artifactId>` instead of `fx-service`.
- Remove the `wiremock-jetty12` test dependency (fx-service needed it for stubbing the Frankfurter API; gateway-simulator has no outbound HTTP calls to third parties to stub — its own "external rail" is an in-process stub per the spec).
- Add these two dependencies (needed for RabbitMQ consumption, matching `holds-service/pom.xml`'s equivalent entries — read that file to copy the exact `<dependency>` blocks and Testcontainers RabbitMQ test dependency):
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```
plus the Testcontainers RabbitMQ module in test scope (copy the exact block from `holds-service/pom.xml`).

- [ ] **Step 3: Create `gateway-simulator/Dockerfile`**

Copy `fx-service/Dockerfile` verbatim, changing only the artifact/jar filename references from `fx-service` to `gateway-simulator` (read the file first to see its exact structure — it should be a 2-line change at most, following this project's existing multi-module Dockerfile convention where each service Dockerfile builds from repo root).

- [ ] **Step 4: Create the Spring Boot application class**

```java
package com.ledger.gatewaysimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GatewaySimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewaySimulatorApplication.class, args);
    }
}
```

- [ ] **Step 5: Create `application.yml`**

```yaml
server:
  port: 8084

spring:
  application:
    name: gateway-simulator
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:gateway_sim_db}
    username: ${DB_USER:gatewaysim}
    password: ${DB_PASSWORD:gatewaysim}
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

gateway-sim:
  withdrawal-timeout-seconds: ${GATEWAY_SIM_WITHDRAWAL_TIMEOUT_SECONDS:60}
  withdrawal-sweep:
    interval-ms: ${GATEWAY_SIM_WITHDRAWAL_SWEEP_INTERVAL_MS:15000}

ledger:
  base-url: ${LEDGER_SERVICE_URL:http://localhost:8090}

management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  metrics:
    tags:
      application: ${spring.application.name}
```

(Verify `ledger-service`'s actual internal container port is `8090` by checking `docker-compose.yml`'s `ledger-service` block before finalizing this value — use whatever the real value is if different.)

- [ ] **Step 6: Verify the module builds**

Run: `mvn -pl gateway-simulator -am compile -Dapi.version=1.44`
Expected: BUILD SUCCESS (an empty module with just the application class compiles trivially).

- [ ] **Step 7: Commit**

```bash
git add pom.xml gateway-simulator/
git commit -m "chore(gateway-simulator): scaffold Maven module and join the reactor

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Flyway schema migration

**Files:**
- Create: `gateway-simulator/src/main/resources/db/migration/V1__init_schema.sql`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/SchemaMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `external_deposits`, `external_withdrawals`, `webhook_dedup`, `outbox_events`, `processed_events` tables — every later task's JPA entities map onto these exact columns.

- [ ] **Step 1: Write the failing test**

```java
package com.ledger.gatewaysimulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allFiveTablesExistAfterMigration() {
        for (String table : new String[]{
                "external_deposits", "external_withdrawals", "webhook_dedup", "outbox_events", "processed_events"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table %s should exist", table).isEqualTo(1);
        }
    }

    @Test
    void externalDepositsEnforcesUniqueExternalReference() {
        jdbcTemplate.update(
                "INSERT INTO external_deposits (id, external_reference, account_ref, amount_minor, currency, raw_payload) " +
                        "VALUES (gen_random_uuid(), 'dup-ref-1', 'acct-1', 1000, 'USD', '{}'::jsonb)");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO external_deposits (id, external_reference, account_ref, amount_minor, currency, raw_payload) " +
                        "VALUES (gen_random_uuid(), 'dup-ref-1', 'acct-2', 2000, 'USD', '{}'::jsonb)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` to the imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl gateway-simulator -am test -Dtest=SchemaMigrationIntegrationTest -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: FAIL — no migration exists yet, Flyway has nothing to apply, tables don't exist.

- [ ] **Step 3: Write the migration**

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

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_gateway_sim_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

(This matches `holds-service`'s exact `outbox_events`/`processed_events` shape — verify against `holds-service/src/main/resources/db/migration/V1__init_schema.sql` before finalizing, since Task 6/7's outbox-publishing code will be adapted directly from Holds Service's own `OutboxEventPublisher`/`OutboxPollingPublisher` and must match the column names it expects.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl gateway-simulator -am test -Dtest=SchemaMigrationIntegrationTest -Dapi.version=1.44`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): add Flyway schema migration (deposits, withdrawals, webhook dedup, outbox)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: JPA entities and repositories

**Files:**
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/domain/ExternalDeposit.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/domain/DepositStatus.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/domain/ExternalWithdrawal.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/domain/WithdrawalStatus.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/domain/WebhookDedup.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/domain/OutboxEvent.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/repository/ExternalDepositRepository.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/repository/ExternalWithdrawalRepository.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/repository/WebhookDedupRepository.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/repository/OutboxRepository.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/domain/EntityPersistenceIntegrationTest.java`

**Interfaces:**
- Consumes: the Task 4 schema.
- Produces: `ExternalDepositRepository.findByExternalReference(String)`, `ExternalWithdrawalRepository.findBySourceTransactionId(UUID)`, `ExternalWithdrawalRepository.findByStatusAndSubmittedAtBefore(WithdrawalStatus, Instant)` (used by Task 10's sweep), `WebhookDedupRepository` (standard `JpaRepository<WebhookDedup, String>`), `OutboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc()` (used by Task 7's polling publisher) — later tasks depend on these exact method names.

- [ ] **Step 1: Write `DepositStatus` and `WithdrawalStatus` enums**

```java
package com.ledger.gatewaysimulator.domain;

public enum DepositStatus {
    RECEIVED, CREDITED, REJECTED
}
```

```java
package com.ledger.gatewaysimulator.domain;

public enum WithdrawalStatus {
    SUBMITTED, CONFIRMED, FAILED, TIMED_OUT, REVERSED
}
```

- [ ] **Step 2: Write `ExternalDeposit` entity**

```java
package com.ledger.gatewaysimulator.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_deposits")
public class ExternalDeposit {

    @Id
    private UUID id;

    @Column(name = "external_reference", nullable = false, unique = true)
    private String externalReference;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepositStatus status;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExternalDeposit() {
    }

    public ExternalDeposit(UUID id, String externalReference, String accountRef, long amountMinor,
                            String currency, DepositStatus status, String rawPayload) {
        this.id = id;
        this.externalReference = externalReference;
        this.accountRef = accountRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.rawPayload = rawPayload;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getExternalReference() { return externalReference; }
    public String getAccountRef() { return accountRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public DepositStatus getStatus() { return status; }
    public UUID getTransactionId() { return transactionId; }
    public String getRawPayload() { return rawPayload; }
    public Instant getCreatedAt() { return createdAt; }

    public void markCredited(UUID transactionId) {
        this.status = DepositStatus.CREDITED;
        this.transactionId = transactionId;
        this.updatedAt = Instant.now();
    }

    public void markRejected() {
        this.status = DepositStatus.REJECTED;
        this.updatedAt = Instant.now();
    }
}
```

Verify the exact `@JdbcTypeCode(SqlTypes.JSON)` annotation/import pattern this codebase already uses for a JSONB column by checking how `holds-service`'s or `ledger-service`'s `OutboxEvent.payload` field is annotated (search `grep -rn "columnDefinition = \"jsonb\"" --include="*.java" .` from repo root) — match that exact pattern rather than assuming the above is correct verbatim, since Hibernate JSONB mapping conventions can differ subtly by version/dialect already pinned in this project.

- [ ] **Step 3: Write `ExternalWithdrawal` entity**

```java
package com.ledger.gatewaysimulator.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_withdrawals")
public class ExternalWithdrawal {

    @Id
    private UUID id;

    @Column(name = "source_transaction_id", nullable = false, unique = true)
    private UUID sourceTransactionId;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    protected ExternalWithdrawal() {
    }

    public ExternalWithdrawal(UUID id, UUID sourceTransactionId, String accountRef, long amountMinor,
                               String currency) {
        this.id = id;
        this.sourceTransactionId = sourceTransactionId;
        this.accountRef = accountRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = WithdrawalStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSourceTransactionId() { return sourceTransactionId; }
    public String getAccountRef() { return accountRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public WithdrawalStatus getStatus() { return status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getReversalTransactionId() { return reversalTransactionId; }

    public void markConfirmed() {
        this.status = WithdrawalStatus.CONFIRMED;
        this.resolvedAt = Instant.now();
    }

    public void markFailed() {
        this.status = WithdrawalStatus.FAILED;
        this.resolvedAt = Instant.now();
    }

    public void markTimedOut() {
        this.status = WithdrawalStatus.TIMED_OUT;
        this.resolvedAt = Instant.now();
    }

    public void markReversed(UUID reversalTransactionId) {
        this.status = WithdrawalStatus.REVERSED;
        this.reversalTransactionId = reversalTransactionId;
    }
}
```

- [ ] **Step 4: Write `WebhookDedup` entity**

```java
package com.ledger.gatewaysimulator.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "webhook_dedup")
public class WebhookDedup {

    @Id
    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "webhook_count", nullable = false)
    private int webhookCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected WebhookDedup() {
    }

    public WebhookDedup(String externalReference) {
        this.externalReference = externalReference;
        this.webhookCount = 1;
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = Instant.now();
    }

    public String getExternalReference() { return externalReference; }
    public int getWebhookCount() { return webhookCount; }

    public void recordRedelivery() {
        this.webhookCount++;
        this.lastSeenAt = Instant.now();
    }
}
```

- [ ] **Step 5: Write `OutboxEvent` entity**

Copy `holds-service/src/main/java/com/ledger/holdsservice/messaging/OutboxEvent.java` verbatim, changing only the package declaration to `com.ledger.gatewaysimulator.messaging` (place this file at `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/OutboxEvent.java` instead of under `domain/`, matching where Holds Service itself keeps it — adjust the file path in this task's own file list accordingly when creating it).

- [ ] **Step 6: Write the four repositories**

```java
package com.ledger.gatewaysimulator.repository;

import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExternalDepositRepository extends JpaRepository<ExternalDeposit, UUID> {
    Optional<ExternalDeposit> findByExternalReference(String externalReference);
}
```

```java
package com.ledger.gatewaysimulator.repository;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalWithdrawalRepository extends JpaRepository<ExternalWithdrawal, UUID> {
    Optional<ExternalWithdrawal> findBySourceTransactionId(UUID sourceTransactionId);
    List<ExternalWithdrawal> findByStatusAndSubmittedAtBefore(WithdrawalStatus status, Instant cutoff);
}
```

```java
package com.ledger.gatewaysimulator.repository;

import com.ledger.gatewaysimulator.domain.WebhookDedup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDedupRepository extends JpaRepository<WebhookDedup, String> {
}
```

For `OutboxRepository`, copy `holds-service/src/main/java/com/ledger/holdsservice/messaging/OutboxRepository.java` verbatim, changing only the package to `com.ledger.gatewaysimulator.messaging`.

- [ ] **Step 7: Write the integration test**

```java
package com.ledger.gatewaysimulator.domain;

import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import com.ledger.gatewaysimulator.repository.WebhookDedupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EntityPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ExternalDepositRepository depositRepository;
    @Autowired private ExternalWithdrawalRepository withdrawalRepository;
    @Autowired private WebhookDedupRepository webhookDedupRepository;

    @Test
    void findByExternalReferenceRoundTrips() {
        ExternalDeposit deposit = new ExternalDeposit(UUID.randomUUID(), "ext-ref-1", "acct-1",
                1000L, "USD", DepositStatus.RECEIVED, "{}");
        depositRepository.saveAndFlush(deposit);

        assertThat(depositRepository.findByExternalReference("ext-ref-1")).isPresent();
        assertThat(depositRepository.findByExternalReference("does-not-exist")).isEmpty();
    }

    @Test
    void findByStatusAndSubmittedAtBeforeFindsOnlyStuckSubmittedRows() {
        UUID stuckTxnId = UUID.randomUUID();
        ExternalWithdrawal stuck = new ExternalWithdrawal(UUID.randomUUID(), stuckTxnId, "acct-1", 500L, "USD");
        withdrawalRepository.saveAndFlush(stuck);
        withdrawalRepository.flush();
        // Backdate submitted_at directly via a fresh fetch + native update would require an
        // EntityManager; simplest correct approach: use a repository method or JdbcTemplate to
        // backdate this row's submitted_at column to 2 minutes ago, then query with a cutoff of
        // "now minus 1 minute" and confirm the stuck row is found. Implementer: inject
        // JdbcTemplate in this test and run
        // "UPDATE external_withdrawals SET submitted_at = ? WHERE id = ?" with a backdated
        // Instant, then assert findByStatusAndSubmittedAtBefore(SUBMITTED, now-1min) contains it,
        // and a second freshly-submitted row (not backdated) is excluded.
    }

    @Test
    void webhookDedupRecordsRedeliveryCount() {
        WebhookDedup dedup = new WebhookDedup("wh-ref-1");
        webhookDedupRepository.saveAndFlush(dedup);
        dedup.recordRedelivery();
        webhookDedupRepository.saveAndFlush(dedup);

        WebhookDedup reloaded = webhookDedupRepository.findById("wh-ref-1").orElseThrow();
        assertThat(reloaded.getWebhookCount()).isEqualTo(2);
    }
}
```

Fill in the backdating logic in `findByStatusAndSubmittedAtBeforeFindsOnlyStuckSubmittedRows` exactly as the inline comment describes (inject `JdbcTemplate`, run the native `UPDATE`, then assert) before running this test — this is a real step, not a placeholder to leave as a comment; write the actual JDBC call and both assertions (stuck row present, fresh row absent).

- [ ] **Step 8: Run tests**

Run: `mvn -pl gateway-simulator -am test -Dapi.version=1.44`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): add JPA entities and repositories

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Outbox publisher (polling, matching Holds Service's pattern)

**Files:**
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/OutboxEventPublisher.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/OutboxPollingPublisher.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/RabbitConfig.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/MessagingConstants.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/messaging/OutboxPollingPublisherIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxRepository` (Task 5).
- Produces: `OutboxEventPublisher.publish(OutboxEvent)` — Task 8 and Task 9's services call `outboxRepository.save(...)` directly (the polling publisher picks up unpublished rows on its own schedule), so this task's only consumed interface by later tasks is the existence of a working outbox-row-to-RabbitMQ pipeline, not a directly-called Java method.

- [ ] **Step 1: Read Holds Service's existing outbox-publishing code as the template**

Read, in full: `holds-service/src/main/java/com/ledger/holdsservice/messaging/OutboxEventPublisher.java`, `OutboxPollingPublisher.java`, and the outbox-related bean declarations inside `holds-service/src/main/java/com/ledger/holdsservice/messaging/RabbitConfig.java` (the part that is NOT the `ledger.transaction.posted` consumer wiring — Holds Service both consumes that external event AND publishes its own `hold.created`/`hold.captured`/`hold.released` events via this same polling-publisher mechanism; copy the publishing half only).

- [ ] **Step 2: Write `MessagingConstants`**

```java
package com.ledger.gatewaysimulator.messaging;

public final class MessagingConstants {
    public static final String GATEWAY_SIM_EXCHANGE = "gateway-sim.events";
    public static final String HEADER_OUTBOX_EVENT_ID = "outboxEventId";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    // Must match transaction-processor's MessagingConstants.LEDGER_EXCHANGE /
    // TRANSACTION_POSTED_ROUTING_KEY exactly — this is V1's existing exchange, not a new one.
    // Verified against the real, current
    // transaction-processor/src/main/java/com/ledger/txprocessor/messaging/MessagingConstants.java
    // as of this task (Task 7 in this plan consumes it).
    public static final String LEDGER_EXCHANGE = "ledger.events";
    public static final String TRANSACTION_POSTED_ROUTING_KEY = "ledger.transaction.posted";
    public static final String GATEWAY_SIM_TRANSACTION_POSTED_QUEUE =
            "gateway-sim.ledger.transaction.posted.queue";

    private MessagingConstants() {
    }
}
```

- [ ] **Step 3: Write `OutboxEventPublisher` and `OutboxPollingPublisher`**

Adapt Holds Service's versions directly: same publisher-confirms pattern (if Holds Service's publisher uses confirms; verify by reading it), same polling interval/batch-size configuration style, same `published_at` marking logic. Change only: package declaration, and the exchange name from Holds Service's own exchange to `MessagingConstants.GATEWAY_SIM_EXCHANGE` (Gateway Simulator publishes its own events — `deposit.credited`, `withdrawal.reversed`, etc. — on its own exchange, distinct from the `ledger.events` exchange it separately consumes from in Task 7). No other service currently needs to consume Gateway Simulator's own published events in this plan's scope, but declaring the exchange and publishing pipeline now keeps this service's shape consistent with every other service in the platform and leaves room for a future consumer without rework.

- [ ] **Step 4: Write `RabbitConfig`**

Declare: the `RabbitTemplate` bean (Jackson2JsonMessageConverter, matching every other service), the `GATEWAY_SIM_EXCHANGE` topic exchange declaration (this service owns and declares this one, unlike the `ledger.events` exchange it only binds to in Task 7).

- [ ] **Step 5: Write the integration test**

Adapt `holds-service/src/test/java/com/ledger/holdsservice/messaging/OutboxPollingPublisherIntegrationTest.java` directly: same Testcontainers Postgres + RabbitMQ setup, same "insert an outbox row directly, wait for `published_at` to become non-null, confirm the message actually arrived on the exchange" assertion shape.

- [ ] **Step 6: Run tests**

Run: `mvn -pl gateway-simulator -am test -Dapi.version=1.44` (with Docker env vars)
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): add polling outbox publisher on its own exchange

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Consume `ledger.transaction.posted`, filtered to `WITHDRAWAL_EXTERNAL`

**Files:**
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/LedgerTransactionPostedConsumer.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/ProcessedEventGate.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/service/WithdrawalSubmissionService.java`
- Modify: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/messaging/RabbitConfig.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/messaging/LedgerTransactionPostedConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1's outbox `transactionType` payload field; Task 6's `RabbitConfig` (adds a binding); `ExternalWithdrawalRepository` (Task 5).
- Produces: `WithdrawalSubmissionService.submit(UUID sourceTransactionId, String accountRef, long amountMinor, String currency)` — creates the `external_withdrawals` row and invokes the simulated-rail stub. Task 8's confirm endpoint reads rows this creates.

- [ ] **Step 1: Add the RabbitMQ binding to `RabbitConfig`**

Add, to the existing `RabbitConfig` class from Task 6: a `TopicExchange` bean for `MessagingConstants.LEDGER_EXCHANGE` (declared idempotently — matches Holds Service's own `RabbitConfig.ledgerExchange()` bean exactly, since it's not a resource this service owns), a `Queue` bean for `MessagingConstants.GATEWAY_SIM_TRANSACTION_POSTED_QUEUE`, and a `Binding` bean binding that queue to that exchange with routing key `MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY`. Copy the exact bean-declaration shape from `holds-service/src/main/java/com/ledger/holdsservice/messaging/RabbitConfig.java`'s equivalent three beans.

- [ ] **Step 2: Write `ProcessedEventGate`**

Copy `holds-service/src/main/java/com/ledger/holdsservice/messaging/ProcessedEventGate.java` verbatim, changing only the package declaration. (This is the exact same dedup-gate shape as every other consumer in the platform: `markProcessedIfNew(UUID eventId)` returns `true` on first delivery, `false` on redelivery, backed by the `processed_events` table's primary key as the atomicity mechanism.)

- [ ] **Step 3: Write `WithdrawalSubmissionService`**

```java
package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WithdrawalSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalSubmissionService.class);

    private final ExternalWithdrawalRepository withdrawalRepository;

    public WithdrawalSubmissionService(ExternalWithdrawalRepository withdrawalRepository) {
        this.withdrawalRepository = withdrawalRepository;
    }

    public void submit(UUID sourceTransactionId, String accountRef, long amountMinor, String currency) {
        ExternalWithdrawal withdrawal = new ExternalWithdrawal(
                UUID.randomUUID(), sourceTransactionId, accountRef, amountMinor, currency);
        withdrawalRepository.save(withdrawal);
        submitToSimulatedRail(withdrawal);
    }

    /**
     * Stands in for a real payment rail's submission call. No real external call, no
     * synchronous outcome — resolution always arrives later via the confirm endpoint
     * (Task 8) or the timeout sweep (Task 10), never from this method, so the async
     * resolution path is always exercised uniformly.
     */
    private void submitToSimulatedRail(ExternalWithdrawal withdrawal) {
        log.info("Submitted withdrawal {} for account {} ({} {}) to simulated rail",
                withdrawal.getId(), withdrawal.getAccountRef(), withdrawal.getAmountMinor(), withdrawal.getCurrency());
    }
}
```

- [ ] **Step 4: Write `LedgerTransactionPostedConsumer`**

```java
package com.ledger.gatewaysimulator.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.gatewaysimulator.service.WithdrawalSubmissionService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Consumes V1's existing {@code ledger.transaction.posted} event, filtered to
 * {@code transactionType == "WITHDRAWAL_EXTERNAL"}. Field names in the payload
 * ({@code transactionId}, {@code debitAccountRef}, {@code amountMinor}, {@code currency},
 * {@code transactionType}) are verified against the real, current
 * {@code ledger-service/.../TransactionPoster.buildOutboxPayload} as of this task
 * (transactionType added in Task 1 of this plan).
 */
@Component
public class LedgerTransactionPostedConsumer {

    private static final String WITHDRAWAL_EXTERNAL_TYPE = "WITHDRAWAL_EXTERNAL";

    private final WithdrawalSubmissionService submissionService;
    private final ProcessedEventGate processedEventGate;
    private final ObjectMapper objectMapper;

    public LedgerTransactionPostedConsumer(WithdrawalSubmissionService submissionService,
                                            ProcessedEventGate processedEventGate,
                                            ObjectMapper objectMapper) {
        this.submissionService = submissionService;
        this.processedEventGate = processedEventGate;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = MessagingConstants.GATEWAY_SIM_TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    public void handle(Message message, com.rabbitmq.client.Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String outboxEventIdHeader = (String) message.getMessageProperties()
                .getHeaders().get(MessagingConstants.HEADER_OUTBOX_EVENT_ID);

        if (outboxEventIdHeader == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        JsonNode payload = objectMapper.readTree(message.getBody());
        String transactionType = payload.path("transactionType").asText("TRANSFER");

        if (!WITHDRAWAL_EXTERNAL_TYPE.equals(transactionType)) {
            // Not a withdrawal — this service only cares about WITHDRAWAL_EXTERNAL, but every
            // ledger.transaction.posted event flows through this same queue, so ordinary
            // transfers must be acked and ignored, not left unacknowledged.
            channel.basicAck(deliveryTag, false);
            return;
        }

        UUID eventId = UUID.fromString(outboxEventIdHeader);
        boolean firstDelivery = processedEventGate.markProcessedIfNew(eventId);
        if (firstDelivery) {
            UUID transactionId = UUID.fromString(payload.path("transactionId").asText());
            String accountRef = payload.path("debitAccountRef").asText();
            long amountMinor = payload.path("amountMinor").asLong();
            String currency = payload.path("currency").asText();
            submissionService.submit(transactionId, accountRef, amountMinor, currency);
        }
        channel.basicAck(deliveryTag, false);
    }
}
```

- [ ] **Step 5: Write the integration test**

Adapt `holds-service/src/test/java/com/ledger/holdsservice/messaging/LedgerTransactionPostedConsumerIntegrationTest.java`'s Testcontainers setup (real Postgres + RabbitMQ, `rabbitTemplate.send(...)` with the `outboxEventId`/`aggregateId`/`eventType` headers, `awaitility` for async assertions). Write 3 tests:
1. A `WITHDRAWAL_EXTERNAL`-typed message creates an `external_withdrawals` row with the correct fields.
2. A `TRANSFER`-typed message (ordinary transfer) is acked and ignored — no `external_withdrawals` row is created.
3. Redelivering the same `WITHDRAWAL_EXTERNAL` message twice (same `outboxEventId`) creates exactly one `external_withdrawals` row — proving `ProcessedEventGate` dedups correctly (mirrors the existing redelivery test pattern already used by every other consumer in this codebase, e.g. `holds-service`'s own duplicate-delivery test added during the hardening plan's Task 10).

- [ ] **Step 6: Run tests**

Run: `mvn -pl gateway-simulator -am test -Dapi.version=1.44` (with Docker env vars)
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): consume ledger.transaction.posted filtered to WITHDRAWAL_EXTERNAL

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Ledger transaction client + reversal posting

**Files:**
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/ledger/LedgerTransactionClient.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/ledger/LedgerServiceUnavailableException.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/ledger/LedgerTransactionClientIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `LedgerTransactionClient.postTransaction(String debitAccountRef, String creditAccountRef, long amountMinor, String currency, String description, String idempotencyKey)` returning the created/replayed `transactionId` — used by Task 9 (deposit credit) and Task 10 (reversal posting).

- [ ] **Step 1: Read the existing HTTP-client precedent**

Read `holds-service/src/main/java/com/ledger/holdsservice/messaging/LedgerTransactionClient.java` (Holds Service already has a client that calls Ledger's `POST /transactions` for capture) in full — this is the closest existing precedent for exactly the call this task needs to make, including its request/response DTO shapes and error handling for non-2xx responses.

- [ ] **Step 2: Write the failing test**

```java
package com.ledger.gatewaysimulator.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionClientIntegrationTest {
    // Follow HoldsServiceClientIntegrationTest's structure exactly (from the hardening plan,
    // ledger-service/src/test/java/com/ledger/ledgerservice/holds/HoldsServiceClientIntegrationTest.java):
    // a lightweight com.sun.net.httpserver.HttpServer stub for the success case, a
    // connection-refused case against http://localhost:1, and (if the client is given a
    // configurable timeout, matching this plan's Global Constraints precedent from the
    // hardening plan's HoldsServiceClient) a slow-response timeout case.
    //
    // Write actual test methods here proving: (1) postTransaction() against a stub HTTP server
    // returning 201 with {"transactionId": "<uuid>", ...} returns that UUID; (2) against a 409
    // conflict response with a mismatched-hash body, throws an appropriate exception (do NOT
    // treat 409 as success); (3) against an unreachable server (http://localhost:1), throws
    // LedgerServiceUnavailableException.
}
```

Fill in the 3 real test methods per the comment before running — do not leave this file as scaffolding-only.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -pl gateway-simulator -am test -Dtest=LedgerTransactionClientIntegrationTest -Dapi.version=1.44`
Expected: compile failure (the class doesn't exist yet).

- [ ] **Step 4: Write `LedgerServiceUnavailableException`**

```java
package com.ledger.gatewaysimulator.ledger;

public class LedgerServiceUnavailableException extends RuntimeException {
    public LedgerServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Write `LedgerTransactionClient`**

Adapt Holds Service's `LedgerTransactionClient` directly: `RestClient`-based, constructor takes the base URL, `postTransaction(...)` builds a `CreateTransactionRequest`-shaped JSON body (matching Task 1's DTO: `debitAccountRef`, `creditAccountRef`, `amountMinor`, `currency`, `description`, `transactionType` — omit `transactionType` for deposit credits and reversals, since both should default to `"TRANSFER"` per Task 1's Step 3 defaulting behavior, since neither a deposit credit nor a withdrawal reversal is itself a `WITHDRAWAL_EXTERNAL`-typed transaction — only the original client-initiated debit is), sets the `Idempotency-Key` header, parses the `transactionId` field from the response body on `2xx`, throws `LedgerServiceUnavailableException` on a connection failure/timeout, and lets a non-2xx response's exception propagate to the caller (Task 9/10 decide how to handle a definitive rejection — do not swallow it inside this client).

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -pl gateway-simulator -am test -Dtest=LedgerTransactionClientIntegrationTest -Dapi.version=1.44`
Expected: PASS, all 3 tests.

- [ ] **Step 7: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): add LedgerTransactionClient for posting deposit credits and reversals

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: Inbound deposit flow — webhook + control-plane trigger

**Files:**
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/DepositController.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/dto/SimulateDepositRequest.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/dto/DepositWebhookRequest.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/dto/DepositResponse.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/service/DepositService.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/error/ApiExceptionHandler.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/service/DepositServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `LedgerTransactionClient` (Task 8), `ExternalDepositRepository`/`WebhookDedupRepository` (Task 5).
- Produces: `POST /simulator/deposits`, `POST /webhooks/deposits`, `GET /external-deposits/{externalReference}` — Task 12's chaos scenario 07 and Task 13's smoke-test/manual verification call these directly.

- [ ] **Step 1: Write the failing test — first delivery credits, redelivery short-circuits**

```java
package com.ledger.gatewaysimulator.service;

import org.junit.jupiter.api.Test;
// ... standard @SpringBootTest + @Testcontainers (Postgres + RabbitMQ) setup, matching
// this module's other integration tests. Stub LedgerTransactionClient's target with a
// lightweight HTTP stub server returning 201 {"transactionId": "<uuid>"} for any
// POST /transactions call (or point ledger.base-url at a running stub via
// @DynamicPropertySource, following the exact StubHoldsService pattern from
// ledger-service/src/test/java/com/ledger/ledgerservice/testsupport/StubHoldsService.java
// in the hardening plan branch, adapted for this simpler always-201 case).

// Write real test methods:
// 1. firstWebhookDeliveryCreditsTheAccountAndMarksDepositCredited()
// 2. duplicateWebhookDeliveryIsIgnoredAndReturnsTheOriginalOutcome() — same externalReference
//    posted twice, asserts webhook_dedup.webhook_count == 2, exactly one external_deposits row,
//    exactly one downstream POST /transactions call was made (assert via the stub server's own
//    request-count tracking).
// 3. simulateDepositsEndpointGeneratesAReferenceAndInvokesTheRealWebhookPath() — proves
//    POST /simulator/deposits results in a CREDITED external_deposits row without the caller
//    needing to construct a webhook payload by hand.
class DepositServiceIntegrationTest {
}
```

Write the 3 test methods for real (with actual assertions, not comments) before proceeding — the comment block above describes intent; the file must contain working JUnit test code.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl gateway-simulator -am test -Dtest=DepositServiceIntegrationTest -Dapi.version=1.44`
Expected: compile failure (classes don't exist yet).

- [ ] **Step 3: Write the DTOs**

```java
package com.ledger.gatewaysimulator.api.dto;

public record SimulateDepositRequest(String accountRef, long amountMinor, String currency) {
}
```

```java
package com.ledger.gatewaysimulator.api.dto;

public record DepositWebhookRequest(String externalReference, String accountRef, long amountMinor, String currency) {
}
```

```java
package com.ledger.gatewaysimulator.api.dto;

import java.util.UUID;

public record DepositResponse(String externalReference, String accountRef, long amountMinor,
                               String currency, String status, UUID transactionId) {
}
```

- [ ] **Step 4: Write `DepositService`**

```java
package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.api.dto.DepositResponse;
import com.ledger.gatewaysimulator.api.dto.DepositWebhookRequest;
import com.ledger.gatewaysimulator.domain.DepositStatus;
import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import com.ledger.gatewaysimulator.domain.WebhookDedup;
import com.ledger.gatewaysimulator.ledger.LedgerTransactionClient;
import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.repository.WebhookDedupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DepositService {

    private static final String EXTERNAL_CLEARING_PREFIX = "external-clearing-";

    private final ExternalDepositRepository depositRepository;
    private final WebhookDedupRepository webhookDedupRepository;
    private final LedgerTransactionClient ledgerTransactionClient;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public DepositService(ExternalDepositRepository depositRepository,
                           WebhookDedupRepository webhookDedupRepository,
                           LedgerTransactionClient ledgerTransactionClient,
                           MeterRegistry meterRegistry,
                           ObjectMapper objectMapper) {
        this.depositRepository = depositRepository;
        this.webhookDedupRepository = webhookDedupRepository;
        this.ledgerTransactionClient = ledgerTransactionClient;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DepositResponse handleWebhook(DepositWebhookRequest request) {
        WebhookDedup dedup = webhookDedupRepository.findById(request.externalReference())
                .orElse(null);
        if (dedup != null) {
            dedup.recordRedelivery();
            webhookDedupRepository.save(dedup);
            meterRegistry.counter("gateway_sim.webhook.duplicate").increment();
            ExternalDeposit existing = depositRepository.findByExternalReference(request.externalReference())
                    .orElseThrow();
            return toResponse(existing);
        }
        webhookDedupRepository.save(new WebhookDedup(request.externalReference()));

        String rawPayload = toJson(request);
        ExternalDeposit deposit = new ExternalDeposit(UUID.randomUUID(), request.externalReference(),
                request.accountRef(), request.amountMinor(), request.currency(),
                DepositStatus.RECEIVED, rawPayload);
        depositRepository.save(deposit);

        try {
            UUID transactionId = ledgerTransactionClient.postTransaction(
                    EXTERNAL_CLEARING_PREFIX + request.currency(), request.accountRef(),
                    request.amountMinor(), request.currency(), "simulated external deposit",
                    "external-deposit-" + request.externalReference());
            deposit.markCredited(transactionId);
        } catch (Exception rejected) {
            deposit.markRejected();
            meterRegistry.counter("gateway_sim.deposit.rejected").increment();
        }
        depositRepository.save(deposit);
        return toResponse(deposit);
    }

    private String toJson(DepositWebhookRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize webhook payload", e);
        }
    }

    private DepositResponse toResponse(ExternalDeposit deposit) {
        return new DepositResponse(deposit.getExternalReference(), deposit.getAccountRef(),
                deposit.getAmountMinor(), deposit.getCurrency(), deposit.getStatus().name(),
                deposit.getTransactionId());
    }
}
```

Note the deliberate `@Transactional` boundary here wraps the dedup-check-and-insert plus the local row updates, but the outbound `ledgerTransactionClient.postTransaction(...)` HTTP call happens inside it — this mirrors the exact same lock-duration tradeoff the hardening plan's README already documents for `TransactionPoster`'s Holds Service call (a blocking network call made while holding a DB transaction open). This is an accepted, consistent pattern already established elsewhere in this codebase, not a new risk introduced here; call this out explicitly in Task 13's README update rather than trying to avoid it via manual transaction-boundary splitting, which would complicate the redelivery-safety guarantee this method provides.

- [ ] **Step 5: Write `DepositController`**

```java
package com.ledger.gatewaysimulator.api;

import com.ledger.gatewaysimulator.api.dto.*;
import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.service.DepositService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class DepositController {

    private final DepositService depositService;
    private final ExternalDepositRepository depositRepository;

    public DepositController(DepositService depositService, ExternalDepositRepository depositRepository) {
        this.depositService = depositService;
        this.depositRepository = depositRepository;
    }

    @PostMapping("/simulator/deposits")
    public ResponseEntity<DepositResponse> simulateDeposit(@RequestBody SimulateDepositRequest request) {
        String externalReference = "sim-deposit-" + UUID.randomUUID();
        DepositWebhookRequest webhookRequest = new DepositWebhookRequest(
                externalReference, request.accountRef(), request.amountMinor(), request.currency());
        DepositResponse response = depositService.handleWebhook(webhookRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/webhooks/deposits")
    public ResponseEntity<DepositResponse> receiveWebhook(@RequestBody DepositWebhookRequest request) {
        DepositResponse response = depositService.handleWebhook(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/external-deposits/{externalReference}")
    public ResponseEntity<DepositResponse> getDeposit(@PathVariable("externalReference") String externalReference) {
        ExternalDeposit deposit = depositRepository.findByExternalReference(externalReference)
                .orElseThrow(() -> new DepositNotFoundException(externalReference));
        return ResponseEntity.ok(new DepositResponse(deposit.getExternalReference(), deposit.getAccountRef(),
                deposit.getAmountMinor(), deposit.getCurrency(), deposit.getStatus().name(),
                deposit.getTransactionId()));
    }
}
```

(`@PathVariable("externalReference")` needs the explicit name argument — this project's Maven compiler configuration lacks the `-parameters` flag, per an established convention from the hardening plan's Task 1. Create a small `DepositNotFoundException extends RuntimeException` alongside this controller if one doesn't already exist, and wire it to `404` in the `ApiExceptionHandler` below.)

- [ ] **Step 6: Write `ApiExceptionHandler`**

Follow the exact shape of `holds-service`'s or `ledger-service`'s `ApiExceptionHandler.java` (`@RestControllerAdvice`, one `@ExceptionHandler` method per known exception type mapped to an HTTP status). At minimum: `DepositNotFoundException` → `404`.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -pl gateway-simulator -am test -Dapi.version=1.44` (with Docker env vars)
Expected: BUILD SUCCESS, all tests pass including the 3 from Step 1.

- [ ] **Step 8: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): add inbound deposit webhook and simulator trigger endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Outbound withdrawal confirm/fail + reversal + timeout sweep

**Files:**
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/WithdrawalController.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/dto/ConfirmWithdrawalRequest.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/dto/WithdrawalResponse.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/error/WithdrawalNotYetSubmittedException.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/error/WithdrawalNotFoundException.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/service/WithdrawalResolutionService.java`
- Create: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/service/WithdrawalTimeoutSweep.java`
- Modify: `gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/error/ApiExceptionHandler.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/service/WithdrawalResolutionServiceIntegrationTest.java`
- Test: `gateway-simulator/src/test/java/com/ledger/gatewaysimulator/service/WithdrawalTimeoutSweepIntegrationTest.java`

**Interfaces:**
- Consumes: `ExternalWithdrawalRepository` (Task 5), `LedgerTransactionClient` (Task 8), `WithdrawalSubmissionService`-created rows (Task 7).
- Produces: `POST /simulator/withdrawals/{id}/confirm`, `GET /external-withdrawals/{id}` — Task 12's chaos scenarios 08/09 and Task 13's manual verification call these directly.

- [ ] **Step 1: Write the failing tests for `WithdrawalResolutionService`**

```java
package com.ledger.gatewaysimulator.service;

// Standard @SpringBootTest + @Testcontainers (Postgres + RabbitMQ) setup, LedgerTransactionClient
// pointed at a stub HTTP server returning 201 for any POST /transactions call.
//
// Write real test methods:
// 1. confirmingAnUnknownWithdrawalIdThrowsWithdrawalNotYetSubmittedException() — proves the
//    "no SUBMITTED row exists yet" race returns something the controller maps to 409, not a
//    generic 500 or silent no-op.
// 2. confirmingAWithdrawalAsConfirmedMarksItConfirmedWithNoReversal() — no downstream
//    POST /transactions call should be made (assert the stub server received zero requests).
// 3. confirmingAWithdrawalAsFailedPostsAReversalAndMarksItReversed() — assert exactly one
//    downstream POST /transactions call was made with the correct debit/credit accounts
//    (external-clearing-{currency} debited, original account credited) and the resulting row's
//    reversal_transaction_id is set.
// 4. reversalIdempotencyKeyIsDeterministicFromSourceTransactionId() — call the FAILED path
//    twice for the same withdrawal id (simulating a retried confirm call after a lost response)
//    and assert only one reversal transaction is ever created downstream (assert via the stub
//    server's own idempotency-key-based dedup, or simply assert the stub received the same
//    Idempotency-Key header both times if it doesn't itself dedup).
class WithdrawalResolutionServiceIntegrationTest {
}
```

Write these 4 tests for real before proceeding.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl gateway-simulator -am test -Dtest=WithdrawalResolutionServiceIntegrationTest -Dapi.version=1.44`
Expected: compile failure.

- [ ] **Step 3: Write the exceptions and DTOs**

```java
package com.ledger.gatewaysimulator.api.error;

import java.util.UUID;

public class WithdrawalNotYetSubmittedException extends RuntimeException {
    public WithdrawalNotYetSubmittedException(UUID withdrawalId) {
        super("No SUBMITTED withdrawal found yet for id: " + withdrawalId);
    }
}
```

```java
package com.ledger.gatewaysimulator.api.error;

import java.util.UUID;

public class WithdrawalNotFoundException extends RuntimeException {
    public WithdrawalNotFoundException(UUID withdrawalId) {
        super("Withdrawal not found: " + withdrawalId);
    }
}
```

```java
package com.ledger.gatewaysimulator.api.dto;

public record ConfirmWithdrawalRequest(String outcome) {
}
```

```java
package com.ledger.gatewaysimulator.api.dto;

import java.util.UUID;

public record WithdrawalResponse(UUID id, UUID sourceTransactionId, String accountRef, long amountMinor,
                                  String currency, String status, UUID reversalTransactionId) {
}
```

- [ ] **Step 4: Write `WithdrawalResolutionService`**

```java
package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.api.error.WithdrawalNotYetSubmittedException;
import com.ledger.gatewaysimulator.api.error.WithdrawalNotFoundException;
import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import com.ledger.gatewaysimulator.ledger.LedgerTransactionClient;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WithdrawalResolutionService {

    private static final String EXTERNAL_CLEARING_PREFIX = "external-clearing-";

    private final ExternalWithdrawalRepository withdrawalRepository;
    private final LedgerTransactionClient ledgerTransactionClient;
    private final MeterRegistry meterRegistry;

    public WithdrawalResolutionService(ExternalWithdrawalRepository withdrawalRepository,
                                        LedgerTransactionClient ledgerTransactionClient,
                                        MeterRegistry meterRegistry) {
        this.withdrawalRepository = withdrawalRepository;
        this.ledgerTransactionClient = ledgerTransactionClient;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ExternalWithdrawal confirm(UUID withdrawalId, String outcome) {
        ExternalWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new WithdrawalNotFoundException(withdrawalId));
        if (withdrawal.getStatus() != WithdrawalStatus.SUBMITTED) {
            throw new WithdrawalNotYetSubmittedException(withdrawalId);
        }

        if ("CONFIRMED".equals(outcome)) {
            withdrawal.markConfirmed();
            withdrawalRepository.save(withdrawal);
            return withdrawal;
        }

        withdrawal.markFailed();
        withdrawalRepository.save(withdrawal);
        reverse(withdrawal);
        return withdrawal;
    }

    void reverse(ExternalWithdrawal withdrawal) {
        UUID reversalTransactionId = ledgerTransactionClient.postTransaction(
                EXTERNAL_CLEARING_PREFIX + withdrawal.getCurrency(), withdrawal.getAccountRef(),
                withdrawal.getAmountMinor(), withdrawal.getCurrency(), "external withdrawal reversal",
                "external-withdrawal-reversal-" + withdrawal.getSourceTransactionId());
        withdrawal.markReversed(reversalTransactionId);
        withdrawalRepository.save(withdrawal);
        meterRegistry.counter("gateway_sim.withdrawal.reversal").increment();
    }
}
```

Note: `WithdrawalNotYetSubmittedException` as written above only fires when a row exists but isn't `SUBMITTED` (e.g. already `CONFIRMED`/`FAILED`/`REVERSED`/`TIMED_OUT`) — re-check this against the spec's actual race condition, which is a row that **doesn't exist at all yet** (the RabbitMQ consumption from Task 7 hasn't completed before the confirm call arrives). Adjust: `findById` returning empty should ALSO throw `WithdrawalNotYetSubmittedException` (not `WithdrawalNotFoundException` — that's reserved for the `GET` endpoint's genuinely-permanent "no such id" case), since from the confirm endpoint's caller's perspective, an absent row and a not-yet-SUBMITTED row are the same retryable situation. Update the `orElseThrow` on the first line of `confirm(...)` to throw `WithdrawalNotYetSubmittedException` instead of `WithdrawalNotFoundException`, and drop the separate status check below it (an existing row from `findById` is always `SUBMITTED` at this point in the flow, since nothing else transitions it before this method runs — verify this invariant holds given Task 7's `WithdrawalSubmissionService.submit()` always creates rows in `SUBMITTED` status and nothing else writes to this table except this method and the sweep).

- [ ] **Step 5: Write `WithdrawalTimeoutSweep`**

```java
package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class WithdrawalTimeoutSweep {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalTimeoutSweep.class);

    private final ExternalWithdrawalRepository withdrawalRepository;
    private final WithdrawalResolutionService resolutionService;
    private final MeterRegistry meterRegistry;
    private final long timeoutSeconds;

    public WithdrawalTimeoutSweep(ExternalWithdrawalRepository withdrawalRepository,
                                   WithdrawalResolutionService resolutionService,
                                   MeterRegistry meterRegistry,
                                   @Value("${gateway-sim.withdrawal-timeout-seconds:60}") long timeoutSeconds) {
        this.withdrawalRepository = withdrawalRepository;
        this.resolutionService = resolutionService;
        this.meterRegistry = meterRegistry;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${gateway-sim.withdrawal-sweep.interval-ms:15000}")
    public void run() {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);
        List<ExternalWithdrawal> stuck = withdrawalRepository
                .findByStatusAndSubmittedAtBefore(WithdrawalStatus.SUBMITTED, cutoff);
        for (ExternalWithdrawal withdrawal : stuck) {
            try {
                markTimedOutAndReverse(withdrawal.getId());
                meterRegistry.counter("gateway_sim.withdrawal.timeout").increment();
            } catch (Exception e) {
                log.error("Failed to time out withdrawal {}: {}", withdrawal.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    void markTimedOutAndReverse(java.util.UUID withdrawalId) {
        ExternalWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        if (withdrawal.getStatus() != WithdrawalStatus.SUBMITTED) {
            return; // already resolved by a racing confirm call between the query and this transaction
        }
        withdrawal.markTimedOut();
        withdrawalRepository.save(withdrawal);
        resolutionService.reverse(withdrawal);
    }
}
```

This follows the exact structure of `HoldExpirySweep` (Task 5's read; `holds-service/src/main/java/com/ledger/holdsservice/service/HoldExpirySweep.java`): per-row try/catch so one failing row doesn't block the rest of the sweep, and a status re-check inside the transactional method to guard against a race with a concurrent confirm call between the initial query and the update. `markTimedOutAndReverse` calling `resolutionService.reverse(withdrawal)` (a public method on a different Spring bean) from within its own `@Transactional` method is safe — this is a cross-bean call through the proxy, not a same-class self-invocation, so it does not trigger this codebase's known Spring AOP self-invocation bug (verify this reasoning holds by confirming `WithdrawalTimeoutSweep` and `WithdrawalResolutionService` are genuinely different Spring beans, which they are per their separate `@Component`/`@Service` annotations above).

- [ ] **Step 6: Write `WithdrawalController`**

```java
package com.ledger.gatewaysimulator.api;

import com.ledger.gatewaysimulator.api.dto.ConfirmWithdrawalRequest;
import com.ledger.gatewaysimulator.api.dto.WithdrawalResponse;
import com.ledger.gatewaysimulator.api.error.WithdrawalNotFoundException;
import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import com.ledger.gatewaysimulator.service.WithdrawalResolutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class WithdrawalController {

    private final WithdrawalResolutionService resolutionService;
    private final ExternalWithdrawalRepository withdrawalRepository;

    public WithdrawalController(WithdrawalResolutionService resolutionService,
                                 ExternalWithdrawalRepository withdrawalRepository) {
        this.resolutionService = resolutionService;
        this.withdrawalRepository = withdrawalRepository;
    }

    @PostMapping("/simulator/withdrawals/{id}/confirm")
    public ResponseEntity<WithdrawalResponse> confirm(@PathVariable("id") UUID id,
                                                        @RequestBody ConfirmWithdrawalRequest request) {
        ExternalWithdrawal withdrawal = resolutionService.confirm(id, request.outcome());
        return ResponseEntity.ok(toResponse(withdrawal));
    }

    @GetMapping("/external-withdrawals/{id}")
    public ResponseEntity<WithdrawalResponse> get(@PathVariable("id") UUID id) {
        ExternalWithdrawal withdrawal = withdrawalRepository.findById(id)
                .orElseThrow(() -> new WithdrawalNotFoundException(id));
        return ResponseEntity.ok(toResponse(withdrawal));
    }

    private WithdrawalResponse toResponse(ExternalWithdrawal withdrawal) {
        return new WithdrawalResponse(withdrawal.getId(), withdrawal.getSourceTransactionId(),
                withdrawal.getAccountRef(), withdrawal.getAmountMinor(), withdrawal.getCurrency(),
                withdrawal.getStatus().name(), withdrawal.getReversalTransactionId());
    }
}
```

- [ ] **Step 7: Wire exceptions into `ApiExceptionHandler`**

Add: `WithdrawalNotYetSubmittedException` → `409 CONFLICT`, `WithdrawalNotFoundException` → `404 NOT_FOUND`.

- [ ] **Step 8: Write `WithdrawalTimeoutSweepIntegrationTest`**

```java
package com.ledger.gatewaysimulator.service;

// Standard Testcontainers setup, stub LedgerTransactionClient target.
// Insert an ExternalWithdrawal row directly via the repository, then backdate its
// submitted_at column via JdbcTemplate (same technique as Task 5's
// findByStatusAndSubmittedAtBeforeFindsOnlyStuckSubmittedRows test) to simulate it being
// older than the configured timeout — do NOT sleep in the test.
// Manually invoke sweep.run() (autowired bean) rather than waiting on the real @Scheduled
// interval, matching how this codebase's other sweep tests avoid real-time waiting where
// possible (check HoldExpirySweep's own test for this exact pattern and follow it).
// Assert: status becomes TIMED_OUT, reversal_transaction_id is set, status is then REVERSED,
// gateway_sim.withdrawal.timeout counter incremented by 1.
class WithdrawalTimeoutSweepIntegrationTest {
}
```

Write this test for real before proceeding.

- [ ] **Step 9: Run all tests**

Run: `mvn -pl gateway-simulator -am test -Dapi.version=1.44` (with Docker env vars)
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 10: Commit**

```bash
git add gateway-simulator/
git commit -m "feat(gateway-simulator): add withdrawal confirm/fail, compensating reversal, and timeout sweep

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: Observability — 4 new metrics on the existing Prometheus/Grafana stack

**Files:**
- Modify: `prometheus/prometheus.yml`
- Modify: `grafana/dashboards/ledger-platform.json`

**Interfaces:**
- Consumes: the 4 metrics already instrumented inline in Tasks 9-10 (`gateway_sim.webhook.duplicate`, `gateway_sim.deposit.rejected`, `gateway_sim.withdrawal.timeout`, `gateway_sim.withdrawal.reversal`).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add `gateway-simulator` as a Prometheus scrape target**

In `prometheus/prometheus.yml`, add a new `job_name: 'gateway-simulator'` entry following the exact shape of the existing 5 entries, targeting `gateway-simulator:8084` (container-network hostname/port, matching Task 3's `application.yml` port and whatever `docker-compose.yml`'s Task 12 service name will be).

- [ ] **Step 2: Verify exact metric names live**

Bring up `gateway-simulator` alongside the rest of the stack (or standalone with a local Postgres/RabbitMQ if faster), trigger each of the 4 metrics at least once (a duplicate webhook call, a deposit against an unknown account for the rejected counter, a withdrawal left to time out, a failed withdrawal confirm for the reversal counter), and curl `/actuator/prometheus` to confirm the exact exported names — per the hardening plan's own established practice, do not guess Micrometer's naming transform; verify it live. Expected names following the established transform (dots→underscores, `_total` for Counters): `gateway_sim_webhook_duplicate_total`, `gateway_sim_deposit_rejected_total`, `gateway_sim_withdrawal_timeout_total`, `gateway_sim_withdrawal_reversal_total`.

- [ ] **Step 3: Add a new Grafana panel**

In `grafana/dashboards/ledger-platform.json`, add one new `timeseries` panel titled "Gateway Simulator" with 4 series, one per confirmed metric name from Step 2, following the exact JSON structure of the existing panels in this file (read the file first to match `schemaVersion`, panel `gridPos` conventions, and query structure exactly).

- [ ] **Step 4: Verify Grafana dashboard JSON is still valid**

Run a JSON syntax check (`python3 -m json.tool grafana/dashboards/ledger-platform.json > /dev/null` or equivalent) to confirm the edit didn't break the file.

- [ ] **Step 5: Commit**

```bash
git add prometheus/prometheus.yml grafana/dashboards/ledger-platform.json
git commit -m "feat(observability): add gateway-simulator as a Prometheus target with a dashboard panel

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 12: Docker Compose + API Gateway wiring

**Files:**
- Modify: `docker-compose.yml`
- Modify: `api-gateway/src/main/resources/application.yml`

**Interfaces:**
- Consumes: everything from Tasks 1-11.
- Produces: a fully wired, launchable `gateway-simulator` service reachable through the gateway — Task 13's chaos scenarios and manual verification depend on this.

- [ ] **Step 1: Add `gateway-sim-db` and `gateway-simulator` to `docker-compose.yml`**

Following the exact pattern of the `fx-db`/`fx-service` block (read it first): a `gateway-sim-db` Postgres container (`POSTGRES_DB: gateway_sim_db`, `POSTGRES_USER: gatewaysim`, `POSTGRES_PASSWORD: gatewaysim`, health-checked), and a `gateway-simulator` service (built from `gateway-simulator/Dockerfile`, environment pointing `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` at the new DB container, `RABBITMQ_HOST: rabbitmq`, `LEDGER_SERVICE_URL: http://ledger-service:8090` — verify the exact ledger-service internal port/hostname against the existing `ledger-service` block before finalizing — port `8084:8084`, `depends_on` health-checked on `gateway-sim-db` only, matching V3's precedent of not adding a reverse dependency edge from the services it calls). Add a new named volume `gateway-sim-pgdata` alongside the existing 5 volume declarations.

- [ ] **Step 2: Add API Gateway routes**

In `api-gateway/src/main/resources/application.yml`, add 4 new route entries following the exact shape of the existing `fx-rates`/`fx-conversions` entries: `Path=/webhooks/**`, `Path=/simulator/**`, `Path=/external-deposits/**`, `Path=/external-withdrawals/**`, all with `uri: ${GATEWAY_SIMULATOR_URL:http://localhost:8084}`.

- [ ] **Step 3: Add the gateway's environment variable**

In `docker-compose.yml`'s `api-gateway` service block, add `GATEWAY_SIMULATOR_URL: http://gateway-simulator:8084` to its `environment:` map, matching how `FX_SERVICE_URL` is already set there.

- [ ] **Step 4: Verify `docker compose config` is valid**

Run: `docker compose config --services` — should list all services including `gateway-sim-db` and `gateway-simulator` (16 total now), no YAML errors.

- [ ] **Step 5: Bring up the full stack and verify basic reachability**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
```
Then `curl http://localhost:8080/actuator/prometheus` style basic reachability isn't the target here — instead confirm `gateway-simulator` itself started healthily: `docker compose ps gateway-simulator` shows it running, and `curl http://localhost:8084/actuator/health` (direct container-mapped port, bypassing the gateway) returns `{"status":"UP"}`. Tear down: `docker compose down -v`.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml api-gateway/src/main/resources/application.yml
git commit -m "feat: wire gateway-simulator into Docker Compose and API Gateway routes

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: Extend `scripts/smoke-test.sh`, add `post_transaction_with_type` chaos helper, add 3 new chaos scenarios

**Files:**
- Modify: `scripts/smoke-test.sh`
- Modify: `chaos/lib/common.sh`
- Create: `chaos/scenarios/07_duplicate_webhook.sh`
- Create: `chaos/scenarios/08_withdrawal_timeout_sweep.sh`
- Create: `chaos/scenarios/09_out_of_order_confirmation.sh`

**Interfaces:**
- Consumes: everything from Tasks 1-12.
- Produces: nothing consumed by later tasks — this is the last task before final verification.

- [ ] **Step 1: Extend `smoke-test.sh` with a deposit-then-withdrawal round trip**

Read `scripts/smoke-test.sh` in full first to match its existing structure/helper conventions exactly (it likely already has functions or inline curl blocks for account creation, balance checks, and pass/fail reporting — reuse those, don't invent a parallel style). Add a new section: simulate a deposit via `POST /simulator/deposits` for a fresh account, poll `GET /external-deposits/{ref}` until `CREDITED`, assert the account balance increased by the expected amount; then post a `WITHDRAWAL_EXTERNAL` transaction, poll for the resulting `external_withdrawals` row via `GET /external-withdrawals/{id}` (the withdrawal's own id needs to be discoverable — either via a new read endpoint filtering by `sourceTransactionId`, which is a small addition worth adding to `WithdrawalController` now if the smoke test genuinely needs it: `GET /external-withdrawals?sourceTransactionId={id}`, or by querying the database directly via `docker compose exec` the same way the chaos scripts already do — prefer the DB-query approach here to avoid growing the API surface for a test-only need, matching how chaos scenario 4 already reads `outbox`/`processed_events` directly via `psql`), confirm it reaches `SUBMITTED`, then call the confirm endpoint with `FAILED` and assert the original account's balance is restored via the reversal.

- [ ] **Step 2: Add `post_transaction_with_type` to `chaos/lib/common.sh`**

The existing `post_transaction` helper hardcodes its JSON body without a `transactionType` field. Add a new helper alongside it (do not modify `post_transaction` itself, since every existing chaos scenario 1-6 calls it and depends on its current signature):

```bash
post_transaction_with_type() {
  local debit_ref="$1"
  local credit_ref="$2"
  local amount="$3"
  local idem_key="$4"
  local transaction_type="$5"
  curl -s -w "\n%{http_code}" -X POST "$GATEWAY_URL/transactions" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $idem_key" \
    -d "{\"debitAccountRef\":\"$debit_ref\",\"creditAccountRef\":\"$credit_ref\",\"amountMinor\":$amount,\"currency\":\"USD\",\"description\":\"chaos test\",\"transactionType\":\"$transaction_type\"}"
}
```

- [ ] **Step 3: Write `07_duplicate_webhook.sh`**

Following the exact structure/conventions of `chaos/scenarios/04_duplicate_delivery.sh` (source `common.sh`, `reset_all_toxics`, seed state, `pass`/`fail` at the end):

```bash
#!/usr/bin/env bash
# chaos/scenarios/07_duplicate_webhook.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 7: Duplicate deposit webhook delivery ==="

seed_account "chaos7-deposit-target" 0

EXTERNAL_REF="chaos7-webhook-$(date +%s)"
for i in 1 2 3 4 5; do
  curl -sf -X POST "$GATEWAY_URL/webhooks/deposits" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" \
    -d "{\"externalReference\":\"$EXTERNAL_REF\",\"accountRef\":\"chaos7-deposit-target\",\"amountMinor\":1000,\"currency\":\"USD\"}" \
    > /dev/null
done

sleep 3

BALANCE=$(get_account_balance "chaos7-deposit-target")
[ "$BALANCE" = "1000" ] || fail "expected exactly one credit of 1000 despite 5 duplicate webhooks, got balance $BALANCE"

DEPOSIT_COUNT=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
  "SELECT count(*) FROM external_deposits WHERE external_reference = '$EXTERNAL_REF';" | tr -d ' \r\n')
[ "$DEPOSIT_COUNT" = "1" ] || fail "expected exactly one external_deposits row, found $DEPOSIT_COUNT"

WEBHOOK_COUNT=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
  "SELECT webhook_count FROM webhook_dedup WHERE external_reference = '$EXTERNAL_REF';" | tr -d ' \r\n')
[ "$WEBHOOK_COUNT" = "5" ] || fail "expected webhook_count=5 after 5 deliveries, got $WEBHOOK_COUNT"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 7"

pass "Scenario 7: 5 duplicate webhook deliveries produced exactly one credit"
```

(Verify `assert_reconciliation_clean` doesn't fail spuriously due to `gateway-simulator`'s own `external-clearing-` accounts being outside Ledger's reconciliation scope — reconciliation in this codebase checks Ledger's own `entries`/`outbox`/Processor cross-check, not Gateway Simulator's tables, so this should be unaffected, but confirm by reading `assert_reconciliation_clean`'s implementation in `common.sh` before relying on it here.)

- [ ] **Step 4: Write `08_withdrawal_timeout_sweep.sh`**

```bash
#!/usr/bin/env bash
# chaos/scenarios/08_withdrawal_timeout_sweep.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 8: Withdrawal never confirmed, timeout sweep reverses it ==="

seed_account "chaos8-withdrawal-source" 10000

IDEM_KEY="chaos8-$(date +%s)"
RESPONSE=$(post_transaction_with_type "chaos8-withdrawal-source" "external-clearing-USD" 3000 "$IDEM_KEY" "WITHDRAWAL_EXTERNAL")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting withdrawal transaction, got $HTTP_CODE: $BODY"

BALANCE_AFTER_DEBIT=$(get_account_balance "chaos8-withdrawal-source")
[ "$BALANCE_AFTER_DEBIT" = "7000" ] || fail "expected balance 7000 immediately after withdrawal debit, got $BALANCE_AFTER_DEBIT"

echo "Waiting for the withdrawal to be picked up and marked SUBMITTED..."
for i in $(seq 1 30); do
  STATUS=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
    "SELECT status FROM external_withdrawals WHERE account_ref = 'chaos8-withdrawal-source';" | tr -d ' \r\n')
  [ "$STATUS" = "SUBMITTED" ] && break
  sleep 2
done
[ "$STATUS" = "SUBMITTED" ] || fail "withdrawal never reached SUBMITTED status, last seen: $STATUS"

echo "Not confirming — waiting past the configured timeout for the sweep to mark it TIMED_OUT and reverse it..."
echo "NOTE: this scenario requires GATEWAY_SIM_WITHDRAWAL_TIMEOUT_SECONDS to be set low (e.g. 10) via a test-profile"
echo "override for this scenario to complete in reasonable chaos-suite time — verify this override is documented and"
echo "actually applied to the running stack before this scenario runs, either via a dedicated compose override file"
echo "or an env var set for this specific run; do not simply wait out a full 60s production default without confirming"
echo "that's genuinely the intended behavior for this script's runtime."
for i in $(seq 1 30); do
  STATUS=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
    "SELECT status FROM external_withdrawals WHERE account_ref = 'chaos8-withdrawal-source';" | tr -d ' \r\n')
  [ "$STATUS" = "REVERSED" ] && break
  sleep 2
done
[ "$STATUS" = "REVERSED" ] || fail "withdrawal never reached REVERSED status after timeout, last seen: $STATUS"

BALANCE_AFTER_REVERSAL=$(get_account_balance "chaos8-withdrawal-source")
[ "$BALANCE_AFTER_REVERSAL" = "10000" ] || fail "expected balance restored to 10000 after reversal, got $BALANCE_AFTER_REVERSAL"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 8"

pass "Scenario 8: unconfirmed withdrawal timed out and was correctly reversed"
```

Resolve the timeout-override concern named in the script's own echo statements as a real implementation step, not just a comment: add a `GATEWAY_SIM_WITHDRAWAL_TIMEOUT_SECONDS: 10` override specifically for chaos-test runs, either via a `docker-compose.override.yml` used only by `make chaos-test` (check whether such an override file convention already exists in this repo for other test-specific tuning — `grep -rn "override" Makefile docker-compose*.yml`) or by having this script itself restart just the `gateway-simulator` container with the env var overridden before running (`docker compose stop gateway-simulator && docker compose run -e GATEWAY_SIM_WITHDRAWAL_TIMEOUT_SECONDS=10 -d gateway-simulator`, adjusted to whatever actually works cleanly against this project's existing compose setup). Pick whichever mechanism is simpler given what already exists, and make sure the 60-second production default in `application.yml` from Task 3 is left unchanged — only this chaos scenario's own run environment should override it.

- [ ] **Step 5: Add a confirm-by-transaction-id route to `WithdrawalController` (small addition to Task 10's controller)**

`POST /simulator/withdrawals/{id}/confirm` (Task 10) is keyed by Gateway Simulator's own internal withdrawal `id`, generated inside `WithdrawalSubmissionService.submit()` (Task 7) — a value that does not exist yet, and is not knowable to an external caller, until the RabbitMQ-driven consumption of the withdrawal event has actually happened. Scenario 09 below needs to race a confirmation attempt against that consumption, so it needs a lookup key the caller knows immediately: `sourceTransactionId`, returned directly by `POST /transactions`.

Add to `WithdrawalController` (`gateway-simulator/src/main/java/com/ledger/gatewaysimulator/api/WithdrawalController.java`):

```java
@PostMapping("/simulator/withdrawals/by-transaction/{sourceTransactionId}/confirm")
public ResponseEntity<WithdrawalResponse> confirmByTransactionId(
        @PathVariable("sourceTransactionId") UUID sourceTransactionId,
        @RequestBody ConfirmWithdrawalRequest request) {
    ExternalWithdrawal withdrawal = withdrawalRepository.findBySourceTransactionId(sourceTransactionId)
            .orElseThrow(() -> new WithdrawalNotYetSubmittedException(sourceTransactionId));
    return confirm(withdrawal.getId(), request);
}
```

This reuses `ExternalWithdrawalRepository.findBySourceTransactionId` (already produced by Task 5) and delegates to the existing `confirm(UUID, ConfirmWithdrawalRequest)` method above once the row is found, so it inherits the exact same `409`-on-not-yet-submitted behavior with no duplicated logic. `WithdrawalNotYetSubmittedException`'s constructor takes a `UUID` for its message — either add an overload accepting any UUID with a generic label, or adjust the message to not assume it's always a withdrawal id; a one-line change to that exception class covers both call sites.

Add one integration test to `WithdrawalResolutionServiceIntegrationTest` (or a small new test class alongside `WithdrawalController` if this codebase's convention is to test controllers separately from services — check the existing convention in `holds-service`'s tests first) proving: confirming by an unknown `sourceTransactionId` returns `409`; confirming by a known `sourceTransactionId` succeeds identically to confirming by internal `id`.

Run `mvn -pl gateway-simulator -am test -Dapi.version=1.44` and confirm it still passes before moving to the chaos script.

- [ ] **Step 6: Write `09_out_of_order_confirmation.sh`**

Following the exact structure/conventions of `chaos/scenarios/04_duplicate_delivery.sh` (source `common.sh`, `reset_all_toxics`, seed state, `pass`/`fail` at the end):

```bash
#!/usr/bin/env bash
# chaos/scenarios/09_out_of_order_confirmation.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 9: Confirmation races the withdrawal's own RabbitMQ consumption ==="

reset_all_toxics
seed_account "chaos9-withdrawal-source" 10000

echo "Adding latency to gateway-simulator's RabbitMQ connection to widen the race window..."
add_toxic "gateway-sim-rabbitmq" "latency" "downstream" '{"latency": 5000}'

IDEM_KEY="chaos9-$(date +%s)"
RESPONSE=$(post_transaction_with_type "chaos9-withdrawal-source" "external-clearing-USD" 2000 "$IDEM_KEY" "WITHDRAWAL_EXTERNAL")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting withdrawal transaction, got $HTTP_CODE: $BODY"
SOURCE_TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Attempting confirm-by-transaction-id immediately, before the delayed RabbitMQ message has been consumed..."
EARLY_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
  "$GATEWAY_URL/simulator/withdrawals/by-transaction/$SOURCE_TXN_ID/confirm" \
  -H "Authorization: Bearer $(get_chaos_suite_token)" \
  -H "Content-Type: application/json" -d '{"outcome":"CONFIRMED"}')
EARLY_HTTP_CODE=$(echo "$EARLY_RESPONSE" | tail -n1)
[ "$EARLY_HTTP_CODE" = "409" ] || fail "expected 409 confirming before the withdrawal row exists, got $EARLY_HTTP_CODE"

echo "Removing the latency toxic so the delayed message can now be consumed..."
remove_toxic "gateway-sim-rabbitmq" "latency"

echo "Retrying confirm-by-transaction-id until it succeeds..."
for i in $(seq 1 30); do
  RETRY_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST \
    "$GATEWAY_URL/simulator/withdrawals/by-transaction/$SOURCE_TXN_ID/confirm" \
    -H "Authorization: Bearer $(get_chaos_suite_token)" \
    -H "Content-Type: application/json" -d '{"outcome":"CONFIRMED"}')
  RETRY_HTTP_CODE=$(echo "$RETRY_RESPONSE" | tail -n1)
  [ "$RETRY_HTTP_CODE" = "200" ] && break
  sleep 2
done
[ "$RETRY_HTTP_CODE" = "200" ] || fail "confirm never succeeded after removing the toxic, last code: $RETRY_HTTP_CODE"

FINAL_STATUS=$(docker compose exec -T gateway-sim-db psql -U gatewaysim -d gateway_sim_db -t -c \
  "SELECT status FROM external_withdrawals WHERE source_transaction_id = '$SOURCE_TXN_ID';" | tr -d ' \r\n')
[ "$FINAL_STATUS" = "CONFIRMED" ] || fail "expected final status CONFIRMED, got $FINAL_STATUS"

BALANCE=$(get_account_balance "chaos9-withdrawal-source")
[ "$BALANCE" = "8000" ] || fail "expected balance 8000 (10000 - 2000 debited, no reversal since CONFIRMED), got $BALANCE"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 9"

pass "Scenario 9: out-of-order confirmation correctly rejected with 409, succeeded on retry"
```

Verify `add_toxic`'s exact argument order/shape against `chaos/lib/common.sh`'s real implementation before finalizing this script — the call above assumes `add_toxic <proxy-name> <toxic-type> <direction> <json-attributes>`, but confirm this against the function's actual signature (used correctly by scenario 05's own `add_toxic` call) rather than assuming. Also verify whether a Toxiproxy proxy for gateway-simulator's RabbitMQ connection needs to be added to `scripts/provision.sh`'s existing proxy provisioning as part of this task — read that script first to see how existing per-service or shared RabbitMQ proxies are named and provisioned, and add one for `gateway-simulator` if none covers it yet.

- [ ] **Step 7: Run the full chaos suite**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
bash scripts/smoke-test.sh
bash chaos/scenarios/01_rabbitmq_down_mid_publish.sh
bash chaos/scenarios/02_ledger_db_crash_post_commit.sh
bash chaos/scenarios/03_processor_crash_mid_consume.sh
bash chaos/scenarios/04_duplicate_delivery.sh
bash chaos/scenarios/05_partition_during_lock.sh
bash chaos/scenarios/06_fx_saga_crash_mid_leg.sh
bash chaos/scenarios/07_duplicate_webhook.sh
bash chaos/scenarios/08_withdrawal_timeout_sweep.sh
bash chaos/scenarios/09_out_of_order_confirmation.sh
```
Expected: all 9 scenarios green plus the extended smoke test. Tear down: `docker compose down -v`.

- [ ] **Step 8: Commit**

```bash
git add scripts/smoke-test.sh chaos/lib/common.sh chaos/scenarios/07_duplicate_webhook.sh \
        chaos/scenarios/08_withdrawal_timeout_sweep.sh chaos/scenarios/09_out_of_order_confirmation.sh \
        gateway-simulator/
git commit -m "test: extend smoke test and add chaos scenarios 7-9 for Gateway Simulator

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 14: README update and final full-platform acceptance verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-13.
- Produces: the final, complete README — the last task of this plan.

- [ ] **Step 1: Update the README**

Read the current `README.md` in full first. Update it to reflect V5:

- **Architecture**: add Gateway Simulator to the service list (port `8084`), describing both the inbound deposit-webhook flow and the outbound withdrawal/confirm/reversal flow. Note the lock-duration tradeoff of `DepositService.handleWebhook`/`WithdrawalResolutionService.reverse` making a blocking `POST /transactions` call while holding a local DB transaction open — consistent with the same documented tradeoff already noted for `TransactionPoster`'s Holds Service call.
- **What this demonstrates**: add "External payment-rail simulation: webhook deduplication at three layers, saga-style compensation on withdrawal failure/timeout, and out-of-order-confirmation handling via retryable rejection rather than a placeholder-state machine."
- **Known limitations**: note that `POST /webhooks/deposits` uses this platform's own OAuth2 auth rather than a real webhook-signature scheme (a deliberate simplification, per the V5 design spec); note the 60-second (configurable) withdrawal timeout is short by real-payment-rail standards, appropriate for a demo/chaos-test platform.
- **Running locally**: update the container count to 16, mention `gateway-simulator` reachable at `http://localhost:8084` directly or via the gateway's `/webhooks/**`, `/simulator/**`, `/external-deposits/**`, `/external-withdrawals/**` routes.
- Update the "no fees or external payment simulation yet" known-limitations line (search for it — it references both V4 and V5) to reflect that V5 is now done and V4 remains permanently out of scope, not merely "not yet built."

- [ ] **Step 2: Run the complete Maven test suite one final time**

Run: `mvn clean verify -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`) from the repo root.
Expected: `BUILD SUCCESS` across all 6 modules.

- [ ] **Step 3: Run the full Docker Compose acceptance sequence, including all 9 chaos scenarios**

Same sequence as Task 13 Step 6, run once more from a clean slate as the final gate.

- [ ] **Step 4: Manually verify both flows live through the gateway**

```bash
TOKEN=$(bash scripts/get-token.sh client)
curl -s -X POST http://localhost:8080/accounts -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"accountRef":"v5-manual-check","currency":"USD"}'
# Deposit:
curl -s -X POST http://localhost:8080/simulator/deposits -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"accountRef":"v5-manual-check","amountMinor":5000,"currency":"USD"}'
# Poll GET /accounts/v5-manual-check (or the ledger's equivalent existing balance endpoint) and
# confirm the balance increased by 5000.
# Withdrawal + failure + reversal:
curl -s -X POST http://localhost:8080/transactions -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "Idempotency-Key: v5-manual-withdrawal-1" \
  -d '{"debitAccountRef":"v5-manual-check","creditAccountRef":"external-clearing-USD","amountMinor":2000,"currency":"USD","description":"manual check","transactionType":"WITHDRAWAL_EXTERNAL"}'
# Poll gateway-simulator's DB or a status endpoint for the resulting withdrawal id, then:
curl -s -X POST http://localhost:8080/simulator/withdrawals/{id}/confirm -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"outcome":"FAILED"}'
# Confirm the account balance is restored to its pre-withdrawal value (5000, since the deposit
# credited it first).
```
Verify the actual numbers/ids work out before running — don't copy blindly; substitute the real withdrawal id discovered from the previous step.

Tear down: `docker compose down -v`.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: update README for V5 (Gateway Simulator)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
