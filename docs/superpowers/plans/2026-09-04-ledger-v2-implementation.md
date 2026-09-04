# Ledger V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build V2 of the Ledger platform — an API Gateway (Spring Cloud Gateway, built for
the first time), Keycloak-backed OAuth2 authentication (client-credentials for machine
callers, password grant for seeded demo users), and a Holds Service (authorize/capture/
release fund holds against a Ledger account).

**Architecture:** A new `api-gateway` Maven module routes `/transactions/**`,
`/reconciliation/**` to Ledger Service and `/holds/**`, `/accounts/*/available-balance` to a
new Holds Service, validating every request's JWT against Keycloak (a new `keycloak`
container) as an OAuth2 Resource Server. Holds Service is a new microservice with its own
Postgres (`holds_db`), a locally-cached available balance checked under row lock at
hold-creation time, and a lightweight `@Scheduled` polling outbox publisher (not embedded
Debezium, unlike V1's Transaction Processor) relaying `hold.*` events to RabbitMQ. Capture
calls Transaction Processor's existing idempotent `POST /transactions`.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Cloud Gateway, Spring Security OAuth2
Resource Server, Keycloak 25.x, Spring Data JPA, Flyway, PostgreSQL 16, RabbitMQ 3 (Spring
AMQP), Testcontainers, JUnit 5, Maven, Docker Compose.

**Spec:** [docs/superpowers/specs/2026-09-04-ledger-v2-holds-and-auth-design.md](../specs/2026-09-04-ledger-v2-holds-and-auth-design.md)
— this plan implements that spec in full; V1 is already complete and merged to `master`. V3+
sections of the platform architecture spec are out of scope here.

## Global Constraints

- Database-per-service: Holds Service gets its own Postgres database/container
  (`holds-db`/`holds_db`). It never queries Ledger's or Transaction Processor's tables
  directly.
- Money is always represented as integer minor units (`BIGINT amount_minor` /
  `posted_balance_minor` / `held_balance_minor`) — never floating point.
- All cross-service calls are REST (Holds → Ledger's `GET /accounts/{id}`, Holds →
  Transaction Processor's `POST /transactions`); RabbitMQ is used only for the async
  `ledger.transaction.posted` consumption and `hold.*` event publishing.
- Holds Service's outbox relay is a `@Scheduled` polling publisher against its own
  `outbox_events` table (with a `published_at` column) — NOT embedded Debezium. Do not
  introduce a replication slot/publication/pgoutput setup for this service.
- Placing a hold never writes to Ledger Service's tables. Only capture does, via the
  existing idempotent `POST /transactions` with `Idempotency-Key = hold-capture-{holdId}`.
  Ledger Service remains completely unaware holds exist — no changes to `ledger-service` in
  this plan.
- The gateway is the only component that validates JWTs (via Keycloak's JWKS endpoint).
  Backend services (Ledger, Holds) do not independently validate tokens.
- The known overdraw gap (a direct `POST /transactions` call bypassing Holds Service can
  still overdraw an account with active holds) is a documented, accepted limitation — do not
  attempt to close it in this plan (no Ledger Service changes, no cross-service locking).
- The hold-expiry sweep strictly expires (`ACTIVE` past `expires_at` → `EXPIRED`,
  decrementing `held_balance_minor`) — it never attempts to detect or correct balance drift
  from other causes.
- No new Toxiproxy chaos scenarios in V2 — ordinary Testcontainers-backed unit/integration
  tests only, matching V1's rigor (real Postgres/RabbitMQ, never H2 or mocks for integration
  tests).
- Idempotency-Key on `POST /holds` and `POST /holds/{id}/capture` — dedup source of truth is
  always a DB unique constraint or conditional UPDATE, never an application-level
  read-then-write check alone (same discipline V1 established).
- Build tool is Maven; new modules join the existing `ledger-platform` reactor at the repo
  root (`D:\Ledger\pom.xml`).

---

### Task 1: Holds Service — Maven module scaffold

**Files:**
- Modify: `D:\Ledger\pom.xml` (add `holds-service` to `<modules>`)
- Create: `D:\Ledger\holds-service\pom.xml`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\HoldsServiceApplication.java`
- Create: `D:\Ledger\holds-service\src\main\resources\application.yml`
- Create: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\HoldsServiceApplicationTests.java`

**Interfaces:**
- Produces: a third reactor module, `holds-service` (port `8082`), bootable via
  `mvn spring-boot:run` and passing `mvn test` with a trivial context-loads test. Later tasks
  add the real schema/entities/API — this task only proves the module joins the reactor and
  boots.

- [ ] **Step 1: Add `holds-service` to the root reactor**

```xml
<!-- modify D:\Ledger\pom.xml — add inside <modules> -->
<modules>
    <module>ledger-service</module>
    <module>transaction-processor</module>
    <module>holds-service</module>
</modules>
```

- [ ] **Step 2: Create the module POM**

```xml
<!-- D:\Ledger\holds-service\pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.ledger</groupId>
        <artifactId>ledger-platform</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>holds-service</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Note: the root `pom.xml`'s `<pluginManagement>` block (added in V1 to fix a repackage-binding
gap — see `pom.xml`'s existing comment above that block) already binds
`spring-boot-maven-plugin`'s `repackage` goal for every module in the reactor, including this
one — no per-module `<executions>` needed here, matching `ledger-service`/
`transaction-processor`'s existing POMs.

- [ ] **Step 3: Create the application class**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\HoldsServiceApplication.java
package com.ledger.holdsservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HoldsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HoldsServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Create application.yml**

```yaml
# D:\Ledger\holds-service\src\main\resources\application.yml
server:
  port: 8082

spring:
  application:
    name: holds-service
```

- [ ] **Step 5: Write the context-loads test**

Following the same temporary-exclusion pattern V1 used in its own Task 1 (superseded by a
real Testcontainers-backed test in Task 2 of this plan, once a datasource exists):

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\HoldsServiceApplicationTests.java
package com.ledger.holdsservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
class HoldsServiceApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 6: Build the whole reactor and run all tests**

Run: `mvn -f D:\Ledger\pom.xml clean verify`
Expected: BUILD SUCCESS across all three modules (`ledger-service`, `transaction-processor`,
`holds-service`); this module's `contextLoads` test passes.

- [ ] **Step 7: Commit**

```bash
git add pom.xml holds-service
git commit -m "chore(holds-service): scaffold Maven module and join the reactor

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Holds Service Postgres schema via Flyway, verified with Testcontainers

**Files:**
- Modify: `D:\Ledger\holds-service\pom.xml` (add Flyway, PostgreSQL driver, Testcontainers deps)
- Create: `D:\Ledger\holds-service\src\main\resources\db\migration\V1__init_schema.sql`
- Modify: `D:\Ledger\holds-service\src\main\resources\application.yml` (datasource/Flyway config)
- Create: `D:\Ledger\holds-service\src\test\resources\application-test.yml`
- Delete: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\HoldsServiceApplicationTests.java` (superseded)
- Create: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\SchemaMigrationIntegrationTest.java`

**Interfaces:**
- Produces: a `holds_db` schema (via Flyway migration `V1__init_schema.sql`) containing
  tables `holds`, `account_balance_cache`, `outbox_events`, `processed_events` exactly per
  the spec. Later tasks' JPA entities and repositories map onto these exact table/column
  names.

- [ ] **Step 1: Add Flyway, PostgreSQL, and Testcontainers dependencies**

```xml
<!-- add inside D:\Ledger\holds-service\pom.xml <dependencies> -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

(The Testcontainers BOM is already declared in the root `pom.xml`'s `<dependencyManagement>`
from V1 — no change needed there.)

- [ ] **Step 2: Write the V1 schema migration**

```sql
-- D:\Ledger\holds-service\src\main\resources\db\migration\V1__init_schema.sql
CREATE TABLE holds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_ref             VARCHAR(128) NOT NULL,
    destination_account_ref VARCHAR(128) NOT NULL,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency                CHAR(3) NOT NULL,
    status                  VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
    idempotency_key         VARCHAR(255) NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    captured_amount_minor   BIGINT NOT NULL DEFAULT 0,
    created_transaction_id  UUID,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_holds_idem_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_holds_account_status ON holds(account_ref, status);

CREATE TABLE account_balance_cache (
    account_ref             VARCHAR(128) PRIMARY KEY,
    posted_balance_minor    BIGINT NOT NULL DEFAULT 0,
    held_balance_minor      BIGINT NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
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

- [ ] **Step 3: Configure the main application datasource**

```yaml
# D:\Ledger\holds-service\src\main\resources\application.yml
server:
  port: 8082

spring:
  application:
    name: holds-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:holds_db}
    username: ${DB_USER:holds}
    password: ${DB_PASSWORD:holds}
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 4: Create the test profile config**

```yaml
# D:\Ledger\holds-service\src\test\resources\application-test.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 5: Delete the Task 1 placeholder test**

Delete `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\HoldsServiceApplicationTests.java`
— superseded by the Testcontainers-backed test below.

- [ ] **Step 6: Write the failing Testcontainers schema-migration test**

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\SchemaMigrationIntegrationTest.java
package com.ledger.holdsservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allExpectedTablesExist() {
        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "holds", "account_balance_cache", "outbox_events", "processed_events",
                "flyway_schema_history"
        );
    }

    @Test
    void idempotencyKeyUniqueConstraintIsEnforced() {
        jdbcTemplate.update("""
                INSERT INTO holds (id, account_ref, destination_account_ref, amount_minor,
                                    currency, status, idempotency_key, expires_at)
                VALUES (gen_random_uuid(), 'acct-a', 'acct-merchant', 500, 'USD', 'ACTIVE',
                        'dup-key', now() + interval '1 hour')
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        INSERT INTO holds (id, account_ref, destination_account_ref, amount_minor,
                                            currency, status, idempotency_key, expires_at)
                        VALUES (gen_random_uuid(), 'acct-a', 'acct-merchant', 500, 'USD', 'ACTIVE',
                                'dup-key', now() + interval '1 hour')
                        """)
        ).hasMessageContaining("uq_holds_idem_key");
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test`
Expected: PASS (2 tests: `allExpectedTablesExist`, `idempotencyKeyUniqueConstraintIsEnforced`).
If it fails before the migration file exists, that confirms the RED state — restore/complete
step 2 and re-run.

- [ ] **Step 8: Commit**

```bash
git add holds-service/pom.xml \
        holds-service/src/main/resources/db/migration \
        holds-service/src/main/resources/application.yml \
        holds-service/src/test/resources/application-test.yml \
        holds-service/src/test/java/com/ledger/holdsservice/SchemaMigrationIntegrationTest.java
git rm holds-service/src/test/java/com/ledger/holdsservice/HoldsServiceApplicationTests.java
git commit -m "feat(holds-service): add Flyway schema migration (holds, balance cache, outbox, processed_events)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Holds Service JPA entities and repositories

**Files:**
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\HoldStatus.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\Hold.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\AccountBalanceCache.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\OutboxEvent.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\repository\HoldRepository.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\repository\AccountBalanceCacheRepository.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\repository\OutboxRepository.java`
- Test: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\repository\AccountBalanceCacheLockingIntegrationTest.java`

**Interfaces:**
- Consumes: the schema from Task 2 (`holds`, `account_balance_cache`, `outbox_events` tables
  — column names must match exactly).
- Produces: `Hold` (fields: `id: UUID`, `accountRef: String`, `destinationAccountRef: String`,
  `amountMinor: long`, `currency: String`, `status: HoldStatus`, `idempotencyKey: String`,
  `expiresAt: Instant`, `capturedAmountMinor: long`, `createdTransactionId: UUID`,
  `version: long`), `AccountBalanceCache` (fields: `accountRef: String`,
  `postedBalanceMinor: long`, `heldBalanceMinor: long`), `OutboxEvent` (fields: `id: UUID`,
  `aggregateId: UUID`, `eventType: String`, `payload: String`, `publishedAt: Instant`).
  `AccountBalanceCacheRepository.lockByAccountRef(String): Optional<AccountBalanceCache>` is
  the method Task 4's hold-creation logic calls — it must return the row locked via
  `SELECT ... FOR UPDATE`.

Applies the same two lessons V1's Task 3 learned the hard way (documented in V1's plan and
ledger): (1) Postgres `CHAR(n)` columns need `@JdbcTypeCode(SqlTypes.CHAR)` on the entity
field, not a bare `String`, or `ddl-auto: validate` fails; (2) `@Column(nullable = false)`
timestamp fields need `@PrePersist`/`@PreUpdate` lifecycle callbacks to populate
`createdAt`/`updatedAt`, since Hibernate's own not-null check runs before a DB-side
`DEFAULT now()` can apply. Apply both proactively — do not wait to discover them by trial
and error.

- [ ] **Step 1: Write the HoldStatus enum**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\HoldStatus.java
package com.ledger.holdsservice.domain;

public enum HoldStatus {
    ACTIVE, CAPTURED, RELEASED, EXPIRED
}
```

- [ ] **Step 2: Write the Hold entity**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\Hold.java
package com.ledger.holdsservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "holds")
public class Hold {

    @Id
    private UUID id;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "destination_account_ref", nullable = false)
    private String destinationAccountRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "created_transaction_id")
    private UUID createdTransactionId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Hold() {
        // JPA
    }

    public Hold(UUID id, String accountRef, String destinationAccountRef, long amountMinor,
                String currency, HoldStatus status, String idempotencyKey, Instant expiresAt) {
        this.id = id;
        this.accountRef = accountRef;
        this.destinationAccountRef = destinationAccountRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.capturedAmountMinor = 0L;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAccountRef() { return accountRef; }
    public String getDestinationAccountRef() { return destinationAccountRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public HoldStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getCapturedAmountMinor() { return capturedAmountMinor; }
    public UUID getCreatedTransactionId() { return createdTransactionId; }
    public long getVersion() { return version; }

    public long remainingAmountMinor() {
        return amountMinor - capturedAmountMinor;
    }

    public void markCaptured(long capturedNowMinor, UUID transactionId) {
        this.capturedAmountMinor += capturedNowMinor;
        this.createdTransactionId = transactionId;
        this.status = HoldStatus.CAPTURED;
    }

    public void markReleased() {
        this.status = HoldStatus.RELEASED;
    }

    public void markExpired() {
        this.status = HoldStatus.EXPIRED;
    }
}
```

- [ ] **Step 3: Write the AccountBalanceCache entity**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\AccountBalanceCache.java
package com.ledger.holdsservice.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "account_balance_cache")
public class AccountBalanceCache {

