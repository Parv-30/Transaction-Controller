# Ledger V3 (FX Service + Multi-Currency) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new FX Service (exchange rates + quote-locking, synced from the free Frankfurter API) and extend Ledger Service with multi-currency wallets and a cross-currency transfer saga (two ledger legs + compensation), wired through the existing API Gateway.

**Architecture:** FX Service is a new, fully independent Spring Boot module (own `fx_db`, no funds/accounts/transactions) that syncs rates hourly from Frankfurter and serves quote-locks. Ledger Service is extended in place — a schema migration adds `accounts.account_group_id` and a new `pending_fx_transfers` table, plus a new `CrossCurrencyTransferService` that calls FX Service via a small HTTP client and posts both legs via the *existing* `TransactionService.postTransaction` (a plain in-process Java call, not a new HTTP round-trip, since the saga runs inside Ledger Service itself). Transaction Processor is untouched.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, Flyway, PostgreSQL 16.4, Spring WebClient (for the Frankfurter + FX Service HTTP calls), Testcontainers, JUnit 5, WireMock (for stubbing Frankfurter in tests), Maven multi-module monorepo.

**Spec:** [docs/superpowers/specs/2026-09-13-ledger-v3-fx-and-multicurrency-design.md](../specs/2026-09-13-ledger-v3-fx-and-multicurrency-design.md)

## Global Constraints

- Money is always integer minor units (`long`/`BIGINT`), never floating point, except `rate` itself which is `NUMERIC(18,8)` (a ratio, not currency) — consistent with V1/V2.
- Every externally-triggered write and every internal saga step gets a deterministic idempotency key backed by a DB unique constraint — never an application-level read-then-write check. This is the project's Global Constraint since V1 and is *load-bearing* for this plan's crash-recovery sweep (see spec's "Deviation" and "The Saga" sections).
- `@Transactional` methods must never be called via self-invocation (`this.foo()` from another method in the same class) — Spring's proxy silently no-ops in that case. This bug has been hit and fixed via bean-extraction 6 times across V1/V2 (`TransactionPoster`, `DedupGateService`, `OutboxConfirmHandler`, `HoldPoster`, `OutboxEventPublisher`, `LedgerTransactionPostedApplier`). Every new `@Transactional` method in this plan must live on a bean that is called from a *different* bean, never self-invoked.
- FX Service never touches funds, accounts, or transactions — verified by construction: it has no dependency on Ledger Service's DB and no HTTP client calling into Ledger Service or Transaction Processor.
- Transaction Processor is not modified anywhere in this plan (deliberate deviation from the original platform architecture doc — see spec's "Deviation from the original spec" section).
- FX Service runs on port `8083`, its own `fx_db` (own Postgres container `fx-db` in Docker Compose, following the exact pattern of `holds-db`).
- Ports already in use, do not collide with them: API Gateway `8080`, Transaction Processor `8081`, Holds Service `8082`, Ledger Service `8090`, Keycloak `8180`.
- Testcontainers on this dev machine requires `DOCKER_HOST=tcp://127.0.0.1:2375`, `DOCKER_API_VERSION=1.44`, and Maven flag `-Dapi.version=1.44` (NOT `tcp://localhost:2375` — that fails DNS resolution in this shell). Plain `docker`/`docker compose` CLI commands work without these.
- JDBC tests need `-Duser.timezone=UTC` in Surefire's `<argLine>` — already configured project-wide in every module's `pom.xml`; no new configuration needed.

---

### Task 1: Scaffold the `fx-service` Maven module

**Files:**
- Create: `fx-service/pom.xml`
- Create: `fx-service/src/main/java/com/ledger/fxservice/FxServiceApplication.java`
- Create: `fx-service/src/main/resources/application.yml`
- Create: `fx-service/src/test/resources/application-test.yml`
- Modify: `pom.xml:<modules>` (root reactor pom)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a buildable, runnable Spring Boot module on port `8083`, joined to the Maven reactor, with `spring.application.name: fx-service`. Later tasks add packages under `com.ledger.fxservice.*`.

- [ ] **Step 1: Create `fx-service/pom.xml`**

Model this directly on `holds-service/pom.xml` (same parent, same Spring Boot starters), with a `webflux`/`webclient`-capable starter added since FX Service calls out to Frankfurter over HTTP (use plain `spring-boot-starter-web`'s `RestClient`, not WebFlux — this project's non-gateway services are all Servlet-stack, not reactive; only `api-gateway` is WebFlux). No RabbitMQ dependency — FX Service never publishes or consumes events.

```xml
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

    <artifactId>fx-service</artifactId>
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
        <dependency>
            <groupId>com.github.tomakehurst</groupId>
            <artifactId>wiremock-jre8</artifactId>
            <version>3.0.1</version>
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

Note the explicit `<version>3.0.1</version>` on `wiremock-jre8` — unlike the Spring/Testcontainers artifacts, it is not covered by any BOM already imported in the parent reactor pom, so it needs a pinned version or the build will fail to resolve it. Verify this version is actually resolvable before moving on (Step 2 does this).

- [ ] **Step 2: Add the module to the root reactor pom and verify it resolves**

In `pom.xml` at the repo root, find the `<modules>` block:
```xml
<modules>
    <module>ledger-service</module>
    <module>transaction-processor</module>
    <module>holds-service</module>
    <module>api-gateway</module>
</modules>
```
Add `<module>fx-service</module>` after `<module>api-gateway</module>`.

Run: `mvn -pl fx-service -am dependency:resolve`
Expected: BUILD SUCCESS, all dependencies including `wiremock-jre8:3.0.1` resolve. If `wiremock-jre8:3.0.1` fails to resolve, check https://mvnrepository.com/artifact/com.github.tomakehurst/wiremock-jre8 for the latest available version and use that instead — do not guess.

- [ ] **Step 3: Create the Spring Boot application class**

```java
package com.ledger.fxservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FxServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FxServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `application.yml`**

```yaml
server:
  port: 8083

spring:
  application:
    name: fx-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:fx_db}
    username: ${DB_USER:fx}
    password: ${DB_PASSWORD:fx}
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

frankfurter:
  base-url: ${FRANKFURTER_BASE_URL:https://api.frankfurter.dev}

fx:
  rate-sync:
    interval-ms: ${FX_RATE_SYNC_INTERVAL_MS:3600000}
  quote:
    ttl-seconds: ${FX_QUOTE_TTL_SECONDS:60}
```

`frankfurter.base-url` is overridable so tests can point it at a local WireMock stub instead of the real API (never call the real Frankfurter API from an automated test — keep tests offline and deterministic, per the spec's testing strategy).

- [ ] **Step 5: Create `src/test/resources/application-test.yml`**

```yaml
spring:
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
```

(Mirrors `holds-service/src/test/resources/application-test.yml` — Testcontainers supplies the actual datasource URL dynamically via `@DynamicPropertySource` in each test, this file only needs to confirm Flyway/JPA validate mode stays on under the `test` profile.)

- [ ] **Step 6: Build and verify the module starts**

Run: `mvn -pl fx-service -am spring-boot:run -Dspring-boot.run.profiles=test &` then check it started on port 8083, then kill it. Actually — simpler and non-interactive: run `mvn -pl fx-service -am compile` and confirm `BUILD SUCCESS`. A full run requires a real Postgres, which Task 2 wires up via Flyway; skip attempting to actually start the app until Task 2 has a schema for Flyway to apply.

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add fx-service pom.xml
git commit -m "chore(fx-service): scaffold Maven module and join the reactor

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: FX Service Flyway schema — `fx_rates` and `fx_quotes`

**Files:**
- Create: `fx-service/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `fx-service/src/test/java/com/ledger/fxservice/SchemaMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: the `fx_rates` and `fx_quotes` tables, verified live against a real Postgres via Testcontainers. Later tasks' JPA entities (Task 3) map onto these tables exactly.

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE fx_rates (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate           NUMERIC(18,8) NOT NULL,
    source         VARCHAR(32) NOT NULL DEFAULT 'frankfurter',
    fetched_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (base_currency, quote_currency, fetched_at)
);
CREATE INDEX idx_fx_rates_pair_fetched ON fx_rates(base_currency, quote_currency, fetched_at DESC);

CREATE TABLE fx_quotes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate_used      NUMERIC(18,8) NOT NULL,
    locked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ
);
```

- [ ] **Step 2: Write the failing schema-migration test**

Model directly on `holds-service/src/test/java/com/ledger/holdsservice/SchemaMigrationIntegrationTest.java` — read that file first to match its exact style (Testcontainers Postgres, Flyway auto-applies via Spring Boot autoconfiguration, then assert against `information_schema` or by inserting/selecting a row). Write:

```java
package com.ledger.fxservice;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void fxRatesTableAcceptsAnInsertAndEnforcesUniquePairAndFetchedAt() {
        Instant fetchedAt = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO fx_rates (base_currency, quote_currency, rate, fetched_at) VALUES (?,?,?,?)",
                "USD", "EUR", new BigDecimal("0.92000000"), java.sql.Timestamp.from(fetchedAt));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fx_rates WHERE base_currency = 'USD' AND quote_currency = 'EUR'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void fxQuotesTableAcceptsAnInsertWithNullableConsumedAt() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO fx_quotes (id, base_currency, quote_currency, rate_used, locked_at, expires_at) " +
                        "VALUES (?,?,?,?,?,?)",
                id, "USD", "EUR", new BigDecimal("0.92000000"),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(60)));

        Boolean consumedAtIsNull = jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NULL FROM fx_quotes WHERE id = ?", Boolean.class, id);
        assertThat(consumedAtIsNull).isTrue();
    }
}
```

- [ ] **Step 3: Run the test to verify the migration is picked up**

Run (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`):
```bash
mvn -pl fx-service -am test -Dtest=SchemaMigrationIntegrationTest -Dapi.version=1.44
```
Expected: `Tests run: 2, Failures: 0, Errors: 0`, `BUILD SUCCESS`. Flyway applies `V1__init_schema.sql` automatically on Spring context startup — there is no separate "run migration" step to fail first, so this test either passes outright (correct SQL) or fails with a Flyway/SQL error (fix the SQL). If the test fails, read the actual error before changing anything — do not guess at the fix.

- [ ] **Step 4: Commit**

```bash
git add fx-service/src/main/resources/db/migration/V1__init_schema.sql \
        fx-service/src/test/java/com/ledger/fxservice/SchemaMigrationIntegrationTest.java
git commit -m "feat(fx-service): add Flyway schema migration (fx_rates, fx_quotes)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: JPA entities and repositories for `FxRate` and `FxQuote`

**Files:**
- Create: `fx-service/src/main/java/com/ledger/fxservice/domain/FxRate.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/domain/FxQuote.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/repository/FxRateRepository.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/repository/FxQuoteRepository.java`
- Create: `fx-service/src/test/java/com/ledger/fxservice/repository/FxRateRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: the `fx_rates`/`fx_quotes` tables from Task 2.
- Produces:
  - `FxRate(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rate, String source, Instant fetchedAt)` — getters only, immutable after construction (a rate row is never updated, only inserted).
  - `FxQuote(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rateUsed, Instant lockedAt, Instant expiresAt)` — getters, plus `markConsumed()` mutator and `getConsumedAt()`.
  - `FxRateRepository.findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc(String base, String quote): Optional<FxRate>` — the "current rate for this pair" lookup Task 5 needs.
  - `FxRateRepository.save(FxRate): FxRate`.
  - `FxQuoteRepository extends JpaRepository<FxQuote, UUID>` — plain CRUD, no custom queries needed yet.

- [ ] **Step 1: Write `FxRate` entity**

```java
package com.ledger.fxservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, columnDefinition = "char(3)")
    private String baseCurrency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency", nullable = false, columnDefinition = "char(3)")
    private String quoteCurrency;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false)
    private String source;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected FxRate() {
        // JPA
    }

    public FxRate(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rate,
                  String source, Instant fetchedAt) {
        this.id = id;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.source = source;
        this.fetchedAt = fetchedAt;
    }

    public UUID getId() { return id; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRate() { return rate; }
    public String getSource() { return source; }
    public Instant getFetchedAt() { return fetchedAt; }
}
```

- [ ] **Step 2: Write `FxQuote` entity**