    @Id
    @Column(name = "account_ref")
    private String accountRef;

    @Column(name = "posted_balance_minor", nullable = false)
    private long postedBalanceMinor;

    @Column(name = "held_balance_minor", nullable = false)
    private long heldBalanceMinor;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalanceCache() {
        // JPA
    }

    public AccountBalanceCache(String accountRef, long postedBalanceMinor, long heldBalanceMinor) {
        this.accountRef = accountRef;
        this.postedBalanceMinor = postedBalanceMinor;
        this.heldBalanceMinor = heldBalanceMinor;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getAccountRef() { return accountRef; }
    public long getPostedBalanceMinor() { return postedBalanceMinor; }
    public long getHeldBalanceMinor() { return heldBalanceMinor; }

    public long availableBalanceMinor() {
        return postedBalanceMinor - heldBalanceMinor;
    }

    public void setPostedBalanceMinor(long postedBalanceMinor) {
        this.postedBalanceMinor = postedBalanceMinor;
    }

    public void hold(long amountMinor) {
        this.heldBalanceMinor += amountMinor;
    }

    public void releaseHeld(long amountMinor) {
        this.heldBalanceMinor -= amountMinor;
    }
}
```

- [ ] **Step 4: Write the OutboxEvent entity**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\domain\OutboxEvent.java
package com.ledger.holdsservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, UUID aggregateId, String eventType, String payload) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
```

- [ ] **Step 5: Write the repositories**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\repository\HoldRepository.java
package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
    Optional<Hold> findByIdempotencyKey(String idempotencyKey);
    List<Hold> findByStatusAndExpiresAtBefore(HoldStatus status, Instant threshold);
}
```

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\repository\AccountBalanceCacheRepository.java
package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountBalanceCacheRepository extends JpaRepository<AccountBalanceCache, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AccountBalanceCache c WHERE c.accountRef = :accountRef")
    Optional<AccountBalanceCache> lockByAccountRef(@Param("accountRef") String accountRef);
}
```

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\repository\OutboxRepository.java
package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc();
}
```

- [ ] **Step 6: Write the failing locking integration test**

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\repository\AccountBalanceCacheLockingIntegrationTest.java
package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountBalanceCacheLockingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedCache() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status ->
                accountBalanceCacheRepository.save(new AccountBalanceCache("acct-lock-test", 10_000L, 0L)));
    }

    @Test
    void concurrentHoldAttemptsOnSameAccountSerializeAndNeverOversubscribe() throws InterruptedException {
        int threadCount = 10;
        long holdAmount = 1_500L; // 10 * 1500 = 15,000 > 10,000 available -> some must fail
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    boolean won = Boolean.TRUE.equals(tx.execute(status -> {
                        AccountBalanceCache cache = accountBalanceCacheRepository
                                .lockByAccountRef("acct-lock-test").orElseThrow();
                        if (cache.availableBalanceMinor() < holdAmount) {
                            return false;
                        }
                        cache.hold(holdAmount);
                        accountBalanceCacheRepository.save(cache);
                        return true;
                    }));
                    if (won) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Exactly 6 of 10 can succeed: 6 * 1500 = 9000 <= 10000, 7 * 1500 = 10500 > 10000
        assertThat(successes.get()).isEqualTo(6);

        AccountBalanceCache finalCache = accountBalanceCacheRepository.findById("acct-lock-test").orElseThrow();
        assertThat(finalCache.getHeldBalanceMinor()).isEqualTo(6 * holdAmount);
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test -Dtest=AccountBalanceCacheLockingIntegrationTest`
Expected: PASS — exactly 6 of 10 concurrent threads succeed (no oversubscription past
available balance), proving the lock genuinely serializes access rather than allowing a
race.

- [ ] **Step 8: Commit**

```bash
git add holds-service/src/main/java/com/ledger/holdsservice/domain \
        holds-service/src/main/java/com/ledger/holdsservice/repository \
        holds-service/src/test/java/com/ledger/holdsservice/repository
git commit -m "feat(holds-service): add JPA entities and repositories with row-locked balance cache

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: HoldService — create/release with atomic balance-cache locking

**Files:**
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\InsufficientAvailableBalanceException.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldNotFoundException.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldIdempotencyConflictException.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldPoster.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldService.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\CreateHoldRequest.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\HoldResponse.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\AvailableBalanceResponse.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\HoldController.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\error\ApiExceptionHandler.java`
- Test: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\service\HoldServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `AccountBalanceCacheRepository.lockByAccountRef`, `HoldRepository`,
  `OutboxRepository` from Task 3.
- Produces: `HoldService.createHold(CreateHoldRequest, String idempotencyKey): HoldResponse`,
  `HoldService.release(UUID holdId): HoldResponse` — Task 5's capture logic and Task 6's
  expiry sweep both build on `HoldRepository`/`AccountBalanceCacheRepository` directly using
  this same locking discipline, not by calling `HoldService`.

Follows V1's `TransactionService`/`TransactionPoster` split (documented in V1's plan and
ledger): a same-class self-invocation of an `@Transactional` method silently bypasses
Spring's AOP proxy, and any `DataAccessException` that propagates through a `@Transactional`
method marks the whole transaction rollback-only, breaking a subsequent replay lookup in the
same method. `HoldPoster` holds the single `@Transactional` orchestration method;
`HoldService` is a thin, non-transactional entry point that calls it through a real Spring
bean boundary and performs idempotency-replay recovery in a fresh transaction after a losing
race.

- [ ] **Step 1: Write the domain exceptions**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\InsufficientAvailableBalanceException.java
package com.ledger.holdsservice.service;

public class InsufficientAvailableBalanceException extends RuntimeException {
    public InsufficientAvailableBalanceException(String accountRef) {
        super("Insufficient available balance for account: " + accountRef);
    }
}
```

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldNotFoundException.java
package com.ledger.holdsservice.service;

import java.util.UUID;

public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(UUID holdId) {
        super("Hold not found: " + holdId);
    }
}
```

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldIdempotencyConflictException.java
package com.ledger.holdsservice.service;

public class HoldIdempotencyConflictException extends RuntimeException {
    public HoldIdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key reused with a different request body: " + idempotencyKey);
    }
}
```

- [ ] **Step 2: Write the DTOs**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\CreateHoldRequest.java
package com.ledger.holdsservice.api.dto;

public record CreateHoldRequest(
        String accountRef,
        String destinationAccountRef,
        long amountMinor,
        String currency,
        long expiresInSeconds
) {
}
```

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\HoldResponse.java
package com.ledger.holdsservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record HoldResponse(
        UUID holdId,
        String status,
        String accountRef,
        String destinationAccountRef,
        long amountMinor,
        long capturedAmountMinor,
        String currency,
        Instant expiresAt,
        boolean replay
) {
}
```

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\AvailableBalanceResponse.java
package com.ledger.holdsservice.api.dto;

public record AvailableBalanceResponse(
        String accountRef,
        long postedBalanceMinor,
        long heldBalanceMinor,
        long availableBalanceMinor
) {
}
```

- [ ] **Step 3: Write HoldPoster — the atomic orchestrator**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldPoster.java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.domain.HoldStatus;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
import com.ledger.holdsservice.domain.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class HoldPoster {

    private final HoldRepository holdRepository;
    private final AccountBalanceCacheRepository accountBalanceCacheRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public HoldPoster(HoldRepository holdRepository,
                       AccountBalanceCacheRepository accountBalanceCacheRepository,
                       OutboxRepository outboxRepository,
                       ObjectMapper objectMapper) {
        this.holdRepository = holdRepository;
        this.accountBalanceCacheRepository = accountBalanceCacheRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HoldResponse createInTransaction(CreateHoldRequest request, String idempotencyKey) {
        AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(request.accountRef())
                .orElseGet(() -> {
                    AccountBalanceCache fresh = new AccountBalanceCache(request.accountRef(), 0L, 0L);
                    accountBalanceCacheRepository.save(fresh);
                    return accountBalanceCacheRepository.lockByAccountRef(request.accountRef()).orElseThrow();
                });

        if (cache.availableBalanceMinor() < request.amountMinor()) {
            throw new InsufficientAvailableBalanceException(request.accountRef());
        }

        UUID holdId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(request.expiresInSeconds());
        Hold hold = new Hold(holdId, request.accountRef(), request.destinationAccountRef(),
                request.amountMinor(), request.currency(), HoldStatus.ACTIVE, idempotencyKey, expiresAt);

        holdRepository.saveAndFlush(hold);

        cache.hold(request.amountMinor());
        accountBalanceCacheRepository.save(cache);

        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.created",
                writePayload(hold)));

        return toResponse(hold, false);
    }

    @Transactional
    public HoldResponse releaseInTransaction(UUID holdId) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            return toResponse(hold, false);
        }

        AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(hold.getAccountRef())
                .orElseThrow(() -> new IllegalStateException("Missing balance cache for " + hold.getAccountRef()));
        cache.releaseHeld(hold.remainingAmountMinor());
        accountBalanceCacheRepository.save(cache);

        hold.markReleased();
        holdRepository.save(hold);

        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.released",
                writePayload(hold)));

        return toResponse(hold, false);
    }

    HoldResponse toResponse(Hold hold, boolean replay) {
        return new HoldResponse(hold.getId(), hold.getStatus().name(), hold.getAccountRef(),
                hold.getDestinationAccountRef(), hold.getAmountMinor(), hold.getCapturedAmountMinor(),
                hold.getCurrency(), hold.getExpiresAt(), replay);
    }

    private String writePayload(Hold hold) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "holdId", hold.getId().toString(),
                    "accountRef", hold.getAccountRef(),
                    "destinationAccountRef", hold.getDestinationAccountRef(),
                    "amountMinor", hold.getAmountMinor(),
                    "status", hold.getStatus().name(),
                    "occurredAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
```

- [ ] **Step 4: Write HoldService — the non-transactional entry point with race recovery**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldService.java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.repository.HoldRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class HoldService {

    private final HoldPoster holdPoster;
    private final HoldRepository holdRepository;

    public HoldService(HoldPoster holdPoster, HoldRepository holdRepository) {
        this.holdPoster = holdPoster;
        this.holdRepository = holdRepository;
    }

    public HoldResponse createHold(CreateHoldRequest request, String idempotencyKey) {
        var existing = holdRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return holdPoster.toResponse(existing.get(), true);
        }

        try {
            return holdPoster.createInTransaction(request, idempotencyKey);
        } catch (DataIntegrityViolationException raceLost) {
            Hold winner = holdRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            return holdPoster.toResponse(winner, true);
        }
    }

    public HoldResponse release(UUID holdId) {
        return holdPoster.releaseInTransaction(holdId);
    }

    public HoldResponse getHold(UUID holdId) {
        Hold hold = holdRepository.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return holdPoster.toResponse(hold, false);
    }
}
```

- [ ] **Step 5: Write the REST controller**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\HoldController.java
package com.ledger.holdsservice.api;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> create(
            @RequestBody CreateHoldRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        HoldResponse response = holdService.createHold(request, idempotencyKey);
        HttpStatus status = response.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (response.replay()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(response);
    }

    @PostMapping("/holds/{id}/release")
    public ResponseEntity<HoldResponse> release(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(holdService.release(id));
    }

    @GetMapping("/holds/{id}")
    public ResponseEntity<HoldResponse> get(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(holdService.getHold(id));
    }
}
```

- [ ] **Step 6: Write the exception handler**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\error\ApiExceptionHandler.java
package com.ledger.holdsservice.api.error;

import com.ledger.holdsservice.service.HoldIdempotencyConflictException;
import com.ledger.holdsservice.service.HoldNotFoundException;
import com.ledger.holdsservice.service.InsufficientAvailableBalanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HoldNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(HoldNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientAvailableBalanceException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientBalance(InsufficientAvailableBalanceException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HoldIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(HoldIdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 7: Write the failing integration test covering creation, oversubscription, and release**

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\service\HoldServiceIntegrationTest.java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class HoldServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private HoldService holdService;
    @Autowired
    private HoldRepository holdRepository;
    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void seedBalance() {
        holdRepository.deleteAll();
        accountBalanceCacheRepository.deleteAll();
        outboxRepository.deleteAll();
        accountBalanceCacheRepository.save(new AccountBalanceCache("acct-holds-a", 10_000L, 0L));
    }

    @Test
    void createsAHoldAndDecrementsAvailableBalance() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 3_000L, "USD", 3600);

        HoldResponse response = holdService.createHold(request, "hold-key-1");

        assertThat(response.replay()).isFalse();
        assertThat(response.status()).isEqualTo("ACTIVE");

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(7_000L);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyReturnsReplayWithoutDoubleHolding() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 2_000L, "USD", 3600);

        HoldResponse first = holdService.createHold(request, "hold-key-2");
        HoldResponse second = holdService.createHold(request, "hold-key-2");

        assertThat(second.replay()).isTrue();
        assertThat(second.holdId()).isEqualTo(first.holdId());

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.heldBalanceMinor: cache.getHeldBalanceMinor()).isEqualTo(2_000L);
    }

    @Test
    void insufficientAvailableBalanceThrowsAndMutatesNothing() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 999_999L, "USD", 3600);

        assertThatThrownBy(() -> holdService.createHold(request, "hold-key-3"))
                .isInstanceOf(InsufficientAvailableBalanceException.class);

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.getHeldBalanceMinor()).isZero();
    }

    @Test
    void releaseReturnsHeldFundsToAvailableBalance() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 4_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "hold-key-4");

        HoldResponse released = holdService.release(created.holdId());

        assertThat(released.status()).isEqualTo("RELEASED");
        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L);
    }

    @Test
    void releasingAnAlreadyTerminalHoldIsANoOp() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 1_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "hold-key-5");
        holdService.release(created.holdId());

        HoldResponse secondRelease = holdService.release(created.holdId());

        assertThat(secondRelease.status()).isEqualTo("RELEASED");
        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L); // not double-released
    }
}
```

Fix the typo in the test above before running it: `cache.heldBalanceMinor: cache.getHeldBalanceMinor()`
in `sameIdempotencyKeyReturnsReplayWithoutDoubleHolding` must read simply
`cache.getHeldBalanceMinor()` — this was left in this plan as a labeled-statement typo;
remove the `cache.heldBalanceMinor:` label entirely when writing the actual test file.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test -Dtest=HoldServiceIntegrationTest`
Expected: PASS — all 5 tests.

- [ ] **Step 9: Commit**

```bash
git add holds-service/src/main/java/com/ledger/holdsservice/service \
        holds-service/src/main/java/com/ledger/holdsservice/api \
        holds-service/src/test/java/com/ledger/holdsservice/service
git commit -m "feat(holds-service): add idempotent hold creation/release with atomic balance locking

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Hold capture — calling Transaction Processor's POST /transactions

**Files:**
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\LedgerTransactionClient.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\CaptureExceedsRemainingAmountException.java`
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldPoster.java` (add capture logic)
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldService.java` (add capture entry point)
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\CreateHoldRequest.java`
  — no change needed; new DTO below instead
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\CaptureHoldRequest.java`
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\HoldController.java` (add capture endpoint)
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\error\ApiExceptionHandler.java` (map new exception)
- Modify: `D:\Ledger\holds-service\src\main\resources\application.yml` (add `processor.base-url`)
- Test: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\service\HoldCaptureIntegrationTest.java`

**Interfaces:**
- Consumes: `HoldPoster`/`HoldService` from Task 4; calls Transaction Processor's real
  `POST /transactions` (built in V1, `debitAccountRef`/`creditAccountRef`/`amountMinor`/
  `currency`/`description` request shape, response includes `transactionId: UUID`).
- Produces: `HoldService.capture(UUID holdId, long amountMinor): HoldResponse`.

The capture call to Transaction Processor is a real blocking HTTP call. Following the same
lesson V1's reconciliation job learned (documented in V1's ledger): a blocking HTTP call
must NOT happen inside the same `@Transactional` method that also does DB writes before and
after it — holding a DB connection open for the duration of an external call is wasteful,
and if the HTTP call throws partway through a transaction that already did other writes,
Spring's rollback-only marking can make a subsequent write in the same transaction silently
fail. `HoldPoster.captureInTransaction` therefore only prepares the request and performs the
final DB update; `HoldService.capture` orchestrates the HTTP call in between two separate
transactional steps.

- [ ] **Step 1: Write the REST client for Transaction Processor**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\LedgerTransactionClient.java
package com.ledger.holdsservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class LedgerTransactionClient {

    public record PostTransactionResult(UUID transactionId, String status) {
    }

    private final RestClient restClient;

    public LedgerTransactionClient(@Value("${processor.base-url}") String processorBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(processorBaseUrl).build();
    }

    public PostTransactionResult postTransaction(String debitAccountRef, String creditAccountRef,
                                                  long amountMinor, String currency,
                                                  String description, String idempotencyKey) {
        record Request(String debitAccountRef, String creditAccountRef, long amountMinor,
                        String currency, String description) {
        }
        record Response(UUID transactionId, String status) {
        }

        Response response = restClient.post()
                .uri("/transactions")
                .header("Idempotency-Key", idempotencyKey)
                .body(new Request(debitAccountRef, creditAccountRef, amountMinor, currency, description))
                .retrieve()
                .body(Response.class);

        return new PostTransactionResult(response.transactionId(), response.status());
    }
}
```

Note: the spec's platform architecture routes Holds Service's calls to Transaction
Processor directly by URL in V2 (no gateway hop for service-to-service calls — the gateway
only fronts client-facing traffic). `processor.base-url` points at Transaction Processor's
own host:port (e.g. `http://transaction-processor:8081` in Docker Compose), not through the
gateway.

- [ ] **Step 2: Write the capture-amount validation exception**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\CaptureExceedsRemainingAmountException.java
package com.ledger.holdsservice.service;

import java.util.UUID;

public class CaptureExceedsRemainingAmountException extends RuntimeException {
    public CaptureExceedsRemainingAmountException(UUID holdId, long requested, long remaining) {
        super("Capture amount " + requested + " exceeds remaining hold amount " + remaining + " for hold " + holdId);
    }
}
```

- [ ] **Step 3: Add the capture DTO**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\dto\CaptureHoldRequest.java
package com.ledger.holdsservice.api.dto;

public record CaptureHoldRequest(long amountMinor) {
}
```

- [ ] **Step 4: Add capture support to HoldPoster**

Add these methods to the existing `HoldPoster` class from Task 4 (do not remove anything
already there):