```java
package com.ledger.fxservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_quotes")
public class FxQuote {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, columnDefinition = "char(3)")
    private String baseCurrency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency", nullable = false, columnDefinition = "char(3)")
    private String quoteCurrency;

    @Column(name = "rate_used", nullable = false, precision = 18, scale = 8)
    private BigDecimal rateUsed;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected FxQuote() {
        // JPA
    }

    public FxQuote(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rateUsed,
                   Instant lockedAt, Instant expiresAt) {
        this.id = id;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rateUsed = rateUsed;
        this.lockedAt = lockedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRateUsed() { return rateUsed; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }

    public void markConsumed() {
        this.consumedAt = Instant.now();
    }
}
```

- [ ] **Step 3: Write the repositories**

```java
package com.ledger.fxservice.repository;

import com.ledger.fxservice.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {
    Optional<FxRate> findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc(
            String baseCurrency, String quoteCurrency);
}
```

```java
package com.ledger.fxservice.repository;

import com.ledger.fxservice.domain.FxQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FxQuoteRepository extends JpaRepository<FxQuote, UUID> {
}
```

- [ ] **Step 4: Write the failing repository integration test**

```java
package com.ledger.fxservice.repository;

import com.ledger.fxservice.domain.FxRate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class FxRateRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    FxRateRepository fxRateRepository;

    @Test
    void findsTheMostRecentlyFetchedRateForAPair() {
        Instant older = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant newer = Instant.now().minus(1, ChronoUnit.HOURS);

        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.90000000"), "frankfurter", older));
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", newer));

        var latest = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("USD", "EUR");

        assertThat(latest).isPresent();
        assertThat(latest.get().getRate()).isEqualByComparingTo("0.92000000");
        assertThat(latest.get().getFetchedAt()).isEqualTo(newer);
    }

    @Test
    void returnsEmptyForAnUnknownPair() {
        var result = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("XXX", "YYY");
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `mvn -pl fx-service -am test -Dtest=FxRateRepositoryIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 2, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Run the full fx-service test suite so far**

Run: `mvn -pl fx-service -am test -Dapi.version=1.44`
Expected: `Tests run: 4, Failures: 0, Errors: 0` (2 from Task 2 + 2 from this task), `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add fx-service/src/main/java/com/ledger/fxservice/domain/FxRate.java \
        fx-service/src/main/java/com/ledger/fxservice/domain/FxQuote.java \
        fx-service/src/main/java/com/ledger/fxservice/repository/FxRateRepository.java \
        fx-service/src/main/java/com/ledger/fxservice/repository/FxQuoteRepository.java \
        fx-service/src/test/java/com/ledger/fxservice/repository/FxRateRepositoryIntegrationTest.java
git commit -m "feat(fx-service): add JPA entities and repositories for rates and quotes

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Frankfurter sync client + hourly scheduled sync job

**Files:**
- Create: `fx-service/src/main/java/com/ledger/fxservice/sync/FrankfurterClient.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/sync/FrankfurterRatesResponse.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/sync/RateSyncJob.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/FxServiceApplication.java` (modify — add `@EnableScheduling`)
- Create: `fx-service/src/main/java/com/ledger/fxservice/config/RestClientConfig.java`
- Create: `fx-service/src/test/java/com/ledger/fxservice/sync/RateSyncJobIntegrationTest.java`

**Interfaces:**
- Consumes: `FxRateRepository.save` (Task 3), `frankfurter.base-url` config property (Task 1).
- Produces: `RateSyncJob.syncNow(List<String> pairs)` — a public method the scheduled trigger calls, and which Task 6's tests can also call directly to force a sync without waiting for the schedule. `FrankfurterClient.fetchLatestRates(String baseCurrency, List<String> targetCurrencies): FrankfurterRatesResponse` — the raw HTTP call, separated from the persistence logic so it can be tested independently.

- [ ] **Step 1: Write `FrankfurterRatesResponse` (the API's JSON shape)**

Frankfurter's `GET /v1/latest?base=USD&symbols=EUR,GBP` returns:
```json
{"amount":1.0,"base":"USD","date":"2026-09-12","rates":{"EUR":0.92,"GBP":0.79}}
```

```java
package com.ledger.fxservice.sync;

import java.math.BigDecimal;
import java.util.Map;