```java
// add to D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldPoster.java

/**
 * Step 1 of capture: validates the hold is ACTIVE and the requested amount doesn't exceed
 * what remains, but does NOT call Transaction Processor and does NOT mutate anything yet —
 * kept in its own short transaction, separate from the blocking HTTP call in
 * HoldService.capture, per the lesson documented above this task.
 */
@Transactional(readOnly = true)
public Hold validateCaptureRequest(UUID holdId, long amountMinor) {
    Hold hold = holdRepository.findById(holdId)
            .orElseThrow(() -> new HoldNotFoundException(holdId));

    if (hold.getStatus() != HoldStatus.ACTIVE) {
        throw new IllegalStateException("Hold " + holdId + " is not ACTIVE (status: " + hold.getStatus() + ")");
    }
    if (amountMinor > hold.remainingAmountMinor()) {
        throw new CaptureExceedsRemainingAmountException(holdId, amountMinor, hold.remainingAmountMinor());
    }
    return hold;
}

/**
 * Step 2 of capture: called AFTER Transaction Processor has already durably posted the
 * transaction (HoldService.capture calls this only once postTransaction succeeds). Marks the
 * hold CAPTURED, releases any uncaptured remainder from the balance cache's held_balance
 * (the funds are either now posted via the real ledger transaction, or freed back up — either
 * way they must leave held_balance), and records the outbox event, all in one fresh
 * transaction.
 */
@Transactional
public HoldResponse completeCaptureInTransaction(UUID holdId, long capturedAmountMinor, UUID transactionId) {
    Hold hold = holdRepository.findById(holdId)
            .orElseThrow(() -> new HoldNotFoundException(holdId));

    long uncapturedRemainder = hold.remainingAmountMinor() - capturedAmountMinor;
    hold.markCaptured(capturedAmountMinor, transactionId);
    holdRepository.save(hold);

    AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(hold.getAccountRef())
            .orElseThrow(() -> new IllegalStateException("Missing balance cache for " + hold.getAccountRef()));
    cache.releaseHeld(capturedAmountMinor + uncapturedRemainder);
    accountBalanceCacheRepository.save(cache);

    outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.captured", writePayload(hold)));

    return toResponse(hold, false);
}
```

- [ ] **Step 5: Add capture orchestration to HoldService**

```java
// add to D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldService.java
// (add these fields/constructor params and this method to the existing class)

private final HoldPoster holdPoster; // already present from Task 4
private final LedgerTransactionClient ledgerTransactionClient;

// modify the existing constructor to also accept LedgerTransactionClient:
public HoldService(HoldPoster holdPoster, HoldRepository holdRepository,
                    LedgerTransactionClient ledgerTransactionClient) {
    this.holdPoster = holdPoster;
    this.holdRepository = holdRepository;
    this.ledgerTransactionClient = ledgerTransactionClient;
}

public HoldResponse capture(UUID holdId, long amountMinor) {
    var hold = holdPoster.validateCaptureRequest(holdId, amountMinor);

    var result = ledgerTransactionClient.postTransaction(
            hold.getAccountRef(), hold.getDestinationAccountRef(), amountMinor,
            hold.getCurrency(), "hold capture " + holdId,
            "hold-capture-" + holdId);

    return holdPoster.completeCaptureInTransaction(holdId, amountMinor, result.transactionId());
}
```

- [ ] **Step 6: Add the capture endpoint to the controller**

```java
// add to D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\HoldController.java
// (add this method and import to the existing class)

import com.ledger.holdsservice.api.dto.CaptureHoldRequest;

@PostMapping("/holds/{id}/capture")
public ResponseEntity<HoldResponse> capture(
        @PathVariable("id") UUID id,
        @RequestBody CaptureHoldRequest request) {
    return ResponseEntity.ok(holdService.capture(id, request.amountMinor()));
}
```

- [ ] **Step 7: Map the new exception in the error handler**

```java
// add to D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\api\error\ApiExceptionHandler.java
// (add this import and method to the existing class)

import com.ledger.holdsservice.service.CaptureExceedsRemainingAmountException;

@ExceptionHandler(CaptureExceedsRemainingAmountException.class)
public ResponseEntity<Map<String, String>> handleCaptureExceedsRemaining(CaptureExceedsRemainingAmountException e) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
}
```

- [ ] **Step 8: Add processor.base-url to application.yml**

```yaml
# add to D:\Ledger\holds-service\src\main\resources\application.yml (top level)
processor:
  base-url: ${PROCESSOR_BASE_URL:http://localhost:8081}
```

- [ ] **Step 9: Write the failing capture integration test**

This test needs Transaction Processor's real `POST /transactions` endpoint available. Rather
than standing up the whole Transaction Processor Spring context, stub it with a lightweight
embedded HTTP server, matching V1's `ReconciliationServiceIntegrationTest` pattern (see V1's
`ledger-service/src/test/java/com/ledger/ledgerservice/reconciliation/ReconciliationServiceIntegrationTest.java`
for the exact `com.sun.net.httpserver.HttpServer` stub-server style to follow).

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\service\HoldCaptureIntegrationTest.java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class HoldCaptureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    static HttpServer stubProcessor;
    static final AtomicInteger callCount = new AtomicInteger(0);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubProcessor = HttpServer.create(new InetSocketAddress(0), 0);
        stubProcessor.createContext("/transactions", exchange -> {
            callCount.incrementAndGet();
            String responseBody = "{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"POSTED\"}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubProcessor.start();
        registry.add("processor.base-url", () -> "http://localhost:" + stubProcessor.getAddress().getPort());
    }

    @Autowired
    private HoldService holdService;
    @Autowired
    private HoldRepository holdRepository;
    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void seedBalance() {
        holdRepository.deleteAll();
        accountBalanceCacheRepository.deleteAll();
        outboxRepository.deleteAll();
        accountBalanceCacheRepository.save(new AccountBalanceCache("acct-capture-a", 10_000L, 0L));
        callCount.set(0);
    }

    @Test
    void fullCaptureMarksHoldCapturedAndFreesHeldBalance() {
        var request = new CreateHoldRequest("acct-capture-a", "acct-merchant", 5_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "capture-key-1");

        HoldResponse captured = holdService.capture(created.holdId(), 5_000L);

        assertThat(captured.status()).isEqualTo("CAPTURED");
        assertThat(captured.capturedAmountMinor()).isEqualTo(5_000L);
        assertThat(callCount.get()).isEqualTo(1);

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-capture-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L); // held funds fully released from cache
    }

    @Test
    void partialCaptureReleasesUncapturedRemainderFromHeldBalance() {
        var request = new CreateHoldRequest("acct-capture-a", "acct-merchant", 5_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "capture-key-2");

        HoldResponse captured = holdService.capture(created.holdId(), 3_000L);

        assertThat(captured.capturedAmountMinor()).isEqualTo(3_000L);
        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-capture-a").orElseThrow();
        // 5000 was held; 3000 captured + 2000 remainder both leave held_balance
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L);
    }

    @Test
    void captureExceedingRemainingAmountThrowsWithoutCallingProcessor() {
        var request = new CreateHoldRequest("acct-capture-a", "acct-merchant", 2_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "capture-key-3");

        assertThatThrownBy(() -> holdService.capture(created.holdId(), 5_000L))
                .isInstanceOf(CaptureExceedsRemainingAmountException.class);

        assertThat(callCount.get()).isZero(); // validated before ever calling Transaction Processor
    }
}
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test -Dtest=HoldCaptureIntegrationTest`
Expected: PASS — all 3 tests.

- [ ] **Step 11: Run the full module test suite to confirm no regressions**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test`
Expected: PASS — all tests from Tasks 2-5 combined.

- [ ] **Step 12: Commit**

```bash
git add holds-service/src/main/java/com/ledger/holdsservice/service \
        holds-service/src/main/java/com/ledger/holdsservice/api \
        holds-service/src/main/resources/application.yml \
        holds-service/src/test/java/com/ledger/holdsservice/service/HoldCaptureIntegrationTest.java
git commit -m "feat(holds-service): add hold capture via Transaction Processor's POST /transactions

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Hold expiry sweep

**Files:**
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldExpirySweep.java`
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldPoster.java` (add expiry method)
- Modify: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\HoldsServiceApplication.java` (add `@EnableScheduling`)
- Modify: `D:\Ledger\holds-service\src\main\resources\application.yml` (add sweep interval)
- Test: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\service\HoldExpirySweepIntegrationTest.java`

**Interfaces:**
- Consumes: `HoldRepository.findByStatusAndExpiresAtBefore` from Task 3,
  `AccountBalanceCacheRepository.lockByAccountRef` from Task 3.
- Produces: nothing consumed by later tasks — this is a self-contained scheduled job, same
  shape as V1's `ReconciliationJob`.

Per the spec: the sweep strictly expires (`ACTIVE` past `expires_at` → `EXPIRED`,
decrementing `held_balance_minor`) — it never attempts to detect or correct balance drift
from other causes. Do not add any reconciliation/consistency-check logic here.

- [ ] **Step 1: Add expiry logic to HoldPoster**

```java
// add to D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldPoster.java

/**
 * Expires a single ACTIVE hold that is past its expires_at. Locks the account balance cache
 * row, decrements held_balance by the hold's remaining (uncaptured) amount, marks the hold
 * EXPIRED, and emits hold.released — all in one transaction per hold, so a crash mid-sweep
 * leaves already-processed holds correctly expired and simply picks up the rest on the next
 * scheduled run (each hold's expiry is independently idempotent: an already-EXPIRED hold is
 * never re-selected by the WHERE clause driving the sweep).
 */
@Transactional
public void expireHoldInTransaction(UUID holdId) {
    Hold hold = holdRepository.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    if (hold.getStatus() != HoldStatus.ACTIVE) {
        return; // already handled by a prior sweep run or a concurrent capture/release
    }

    AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(hold.getAccountRef())
            .orElseThrow(() -> new IllegalStateException("Missing balance cache for " + hold.getAccountRef()));
    cache.releaseHeld(hold.remainingAmountMinor());
    accountBalanceCacheRepository.save(cache);

    hold.markExpired();
    holdRepository.save(hold);

    outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.released", writePayload(hold)));
}
```

- [ ] **Step 2: Write the scheduled sweep component**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\service\HoldExpirySweep.java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.domain.HoldStatus;
import com.ledger.holdsservice.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class HoldExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweep.class);

    private final HoldRepository holdRepository;
    private final HoldPoster holdPoster;

    public HoldExpirySweep(HoldRepository holdRepository, HoldPoster holdPoster) {
        this.holdRepository = holdRepository;
        this.holdPoster = holdPoster;
    }

    @Scheduled(fixedDelayString = "${holds.expiry-sweep.interval-ms:60000}")
    public void run() {
        var expiredHolds = holdRepository.findByStatusAndExpiresAtBefore(HoldStatus.ACTIVE, Instant.now());
        for (var hold : expiredHolds) {
            try {
                holdPoster.expireHoldInTransaction(hold.getId());
            } catch (Exception e) {
                log.error("Failed to expire hold {}: {}", hold.getId(), e.getMessage(), e);
                // continue sweeping the rest; a failed hold is picked up again next run
            }
        }
    }
}
```