public record FrankfurterRatesResponse(
        BigDecimal amount,
        String base,
        String date,
        Map<String, BigDecimal> rates) {
}
```

- [ ] **Step 2: Write `RestClientConfig`**

```java
package com.ledger.fxservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient frankfurterRestClient(@Value("${frankfurter.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
```

- [ ] **Step 3: Write `FrankfurterClient`**

```java
package com.ledger.fxservice.sync;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class FrankfurterClient {

    private final RestClient restClient;

    public FrankfurterClient(RestClient frankfurterRestClient) {
        this.restClient = frankfurterRestClient;
    }

    public FrankfurterRatesResponse fetchLatestRates(String baseCurrency, List<String> targetCurrencies) {
        String symbols = String.join(",", targetCurrencies);
        return restClient.get()
                .uri("/v1/latest?base={base}&symbols={symbols}", baseCurrency, symbols)
                .retrieve()
                .body(FrankfurterRatesResponse.class);
    }
}
```

- [ ] **Step 4: Write `RateSyncJob`**

The set of currency pairs to sync is fixed and small for this project — USD, EUR, GBP (three currencies, six directed pairs). Hardcode this list as a constant rather than building dynamic currency management; V3's scope is demonstrating the FX mechanism, not a full currency-admin API.

```java
package com.ledger.fxservice.sync;

import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RateSyncJob {

    private static final Logger log = LoggerFactory.getLogger(RateSyncJob.class);
    private static final List<String> BASE_CURRENCIES = List.of("USD", "EUR", "GBP");

    private final FrankfurterClient frankfurterClient;
    private final FxRateRepository fxRateRepository;

    public RateSyncJob(FrankfurterClient frankfurterClient, FxRateRepository fxRateRepository) {
        this.frankfurterClient = frankfurterClient;
        this.fxRateRepository = fxRateRepository;
    }

    @Scheduled(fixedDelayString = "${fx.rate-sync.interval-ms:3600000}")
    public void run() {
        syncNow(BASE_CURRENCIES);
    }

    public void syncNow(List<String> baseCurrencies) {
        Instant fetchedAt = Instant.now();
        for (String base : baseCurrencies) {
            List<String> targets = baseCurrencies.stream().filter(c -> !c.equals(base)).toList();
            try {
                FrankfurterRatesResponse response = frankfurterClient.fetchLatestRates(base, targets);
                response.rates().forEach((quoteCurrency, rate) ->
                        fxRateRepository.save(new FxRate(UUID.randomUUID(), base, quoteCurrency,
                                rate, "frankfurter", fetchedAt)));
            } catch (Exception e) {
                // One base currency's sync failing (provider down, rate-limited, network error)
                // must not block syncing the others -- log and continue. The staleness flag in
                // Task 5's quote logic is what surfaces this to callers, not an exception here.
                log.warn("Failed to sync rates for base currency {}: {}", base, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 5: Add `@EnableScheduling` to `FxServiceApplication`**

```java
package com.ledger.fxservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FxServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FxServiceApplication.class, args);
    }
}
```

- [ ] **Step 6: Write the failing integration test against a WireMock stub**

Model the WireMock setup on how other tests in this codebase stub an HTTP dependency (`HoldCaptureIntegrationTest`'s raw `HttpServer` stub is the closest precedent, but WireMock is more convenient for a JSON-shaped API like Frankfurter's — use WireMock here since it's now a declared test dependency from Task 1).

```java
package com.ledger.fxservice.sync;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.ledger.fxservice.repository.FxRateRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RateSyncJobIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        wireMock = new WireMockServer(0);
        wireMock.start();
        registry.add("frankfurter.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    RateSyncJob rateSyncJob;

    @Autowired
    FxRateRepository fxRateRepository;

    @BeforeEach
    void resetStub() {
        wireMock.resetAll();
    }

    @AfterEach
    void stopServer() {
        // Left running across tests in this class; only reset stubs between tests.
        // WireMock stays up for the whole test class -- @AfterEach intentionally does nothing here.
    }

    @Test
    void syncingPersistsRatesForEveryConfiguredPair() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("USD"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"EUR\":0.92,\"GBP\":0.79}}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("EUR"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.09,\"GBP\":0.86}}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("GBP"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"GBP\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.27,\"EUR\":1.16}}")));

        rateSyncJob.syncNow(List.of("USD", "EUR", "GBP"));

        var usdEur = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("USD", "EUR");
        assertThat(usdEur).isPresent();
        assertThat(usdEur.get().getRate()).isEqualByComparingTo("0.92");
    }

    @Test
    void oneBaseCurrencyFailingDoesNotBlockTheOthersFromSyncing() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("USD"))
                .willReturn(serverError()));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("EUR"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.09,\"GBP\":0.86}}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("GBP"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"GBP\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.27,\"EUR\":1.16}}")));

        rateSyncJob.syncNow(List.of("USD", "EUR", "GBP"));

        var eurUsd = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("EUR", "USD");
        assertThat(eurUsd).isPresent();

        var usdEur = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("USD", "EUR");
        assertThat(usdEur).isEmpty();
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `mvn -pl fx-service -am test -Dtest=RateSyncJobIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 2, Failures: 0, Errors: 0`, `BUILD SUCCESS`. If `wiremock-jre8` has classpath conflicts with Spring Boot's own Jetty/Jackson versions (a known occasional issue with WireMock's shaded jar), read the actual error — it is more likely a version mismatch than a logic bug, and the fix is adjusting the WireMock version pinned in Task 1, not changing this test's logic.

- [ ] **Step 8: Run the full fx-service suite**

Run: `mvn -pl fx-service -am test -Dapi.version=1.44`
Expected: `Tests run: 6, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add fx-service/src/main/java/com/ledger/fxservice/sync \
        fx-service/src/main/java/com/ledger/fxservice/config/RestClientConfig.java \
        fx-service/src/main/java/com/ledger/fxservice/FxServiceApplication.java \
        fx-service/src/test/java/com/ledger/fxservice/sync/RateSyncJobIntegrationTest.java
git commit -m "feat(fx-service): add Frankfurter sync client and hourly scheduled sync job

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: `GET /rates/{base}/{quote}` and `POST /conversions/quote` endpoints

**Files:**
- Create: `fx-service/src/main/java/com/ledger/fxservice/api/RateController.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/api/dto/RateResponse.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/api/dto/QuoteRequest.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/api/dto/QuoteResponse.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/service/RateService.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/service/RateNotAvailableException.java`
- Create: `fx-service/src/main/java/com/ledger/fxservice/api/error/ApiExceptionHandler.java`
- Create: `fx-service/src/test/java/com/ledger/fxservice/service/RateServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `FxRateRepository` (Task 3), `FxQuoteRepository` (Task 3), `fx.rate-sync.interval-ms` and `fx.quote.ttl-seconds` config (Task 1) to compute staleness.
- Produces:
  - `RateService.getLatestRate(String base, String quote): RateResponse` — throws `RateNotAvailableException` (mapped to 422) if the pair has never synced.
  - `RateService.lockQuote(String base, String quote, long amountMinor): QuoteResponse` — throws `RateNotAvailableException` (422) under the same condition.
  - `RateResponse(BigDecimal rate, boolean stale, Instant fetchedAt)`.
  - `QuoteResponse(UUID quoteId, BigDecimal rateUsed, Instant expiresAt, boolean stale)` — Task 8 (Ledger Service's FX client) consumes exactly this shape.

- [ ] **Step 1: Write the DTOs**

```java
package com.ledger.fxservice.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RateResponse(BigDecimal rate, boolean stale, Instant fetchedAt) {
}
```

```java
package com.ledger.fxservice.api.dto;

public record QuoteRequest(String baseCurrency, String quoteCurrency, long amountMinor) {
}
```

```java
package com.ledger.fxservice.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteResponse(UUID quoteId, BigDecimal rateUsed, Instant expiresAt, boolean stale) {
}
```

- [ ] **Step 2: Write `RateNotAvailableException`**

```java
package com.ledger.fxservice.service;

public class RateNotAvailableException extends RuntimeException {
    public RateNotAvailableException(String baseCurrency, String quoteCurrency) {
        super("No exchange rate available for pair: " + baseCurrency + "/" + quoteCurrency);
    }
}
```

- [ ] **Step 3: Write the failing test for `RateService`**

Staleness is defined as: the most recent `fetched_at` for the pair is older than `2 x fx.rate-sync.interval-ms` — i.e., at least one full sync cycle has been missed. Using exactly one interval would flag a rate as stale immediately before the next scheduled sync even under perfectly normal operation; two intervals gives a genuine "the sync job has actually stopped working" signal instead of noise.

```java
package com.ledger.fxservice.service;

import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxQuoteRepository;
import com.ledger.fxservice.repository.FxRateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = "fx.rate-sync.interval-ms=1000")
@ActiveProfiles("test")
class RateServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    RateService rateService;

    @Autowired
    FxRateRepository fxRateRepository;

    @Autowired
    FxQuoteRepository fxQuoteRepository;

    @Test
    void getLatestRateReturnsFreshRateWhenRecentlySynced() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var response = rateService.getLatestRate("USD", "EUR");

        assertThat(response.rate()).isEqualByComparingTo("0.92000000");
        assertThat(response.stale()).isFalse();
    }

    @Test
    void getLatestRateFlagsStaleWhenLastSyncIsOlderThanTwoIntervals() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "GBP",
                new BigDecimal("0.79000000"), "frankfurter",
                Instant.now().minus(1, ChronoUnit.HOURS)));

        var response = rateService.getLatestRate("USD", "GBP");

        assertThat(response.stale()).isTrue();
    }

    @Test
    void getLatestRateThrowsForAnUnknownPair() {
        assertThatThrownBy(() -> rateService.getLatestRate("XXX", "YYY"))
                .isInstanceOf(RateNotAvailableException.class);
    }

    @Test
    void lockQuotePersistsAQuoteRowWithComputedExpiry() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var response = rateService.lockQuote("USD", "EUR", 10_000L);

        assertThat(response.rateUsed()).isEqualByComparingTo("0.92000000");
        assertThat(fxQuoteRepository.findById(response.quoteId())).isPresent();
        assertThat(response.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void lockQuoteThrowsForAnUnknownPair() {
        assertThatThrownBy(() -> rateService.lockQuote("XXX", "YYY", 10_000L))
                .isInstanceOf(RateNotAvailableException.class);
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `mvn -pl fx-service -am test -Dtest=RateServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — compile error, `RateService` does not exist yet.

- [ ] **Step 5: Write `RateService`**

```java
package com.ledger.fxservice.service;

import com.ledger.fxservice.api.dto.QuoteResponse;
import com.ledger.fxservice.api.dto.RateResponse;
import com.ledger.fxservice.domain.FxQuote;
import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxQuoteRepository;
import com.ledger.fxservice.repository.FxRateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class RateService {

    private final FxRateRepository fxRateRepository;
    private final FxQuoteRepository fxQuoteRepository;
    private final long syncIntervalMs;
    private final long quoteTtlSeconds;

    public RateService(FxRateRepository fxRateRepository,
                        FxQuoteRepository fxQuoteRepository,
                        @Value("${fx.rate-sync.interval-ms:3600000}") long syncIntervalMs,
                        @Value("${fx.quote.ttl-seconds:60}") long quoteTtlSeconds) {
        this.fxRateRepository = fxRateRepository;
        this.fxQuoteRepository = fxQuoteRepository;
        this.syncIntervalMs = syncIntervalMs;
        this.quoteTtlSeconds = quoteTtlSeconds;
    }

    public RateResponse getLatestRate(String baseCurrency, String quoteCurrency) {
        FxRate rate = findLatestOrThrow(baseCurrency, quoteCurrency);
        boolean stale = isStale(rate.getFetchedAt());
        return new RateResponse(rate.getRate(), stale, rate.getFetchedAt());
    }

    public QuoteResponse lockQuote(String baseCurrency, String quoteCurrency, long amountMinor) {
        FxRate rate = findLatestOrThrow(baseCurrency, quoteCurrency);
        boolean stale = isStale(rate.getFetchedAt());

        Instant now = Instant.now();
        FxQuote quote = new FxQuote(UUID.randomUUID(), baseCurrency, quoteCurrency,
                rate.getRate(), now, now.plusSeconds(quoteTtlSeconds));
        fxQuoteRepository.save(quote);

        return new QuoteResponse(quote.getId(), quote.getRateUsed(), quote.getExpiresAt(), stale);
    }

    private FxRate findLatestOrThrow(String baseCurrency, String quoteCurrency) {
        return fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc(baseCurrency, quoteCurrency)
                .orElseThrow(() -> new RateNotAvailableException(baseCurrency, quoteCurrency));
    }

    private boolean isStale(Instant fetchedAt) {
        return fetchedAt.isBefore(Instant.now().minusMillis(syncIntervalMs * 2));
    }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `mvn -pl fx-service -am test -Dtest=RateServiceIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 5, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Write `RateController`**

```java
package com.ledger.fxservice.api;

import com.ledger.fxservice.api.dto.QuoteRequest;
import com.ledger.fxservice.api.dto.QuoteResponse;
import com.ledger.fxservice.api.dto.RateResponse;
import com.ledger.fxservice.service.RateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class RateController {

    private final RateService rateService;

    public RateController(RateService rateService) {
        this.rateService = rateService;
    }

    @GetMapping("/rates/{base}/{quote}")
    public ResponseEntity<RateResponse> getRate(@PathVariable String base, @PathVariable String quote) {
        return ResponseEntity.ok(rateService.getLatestRate(base, quote));
    }

    @PostMapping("/conversions/quote")
    public ResponseEntity<QuoteResponse> lockQuote(@RequestBody QuoteRequest request) {
        return ResponseEntity.ok(rateService.lockQuote(
                request.baseCurrency(), request.quoteCurrency(), request.amountMinor()));
    }
}
```

(The spec's `?at=<timestamp>` historical-lookup query parameter on `GET /rates/{base}/{quote}` is deferred — see Task 5's self-review note. It is additive and does not block any other task; add it now only if straightforward, otherwise track it as a known gap rather than blocking this task on it. For this plan, implement it: add `@RequestParam(required = false) String at` to `getRate`, and if present, parse it as an `Instant` and add a `RateService.getRateAt(base, quote, Instant at)` method using a new repository query `findTopByBaseCurrencyAndQuoteCurrencyAndFetchedAtLessThanEqualOrderByFetchedAtDesc`. Write this now rather than deferring, since deferring silent scope was exactly the class of gap that caused rework in V2.)

- [ ] **Step 8: Add the historical lookup**

Add to `FxRateRepository`:
```java
Optional<FxRate> findTopByBaseCurrencyAndQuoteCurrencyAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
        String baseCurrency, String quoteCurrency, Instant at);
```

Add to `RateService`:
```java
public RateResponse getRateAt(String baseCurrency, String quoteCurrency, Instant at) {
    FxRate rate = fxRateRepository
            .findTopByBaseCurrencyAndQuoteCurrencyAndFetchedAtLessThanEqualOrderByFetchedAtDesc(
                    baseCurrency, quoteCurrency, at)
            .orElseThrow(() -> new RateNotAvailableException(baseCurrency, quoteCurrency));
    return new RateResponse(rate.getRate(), isStale(rate.getFetchedAt()), rate.getFetchedAt());
}
```

Update `RateController.getRate`:
```java
@GetMapping("/rates/{base}/{quote}")
public ResponseEntity<RateResponse> getRate(@PathVariable String base, @PathVariable String quote,
                                             @RequestParam(required = false) Instant at) {
    RateResponse response = (at != null)
            ? rateService.getRateAt(base, quote, at)
            : rateService.getLatestRate(base, quote);
    return ResponseEntity.ok(response);
}
```

- [ ] **Step 9: Write `ApiExceptionHandler`**

Model on `holds-service/src/main/java/com/ledger/holdsservice/api/error/ApiExceptionHandler.java` — read that file first to match its exact structure and response-body shape.

```java
package com.ledger.fxservice.api.error;

import com.ledger.fxservice.service.RateNotAvailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RateNotAvailableException.class)
    public ResponseEntity<Map<String, String>> handleRateNotAvailable(RateNotAvailableException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 10: Write a controller-level test hitting the real HTTP endpoints**

```java
package com.ledger.fxservice.api;

import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxRateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RateControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    FxRateRepository fxRateRepository;

    TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void getRateReturns200ForAKnownPair() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var response = restTemplate.getForEntity(
                "http://localhost:" + port + "/rates/USD/EUR", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("rate");
    }

    @Test
    void getRateReturns422ForAnUnknownPair() {
        var response = restTemplate.getForEntity(
                "http://localhost:" + port + "/rates/XXX/YYY", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void postConversionsQuoteReturns200AndAQuoteIdForAKnownPair() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var request = Map.of("baseCurrency", "USD", "quoteCurrency", "EUR", "amountMinor", 10_000);
        var response = restTemplate.postForEntity(
                "http://localhost:" + port + "/conversions/quote", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("quoteId");
    }
}
```

- [ ] **Step 11: Run the full fx-service suite**

Run: `mvn -pl fx-service -am test -Dapi.version=1.44`
Expected: `Tests run: 11, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 12: Commit**

```bash
git add fx-service/src/main/java/com/ledger/fxservice/api \
        fx-service/src/main/java/com/ledger/fxservice/service \
        fx-service/src/test/java/com/ledger/fxservice
git commit -m "feat(fx-service): add GET /rates and POST /conversions/quote endpoints

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Ledger Service migration — `account_group_id` and `pending_fx_transfers`

**Files:**
- Create: `ledger-service/src/main/resources/db/migration/V3__multicurrency_and_fx_transfers.sql`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/MultiCurrencySchemaMigrationIntegrationTest.java`

(Note: `V2__constraint_trigger_zero_sum.sql` already exists from V1, so this migration is `V3`.)

**Interfaces:**
- Consumes: nothing new.
- Produces: `accounts.account_group_id` (nullable UUID) and the `pending_fx_transfers` table, verified live. Task 7's JPA changes and Task 9's saga service map onto these.

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE accounts ADD COLUMN account_group_id UUID;
CREATE INDEX idx_accounts_group_id ON accounts(account_group_id);

CREATE TABLE pending_fx_transfers (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key              VARCHAR(255) NOT NULL UNIQUE,
    quote_id                     UUID NOT NULL,
    source_account_ref           VARCHAR(128) NOT NULL,
    dest_account_ref             VARCHAR(128) NOT NULL,
    source_amount_minor          BIGINT NOT NULL,
    rate_used                    NUMERIC(18,8) NOT NULL,
    dest_amount_minor            BIGINT NOT NULL,
    status                       VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING','LEG1_POSTED','LEG2_POSTED',
                                                      'COMPLETED','COMPENSATING','COMPENSATED','FAILED')),
    leg1_transaction_id          UUID,
    leg2_transaction_id          UUID,
    compensation_transaction_id  UUID,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_fx_transfers_status ON pending_fx_transfers(status);
```

- [ ] **Step 2: Write the failing migration test**

Model on `ledger-service/src/test/java/com/ledger/ledgerservice/SchemaMigrationIntegrationTest.java` (read it first for exact style).

```java
package com.ledger.ledgerservice;

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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MultiCurrencySchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void accountsTableAcceptsANullableAccountGroupId() {
        UUID accountId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, account_ref, currency, account_group_id) VALUES (?,?,?,?)",
                accountId, "fx-test-acct-1", "USD", groupId);

        UUID stored = jdbcTemplate.queryForObject(
                "SELECT account_group_id FROM accounts WHERE id = ?", UUID.class, accountId);
        assertThat(stored).isEqualTo(groupId);
    }

    @Test
    void pendingFxTransfersTableAcceptsAnInsertWithDefaultStatus() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO pending_fx_transfers " +
                        "(id, idempotency_key, quote_id, source_account_ref, dest_account_ref, " +
                        "source_amount_minor, rate_used, dest_amount_minor) VALUES (?,?,?,?,?,?,?,?)",
                id, "test-idem-key-1", UUID.randomUUID(), "acct-a", "acct-b",
                10_000L, new BigDecimal("0.92000000"), 9_200L);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM pending_fx_transfers WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void pendingFxTransfersRejectsAnInvalidStatus() {
        UUID id = UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class, () ->
                        jdbcTemplate.update(
                                "INSERT INTO pending_fx_transfers " +
                                        "(id, idempotency_key, quote_id, source_account_ref, dest_account_ref, " +
                                        "source_amount_minor, rate_used, dest_amount_minor, status) " +
                                        "VALUES (?,?,?,?,?,?,?,?,?)",
                                id, "test-idem-key-2", UUID.randomUUID(), "acct-a", "acct-b",
                                10_000L, new BigDecimal("0.92000000"), 9_200L, "NOT_A_REAL_STATUS"));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=MultiCurrencySchemaMigrationIntegrationTest -Dapi.version=1.44`
Expected: FAIL — the migration file doesn't exist yet, or the columns/tables don't exist. Confirm the failure is a missing-column/table error, not something else.

- [ ] **Step 4: Run to verify it passes**

Run the same command again after Step 1's migration file is in place.
Expected: `Tests run: 3, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Run the full ledger-service suite to confirm no regressions**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all pre-existing tests plus these 3 new ones pass, 0 failures/errors.

- [ ] **Step 6: Commit**

```bash
git add ledger-service/src/main/resources/db/migration/V3__multicurrency_and_fx_transfers.sql \
        ledger-service/src/test/java/com/ledger/ledgerservice/MultiCurrencySchemaMigrationIntegrationTest.java
git commit -m "feat(ledger-service): add multi-currency and pending_fx_transfers migration

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: `Account.accountGroupId` + `POST /accounts` + `GET /wallets/{groupId}/accounts`

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/domain/Account.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/repository/AccountRepository.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/AccountController.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/CreateAccountRequest.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/AccountResponse.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountService.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountRefAlreadyExistsException.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/service/AccountServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `AccountRepository` (extended), `accounts.account_group_id` column (Task 6).
- Produces:
  - `Account.getAccountGroupId(): UUID` (new getter; constructor also extended).
  - `AccountRepository.findByAccountGroupId(UUID groupId): List<Account>`.
  - `AccountService.createAccount(CreateAccountRequest): AccountResponse` — throws `AccountRefAlreadyExistsException` (409) if `accountRef` is already taken.
  - `AccountService.listWalletAccounts(UUID groupId): List<AccountResponse>` — returns empty list for an unknown/empty group, never throws.
  - `AccountResponse(String accountRef, String currency, long balanceMinor, String status, UUID accountGroupId)` — Task 9's saga service does NOT depend on this DTO (it uses `AccountRepository` directly), so this interface only matters to the HTTP layer.

- [ ] **Step 1: Modify `Account` to carry `accountGroupId`**

Add the field, extend the constructor, add the getter. Read the current file first (`ledger-service/src/main/java/com/ledger/ledgerservice/domain/Account.java`) and make these exact changes:

```java
@Column(name = "account_group_id")
private UUID accountGroupId;
```
(add this field after the `version` field)

Change the constructor signature from:
```java
public Account(UUID id, String accountRef, String displayName, String currency,
               long balanceMinor, AccountStatus status) {
```
to:
```java
public Account(UUID id, String accountRef, String displayName, String currency,
               long balanceMinor, AccountStatus status, UUID accountGroupId) {
    this.id = id;
    this.accountRef = accountRef;
    this.displayName = displayName;
    this.currency = currency;
    this.balanceMinor = balanceMinor;
    this.status = status;
    this.accountGroupId = accountGroupId;
}
```

This is a breaking change to every existing call site. Grep for `new Account(` across the whole repo (`grep -rn "new Account(" --include="*.java" .`) and update every call site found (expected: `AccountServiceIntegrationTest`-style test seed helpers, and possibly `AccountRepositoryLockingIntegrationTest`/`TransactionServiceIntegrationTest`) to pass a 7th argument — `null` is a valid, correct value for "not in a wallet group," so existing single-currency test accounts can simply add a trailing `null` argument to their existing constructor calls. Do this as part of this task, not a follow-up — an uncompiled test suite is not a deferrable finding.

Add the getter:
```java
public UUID getAccountGroupId() { return accountGroupId; }
```

- [ ] **Step 2: Verify the whole module still compiles after the constructor change**

Run: `mvn -pl ledger-service -am compile`
Expected: `BUILD SUCCESS`. If it fails, the error output lists every remaining call site needing the new argument — fix each one before proceeding; do not proceed with a broken compile.

- [ ] **Step 3: Add `findByAccountGroupId` to `AccountRepository`**

```java
List<Account> findByAccountGroupId(UUID accountGroupId);
```
(add this method to the existing `AccountRepository` interface, alongside `findByAccountRef`)

- [ ] **Step 4: Write the DTOs**

```java
package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record CreateAccountRequest(String accountRef, String currency, UUID accountGroupId) {
}
```

```java
package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record AccountResponse(String accountRef, String currency, long balanceMinor,
                               String status, UUID accountGroupId) {
}
```

- [ ] **Step 5: Write `AccountRefAlreadyExistsException`**

```java
package com.ledger.ledgerservice.service;

public class AccountRefAlreadyExistsException extends RuntimeException {
    public AccountRefAlreadyExistsException(String accountRef) {
        super("Account already exists: " + accountRef);
    }
}
```

- [ ] **Step 6: Write the failing test for `AccountService`**

```java
package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    AccountService accountService;

    @Test
    void createAccountGeneratesAGroupIdWhenNoneIsGiven() {
        var response = accountService.createAccount(
                new CreateAccountRequest("wallet-test-solo", "USD", null));

        assertThat(response.accountGroupId()).isNotNull();
    }

    @Test
    void createAccountJoinsAnExistingGroupWhenOneIsGiven() {
        UUID groupId = UUID.randomUUID();
        var response = accountService.createAccount(
                new CreateAccountRequest("wallet-test-joined", "EUR", groupId));

        assertThat(response.accountGroupId()).isEqualTo(groupId);
    }

    @Test
    void createAccountThrowsWhenAccountRefAlreadyExists() {
        accountService.createAccount(new CreateAccountRequest("wallet-test-dup", "USD", null));

        assertThatThrownBy(() -> accountService.createAccount(
                new CreateAccountRequest("wallet-test-dup", "EUR", null)))
                .isInstanceOf(AccountRefAlreadyExistsException.class);
    }

    @Test
    void listWalletAccountsReturnsAllAccountsSharingAGroup() {
        UUID groupId = UUID.randomUUID();
        accountService.createAccount(new CreateAccountRequest("wallet-test-usd", "USD", groupId));
        accountService.createAccount(new CreateAccountRequest("wallet-test-eur", "EUR", groupId));

        var accounts = accountService.listWalletAccounts(groupId);

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting("accountRef")
                .containsExactlyInAnyOrder("wallet-test-usd", "wallet-test-eur");
    }

    @Test
    void listWalletAccountsReturnsEmptyListForAnUnknownGroup() {
        var accounts = accountService.listWalletAccounts(UUID.randomUUID());

        assertThat(accounts).isEmpty();
    }
}
```

- [ ] **Step 7: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=AccountServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — compile error, `AccountService` does not exist.

- [ ] **Step 8: Write `AccountService`**

```java
package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.AccountResponse;
import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public AccountResponse createAccount(CreateAccountRequest request) {
        if (accountRepository.findByAccountRef(request.accountRef()).isPresent()) {
            throw new AccountRefAlreadyExistsException(request.accountRef());
        }

        UUID groupId = request.accountGroupId() != null ? request.accountGroupId() : UUID.randomUUID();
        Account account = new Account(UUID.randomUUID(), request.accountRef(), null,
                request.currency(), 0L, AccountStatus.ACTIVE, groupId);

        try {
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException raceLost) {
            throw new AccountRefAlreadyExistsException(request.accountRef());
        }

        return toResponse(account);
    }

    public List<AccountResponse> listWalletAccounts(UUID groupId) {
        return accountRepository.findByAccountGroupId(groupId).stream()
                .map(this::toResponse)
                .toList();
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getAccountRef(), account.getCurrency(),
                account.getBalanceMinor(), account.getStatus().name(), account.getAccountGroupId());
    }
}
```

- [ ] **Step 9: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=AccountServiceIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 5, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 10: Write `AccountController`**

```java
package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.AccountResponse;
import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
import com.ledger.ledgerservice.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> create(@RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/wallets/{groupId}/accounts")
    public ResponseEntity<List<AccountResponse>> listWalletAccounts(@PathVariable UUID groupId) {
        return ResponseEntity.ok(accountService.listWalletAccounts(groupId));
    }
}
```

- [ ] **Step 11: Add the 409 mapping to `ApiExceptionHandler`**

Add this handler to the existing `ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java`:
```java
@ExceptionHandler(AccountRefAlreadyExistsException.class)
public ResponseEntity<Map<String, String>> handleAccountRefAlreadyExists(AccountRefAlreadyExistsException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
}
```

- [ ] **Step 12: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors — includes every pre-existing V1/V2 test plus this task's 5 new ones and Task 6's 3.

- [ ] **Step 13: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/domain/Account.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/repository/AccountRepository.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/AccountController.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/CreateAccountRequest.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/dto/AccountResponse.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountService.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/AccountRefAlreadyExistsException.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/AccountServiceIntegrationTest.java
git commit -m "feat(ledger-service): add POST /accounts and GET /wallets/{groupId}/accounts

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: `FxServiceClient` — Ledger Service's HTTP client to FX Service

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxServiceClient.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxQuote.java`
- Modify: `ledger-service/src/main/resources/application.yml`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/fx/FxServiceClientIntegrationTest.java`

**Interfaces:**
- Consumes: `fx.base-url` config property (new).
- Produces: `FxServiceClient.lockQuote(String baseCurrency, String quoteCurrency, long amountMinor): FxQuote` — Task 9's saga service depends on exactly this signature. `FxQuote(UUID quoteId, BigDecimal rateUsed, Instant expiresAt, boolean stale)`.

- [ ] **Step 1: Add `fx.base-url` to `application.yml`**

Add to `ledger-service/src/main/resources/application.yml`:
```yaml
fx:
  base-url: ${FX_SERVICE_URL:http://localhost:8083}
```

- [ ] **Step 2: Write `FxQuote`**

```java
package com.ledger.ledgerservice.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FxQuote(UUID quoteId, BigDecimal rateUsed, Instant expiresAt, boolean stale) {
}
```

- [ ] **Step 3: Write the failing test for `FxServiceClient`**

Model the stub server on `HoldCaptureIntegrationTest`'s raw `HttpServer` approach (no Testcontainers/Spring context needed here — this is a plain unit-style test of an HTTP client class).

```java
package com.ledger.ledgerservice.fx;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FxServiceClientIntegrationTest {

    HttpServer stubFxService;

    @AfterEach
    void stopStub() {
        if (stubFxService != null) {
            stubFxService.stop(0);
        }
    }

    @Test
    void lockQuoteParsesTheResponseCorrectly() throws Exception {
        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
        stubFxService.createContext("/conversions/quote", exchange -> {
            String body = "{\"quoteId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"rateUsed\":0.92000000,\"expiresAt\":\"2026-09-13T12:01:00Z\",\"stale\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubFxService.start();

        FxServiceClient client = new FxServiceClient(
                "http://localhost:" + stubFxService.getAddress().getPort());

        FxQuote quote = client.lockQuote("USD", "EUR", 10_000L);

        assertThat(quote.quoteId().toString()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(quote.rateUsed()).isEqualByComparingTo("0.92000000");
        assertThat(quote.stale()).isFalse();
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=FxServiceClientIntegrationTest -Dapi.version=1.44`
Expected: FAIL — compile error, `FxServiceClient` does not exist.

- [ ] **Step 5: Write `FxServiceClient`**

```java
package com.ledger.ledgerservice.fx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FxServiceClient {

    private final RestClient restClient;

    public FxServiceClient(@Value("${fx.base-url}") String fxBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(fxBaseUrl).build();
    }

    public FxQuote lockQuote(String baseCurrency, String quoteCurrency, long amountMinor) {
        record Request(String baseCurrency, String quoteCurrency, long amountMinor) {
        }

        return restClient.post()
                .uri("/conversions/quote")
                .body(new Request(baseCurrency, quoteCurrency, amountMinor))
                .retrieve()
                .body(FxQuote.class);
    }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=FxServiceClientIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors.

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/fx \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/FxServiceClientIntegrationTest.java
git commit -m "feat(ledger-service): add FxServiceClient for quote-locking

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: `CrossCurrencyTransferService` — the saga (leg 1, leg 2, compensation)

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransfer.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransferStatus.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransferRepository.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferService.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferPoster.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxClearingAccountsProperties.java`
- Modify: `ledger-service/src/main/resources/application.yml` (add clearing-account refs)
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `TransactionService.postTransaction(CreateTransactionRequest, String idempotencyKey): TransactionResponse` (existing, from Task 0/V1 — the exact reuse point named in the spec's "Deviation" section), `FxServiceClient.lockQuote` (Task 8), `AccountRepository` (existing), `pending_fx_transfers` table (Task 6).
- Produces: `CrossCurrencyTransferService.transfer(CreateCrossCurrencyTransferRequest): PendingFxTransferResponse` — Task 11's controller depends on exactly this signature. `PendingFxTransferStatus` enum with values `PENDING, LEG1_POSTED, LEG2_POSTED, COMPLETED, COMPENSATING, COMPENSATED, FAILED` (matching the DB CHECK constraint from Task 6 exactly — Task 10's sweep depends on these exact names).

**Why this task is structured as service + poster (not one class):** Every `@Transactional` method here must be called from a *different* bean than the one it's declared on, per this codebase's established self-invocation-avoidance pattern (see Global Constraints). `CrossCurrencyTransferService` orchestrates the multi-step saga (which spans multiple separate transactions — quote-lock is not transactional at all, each leg is its own transaction) and calls into `CrossCurrencyTransferPoster`, whose individual `@Transactional` methods do the atomic DB work for one step at a time.

- [ ] **Step 1: Write `PendingFxTransferStatus`**

```java
package com.ledger.ledgerservice.fx;

public enum PendingFxTransferStatus {
    PENDING, LEG1_POSTED, LEG2_POSTED, COMPLETED, COMPENSATING, COMPENSATED, FAILED
}
```

- [ ] **Step 2: Write the `PendingFxTransfer` entity**

```java
package com.ledger.ledgerservice.fx;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pending_fx_transfers")
public class PendingFxTransfer {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "quote_id", nullable = false)
    private UUID quoteId;

    @Column(name = "source_account_ref", nullable = false)
    private String sourceAccountRef;

    @Column(name = "dest_account_ref", nullable = false)
    private String destAccountRef;

    @Column(name = "source_amount_minor", nullable = false)
    private long sourceAmountMinor;

    @Column(name = "rate_used", nullable = false, precision = 18, scale = 8)
    private BigDecimal rateUsed;

    @Column(name = "dest_amount_minor", nullable = false)
    private long destAmountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PendingFxTransferStatus status;

    @Column(name = "leg1_transaction_id")
    private UUID leg1TransactionId;

    @Column(name = "leg2_transaction_id")
    private UUID leg2TransactionId;

    @Column(name = "compensation_transaction_id")
    private UUID compensationTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PendingFxTransfer() {
        // JPA
    }

    public PendingFxTransfer(UUID id, String idempotencyKey, UUID quoteId, String sourceAccountRef,
                              String destAccountRef, long sourceAmountMinor, BigDecimal rateUsed,
                              long destAmountMinor) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.quoteId = quoteId;
        this.sourceAccountRef = sourceAccountRef;
        this.destAccountRef = destAccountRef;
        this.sourceAmountMinor = sourceAmountMinor;
        this.rateUsed = rateUsed;
        this.destAmountMinor = destAmountMinor;
        this.status = PendingFxTransferStatus.PENDING;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getQuoteId() { return quoteId; }
    public String getSourceAccountRef() { return sourceAccountRef; }
    public String getDestAccountRef() { return destAccountRef; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public BigDecimal getRateUsed() { return rateUsed; }
    public long getDestAmountMinor() { return destAmountMinor; }
    public PendingFxTransferStatus getStatus() { return status; }
    public UUID getLeg1TransactionId() { return leg1TransactionId; }
    public UUID getLeg2TransactionId() { return leg2TransactionId; }
    public UUID getCompensationTransactionId() { return compensationTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markLeg1Posted(UUID transactionId) {
        this.leg1TransactionId = transactionId;
        this.status = PendingFxTransferStatus.LEG1_POSTED;
    }

    public void markLeg2Posted(UUID transactionId) {
        this.leg2TransactionId = transactionId;
        this.status = PendingFxTransferStatus.COMPLETED;
    }

    public void markCompensating() {
        this.status = PendingFxTransferStatus.COMPENSATING;
    }

    public void markCompensated(UUID transactionId) {
        this.compensationTransactionId = transactionId;
        this.status = PendingFxTransferStatus.COMPENSATED;
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
}
```

- [ ] **Step 3: Write `PendingFxTransferRepository`**

```java
package com.ledger.ledgerservice.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PendingFxTransferRepository extends JpaRepository<PendingFxTransfer, UUID> {
    Optional<PendingFxTransfer> findByIdempotencyKey(String idempotencyKey);
}
```

(Task 10 adds a second query method, `findByStatusInAndUpdatedAtBefore`, directly to this interface — not duplicated here since Task 9 doesn't need it yet.)

- [ ] **Step 4: Add clearing-account configuration**

Add to `ledger-service/src/main/resources/application.yml`:
```yaml
fx:
  base-url: ${FX_SERVICE_URL:http://localhost:8083}
  clearing-accounts:
    USD: ${FX_CLEARING_ACCOUNT_USD:fx-clearing-USD}
    EUR: ${FX_CLEARING_ACCOUNT_EUR:fx-clearing-EUR}
    GBP: ${FX_CLEARING_ACCOUNT_GBP:fx-clearing-GBP}
```

(This nested `fx.base-url` line already exists from Task 8 — add `clearing-accounts` as a sibling key under the same `fx:` block, don't duplicate `base-url`.)

- [ ] **Step 5: Write the failing test for the saga's happy path**

This test exercises the saga against a stubbed FX Service (same `HttpServer` stub pattern as Task 8 and V2's `HoldCaptureIntegrationTest`) and asserts real account balance changes, not just status fields — matching the spec's testing-strategy requirement to verify via balances, not just status.

```java
package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CrossCurrencyTransferServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubFxService;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
        stubFxService.createContext("/conversions/quote", exchange -> {
            String body = "{\"quoteId\":\"" + UUID.randomUUID() + "\","
                    + "\"rateUsed\":0.92000000,\"expiresAt\":\"2099-01-01T00:00:00Z\",\"stale\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubFxService.start();
        registry.add("fx.base-url", () -> "http://localhost:" + stubFxService.getAddress().getPort());
    }

    @AfterEach
    void noop() {
        // stubFxService stays up for the whole test class
    }

    @Autowired
    CrossCurrencyTransferService crossCurrencyTransferService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    PendingFxTransferRepository pendingFxTransferRepository;

    @BeforeEach
    void seedAccounts() {
        seedAccountIfAbsent("fx-saga-source-usd", "USD", 100_000L);
        seedAccountIfAbsent("fx-saga-dest-eur", "EUR", 0L);
        seedAccountIfAbsent("fx-clearing-USD", "USD", 0L);
        seedAccountIfAbsent("fx-clearing-EUR", "EUR", 0L);
    }

    private void seedAccountIfAbsent(String ref, String currency, long balance) {
        if (accountRepository.findByAccountRef(ref).isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), ref, null, currency,
                    balance, AccountStatus.ACTIVE, null));
        }
    }

    @Test
    void happyPathCompletesBothLegsAndUpdatesBalancesCorrectly() {
        var request = new CreateCrossCurrencyTransferRequest(
                "fx-saga-source-usd", "fx-saga-dest-eur", 10_000L, "cc-transfer-happy-1");

        var response = crossCurrencyTransferService.transfer(request);

        assertThat(response.status()).isEqualTo(PendingFxTransferStatus.COMPLETED.name());

        Account source = accountRepository.findByAccountRef("fx-saga-source-usd").orElseThrow();
        Account dest = accountRepository.findByAccountRef("fx-saga-dest-eur").orElseThrow();
        Account clearingUsd = accountRepository.findByAccountRef("fx-clearing-USD").orElseThrow();
        Account clearingEur = accountRepository.findByAccountRef("fx-clearing-EUR").orElseThrow();

        assertThat(source.getBalanceMinor()).isEqualTo(90_000L);
        assertThat(dest.getBalanceMinor()).isEqualTo(9_200L); // 10,000 * 0.92
        assertThat(clearingUsd.getBalanceMinor()).isEqualTo(0L); // debited back out by leg 2... actually credited then debited
        assertThat(clearingEur.getBalanceMinor()).isEqualTo(0L);
    }

    @Test
    void retryingWithTheSameIdempotencyKeyReturnsTheExistingResultWithoutDoublePosting() {
        var request = new CreateCrossCurrencyTransferRequest(
                "fx-saga-source-usd", "fx-saga-dest-eur", 5_000L, "cc-transfer-idem-1");

        var first = crossCurrencyTransferService.transfer(request);
        var second = crossCurrencyTransferService.transfer(request);

        assertThat(second.pendingTransferId()).isEqualTo(first.pendingTransferId());

        Account source = accountRepository.findByAccountRef("fx-saga-source-usd").orElseThrow();
        // Only ONE 5,000 debit should have happened, not two -- this is the idempotency
        // proof, not just a status-field check.
        assertThat(source.getBalanceMinor()).isEqualTo(95_000L);
    }
}
```

**Clearing-account balance mechanics, resolved explicitly** — leg 1 is `debit source (USD) -> credit USD clearing`; leg 2 is `debit EUR clearing -> credit dest (EUR)`. These are two different currency-specific clearing accounts, each single-currency (required by the zero-sum invariant — you cannot debit a USD-balance account by a EUR amount). The USD clearing account therefore only ever receives money (ends up permanently positive), and the EUR clearing account only ever pays money out (ends up permanently negative) unless separately funded. Clearing accounts are the platform's internal netting mechanism, not real funded accounts, so running negative is correct and expected — not a bug to design around. This requires relaxing `TransactionPoster`'s insufficient-funds check specifically for accounts whose ref starts with `fx-clearing-`, done in **Step 5a** below, before writing the saga test's final assertions.

- [ ] **Step 5a: Allow FX clearing accounts to go negative in `TransactionPoster`**

Read `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java` (already shown in full earlier in this plan's exploration). Change:
```java
if (debitAccount.getBalanceMinor() < request.amountMinor()) {
    throw new InsufficientFundsException(debitAccount.getAccountRef());
}
```
to:
```java
boolean debitAccountAllowsNegativeBalance = debitAccount.getAccountRef().startsWith("fx-clearing-");
if (!debitAccountAllowsNegativeBalance && debitAccount.getBalanceMinor() < request.amountMinor()) {
    throw new InsufficientFundsException(debitAccount.getAccountRef());
}
```

Add a test to the existing `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java` (read it first for the exact existing test style and helper methods) confirming: (a) a transfer debiting an account named `fx-clearing-USD` with insufficient balance succeeds and leaves it negative, and (b) a transfer debiting an ordinary account (any name not starting with `fx-clearing-`) with insufficient balance still throws `InsufficientFundsException` exactly as before — this second assertion is the regression guard proving the check wasn't weakened for real accounts.

With this fix, the earlier happy-path test's assertions become: `clearingUsd.getBalanceMinor()` ends at `+10_000L` (received from leg 1, never debited), `clearingEur.getBalanceMinor()` ends at `-9_200L` (debited by leg 2, never funded) — update the test's assertions to these corrected values before running it.

- [ ] **Step 6: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest,CrossCurrencyTransferServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — `CrossCurrencyTransferService`, `CreateCrossCurrencyTransferRequest`, etc. do not exist yet.

- [ ] **Step 7: Write `CreateCrossCurrencyTransferRequest` and the response DTO**

```java
package com.ledger.ledgerservice.fx;

public record CreateCrossCurrencyTransferRequest(
        String sourceAccountRef, String destAccountRef, long sourceAmountMinor, String idempotencyKey) {
}
```

```java
package com.ledger.ledgerservice.fx;

import java.util.UUID;

public record PendingFxTransferResponse(
        UUID pendingTransferId, String status, String sourceAccountRef, String destAccountRef,
        long sourceAmountMinor, long destAmountMinor, String errorMessage) {
}
```

- [ ] **Step 8: Write `FxClearingAccountsProperties` and `CrossCurrencyTransferPoster`**

`FxClearingAccountsProperties` binds the `fx.clearing-accounts` YAML map (added in Step 4) to a typed Spring `@ConfigurationProperties` class — the standard, robust Spring idiom for map-shaped configuration, preferred here over a raw `@Value` SpEL map expression (which is fragile to get exactly right syntactically for nested YAML maps).

```java
package com.ledger.ledgerservice.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "fx")
public class FxClearingAccountsProperties {

    private final Map<String, String> clearingAccounts = new HashMap<>();

    public Map<String, String> getClearingAccounts() {
        return clearingAccounts;
    }

    public String get(String currency) {
        return clearingAccounts.get(currency);
    }
}
```

Holds the individual `@Transactional` steps, each callable independently (Task 10's sweep calls these same methods directly, one at a time, for crash recovery).

```java
package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Holds the @Transactional-adjacent steps of the cross-currency saga. Each public method here
 * is called by {@link CrossCurrencyTransferService} (a different bean) or directly by
 * {@link FxTransferRecoverySweep} (Task 10) -- never self-invoked -- so Spring's transactional
 * proxy applies correctly to each step, per this codebase's established
 * self-invocation-avoidance pattern.
 *
 * <p>Individual steps here are NOT @Transactional themselves (they delegate the actual DB
 * commit to {@link TransactionService#postTransaction}, which owns its own transaction
 * boundary via {@code TransactionPoster}) -- this class's role is orchestrating which step
 * runs next and updating {@link PendingFxTransfer}'s status, which IS its own local
 * @Transactional unit per step.
 */
@Service
public class CrossCurrencyTransferPoster {

    private final TransactionService transactionService;
    private final AccountRepository accountRepository;
    private final PendingFxTransferRepository pendingFxTransferRepository;
    private final FxClearingAccountsProperties clearingAccounts;

    public CrossCurrencyTransferPoster(TransactionService transactionService,
                                        AccountRepository accountRepository,
                                        PendingFxTransferRepository pendingFxTransferRepository,
                                        FxClearingAccountsProperties clearingAccounts) {
        this.transactionService = transactionService;
        this.accountRepository = accountRepository;
        this.pendingFxTransferRepository = pendingFxTransferRepository;
        this.clearingAccounts = clearingAccounts;
    }

    @Transactional
    public void postLeg1(UUID pendingTransferId) {
        PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
        String sourceCurrency = accountRepository.findByAccountRef(transfer.getSourceAccountRef())
                .orElseThrow().getCurrency();
        String clearingAccountRef = clearingAccounts.get(sourceCurrency);

        TransactionResponse leg1 = transactionService.postTransaction(
                new CreateTransactionRequest(transfer.getSourceAccountRef(), clearingAccountRef,
                        transfer.getSourceAmountMinor(), sourceCurrency, "fx-transfer-leg1"),
                "fx-leg1-" + pendingTransferId);

        transfer.markLeg1Posted(leg1.transactionId());
        pendingFxTransferRepository.save(transfer);
    }

    @Transactional
    public void postLeg2(UUID pendingTransferId) {
        PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
        String destCurrency = accountRepository.findByAccountRef(transfer.getDestAccountRef())
                .orElseThrow().getCurrency();
        String clearingAccountRef = clearingAccounts.get(destCurrency);

        TransactionResponse leg2 = transactionService.postTransaction(
                new CreateTransactionRequest(clearingAccountRef, transfer.getDestAccountRef(),
                        transfer.getDestAmountMinor(), destCurrency, "fx-transfer-leg2"),
                "fx-leg2-" + pendingTransferId);

        transfer.markLeg2Posted(leg2.transactionId());
        pendingFxTransferRepository.save(transfer);
    }

    @Transactional
    public void compensate(UUID pendingTransferId) {
        PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
        if (transfer.getStatus() != PendingFxTransferStatus.COMPENSATING) {
            transfer.markCompensating();
            pendingFxTransferRepository.save(transfer);
        }

        String sourceCurrency = accountRepository.findByAccountRef(transfer.getSourceAccountRef())
                .orElseThrow().getCurrency();
        String clearingAccountRef = clearingAccounts.get(sourceCurrency);

        TransactionResponse reversal = transactionService.postTransaction(
                new CreateTransactionRequest(clearingAccountRef, transfer.getSourceAccountRef(),
                        transfer.getSourceAmountMinor(), sourceCurrency, "fx-transfer-compensation"),
                "fx-compensate-" + pendingTransferId);

        transfer.markCompensated(reversal.transactionId());
        pendingFxTransferRepository.save(transfer);
    }
}
```

- [ ] **Step 9: Write `CrossCurrencyTransferService`**

```java
package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.service.AccountNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class CrossCurrencyTransferService {

    private final FxServiceClient fxServiceClient;
    private final PendingFxTransferRepository pendingFxTransferRepository;
    private final CrossCurrencyTransferPoster poster;
    private final AccountRepository accountRepository;

    public CrossCurrencyTransferService(FxServiceClient fxServiceClient,
                                         PendingFxTransferRepository pendingFxTransferRepository,
                                         CrossCurrencyTransferPoster poster,
                                         AccountRepository accountRepository) {
        this.fxServiceClient = fxServiceClient;
        this.pendingFxTransferRepository = pendingFxTransferRepository;
        this.poster = poster;
        this.accountRepository = accountRepository;
    }

    public PendingFxTransferResponse transfer(CreateCrossCurrencyTransferRequest request) {
        var existing = pendingFxTransferRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        // Currency lookup here (for the quote request) duplicates the lookup
        // CrossCurrencyTransferPoster does again inside postLeg1/postLeg2 -- deliberate,
        // since this service needs the currencies up front to know which pair to quote,
        // before any PendingFxTransfer row exists for the poster to work from.
        String sourceCurrency = resolveCurrency(request.sourceAccountRef());
        String destCurrency = resolveCurrency(request.destAccountRef());

        FxQuote quote = fxServiceClient.lockQuote(sourceCurrency, destCurrency, request.sourceAmountMinor());
        long destAmountMinor = quote.rateUsed()
                .multiply(BigDecimal.valueOf(request.sourceAmountMinor()))
                .longValue();

        PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), request.idempotencyKey(),
                quote.quoteId(), request.sourceAccountRef(), request.destAccountRef(),
                request.sourceAmountMinor(), quote.rateUsed(), destAmountMinor);
        pendingFxTransferRepository.save(transfer);

        try {
            poster.postLeg1(transfer.getId());
            poster.postLeg2(transfer.getId());
        } catch (Exception legFailure) {
            poster.compensate(transfer.getId());
        }

        PendingFxTransfer finalState = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
        return toResponse(finalState);
    }

    private String resolveCurrency(String accountRef) {
        return accountRepository.findByAccountRef(accountRef)
                .orElseThrow(() -> new AccountNotFoundException(accountRef))
                .getCurrency();
    }

    private PendingFxTransferResponse toResponse(PendingFxTransfer transfer) {
        return new PendingFxTransferResponse(transfer.getId(), transfer.getStatus().name(),
                transfer.getSourceAccountRef(), transfer.getDestAccountRef(),
                transfer.getSourceAmountMinor(), transfer.getDestAmountMinor(), null);
    }
}
```

(`AccountNotFoundException` already exists from V1.)

- [ ] **Step 10: Run to verify the happy-path and idempotency tests pass**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest,CrossCurrencyTransferServiceIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: <existing + 2 new>, Failures: 0, Errors: 0`, `BUILD SUCCESS`. If the happy-path balance assertions don't match, re-derive them by hand from the leg definitions in Step 8 rather than adjusting the implementation to fit a guessed number.

- [ ] **Step 11: Write and run the leg-2-failure-triggers-compensation test**

Add to `CrossCurrencyTransferServiceIntegrationTest`:

```java
@Test
void leg2FailureTriggersCompensationAndRestoresSourceBalance() {
    // CrossCurrencyTransferService.transfer() resolves both accounts' currencies (via
    // resolveCurrency) BEFORE creating the PendingFxTransfer row or calling postLeg1 at all --
    // so a dest account that doesn't exist at all would fail at that early resolveCurrency
    // call, never reaching postLeg2, and this test would never see a COMPENSATED result.
    // Instead, force failure specifically inside postLeg2 by giving the dest account a real,
    // resolvable currency that has NO configured clearing account (fx.clearing-accounts only
    // configures USD/EUR/GBP) -- postLeg2's clearingAccounts.get(destCurrency)
    // then returns null, and passing a null account ref to TransactionService.postTransaction
    // fails inside leg 2's own posting attempt, which is the actual failure mode this test
    // needs to exercise.
    seedIfAbsentInThisTest("fx-saga-dest-jpy", "JPY", 0L);

    var request = new CreateCrossCurrencyTransferRequest(
            "fx-saga-source-usd", "fx-saga-dest-jpy", 5_000L, "cc-transfer-fail-1");

    var response = crossCurrencyTransferService.transfer(request);

    assertThat(response.status()).isEqualTo(PendingFxTransferStatus.COMPENSATED.name());

    Account source = accountRepository.findByAccountRef("fx-saga-source-usd").orElseThrow();
    // The debit-then-compensate round trip should net to zero change on the source account.
    assertThat(source.getBalanceMinor()).isEqualTo(100_000L);
}
```

This test also requires FX Service's stub to answer a `USD`/`JPY` quote request (the existing stub in this test class's `@DynamicPropertySource` answers any `/conversions/quote` call identically regardless of the requested pair, so no stub change is needed — confirm this is still true when implementing; if the stub is later made pair-aware, add a `JPY` case to it).

**Test isolation, resolved explicitly**: this test class's `@Container` Postgres is static and shared across every `@Test` method in the class (JUnit re-runs `@BeforeEach` per test, but the database itself persists between tests). Reusing a fixed-name account like `"fx-saga-source-usd"` across multiple tests means balance mutations from one test leak into the next, making assertions order-dependent. Resolve this the same way `TransactionServiceIntegrationTest` (read it first to confirm) or `HoldServiceIntegrationTest` already resolves it in this codebase — follow whichever pattern is actually used there (a `@BeforeEach` that truncates the relevant tables, or per-test uniquely-suffixed account refs) rather than picking a new pattern for this test class. Add a `seedIfAbsentInThisTest` helper (or inline the equivalent) consistent with whichever approach is chosen, and update the `@BeforeEach seedAccounts` method and both existing tests in this class (`happyPathCompletesBothLegsAndUpdatesBalancesCorrectly`, `retryingWithTheSameIdempotencyKeyReturnsTheExistingResultWithoutDoublePosting`) to use it consistently, so all three tests in the class are mutually order-independent.

Run: `mvn -pl ledger-service -am test -Dtest=CrossCurrencyTransferServiceIntegrationTest -Dapi.version=1.44`
Expected: all tests in the class pass, 0 failures/errors, with correct test isolation (verify by running with `-Dsurefire.runOrder=random` or similar if available, or by manually confirming no shared mutable state between test methods).

- [ ] **Step 12: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors.

- [ ] **Step 13: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/fx \
        ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java
git commit -m "feat(ledger-service): add cross-currency transfer saga (leg1/leg2/compensation)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Crash-recovery sweep for stuck `pending_fx_transfers` rows

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransferRepository.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxTransferRecoverySweep.java`
- Modify: `ledger-service/src/main/resources/application.yml`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/fx/FxTransferRecoverySweepIntegrationTest.java`

**Interfaces:**
- Consumes: `CrossCurrencyTransferPoster.postLeg1/postLeg2/compensate` (Task 9), `PendingFxTransferRepository` (Task 9).
- Produces: `FxTransferRecoverySweep.run()` — the `@Scheduled` entry point; no other code depends on this task.

- [ ] **Step 1: Add the stuck-row query to `PendingFxTransferRepository`**

```java
List<PendingFxTransfer> findByStatusInAndUpdatedAtBefore(
        List<PendingFxTransferStatus> statuses, Instant cutoff);
```
(add this to the existing interface from Task 9, alongside `findByIdempotencyKey`; add `import java.time.Instant;` and `import java.util.List;`)

- [ ] **Step 2: Add sweep timing configuration**

Add to `ledger-service/src/main/resources/application.yml`:
```yaml
fx:
  transfer-sweep:
    interval-ms: ${FX_TRANSFER_SWEEP_INTERVAL_MS:15000}
    stuck-threshold-ms: ${FX_TRANSFER_STUCK_THRESHOLD_MS:30000}
```

- [ ] **Step 3: Write the failing test**

Manually stall a row in `LEG1_POSTED` (by directly manipulating `updated_at` via `JdbcTemplate`, the same technique V2's `HoldExpirySweepIntegrationTest` used to backdate `expires_at`) and assert the sweep drives it to `COMPLETED`.

```java
package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.sun.net.httpserver.HttpServer;
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

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "fx.transfer-sweep.stuck-threshold-ms=1000")
@ActiveProfiles("test")
class FxTransferRecoverySweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubFxService;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
        stubFxService.createContext("/conversions/quote", exchange -> {
            String body = "{\"quoteId\":\"" + UUID.randomUUID() + "\","
                    + "\"rateUsed\":0.92000000,\"expiresAt\":\"2099-01-01T00:00:00Z\",\"stale\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubFxService.start();
        registry.add("fx.base-url", () -> "http://localhost:" + stubFxService.getAddress().getPort());
    }

    @Autowired
    FxTransferRecoverySweep sweep;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    PendingFxTransferRepository pendingFxTransferRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CrossCurrencyTransferPoster poster;

    @Test
    void sweepResolvesARowStuckInLeg1PostedByPostingLeg2() {
        accountRepository.save(new Account(UUID.randomUUID(), "fx-sweep-source", null, "USD",
                100_000L, AccountStatus.ACTIVE, null));
        accountRepository.save(new Account(UUID.randomUUID(), "fx-sweep-dest", null, "EUR",
                0L, AccountStatus.ACTIVE, null));
        accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-USD", null, "USD",
                0L, AccountStatus.ACTIVE, null));
        accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-EUR", null, "EUR",
                0L, AccountStatus.ACTIVE, null));

        PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), "sweep-test-1",
                UUID.randomUUID(), "fx-sweep-source", "fx-sweep-dest", 10_000L,
                new BigDecimal("0.92000000"), 9_200L);
        pendingFxTransferRepository.save(transfer);
        poster.postLeg1(transfer.getId());

        // Backdate updated_at past the stuck threshold, simulating a crash between leg 1 and leg 2.
        jdbcTemplate.update("UPDATE pending_fx_transfers SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), transfer.getId());

        sweep.run();

        PendingFxTransfer resolved = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PendingFxTransferStatus.COMPLETED);

        Account dest = accountRepository.findByAccountRef("fx-sweep-dest").orElseThrow();
        assertThat(dest.getBalanceMinor()).isEqualTo(9_200L);
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=FxTransferRecoverySweepIntegrationTest -Dapi.version=1.44`
Expected: FAIL — compile error, `FxTransferRecoverySweep` does not exist.

- [ ] **Step 5: Write `FxTransferRecoverySweep`**

```java
package com.ledger.ledgerservice.fx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class FxTransferRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(FxTransferRecoverySweep.class);

    private final PendingFxTransferRepository pendingFxTransferRepository;
    private final CrossCurrencyTransferPoster poster;
    private final long stuckThresholdMs;

    public FxTransferRecoverySweep(PendingFxTransferRepository pendingFxTransferRepository,
                                    CrossCurrencyTransferPoster poster,
                                    @Value("${fx.transfer-sweep.stuck-threshold-ms:30000}") long stuckThresholdMs) {
        this.pendingFxTransferRepository = pendingFxTransferRepository;
        this.poster = poster;
        this.stuckThresholdMs = stuckThresholdMs;
    }

    @Scheduled(fixedDelayString = "${fx.transfer-sweep.interval-ms:15000}")
    public void run() {
        Instant cutoff = Instant.now().minusMillis(stuckThresholdMs);
        List<PendingFxTransfer> stuck = pendingFxTransferRepository.findByStatusInAndUpdatedAtBefore(
                List.of(PendingFxTransferStatus.PENDING, PendingFxTransferStatus.LEG1_POSTED,
                        PendingFxTransferStatus.COMPENSATING),
                cutoff);

        for (PendingFxTransfer transfer : stuck) {
            try {
                switch (transfer.getStatus()) {
                    case PENDING -> poster.postLeg1(transfer.getId());
                    case LEG1_POSTED -> poster.postLeg2(transfer.getId());
                    case COMPENSATING -> poster.compensate(transfer.getId());
                    default -> { /* not stuck-relevant, skip */ }
                }
            } catch (Exception e) {
                // One row's recovery failing must not block the sweep from processing the
                // rest -- log and move on. This row stays stuck and is retried on the next
                // scheduled run. See the spec's explicit non-goal: a row that fails
                // compensation repeatedly stays COMPENSATING forever with no dead-letter
                // path, by design.
                log.warn("Failed to recover pending_fx_transfer {} from status {}: {}",
                        transfer.getId(), transfer.getStatus(), e.getMessage());
            }
        }
    }
}
```

Note: if `postLeg2` itself fails inside this sweep-driven retry, this simple version leaves the row in `LEG1_POSTED` rather than triggering compensation the way `CrossCurrencyTransferService.transfer`'s own try/catch does. Add compensation-on-failure here too for consistency: wrap the `LEG1_POSTED -> poster.postLeg2(...)` branch in its own try/catch that calls `poster.compensate(transfer.getId())` on failure, mirroring `CrossCurrencyTransferService`'s behavior exactly. Update the code above to:

```java
case LEG1_POSTED -> {
    try {
        poster.postLeg2(transfer.getId());
    } catch (Exception leg2Failure) {
        poster.compensate(transfer.getId());
    }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=FxTransferRecoverySweepIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors.

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransferRepository.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxTransferRecoverySweep.java \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/FxTransferRecoverySweepIntegrationTest.java
git commit -m "feat(ledger-service): add crash-recovery sweep for stuck FX transfers

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: `POST /transfers/cross-currency` endpoint

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/api/CrossCurrencyTransferController.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/FxServiceApplication` — actually modify `ledger-service/src/main/java/com/ledger/ledgerservice/LedgerServiceApplication.java` to add `@EnableScheduling` (check first whether it's already present from V1's `ReconciliationJob` — likely already there, in which case this step is a no-op verification, not a change)
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/api/CrossCurrencyTransferControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `CrossCurrencyTransferService.transfer` (Task 9).
- Produces: the public HTTP surface for the whole feature; no later task depends on this one.

- [ ] **Step 1: Verify `@EnableScheduling` is already present**

Read `ledger-service/src/main/java/com/ledger/ledgerservice/LedgerServiceApplication.java`. Since `ReconciliationJob` (V1) already uses `@Scheduled`, `@EnableScheduling` must already be present on this class for V1's reconciliation to have ever worked — confirm this by reading the file rather than assuming. If for some reason it's missing, add it now (it would mean V1's reconciliation job has never actually run on a schedule, which would itself be a significant, separately-reportable finding — flag this loudly if found, do not silently fix and move on).

- [ ] **Step 2: Write the controller**

```java
package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.fx.CreateCrossCurrencyTransferRequest;
import com.ledger.ledgerservice.fx.CrossCurrencyTransferService;
import com.ledger.ledgerservice.fx.PendingFxTransferResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CrossCurrencyTransferController {

    private final CrossCurrencyTransferService crossCurrencyTransferService;

    public CrossCurrencyTransferController(CrossCurrencyTransferService crossCurrencyTransferService) {
        this.crossCurrencyTransferService = crossCurrencyTransferService;
    }

    @PostMapping("/transfers/cross-currency")
    public ResponseEntity<PendingFxTransferResponse> transfer(
            @RequestBody CreateCrossCurrencyTransferRequest request) {
        return ResponseEntity.ok(crossCurrencyTransferService.transfer(request));
    }
}
```

- [ ] **Step 3: Write the failing controller-level test**

Model on `RateControllerIntegrationTest` (Task 5) for the `RANDOM_PORT` + `TestRestTemplate` pattern. Stub FX Service the same way Task 9's saga test does.

```java
package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.fx.CreateCrossCurrencyTransferRequest;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CrossCurrencyTransferControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubFxService;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
        stubFxService.createContext("/conversions/quote", exchange -> {
            String body = "{\"quoteId\":\"" + UUID.randomUUID() + "\","
                    + "\"rateUsed\":0.92000000,\"expiresAt\":\"2099-01-01T00:00:00Z\",\"stale\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubFxService.start();
        registry.add("fx.base-url", () -> "http://localhost:" + stubFxService.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountRepository accountRepository;

    TestRestTemplate restTemplate = new TestRestTemplate();

    @BeforeEach
    void seedAccounts() {
        seedIfAbsent("cc-ctrl-source", "USD", 50_000L);
        seedIfAbsent("cc-ctrl-dest", "EUR", 0L);
        seedIfAbsent("fx-clearing-USD", "USD", 0L);
        seedIfAbsent("fx-clearing-EUR", "EUR", 0L);
    }

    private void seedIfAbsent(String ref, String currency, long balance) {
        if (accountRepository.findByAccountRef(ref).isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), ref, null, currency,
                    balance, AccountStatus.ACTIVE, null));
        }
    }

    @Test
    void postTransfersCrossCurrencyReturns200AndACompletedStatus() {
        var request = new CreateCrossCurrencyTransferRequest(
                "cc-ctrl-source", "cc-ctrl-dest", 5_000L, "cc-ctrl-test-1");

        var response = restTemplate.postForEntity(
                "http://localhost:" + port + "/transfers/cross-currency", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED");
    }
}
```

- [ ] **Step 4: Run to verify it fails, then passes**

Run: `mvn -pl ledger-service -am test -Dtest=CrossCurrencyTransferControllerIntegrationTest -Dapi.version=1.44`
Expected first: FAIL (controller doesn't exist). After Step 2's code is in place: `Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors.

- [ ] **Step 6: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/api/CrossCurrencyTransferController.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/api/CrossCurrencyTransferControllerIntegrationTest.java
git commit -m "feat(ledger-service): add POST /transfers/cross-currency endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 12: Docker Compose — wire up `fx-service` + `fx-db`, gateway routes, seed clearing accounts

**Files:**
- Create: `fx-service/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `api-gateway/src/main/resources/application.yml`
- Modify: `ledger-service/Dockerfile`, `holds-service/Dockerfile`, `transaction-processor/Dockerfile`, `api-gateway/Dockerfile` (each needs `COPY fx-service/pom.xml fx-service/pom.xml` added — the exact gap Task 10 of the V2 plan hit and fixed for `holds-service`/`api-gateway`; do not repeat that mistake here)
- Create: `scripts/seed-clearing-accounts.sh`
- Modify: `scripts/provision.sh` (call the new seed script)

**Interfaces:**
- Consumes: everything from Tasks 1-11.
- Produces: a coherent 12-container Docker Compose stack (the V2 10 plus `fx-db` and `fx-service`).

- [ ] **Step 1: Create `fx-service/Dockerfile`**

Copy `holds-service/Dockerfile` exactly, changing only the module name and jar path references from `holds-service` to `fx-service`. Read `holds-service/Dockerfile` first to match its multi-stage build structure precisely (builder stage + runtime stage, reactor pom copies, etc.).

- [ ] **Step 2: Fix the reactor-validation Dockerfile gap proactively**

In `ledger-service/Dockerfile`, `holds-service/Dockerfile`, `transaction-processor/Dockerfile`, and `api-gateway/Dockerfile`, find the existing `COPY holds-service/pom.xml holds-service/pom.xml` and `COPY api-gateway/pom.xml api-gateway/pom.xml` lines (added during V2's Task 10 to fix Maven's reactor validation requiring all declared modules' pom.xml files to exist in the build context regardless of `-pl`). Add a matching `COPY fx-service/pom.xml fx-service/pom.xml` line alongside them in each of the four files. In the new `fx-service/Dockerfile` itself, add `COPY ledger-service/pom.xml`, `COPY transaction-processor/pom.xml`, `COPY holds-service/pom.xml`, `COPY api-gateway/pom.xml` lines matching the same pattern (every service's Dockerfile needs every other module's pom.xml present, since the root reactor pom lists all five modules and Maven validates all of them regardless of which one `-pl` targets).

- [ ] **Step 3: Add `fx-db` and `fx-service` to `docker-compose.yml`**

Model directly on the existing `holds-db`/`holds-service` blocks (read them first). Add:

```yaml
  fx-db:
    image: postgres:16.4
    environment:
      POSTGRES_DB: fx_db
      POSTGRES_USER: fx
      POSTGRES_PASSWORD: fx
    volumes:
      - fx-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U fx -d fx_db"]
      interval: 5s
      timeout: 5s
      retries: 10

  fx-service:
    build:
      context: .
      dockerfile: fx-service/Dockerfile
    environment:
      DB_HOST: fx-db
      DB_PORT: "5432"
      DB_NAME: fx_db
      DB_USER: fx
      DB_PASSWORD: fx
    ports:
      - "8083:8083"
    depends_on:
      fx-db:
        condition: service_healthy
```

Add `fx-pgdata:` to the existing top-level `volumes:` block (alongside `holds-pgdata`, etc.).

Modify `ledger-service`'s existing block: add `FX_SERVICE_URL: http://fx-service:8083` to its `environment:` block, and add `fx-service: condition: service_started` to its `depends_on:` block (Ledger Service now has a real runtime dependency on FX Service, exactly the class of dependency V2's Task 11 caught was missing for `holds-service` -> `ledger-service` — do not repeat that omission here).

- [ ] **Step 4: Add gateway routes**

In `api-gateway/src/main/resources/application.yml`, add to the existing `spring.cloud.gateway.routes` list:

```yaml
        - id: fx-rates
          uri: ${FX_SERVICE_URL:http://localhost:8083}
          predicates:
            - Path=/rates/**
        - id: fx-conversions
          uri: ${FX_SERVICE_URL:http://localhost:8083}
          predicates:
            - Path=/conversions/**
        - id: ledger-accounts
          uri: ${LEDGER_SERVICE_URL:http://localhost:8090}
          predicates:
            - Path=/accounts/**
        - id: ledger-wallets
          uri: ${LEDGER_SERVICE_URL:http://localhost:8090}
          predicates:
            - Path=/wallets/**
        - id: ledger-transfers
          uri: ${LEDGER_SERVICE_URL:http://localhost:8090}
          predicates:
            - Path=/transfers/**
```

**Route-ordering hazard, check before finalizing**: the existing route `holds-available-balance` matches `Path=/accounts/*/available-balance`. Spring Cloud Gateway evaluates routes in declared order and uses the first match — since `/accounts/*/available-balance` is a strict subset of the new `/accounts/**` pattern, `ledger-accounts` (routing to Ledger Service) must NOT be declared before `holds-available-balance` (routing to Holds Service), or every available-balance request will be silently misrouted to Ledger Service instead of Holds Service, which has no such endpoint. Declare `ledger-accounts` AFTER `holds-available-balance` in the routes list, and add a test (Step 6 below) proving both routes still go to the correct backend — do not just trust route ordering by inspection, verify it live.

Add `FX_SERVICE_URL: http://fx-service:8083` to `api-gateway`'s environment block in `docker-compose.yml`, and `fx-service: condition: service_started` to its `depends_on:`.

- [ ] **Step 5: Write `scripts/seed-clearing-accounts.sh`**

Read `scripts/provision.sh` first to match its existing style (bash, `set -euo pipefail`, curl-based, clear echo statements marking each step). The clearing accounts need to exist before any cross-currency transfer can succeed — seed them via `POST /accounts` (Task 7's endpoint) directly against Ledger Service (not through the gateway, matching how `provision.sh` already calls Ledger Service directly for its own health check, per the existing pattern).

```bash
#!/usr/bin/env bash
# scripts/seed-clearing-accounts.sh
# Creates the platform-owned FX clearing accounts if they don't already exist.
# Idempotent: safe to run multiple times (a 409 from an already-existing account is expected
# and ignored).
set -euo pipefail

LEDGER_URL="${LEDGER_URL:-http://localhost:8090}"

seed_account() {
  local account_ref=$1
  local currency=$2
  echo "Seeding clearing account: $account_ref ($currency)..."
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$LEDGER_URL/accounts" \
    -H "Content-Type: application/json" \
    -d "{\"accountRef\":\"$account_ref\",\"currency\":\"$currency\"}")
  if [ "$http_code" == "201" ]; then
    echo "  created."
  elif [ "$http_code" == "409" ]; then
    echo "  already exists, skipping."
  else
    echo "  ERROR: unexpected HTTP $http_code creating $account_ref" >&2
    exit 1
  fi
}

seed_account "fx-clearing-USD" "USD"
seed_account "fx-clearing-EUR" "EUR"
seed_account "fx-clearing-GBP" "GBP"

echo "Clearing accounts seeded."
```

Run: `chmod +x scripts/seed-clearing-accounts.sh`

- [ ] **Step 6: Call the seed script from `provision.sh` and add a route-ordering verification to `smoke-test.sh`**

Read `scripts/provision.sh` in full and add a call to `bash scripts/seed-clearing-accounts.sh` after Ledger Service is confirmed healthy (the same point in the sequence where the CDC grant/publication is set up, since both are "one-time platform setup after Ledger Service is up").

Add to `scripts/smoke-test.sh` (after existing checks, following its established style) a verification that both `/accounts/*/available-balance` (Holds Service) and `/accounts/{ref}` or `/accounts` (Ledger Service, if such a plain GET exists — if not, use `/accounts` creation's own 409-on-duplicate response as the proof) route correctly and are not confused with each other. At minimum: call `GET $GATEWAY_URL/accounts/smoke-a/available-balance` (existing Holds behavior, must still return 200 with a balance body) immediately after exercising the new `POST $GATEWAY_URL/accounts` (must return 201 or 409, never 404/wrong-service-error) — if the routing is broken, one of these two will fail or return an unexpected body shape.

- [ ] **Step 7: Bring up the full stack and verify manually**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
bash scripts/smoke-test.sh
```
Expected: all 12 containers start; `provision.sh` completes including the new clearing-account seeding step; `smoke-test.sh` passes including the new routing-disambiguation check.

Then manually verify the full cross-currency flow through the gateway with a real token:
```bash
TOKEN=$(bash scripts/get-token.sh client)
curl -X POST http://localhost:8080/accounts -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"accountRef":"fx-demo-usd","currency":"USD"}'
curl -X POST http://localhost:8080/accounts -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"accountRef":"fx-demo-eur","currency":"EUR"}'
# fx-demo-usd starts at 0 balance -- top it up via an ordinary transfer from an existing
# funded account first (e.g. smoke-a), or accept a 422 here and note it as expected until
# a funding step is added; do not silently skip verifying this end-to-end.
curl -X POST http://localhost:8080/transfers/cross-currency -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccountRef":"fx-demo-usd","destAccountRef":"fx-demo-eur","sourceAmountMinor":1000,"idempotencyKey":"manual-fx-test-1"}'
```
Expected: final call returns `200` with `"status":"COMPLETED"` (assuming `fx-demo-usd` has sufficient funded balance — fund it first via `POST /transactions` from `smoke-a` if needed) or a clear, correctly-attributed error if funds are insufficient (`422` from the insufficient-funds check inside leg 1, not a routing or 404 error).

Tear down: `docker compose down -v`.

- [ ] **Step 8: Commit**

```bash
git add fx-service/Dockerfile ledger-service/Dockerfile holds-service/Dockerfile \
        transaction-processor/Dockerfile api-gateway/Dockerfile docker-compose.yml \
        api-gateway/src/main/resources/application.yml \
        scripts/seed-clearing-accounts.sh scripts/provision.sh scripts/smoke-test.sh
git commit -m "feat: wire fx-service into Docker Compose, gateway routes, and provisioning

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: 6th chaos scenario — crash mid-saga

**Files:**
- Create: `chaos/scenarios/06_fx_saga_crash_mid_leg.sh`
- Modify: `chaos/lib/common.sh` (add any new helper needed, following its existing conventions)

**Interfaces:**
- Consumes: `scripts/get-token.sh`, `chaos/lib/common.sh`'s existing helpers, the live Docker Compose stack.
- Produces: nothing consumed by later tasks — this is a leaf verification artifact.

- [ ] **Step 1: Read the existing chaos scenario pattern**

Read `chaos/scenarios/03_processor_crash_mid_consume.sh` in full (the closest existing precedent: kill a container mid-flow, verify recovery) and `chaos/lib/common.sh` in full, to match their exact style, helper usage, and PASS/FAIL reporting convention before writing anything new.

- [ ] **Step 2: Write the new scenario**

Structure (following `03_processor_crash_mid_consume.sh`'s pattern exactly, adapted for this saga):
1. Seed/confirm two funded accounts in different currencies (reuse `seed-clearing-accounts.sh`'s accounts plus two demo accounts) and confirm clearing accounts exist.
2. Fire `POST /transfers/cross-currency` in the background (or use a Toxiproxy `timeout` toxic on Ledger Service's own DB connection, timed via a `latency` toxic first, to land a restart between leg 1 and leg 2 — follow whichever timing mechanism `03_processor_crash_mid_consume.sh` uses, since Ledger Service already has a Toxiproxy-fronted DB connection from V1).
3. Restart `ledger-service` (`docker compose restart ledger-service`) at the tuned delay.
4. Poll `pending_fx_transfers` (via a small diagnostic query through `docker compose exec ledger-postgres psql` — or, if a lookup endpoint exists, prefer that; if not, direct SQL is acceptable and consistent with how other chaos scripts verify DB state) until status reaches `COMPLETED` or `COMPENSATED`, with a bounded retry loop (mirror the exact retry-loop shape used elsewhere in this codebase, e.g. `smoke-test.sh`'s hold-flow retry).
5. Assert final state is one of the two terminal statuses (never left in `LEG1_POSTED`/`PENDING`/`COMPENSATING` after the sweep has had time to run), and assert account balances are internally consistent (source debited exactly once, dest credited exactly once XOR compensation fully reversed the debit — never both, never neither).
6. Print `PASS:` or `FAIL:` matching the existing scripts' exact output convention, and exit non-zero on failure.

Write the actual bash script now, following the structural template above but with real, runnable commands — do not leave this as a step description; produce the complete script file content as this task's deliverable.

- [ ] **Step 3: Run the new scenario against the live stack**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
bash chaos/scenarios/06_fx_saga_crash_mid_leg.sh
```
Expected: `PASS:` printed, exit code 0. If it fails, debug via `docker compose logs ledger-service` and direct `psql` inspection of `pending_fx_transfers` — do not adjust the assertion to match unexpected behavior without first confirming whether the unexpected behavior is a real bug or a timing issue in the script's own restart-delay tuning.

- [ ] **Step 4: Re-run all 6 chaos scenarios together to confirm no regression**

```bash
bash chaos/scenarios/01_rabbitmq_down_mid_publish.sh
bash chaos/scenarios/02_ledger_db_crash_post_commit.sh
bash chaos/scenarios/03_processor_crash_mid_consume.sh
bash chaos/scenarios/04_duplicate_delivery.sh
bash chaos/scenarios/05_partition_during_lock.sh
bash chaos/scenarios/06_fx_saga_crash_mid_leg.sh
```
Expected: all 6 print `PASS:`.

Tear down: `docker compose down -v`.

- [ ] **Step 5: Commit**

```bash
git add chaos/scenarios/06_fx_saga_crash_mid_leg.sh chaos/lib/common.sh
git commit -m "test: add 6th chaos scenario for FX saga crash recovery

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 14: README update and final V3 acceptance verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-13.
- Produces: the final, complete README for the whole V1+V2+V3 platform — the last task of this plan.

- [ ] **Step 1: Update the README**

Read the current `README.md` in full first (documents V1+V2). Update it to reflect V3:

- **Architecture**: add FX Service (`:8083`) to the service list. Note that Ledger Service now supports multi-currency accounts grouped into wallets.
- **What this demonstrates**: add "Cross-currency transfers via a two-legged saga with compensation" and "External rate integration (Frankfurter, free ECB-backed API) with staleness detection and last-known-good fallback."
- **Running locally**: note the new `scripts/seed-clearing-accounts.sh` step (called automatically by `provision.sh` now).
- **API**: add `POST /accounts`, `GET /wallets/{groupId}/accounts`, `POST /transfers/cross-currency`, `GET /rates/{base}/{quote}`, `POST /conversions/quote` (this last one is FX Service's own endpoint, not gateway-routed to Ledger — clarify in the README which service owns which of the new endpoints, since Tasks 5 and 7-11 split them across two services).
- **Known limitations**: add — no dead-letter/manual-intervention path for a saga stuck in `COMPENSATING` indefinitely (documented, accepted gap per the spec); Frankfurter coverage is limited to major currencies (USD/EUR/GBP seeded in this project); clearing accounts are platform-internal netting accounts allowed to run negative balances by convention (`fx-clearing-*` account-ref prefix), not real funded accounts.

- [ ] **Step 2: Run the complete Maven test suite one final time**

Run: `mvn clean verify -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`)
Expected: `BUILD SUCCESS` across all five modules (ledger-service, transaction-processor, holds-service, api-gateway, fx-service) — every unit and Testcontainers integration test from Tasks 1-13 passes.

- [ ] **Step 3: Run the full Docker Compose + smoke test + all 6 chaos scenarios one final time**

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
```
Expected: all green. Then manually verify the full cross-currency flow through the gateway one final time (same commands as Task 12 Step 7), confirming a `COMPLETED` result end-to-end after the README update.

Tear down: `docker compose down -v`.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: update README for V3 (FX Service, multi-currency, cross-currency transfers)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