- [ ] **Step 3: Enable scheduling and configure the interval**

```java
// modify D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\HoldsServiceApplication.java
package com.ledger.holdsservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HoldsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(HoldsServiceApplication.class, args);
    }
}
```

```yaml
# add to D:\Ledger\holds-service\src\main\resources\application.yml (top level)
holds:
  expiry-sweep:
    interval-ms: ${HOLDS_EXPIRY_SWEEP_INTERVAL_MS:60000}
```

- [ ] **Step 4: Write the failing integration test**

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\service\HoldExpirySweepIntegrationTest.java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.domain.HoldStatus;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class HoldExpirySweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("holds.expiry-sweep.interval-ms", () -> "3600000"); // disable the real timer during the test
    }

    @Autowired
    private HoldService holdService;
    @Autowired
    private HoldExpirySweep holdExpirySweep;
    @Autowired
    private HoldRepository holdRepository;
    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedBalance() {
        holdRepository.deleteAll();
        accountBalanceCacheRepository.deleteAll();
        outboxRepository.deleteAll();
        accountBalanceCacheRepository.save(new AccountBalanceCache("acct-expiry-a", 10_000L, 0L));
    }

    @Test
    void sweepExpiresHoldsPastTheirExpiryAndReleasesHeldBalance() {
        var request = new CreateHoldRequest("acct-expiry-a", "acct-merchant", 3_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "expiry-key-1");

        // Backdate expires_at directly via SQL, since the hold API only accepts a future-relative TTL.
        jdbcTemplate.update("UPDATE holds SET expires_at = ? WHERE id = ?",
                Instant.now().minusSeconds(10), created.holdId());

        holdExpirySweep.run();

        var expired = holdRepository.findById(created.holdId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-expiry-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L);
    }

    @Test
    void sweepDoesNotTouchHoldsNotYetExpired() {
        var request = new CreateHoldRequest("acct-expiry-a", "acct-merchant", 2_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "expiry-key-2");

        holdExpirySweep.run();

        var stillActive = holdRepository.findById(created.holdId()).orElseThrow();
        assertThat(stillActive.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-expiry-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(8_000L); // still held
    }

    @Test
    void sweepDoesNotTouchAlreadyCapturedHolds() {
        // A captured hold with a past expires_at should never be re-processed by the sweep,
        // since findByStatusAndExpiresAtBefore filters on status = ACTIVE only.
        var request = new CreateHoldRequest("acct-expiry-a", "acct-merchant", 1_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "expiry-key-3");
        jdbcTemplate.update("UPDATE holds SET status = 'CAPTURED', expires_at = ? WHERE id = ?",
                Instant.now().minusSeconds(10), created.holdId());

        holdExpirySweep.run();

        var stillCaptured = holdRepository.findById(created.holdId()).orElseThrow();
        assertThat(stillCaptured.getStatus()).isEqualTo(HoldStatus.CAPTURED);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test -Dtest=HoldExpirySweepIntegrationTest`
Expected: PASS — all 3 tests.

- [ ] **Step 6: Commit**

```bash
git add holds-service/src/main/java/com/ledger/holdsservice/service \
        holds-service/src/main/java/com/ledger/holdsservice/HoldsServiceApplication.java \
        holds-service/src/main/resources/application.yml \
        holds-service/src/test/java/com/ledger/holdsservice/service/HoldExpirySweepIntegrationTest.java
git commit -m "feat(holds-service): add scheduled hold-expiry sweep

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: RabbitMQ — polling outbox publisher + ledger.transaction.posted consumer

**Files:**
- Modify: `D:\Ledger\holds-service\pom.xml` (add `spring-boot-starter-amqp`, `testcontainers-rabbitmq`, `awaitility`)
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\MessagingConstants.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\RabbitConfig.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\OutboxPollingPublisher.java`
- Create: `D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\LedgerTransactionPostedConsumer.java`
- Modify: `D:\Ledger\holds-service\src\main\resources\application.yml` (RabbitMQ connection + poll interval)
- Test: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\messaging\OutboxPollingPublisherIntegrationTest.java`
- Test: `D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\messaging\LedgerTransactionPostedConsumerIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc` from Task 3,
  `AccountBalanceCacheRepository` from Task 3. Consumes the exchange/routing-key contract
  V1's Transaction Processor already publishes on:
  `MessagingConstants.LEDGER_EXCHANGE = "ledger.events"`,
  routing key `"ledger.transaction.posted"` (see V1's
  `transaction-processor/src/main/java/com/ledger/txprocessor/messaging/MessagingConstants.java`
  for the exact existing values — do not invent new ones, bind to V1's real exchange).
- Produces: nothing consumed by later tasks — this closes out Holds Service's own
  messaging surface.

Per the spec's Global Constraint: this is a lightweight polling publisher against
`outbox_events`, not embedded Debezium. Per the spec: nothing in V2 consumes `hold.*` events
yet — publish only, no new consumer for them.

- [ ] **Step 1: Add AMQP and RabbitMQ Testcontainers dependencies**

```xml
<!-- add inside D:\Ledger\holds-service\pom.xml <dependencies> -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Define messaging constants, matching V1's existing exchange/routing-key exactly**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\MessagingConstants.java
package com.ledger.holdsservice.messaging;

public final class MessagingConstants {
    // Must match transaction-processor's MessagingConstants.LEDGER_EXCHANGE /
    // TRANSACTION_POSTED_ROUTING_KEY exactly — this is V1's existing exchange, not a new one.
    public static final String LEDGER_EXCHANGE = "ledger.events";
    public static final String TRANSACTION_POSTED_ROUTING_KEY = "ledger.transaction.posted";
    public static final String HOLDS_TRANSACTION_POSTED_QUEUE = "holds.ledger.transaction.posted.queue";

    public static final String HOLD_EVENTS_ROUTING_KEY_PREFIX = "holds.";
    public static final String HEADER_OUTBOX_EVENT_ID = "outboxEventId";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private MessagingConstants() {
    }
}
```

- [ ] **Step 3: Configure the exchange/queue topology**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\RabbitConfig.java
package com.ledger.holdsservice.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    // Binds this service's own queue to V1's existing exchange to consume
    // ledger.transaction.posted — does NOT redeclare the exchange itself as a new resource
    // this service owns; it's V1's exchange, declared idempotently the same way any consumer
    // safely can (declaring an already-existing exchange with matching properties is a no-op).
    @Bean
    public TopicExchange ledgerExchange() {
        return new TopicExchange(MessagingConstants.LEDGER_EXCHANGE, true, false);
    }

    @Bean
    public Queue holdsTransactionPostedQueue() {
        return new Queue(MessagingConstants.HOLDS_TRANSACTION_POSTED_QUEUE, true);
    }

    @Bean
    public Binding holdsTransactionPostedBinding(Queue holdsTransactionPostedQueue, TopicExchange ledgerExchange) {
        return BindingBuilder.bind(holdsTransactionPostedQueue)
                .to(ledgerExchange)
                .with(MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY);
    }
}
```

- [ ] **Step 4: Write the polling outbox publisher**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\OutboxPollingPublisher.java
package com.ledger.holdsservice.messaging;

import com.ledger.holdsservice.domain.OutboxEvent;
import com.ledger.holdsservice.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Component
public class OutboxPollingPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingPublisher.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPollingPublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${holds.outbox-poll.interval-ms:2000}")
    public void pollAndPublish() {
        var unpublished = outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc();
        for (var event : unpublished) {
            try {
                publishOne(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage(), e);
                // leave published_at null; picked up again next poll
            }
        }
    }

    @Transactional
    void publishOne(OutboxEvent event) {
        Message message = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, event.getId().toString())
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, event.getAggregateId().toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, event.getEventType())
                .setContentType("application/json")
                .build();

        String routingKey = MessagingConstants.HOLD_EVENTS_ROUTING_KEY_PREFIX + event.getEventType();
        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE, routingKey, message);

        event.markPublished();
        outboxRepository.save(event);
    }
}
```

Note: unlike V1's Transaction Processor (which used RabbitMQ publisher-confirms to know
whether a broker ack succeeded before marking a row published), this simpler polling
publisher marks `published_at` immediately after a successful synchronous `send()` call
without waiting for a broker confirm. This is an accepted, deliberate simplification
consistent with the spec's "lighter" outbox-relay decision for secondary services — a
message lost between `send()` and broker receipt (e.g. a connection drop mid-send) would
leave the event's `published_at` set despite the message never truly landing. Since nothing
in V2 consumes `hold.*` events yet, this gap has no functional impact today; if a future
version adds a real consumer for these events, revisit whether publisher-confirms are
needed then.

- [ ] **Step 5: Write the ledger.transaction.posted consumer**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\LedgerTransactionPostedConsumer.java
package com.ledger.holdsservice.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class LedgerTransactionPostedConsumer {

    private final AccountBalanceCacheRepository accountBalanceCacheRepository;
    private final ProcessedEventGate processedEventGate;
    private final ObjectMapper objectMapper;

    public LedgerTransactionPostedConsumer(AccountBalanceCacheRepository accountBalanceCacheRepository,
                                            ProcessedEventGate processedEventGate,
                                            ObjectMapper objectMapper) {
        this.accountBalanceCacheRepository = accountBalanceCacheRepository;
        this.processedEventGate = processedEventGate;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = MessagingConstants.HOLDS_TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    public void handle(Message message, com.rabbitmq.client.Channel channel) throws java.io.IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String outboxEventIdHeader = (String) message.getMessageProperties()
                .getHeaders().get(MessagingConstants.HEADER_OUTBOX_EVENT_ID);

        if (outboxEventIdHeader == null) {
            // Defensive: a malformed/foreign message on this queue. Ack and drop rather than
            // poison-loop forever with no DLQ configured in V2.
            channel.basicAck(deliveryTag, false);
            return;
        }

        UUID eventId = UUID.fromString(outboxEventIdHeader);
        boolean firstDelivery = processedEventGate.markProcessedIfNew(eventId);
        if (firstDelivery) {
            applyBalanceUpdate(message.getBody());
        }
        channel.basicAck(deliveryTag, false);
    }

    @Transactional
    void applyBalanceUpdate(byte[] body) {
        try {
            JsonNode payload = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            String debitAccountRef = payload.get("debitAccountRef").asText();
            String creditAccountRef = payload.get("creditAccountRef").asText();
            long debitBalanceAfter = payload.get("debitAccountBalanceAfter").asLong();
            long creditBalanceAfter = payload.get("creditAccountBalanceAfter").asLong();

            upsertPostedBalance(debitAccountRef, debitBalanceAfter);
            upsertPostedBalance(creditAccountRef, creditBalanceAfter);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply ledger.transaction.posted payload", e);
        }
    }

    private void upsertPostedBalance(String accountRef, long postedBalanceMinor) {
        var cache = accountBalanceCacheRepository.lockByAccountRef(accountRef)
                .orElseGet(() -> new AccountBalanceCache(accountRef, 0L, 0L));
        cache.setPostedBalanceMinor(postedBalanceMinor);
        accountBalanceCacheRepository.save(cache);
    }
}
```

Note: the payload field names (`debitAccountRef`, `creditAccountRef`,
`debitAccountBalanceAfter`, `creditAccountBalanceAfter`) must match V1's actual outbox
payload shape exactly — see V1's
`ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java`'s
`buildOutboxPayload` method for the authoritative field names before writing this file;
this plan's field names are copied from that method as of V1's completion but the
implementer must verify against the real, current file rather than trust this plan blindly
if V1 has changed since.

- [ ] **Step 6: Write the dedup gate for the consumer**

```java
// D:\Ledger\holds-service\src\main\java\com\ledger\holdsservice\messaging\ProcessedEventGate.java
package com.ledger.holdsservice.messaging;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Dedup gate for RabbitMQ's at-least-once delivery, backed by processed_events' primary-key
 * uniqueness (a DB constraint, not a Java-side read-then-write) — the same discipline V1
 * established for Transaction Processor's own dedup gate.
 */
@Component
public class ProcessedEventGate {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean markProcessedIfNew(UUID eventId) {
        try {
            jdbcTemplate.update("INSERT INTO processed_events (event_id) VALUES (?)", eventId);
            return true;
        } catch (DataIntegrityViolationException duplicateKey) {
            return false;
        }
    }
}
```

- [ ] **Step 7: Configure RabbitMQ connection and poll interval**

```yaml
# add to D:\Ledger\holds-service\src\main\resources\application.yml under spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

# add at top level:
holds:
  outbox-poll:
    interval-ms: ${HOLDS_OUTBOX_POLL_INTERVAL_MS:2000}
```

- [ ] **Step 8: Write the failing outbox-publisher integration test**

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\messaging\OutboxPollingPublisherIntegrationTest.java
package com.ledger.holdsservice.messaging;

import com.ledger.holdsservice.domain.OutboxEvent;
import com.ledger.holdsservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OutboxPollingPublisherIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db").withUsername("holds").withPassword("holds");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("holds.outbox-poll.interval-ms", () -> "500");
    }

    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();
    }

    @Test
    void unpublishedOutboxRowIsPolledAndPublishedToRabbitMq() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), aggregateId, "hold.created",
                "{\"holdId\":\"" + aggregateId + "\"}");
        outboxRepository.save(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
            assertThat(updated.getPublishedAt()).isNotNull();
        });

        Message received = rabbitTemplate.receive(MessagingConstants.HOLDS_TRANSACTION_POSTED_QUEUE, 100);
        // holds.hold.created was routed with a different routing key than this queue's binding
        // (ledger.transaction.posted) — this queue must NOT receive it. Assert no cross-delivery.
        assertThat(received).isNull();
    }
}
```

- [ ] **Step 9: Write the failing consumer integration test**

```java
// D:\Ledger\holds-service\src\test\java\com\ledger\holdsservice\messaging\LedgerTransactionPostedConsumerIntegrationTest.java
package com.ledger.holdsservice.messaging;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class LedgerTransactionPostedConsumerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db").withUsername("holds").withPassword("holds");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;

    @BeforeEach
    void cleanUp() {
        accountBalanceCacheRepository.deleteAll();
    }

    @Test
    void consumingATransactionPostedEventUpdatesPostedBalanceForBothAccounts() {
        String outboxEventId = UUID.randomUUID().toString();
        String payload = "{\"debitAccountRef\":\"acct-consumer-a\",\"creditAccountRef\":\"acct-consumer-b\"," +
                "\"debitAccountBalanceAfter\":5000,\"creditAccountBalanceAfter\":15000}";

        var message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, outboxEventId)
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, UUID.randomUUID().toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, "TRANSACTION_POSTED")
                .setContentType("application/json")
                .build();

        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AccountBalanceCache debitCache = accountBalanceCacheRepository.findById("acct-consumer-a").orElseThrow();
            AccountBalanceCache creditCache = accountBalanceCacheRepository.findById("acct-consumer-b").orElseThrow();
            assertThat(debitCache.getPostedBalanceMinor()).isEqualTo(5000L);
            assertThat(creditCache.getPostedBalanceMinor()).isEqualTo(15000L);
        });
    }
}
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test -Dtest=OutboxPollingPublisherIntegrationTest,LedgerTransactionPostedConsumerIntegrationTest`
Expected: PASS — 2 tests.

- [ ] **Step 11: Run the full module suite to confirm no regressions**

Run: `mvn -f D:\Ledger\holds-service\pom.xml test`
Expected: PASS — all tests from Tasks 2-7 combined.

- [ ] **Step 12: Commit**

```bash
git add holds-service/pom.xml \
        holds-service/src/main/java/com/ledger/holdsservice/messaging \
        holds-service/src/main/resources/application.yml \
        holds-service/src/test/java/com/ledger/holdsservice/messaging
git commit -m "feat(holds-service): add polling outbox publisher and ledger.transaction.posted consumer

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: API Gateway — module scaffold and routing (no auth yet)

**Files:**
- Modify: `D:\Ledger\pom.xml` (add `api-gateway` to `<modules>`)
- Create: `D:\Ledger\api-gateway\pom.xml`
- Create: `D:\Ledger\api-gateway\src\main\java\com\ledger\apigateway\ApiGatewayApplication.java`
- Create: `D:\Ledger\api-gateway\src\main\resources\application.yml`
- Test: `D:\Ledger\api-gateway\src\test\java\com\ledger\apigateway\RoutingIntegrationTest.java`

**Interfaces:**
- Produces: a fourth reactor module, `api-gateway` (port `8080` — note this reuses V1's
  Ledger Service port; see Step 4's note on why, and Task 10's Docker Compose task for the
  full port remap across all services), routing `/transactions/**` and `/reconciliation/**`
  to Ledger Service, `/holds/**` and `/accounts/*/available-balance` to Holds Service.

Spring Cloud Gateway (reactive/WebFlux-based) is a new dependency family for this monorepo —
none of V1's services used it. This task proves routing works with real backend stub
servers before Task 9 adds OAuth2 on top, keeping the two concerns testable independently.

- [ ] **Step 1: Add `api-gateway` to the root reactor**

```xml
<!-- modify D:\Ledger\pom.xml — add inside <modules> -->
<modules>
    <module>ledger-service</module>
    <module>transaction-processor</module>
    <module>holds-service</module>
    <module>api-gateway</module>
</modules>
```

- [ ] **Step 2: Add the Spring Cloud BOM to the root pom's dependency management**

```xml
<!-- add inside D:\Ledger\pom.xml <properties> -->
<spring-cloud.version>2023.0.3</spring-cloud.version>
```

```xml
<!-- add inside D:\Ledger\pom.xml <dependencyManagement><dependencies>, alongside the existing
     spring-boot-dependencies and testcontainers-bom entries -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>${spring-cloud.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Spring Cloud 2023.0.3 is the release train compatible with Spring Boot 3.3.x (this repo's
pinned `spring-boot.version`) — verify this compatibility holds if the Spring Boot version
has changed since V1, since Spring Cloud release trains are version-locked to specific Boot
minor versions and a mismatch fails at dependency resolution, not silently.

- [ ] **Step 3: Create the module POM**

```xml
<!-- D:\Ledger\api-gateway\pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.ledger</groupId>
        <artifactId>ledger-platform</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>api-gateway</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 4: Create the application class**

```java
// D:\Ledger\api-gateway\src\main\java\com\ledger\apigateway\ApiGatewayApplication.java
package com.ledger.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

- [ ] **Step 5: Configure routes**

```yaml
# D:\Ledger\api-gateway\src\main\resources\application.yml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: ledger-transactions
          uri: ${LEDGER_SERVICE_URL:http://localhost:8090}
          predicates:
            - Path=/transactions/**
        - id: ledger-reconciliation
          uri: ${LEDGER_SERVICE_URL:http://localhost:8090}
          predicates:
            - Path=/reconciliation/**
        - id: holds
          uri: ${HOLDS_SERVICE_URL:http://localhost:8082}
          predicates:
            - Path=/holds/**
        - id: holds-available-balance
          uri: ${HOLDS_SERVICE_URL:http://localhost:8082}
          predicates:
            - Path=/accounts/*/available-balance
```

Note on ports: the gateway now owns port `8080` as the platform's single client-facing entry
point, so Ledger Service's own listen port must move off `8080` to avoid a collision — this
plan sets Ledger Service to `8090` via the `LEDGER_SERVICE_URL` default above and the actual
`server.port` change happens in Task 10's Docker Compose task (which also updates
`docker-compose.yml` and any smoke-test/chaos scripts hardcoding `localhost:8080` for Ledger
Service directly — grep for `8080` across `scripts/` and `chaos/` before finalizing Task 10,
since V1's smoke test and all 5 chaos scenarios currently call Ledger Service on `:8080`
directly). Holds Service keeps `8082` (chosen in Task 1, no collision). Transaction
Processor keeps `8081` (no client-facing gateway route to it, so no collision risk).

- [ ] **Step 6: Write the failing routing integration test**

Uses WireMock-free, lightweight `com.sun.net.httpserver.HttpServer` stubs (matching V1's
established pattern) standing in for Ledger Service and Holds Service, since this task's
job is proving the gateway's routing predicates work, not re-testing the backend services
themselves.

```java
// D:\Ledger\api-gateway\src\test\java\com\ledger\apigateway\RoutingIntegrationTest.java
package com.ledger.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingIntegrationTest {

    static HttpServer stubLedger;
    static HttpServer stubHolds;

    @LocalServerPort
    private int gatewayPort;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @BeforeAll
    static void startStubs() throws Exception {
        stubLedger = HttpServer.create(new InetSocketAddress(0), 0);
        stubLedger.createContext("/transactions", exchange -> respond(exchange, "ledger-stub"));
        stubLedger.start();

        stubHolds = HttpServer.create(new InetSocketAddress(0), 0);
        stubHolds.createContext("/holds", exchange -> respond(exchange, "holds-stub"));
        stubHolds.start();
    }

    @AfterAll
    static void stopStubs() {
        stubLedger.stop(0);
        stubHolds.stop(0);
    }

    @DynamicPropertySource
    static void registerStubUrls(DynamicPropertyRegistry registry) {
        registry.add("LEDGER_SERVICE_URL", () -> "http://localhost:" + stubLedger.getAddress().getPort());
        registry.add("HOLDS_SERVICE_URL", () -> "http://localhost:" + stubHolds.getAddress().getPort());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Test
    void routesTransactionsPathToLedgerService() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/transactions", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ledger-stub");
    }

    @Test
    void routesHoldsPathToHoldsService() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/holds", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("holds-stub");
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\api-gateway\pom.xml test`
Expected: PASS — both routing tests.

- [ ] **Step 8: Build the whole reactor**

Run: `mvn -f D:\Ledger\pom.xml clean verify`
Expected: BUILD SUCCESS across all four modules.

- [ ] **Step 9: Commit**

```bash
git add pom.xml api-gateway
git commit -m "feat(api-gateway): scaffold Spring Cloud Gateway module with routing to Ledger and Holds

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: Keycloak realm + OAuth2 Resource Server on the gateway

**Files:**
- Modify: `D:\Ledger\api-gateway\pom.xml` (add `spring-boot-starter-oauth2-resource-server`)
- Create: `D:\Ledger\keycloak-realm\ledger-realm.json`
- Modify: `D:\Ledger\api-gateway\src\main\resources\application.yml` (add resource-server config)
- Modify: `D:\Ledger\api-gateway\src\test\java\com\ledger\apigateway\RoutingIntegrationTest.java` (add auth header)
- Test: `D:\Ledger\api-gateway\src\test\java\com\ledger\apigateway\OAuth2ResourceServerIntegrationTest.java`

**Interfaces:**
- Produces: the gateway rejects any request without a valid JWT (401) and forwards
  authenticated requests to the routes from Task 8 unchanged.

This task needs a real Keycloak instance to issue real tokens against, since JWT validation
against a mocked/fake issuer would not prove the actual OAuth2 flow works. Use Testcontainers'
generic container support to run Keycloak in dev mode with the realm import for this task's
tests — Task 10's Docker Compose task wires the same realm import into the actual running
stack.

- [ ] **Step 1: Add the OAuth2 Resource Server dependency**

```xml
<!-- add inside D:\Ledger\api-gateway\pom.xml <dependencies> -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the Keycloak realm import**

```json
// D:\Ledger\keycloak-realm\ledger-realm.json
{
  "realm": "ledger",
  "enabled": true,
  "accessTokenLifespan": 300,
  "clients": [
    {
      "clientId": "chaos-suite-client",
      "enabled": true,
      "clientAuthenticatorType": "client-secret",
      "secret": "chaos-suite-secret",
      "publicClient": false,
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false
    },
    {
      "clientId": "smoke-test-client",
      "enabled": true,
      "clientAuthenticatorType": "client-secret",
      "secret": "smoke-test-secret",
      "publicClient": false,
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false
    },
    {
      "clientId": "demo-users-client",
      "enabled": true,
      "clientAuthenticatorType": "client-secret",
      "secret": "demo-users-secret",
      "publicClient": false,
      "serviceAccountsEnabled": false,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": true
    }
  ],
  "users": [
    {
      "username": "alice",
      "enabled": true,
      "email": "alice@example.com",
      "credentials": [
        {"type": "password", "value": "alice-password", "temporary": false}
      ],
      "realmRoles": ["user"]
    },
    {
      "username": "bob",
      "enabled": true,
      "email": "bob@example.com",
      "credentials": [
        {"type": "password", "value": "bob-password", "temporary": false}
      ],
      "realmRoles": ["user"]
    }
  ],
  "roles": {
    "realm": [
      {"name": "user"}
    ]
  }
}
```

Note: `chaos-suite-client`/`smoke-test-client` use the client-credentials grant
(`serviceAccountsEnabled: true`, `directAccessGrantsEnabled: false` — no password grant for
machine clients). `demo-users-client` is the public-facing client the two seeded demo users
authenticate through via the password grant (`directAccessGrantsEnabled: true`,
`serviceAccountsEnabled: false` — this client has no service account of its own, only
brokers human logins). Secrets here are dev/demo defaults, matching V1's existing pattern of
committing non-production credentials for a local Docker Compose stack (see V1's
`docker-compose.yml`'s Postgres/RabbitMQ credentials for precedent) — do not treat these as
requiring different handling than V1's existing secret-handling approach.

- [ ] **Step 3: Configure the resource server to validate against Keycloak's JWKS endpoint**

```yaml
# add to D:\Ledger\api-gateway\src\main\resources\application.yml under spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/ledger}
```

Also add a security filter that requires authentication for every route (Spring Cloud
Gateway's reactive security model):

```java
// D:\Ledger\api-gateway\src\main\java\com\ledger\apigateway\SecurityConfig.java
package com.ledger.apigateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }
}
```

- [ ] **Step 4: Write the failing OAuth2 Resource Server integration test**

```java
// D:\Ledger\api-gateway\src\test\java\com\ledger\apigateway\OAuth2ResourceServerIntegrationTest.java
package com.ledger.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OAuth2ResourceServerIntegrationTest {

    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:25.0")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../keycloak-realm/ledger-realm.json"),
                    "/opt/keycloak/data/import/ledger-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/ledger").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static HttpServer stubLedger;

    @BeforeAll
    static void startAll() throws Exception {
        keycloak.start();
        stubLedger = HttpServer.create(new InetSocketAddress(0), 0);
        stubLedger.createContext("/transactions", exchange -> {
            byte[] response = "ledger-stub".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubLedger.start();
    }

    @AfterAll
    static void stopAll() {
        keycloak.stop();
        stubLedger.stop(0);
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        String issuerUri = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/ledger";
        registry.add("KEYCLOAK_ISSUER_URI", () -> issuerUri);
        registry.add("LEDGER_SERVICE_URL", () -> "http://localhost:" + stubLedger.getAddress().getPort());
    }

    @LocalServerPort
    private int gatewayPort;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String issuerBaseUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/ledger";
    }

    private String obtainClientCredentialsToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMapAdapter<String, String> form = new MultiValueMapAdapter<>(Map.of(
                "grant_type", java.util.List.of("client_credentials"),
                "client_id", java.util.List.of("chaos-suite-client"),
                "client_secret", java.util.List.of("chaos-suite-secret")
        ));
        ResponseEntity<Map> response = restTemplate.postForEntity(
                issuerBaseUrl() + "/protocol/openid-connect/token",
                new HttpEntity<>(form, headers), Map.class);
        return (String) response.getBody().get("access_token");
    }

    @Test
    void requestWithoutTokenIsRejected() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + gatewayPort + "/transactions", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithValidClientCredentialsTokenIsRoutedThrough() {
        String token = obtainClientCredentialsToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + gatewayPort + "/transactions", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ledger-stub");
    }
}
```

Add the `org.springframework.util.MultiValueMapAdapter` import if your IDE doesn't resolve
it automatically — it's part of `spring-core`, already on the classpath transitively.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\api-gateway\pom.xml test -Dtest=OAuth2ResourceServerIntegrationTest`
Expected: PASS — both tests. Budget real time here: this test pulls and starts a real
Keycloak container, which is slower than the Postgres/RabbitMQ containers used elsewhere in
this plan (often 30-60s to become ready even with the health-check wait strategy).

- [ ] **Step 6: Run the full module suite to confirm Task 8's routing tests still pass**

Run: `mvn -f D:\Ledger\api-gateway\pom.xml test`
Expected: PASS — `RoutingIntegrationTest` (Task 8, unauthenticated stub-based routing) and
`OAuth2ResourceServerIntegrationTest` (this task) both green. If Task 8's
`RoutingIntegrationTest` now fails because requests lack a bearer token (since Task 9's
security filter applies to ALL exchanges), that test needs a token obtained the same way
`OAuth2ResourceServerIntegrationTest` does — update `RoutingIntegrationTest` to start its own
Keycloak Testcontainer and attach a valid token to its requests, following this task's
pattern, rather than leaving it broken.

- [ ] **Step 7: Commit**

```bash
git add api-gateway/pom.xml \
        api-gateway/src/main/java/com/ledger/apigateway/SecurityConfig.java \
        api-gateway/src/main/resources/application.yml \
        api-gateway/src/test/java/com/ledger/apigateway \
        keycloak-realm
git commit -m "feat(api-gateway): add Keycloak-backed OAuth2 Resource Server auth

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Docker Compose — bring up the full V2 stack

**Files:**
- Create: `D:\Ledger\holds-service\Dockerfile`
- Create: `D:\Ledger\api-gateway\Dockerfile`
- Modify: `D:\Ledger\docker-compose.yml` (add `holds-db`, `holds-service`, `keycloak`,
  `api-gateway`; change `ledger-service`'s port from `8080`→`8090`)
- Create: `D:\Ledger\holds-postgres-init\` — none needed; `holds-service`'s own Flyway
  migration creates its schema, matching how `transaction-processor`/`ledger-service` work
  (no separate init-SQL directory required for Holds Service, unlike `ledger-postgres-init`
  which exists specifically for the Debezium replication role V1 needed and Holds Service
  does not)
- Modify: `D:\Ledger\scripts\smoke-test.sh` (route through the gateway; add a hold-flow check)
- Modify: `D:\Ledger\scripts\provision.sh` (no functional change expected, but verify — see
  Step 5)
- Modify: `D:\Ledger\chaos\lib\common.sh` (update `post_transaction`/`LEDGER_URL` default to
  account for the port move — see Step 6)
- Create: `D:\Ledger\scripts\get-token.sh`

**Interfaces:**
- Consumes: all prior tasks' Docker images/config.
- Produces: a `docker compose up` + `provision.sh` command that brings up the entire V2
  stack — Ledger Service, Transaction Processor, Holds Service, API Gateway, Keycloak,
  Postgres ×3, RabbitMQ, Toxiproxy — from a clean state, with V1's existing chaos suite
  still passing against the (now gateway-fronted, port-remapped) stack.

- [ ] **Step 1: Write the Holds Service Dockerfile**

```dockerfile
# D:\Ledger\holds-service\Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
COPY holds-service/pom.xml holds-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -q -pl holds-service -am dependency:go-offline
COPY holds-service holds-service
RUN mvn -q -pl holds-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/holds-service/target/holds-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Write the API Gateway Dockerfile (same pattern)**

```dockerfile
# D:\Ledger\api-gateway\Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
COPY holds-service/pom.xml holds-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN mvn -q -pl api-gateway -am dependency:go-offline
COPY api-gateway api-gateway
RUN mvn -q -pl api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: Update docker-compose.yml**

Read the current `D:\Ledger\docker-compose.yml` in full before editing — this step modifies
`ledger-service`'s existing block and adds four new services. Apply these changes:

```yaml
# modify the existing ledger-service block's environment and ports:
  ledger-service:
    build:
      context: .
      dockerfile: ledger-service/Dockerfile
    environment:
      DB_HOST: toxiproxy
      DB_PORT: "15432"
      DB_NAME: ledger_db
      DB_USER: ledger
      DB_PASSWORD: ledger
      PROCESSOR_BASE_URL: http://transaction-processor:8081
      SERVER_PORT: "8090"           # NEW — moved off 8080 for the gateway
    ports:
      - "8090:8090"                 # CHANGED from "8080:8080"
    depends_on:
      ledger-postgres:
        condition: service_healthy
      toxiproxy:
        condition: service_started
```

Add `server.port: ${SERVER_PORT:8080}` to `ledger-service/src/main/resources/application.yml`
if it is not already externalized this way (check the existing file — V1's `application.yml`
may have `server: port: 8080` hardcoded rather than env-driven; if so, change it to
`server: port: ${SERVER_PORT:8080}` so the `SERVER_PORT: "8090"` compose override above
actually takes effect).

```yaml
# add these four new services to docker-compose.yml
  holds-db:
    image: postgres:16.4
    environment:
      POSTGRES_DB: holds_db
      POSTGRES_USER: holds
      POSTGRES_PASSWORD: holds
    volumes:
      - holds-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U holds -d holds_db"]
      interval: 5s
      timeout: 5s
      retries: 10

  holds-service:
    build:
      context: .
      dockerfile: holds-service/Dockerfile
    environment:
      DB_HOST: holds-db
      DB_PORT: "5432"
      DB_NAME: holds_db
      DB_USER: holds
      DB_PASSWORD: holds
      PROCESSOR_BASE_URL: http://transaction-processor:8081
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: "5672"
    ports:
      - "8082:8082"
    depends_on:
      holds-db:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    volumes:
      - ./keycloak-realm:/opt/keycloak/data/import
    ports:
      - "8180:8080"
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/realms/ledger || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 15

  api-gateway:
    build:
      context: .
      dockerfile: api-gateway/Dockerfile
    environment:
      LEDGER_SERVICE_URL: http://ledger-service:8090
      HOLDS_SERVICE_URL: http://holds-service:8082
      KEYCLOAK_ISSUER_URI: http://keycloak:8080/realms/ledger
    ports:
      - "8080:8080"
    depends_on:
      ledger-service:
        condition: service_started
      holds-service:
        condition: service_started
      keycloak:
        condition: service_healthy
```

```yaml
# add to the existing volumes: block
  holds-pgdata:
```

Note: `keycloak`'s internal container port stays `8080` (its own default); the host mapping
`8180:8080` is what avoids colliding with `api-gateway`'s own `8080:8080` host mapping —
`KEYCLOAK_ISSUER_URI` for services running *inside* the Docker network (like `api-gateway`)
correctly uses `http://keycloak:8080/...` (the container's internal port), while anything
running *outside* Docker (a human calling `curl` from the host, or this plan's non-Dockerized
Testcontainers tests) uses `http://localhost:8180/...`. Keep this distinction straight when
writing `scripts/get-token.sh` in Step 7 — that script runs on the host, so it must target
`localhost:8180`.

- [ ] **Step 4: Bring up the stack and verify all containers become healthy**

Run:
```bash
docker compose down -v
docker compose up -d --build
```
Expected: all containers reach a running/healthy state within a few minutes (Keycloak's
first boot is the slowest — budget 1-2 minutes for `start-dev --import-realm`). Check with
`docker compose ps`. If `ledger-service` exits on first boot before Toxiproxy's proxies
exist, that's V1's already-documented behavior (see `scripts/provision.sh`'s existing
comments) — not a regression.

- [ ] **Step 5: Verify scripts/provision.sh still works and check for any 8080-hardcoded URLs**

Run: `grep -rn "localhost:8080\|:8080/" scripts/ chaos/` and read every match. Ledger
Service's endpoints (`/transactions`, `/reconciliation/runs`) that `provision.sh` or
`smoke-test.sh` called directly on `localhost:8080` in V1 must now either go through the
gateway on `localhost:8080` WITH a bearer token (Task 9's auth now applies), or bypass the
gateway entirely on `localhost:8090` (Ledger Service's new direct port) if a given script
step doesn't need auth (e.g. `provision.sh`'s Toxiproxy proxy creation and CDC grant/
publication setup talk to Toxiproxy/Postgres directly, never to Ledger Service's HTTP API,
so those calls are unaffected by this port move — verify this is actually true by reading
`provision.sh` in full, don't assume).

- [ ] **Step 6: Update chaos/lib/common.sh and scripts/smoke-test.sh for the gateway + auth**

The `post_transaction`/`trigger_reconciliation` helpers in `chaos/lib/common.sh` currently
call `LEDGER_URL` (defaulting to `http://localhost:8080`) directly, unauthenticated. Update
`chaos/lib/common.sh`:

```bash
# modify chaos/lib/common.sh — add near the top, alongside the existing LEDGER_URL/PROCESSOR_URL defaults
KEYCLOAK_TOKEN_URL="${KEYCLOAK_TOKEN_URL:-http://localhost:8180/realms/ledger/protocol/openid-connect/token}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"

get_chaos_suite_token() {
  curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
    -d "grant_type=client_credentials" \
    -d "client_id=chaos-suite-client" \
    -d "client_secret=chaos-suite-secret" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
}
```

Then modify `post_transaction` and `trigger_reconciliation` (existing functions in the same
file) to route through `$GATEWAY_URL` instead of `$LEDGER_URL`, and add
`-H "Authorization: Bearer $(get_chaos_suite_token)"` to their `curl` calls. Read the
existing function bodies in full before editing — do not rewrite them from scratch, only add
the gateway routing and auth header, preserving every existing assertion/parsing behavior
(the 5 chaos scenario scripts from V1 depend on these helpers' exact output format/behavior
being unchanged).

Apply the equivalent change to `scripts/smoke-test.sh`'s transaction-posting and
reconciliation-triggering calls.

- [ ] **Step 7: Write a token-fetching helper script for manual/demo use**

```bash
#!/usr/bin/env bash
# D:\Ledger\scripts\get-token.sh
# Usage: ./scripts/get-token.sh [client|alice|bob]
# Prints an access token to stdout for manual curl testing against the gateway.
set -euo pipefail

MODE="${1:-client}"
KEYCLOAK_TOKEN_URL="http://localhost:8180/realms/ledger/protocol/openid-connect/token"

case "$MODE" in
  client)
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
      -d "grant_type=client_credentials" \
      -d "client_id=chaos-suite-client" \
      -d "client_secret=chaos-suite-secret" \
      | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
    ;;
  alice|bob)
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
      -d "grant_type=password" \
      -d "client_id=demo-users-client" \
      -d "client_secret=demo-users-secret" \
      -d "username=$MODE" \
      -d "password=${MODE}-password" \
      | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
    ;;
  *)
    echo "Unknown mode: $MODE (expected client|alice|bob)" >&2
    exit 1
    ;;
esac
```

- [ ] **Step 8: Run the full acceptance sequence**

Run:
```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
bash scripts/smoke-test.sh
```
Expected: smoke test completes with a clean reconciliation result, now routed through the
gateway with a real Keycloak-issued token.

Then run V1's existing chaos suite unmodified in invocation (only its helpers changed
internally in Step 6):
```bash
bash chaos/scenarios/01_rabbitmq_down_mid_publish.sh
bash chaos/scenarios/02_ledger_db_crash_post_commit.sh
bash chaos/scenarios/03_processor_crash_mid_consume.sh
bash chaos/scenarios/04_duplicate_delivery.sh
bash chaos/scenarios/05_partition_during_lock.sh
```
Expected: all 5 scenarios still `PASS:`, confirming V2's gateway/auth additions did not
regress V1's fault-tolerance guarantees.

Then manually verify the Holds flow end-to-end through the gateway:
```bash
TOKEN=$(bash scripts/get-token.sh client)
curl -X POST http://localhost:8080/holds \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: manual-test-1" \
  -d '{"accountRef":"smoke-a","destinationAccountRef":"smoke-b","amountMinor":500,"currency":"USD","expiresInSeconds":3600}'
```
Expected: HTTP 201 with a `holdId` and `status: ACTIVE`.

Tear down: `docker compose down -v`.

- [ ] **Step 9: Commit**

```bash
git add holds-service/Dockerfile api-gateway/Dockerfile docker-compose.yml \
        ledger-service/src/main/resources/application.yml \
        scripts/smoke-test.sh scripts/get-token.sh chaos/lib/common.sh
git commit -m "feat: wire Holds Service, API Gateway, and Keycloak into the Docker Compose stack

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: README update and final V2 acceptance verification

**Files:**
- Modify: `D:\Ledger\README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-10.
- Produces: an updated top-level README reflecting the full V2 system — the last task of
  this plan.

- [ ] **Step 1: Update the README**

Read the current `D:\Ledger\README.md` in full first (it currently documents V1 only). Update
it to reflect V2:

- **Architecture** section: add Holds Service (`:8082`) and API Gateway (`:8080`, now the
  platform's single client-facing entry point) to the service list. Note Ledger Service moved
  to `:8090` (no longer directly client-facing — all client traffic goes through the gateway).
  Add Keycloak (`:8180`, `ledger` realm) as the auth provider.
- **What this demonstrates**: add "OAuth2/JWT authentication via Keycloak (client-credentials
  for machine clients, password grant for demo users)" and "Hold authorize/capture/release
  with atomic available-balance locking" to the existing V1 bullet list.
- **Running locally**: update the `make up`/`make smoke-test` flow if the sequence changed
  (per Task 10 — `provision.sh` handles CDC + Toxiproxy setup, unchanged from V1's flow;
  confirm this is still accurate after Task 10's changes rather than assuming).
  Add a note on obtaining a token: `bash scripts/get-token.sh client` (or `alice`/`bob`), and
  an example authenticated `curl` call to `POST /holds` through the gateway (reuse the exact
  example already verified working in Task 10 Step 8).
- **API** section: add `POST /holds`, `POST /holds/{id}/capture`, `POST /holds/{id}/release`,
  `GET /holds/{id}`, `GET /accounts/{accountRef}/available-balance`, all noting the required
  `Authorization: Bearer <token>` header.
- **Known V1 limitations** section: rename to "Known limitations" (or keep as-is and add a
  new "V2 limitations" subsection — your call, whichever reads more naturally given the
  current file's structure) and add: the documented overdraw gap (a direct
  `POST /transactions` call bypassing Holds Service can still overdraw an account with active
  holds), and that V2's Keycloak auth covers client-credentials + password grants only (no
  browser login/authorization-code flow, no user self-registration — see the V2 spec's
  Component 2 section for the full list of what's deliberately deferred).

- [ ] **Step 2: Run the complete Maven test suite one final time**

Run: `mvn -f D:\Ledger\pom.xml clean verify`
Expected: BUILD SUCCESS across all four modules — every unit and Testcontainers integration
test from Tasks 1-9 passes.

- [ ] **Step 3: Run the full Docker Compose + smoke test + chaos suite + Holds flow one final time**

Repeat Task 10 Step 8's full acceptance sequence end-to-end one more time from a clean slate,
confirming everything still works together after the README update (which shouldn't affect
runtime behavior, but this is the final gate for the whole plan — verify, don't assume):

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
```
Expected: all green. Then verify the Holds flow through the gateway with both a
client-credentials token and a password-grant token (`alice`), confirming both auth paths
work end-to-end:

```bash
TOKEN_CLIENT=$(bash scripts/get-token.sh client)
TOKEN_ALICE=$(bash scripts/get-token.sh alice)
curl -X POST http://localhost:8080/holds -H "Authorization: Bearer $TOKEN_CLIENT" \
  -H "Content-Type: application/json" -H "Idempotency-Key: final-check-1" \
  -d '{"accountRef":"smoke-a","destinationAccountRef":"smoke-b","amountMinor":500,"currency":"USD","expiresInSeconds":3600}'
curl -X GET http://localhost:8080/accounts/smoke-a/available-balance -H "Authorization: Bearer $TOKEN_ALICE"
```
Expected: both calls succeed (201 and 200 respectively) — proving the gateway accepts tokens
from both grant types, not just one.

Tear down: `docker compose down -v`.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: update README for V2 (Holds Service, API Gateway, Keycloak auth)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---
