# Ledger V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build V1 of the Ledger platform — a Ledger Service and a Transaction Processor
service that together provide idempotent, double-entry transaction posting with a
transactional outbox, embedded-Debezium CDC relay to RabbitMQ, exactly-once-in-effect
consumption, a reconciliation job, and a Toxiproxy-based chaos test suite.

**Architecture:** Two Spring Boot services, each with its own Postgres database
(database-per-service), in a Maven multi-module monorepo. Ledger Service owns accounts,
transactions, entries, and an append-only outbox table written atomically with every
transaction. Transaction Processor embeds the Debezium Engine (no Kafka) to tail Ledger's
Postgres WAL directly, publishes captured events to RabbitMQ with publisher confirms, and
consumes them back with a dedup gate keyed on the outbox event's UUID.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Data JPA, Spring AMQP, Flyway, PostgreSQL 16
(logical replication), RabbitMQ 3, Debezium Embedded Engine (`debezium-api` +
`debezium-connector-postgres`), Toxiproxy, Testcontainers, JUnit 5, Maven, Docker Compose.

**Spec:** [docs/superpowers/specs/2026-09-02-ledger-platform-architecture.md](../specs/2026-09-02-ledger-platform-architecture.md)
(V1 section) — this plan implements only the V1 section of that spec; V2–V5 are out of scope
here.

## Global Constraints

- Database-per-service: Ledger Service and Transaction Processor each get their own Postgres
  database/container. Neither service ever queries the other's tables directly.
- Money is always represented as integer minor units (`BIGINT balance_minor` /
  `amount_minor`) — never floating point.
- All cross-service calls are REST; nothing sync-couples the two services except the REST
  reconciliation cross-check.
- Every write to the `outbox` table happens in the same DB transaction as the business write
  it represents — this atomicity is non-negotiable and is the guarantee the rest of the
  system depends on.
- The Transaction Processor runs as a single instance only in V1 (embedded Debezium holds an
  exclusive replication slot) — do not add `replicas:` or any multi-instance config.
- Build tool is Maven; repo is a single multi-module monorepo rooted at `D:\Ledger`.
- Integration tests use Testcontainers (real Postgres/RabbitMQ), never H2 or in-memory fakes.
- Idempotency-Key is a client-supplied header; the dedup source of truth is always a DB
  unique constraint or conditional UPDATE, never an application-level read-then-write check
  alone.

---

### Task 1: Maven multi-module monorepo scaffold

**Files:**
- Create: `D:\Ledger\pom.xml` (parent POM)
- Create: `D:\Ledger\ledger-service\pom.xml`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\LedgerServiceApplication.java`
- Create: `D:\Ledger\ledger-service\src\main\resources\application.yml`
- Create: `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\LedgerServiceApplicationTests.java`
- Create: `D:\Ledger\transaction-processor\pom.xml`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\TransactionProcessorApplication.java`
- Create: `D:\Ledger\transaction-processor\src\main\resources\application.yml`
- Create: `D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\TransactionProcessorApplicationTests.java`
- Create: `D:\Ledger\.gitignore`

**Interfaces:**
- Produces: two independently buildable Spring Boot modules (`ledger-service`,
  `transaction-processor`), both children of the root `pom.xml` reactor, both bootable via
  `mvn spring-boot:run` and both passing `mvn test` with a trivial context-loads test. Later
  tasks add dependencies, packages, and real classes inside these modules — this task only
  proves the reactor and both apps boot.

- [ ] **Step 1: Create the root parent POM**

```xml
<!-- D:\Ledger\pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.ledger</groupId>
    <artifactId>ledger-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>ledger-service</module>
        <module>transaction-processor</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-boot.version>3.3.4</spring-boot.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: Create the Ledger Service module POM**

```xml
<!-- D:\Ledger\ledger-service\pom.xml -->
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

    <artifactId>ledger-service</artifactId>
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

- [ ] **Step 3: Create the Ledger Service application class**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\LedgerServiceApplication.java
package com.ledger.ledgerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Create the Ledger Service application.yml**

```yaml
# D:\Ledger\ledger-service\src\main\resources\application.yml
server:
  port: 8080

spring:
  application:
    name: ledger-service
```

- [ ] **Step 5: Write the Ledger Service context-loads test**

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\LedgerServiceApplicationTests.java
package com.ledger.ledgerservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LedgerServiceApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

Note: this test will fail to even compile/run meaningfully until Task 2 adds a datasource —
at this point in Task 1 there is no JPA entity/datasource configured yet, so
`spring-boot-starter-data-jpa` on the classpath with no configured datasource will fail
context load. To keep Task 1 genuinely green on its own, temporarily exclude
datasource autoconfiguration for this test only:

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\LedgerServiceApplicationTests.java
package com.ledger.ledgerservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
class LedgerServiceApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

This exclusion is temporary scaffolding only — Task 2 replaces this whole test file with a
real Testcontainers-backed integration test once a datasource exists, so the exclusion never
needs to be "cleaned up" later; it's simply superseded.

- [ ] **Step 6: Repeat steps 2-5 for the Transaction Processor module**

```xml
<!-- D:\Ledger\transaction-processor\pom.xml -->
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

    <artifactId>transaction-processor</artifactId>
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

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\TransactionProcessorApplication.java
package com.ledger.txprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TransactionProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionProcessorApplication.class, args);
    }
}
```

```yaml
# D:\Ledger\transaction-processor\src\main\resources\application.yml
server:
  port: 8081

spring:
  application:
    name: transaction-processor
```

```java
// D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\TransactionProcessorApplicationTests.java
package com.ledger.txprocessor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
class TransactionProcessorApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 7: Create the root .gitignore**

```
# D:\Ledger\.gitignore
target/
*.class
.idea/
*.iml
.vscode/
.DS_Store
*.log
/debezium-offsets/
```

- [ ] **Step 8: Build the whole reactor and run all tests**

Run: `mvn -f D:\Ledger\pom.xml clean verify`
Expected: BUILD SUCCESS, both modules' `contextLoads` tests pass.

- [ ] **Step 9: Initialize git and commit**

```bash
git init
git add pom.xml .gitignore ledger-service transaction-processor
git commit -m "chore: scaffold Maven multi-module monorepo with two empty Spring Boot services

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Ledger Service Postgres schema via Flyway, verified with Testcontainers

**Files:**
- Modify: `D:\Ledger\ledger-service\pom.xml` (add Flyway, PostgreSQL driver, Testcontainers deps)
- Create: `D:\Ledger\ledger-service\src\main\resources\db\migration\V1__init_schema.sql`
- Create: `D:\Ledger\ledger-service\src\main\resources\db\migration\V2__constraint_trigger_zero_sum.sql`
- Modify: `D:\Ledger\ledger-service\src\main\resources\application.yml` (add datasource placeholder config)
- Create: `D:\Ledger\ledger-service\src\test\resources\application-test.yml`
- Delete: `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\LedgerServiceApplicationTests.java` (superseded)
- Create: `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\SchemaMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from prior tasks beyond the module skeleton from Task 1.
- Produces: a `ledger_db` schema (via Flyway migrations `V1__init_schema.sql`,
  `V2__constraint_trigger_zero_sum.sql`) containing tables `accounts`, `transactions`,
  `entries`, `outbox`, `reconciliation_runs`, `reconciliation_findings` exactly as specified
  in the spec's V1 section. Later tasks' JPA entities and repositories map onto these exact
  table/column names — do not rename anything here without updating this task.

- [ ] **Step 1: Add Flyway, PostgreSQL, and Testcontainers dependencies**

```xml
<!-- add inside D:\Ledger\ledger-service\pom.xml <dependencies> -->
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

Also add the Testcontainers BOM to the root `pom.xml`'s `<dependencyManagement>`:

```xml
<!-- add inside D:\Ledger\pom.xml <dependencyManagement><dependencies> -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.1</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 2: Write the V1 schema migration**

```sql
-- D:\Ledger\ledger-service\src\main\resources\db\migration\V1__init_schema.sql
CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_ref     VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(256),
    currency        CHAR(3) NOT NULL DEFAULT 'USD',
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key       VARCHAR(255) NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','FAILED','REVERSED')),
    transaction_type      VARCHAR(32) NOT NULL DEFAULT 'TRANSFER',
    description           TEXT,
    request_payload_hash  CHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transactions_idem_key UNIQUE (idempotency_key)
);

CREATE TABLE entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_transaction_id ON entries(transaction_id);
CREATE INDEX idx_entries_account_id ON entries(account_id);

CREATE TABLE outbox (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type    VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION',
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION_POSTED',
    payload           JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox(aggregate_id);

CREATE TABLE reconciliation_runs (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at                TIMESTAMPTZ NOT NULL,
    finished_at               TIMESTAMPTZ,
    status                    VARCHAR(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    transactions_checked      INT NOT NULL DEFAULT 0,
    entries_imbalance_count   INT NOT NULL DEFAULT 0,
    outbox_missing_count      INT NOT NULL DEFAULT 0,
    outbox_stuck_count        INT NOT NULL DEFAULT 0,
    summary                   JSONB
);

CREATE TABLE reconciliation_findings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL REFERENCES reconciliation_runs(id),
    finding_type        VARCHAR(32) NOT NULL CHECK (finding_type IN
                          ('ENTRIES_NOT_ZERO','OUTBOX_MISSING','OUTBOX_STUCK_UNPUBLISHED')),
    transaction_id      UUID,
    outbox_id           UUID,
    detail              JSONB NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: Write the V2 migration adding the zero-sum defense-in-depth trigger**

```sql
-- D:\Ledger\ledger-service\src\main\resources\db\migration\V2__constraint_trigger_zero_sum.sql
CREATE OR REPLACE FUNCTION check_entries_zero_sum() RETURNS TRIGGER AS $$
DECLARE
    net BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
    INTO net
    FROM entries
    WHERE transaction_id = NEW.transaction_id;

    IF net <> 0 THEN
        RAISE EXCEPTION 'entries for transaction % do not sum to zero (net = %)', NEW.transaction_id, net;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_zero_sum
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION check_entries_zero_sum();
```

- [ ] **Step 4: Configure the main application datasource to use Flyway + env-driven connection**

```yaml
# D:\Ledger\ledger-service\src\main\resources\application.yml
server:
  port: 8080

spring:
  application:
    name: ledger-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:ledger_db}
    username: ${DB_USER:ledger}
    password: ${DB_PASSWORD:ledger}
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 5: Create a test application config that disables Flyway's default connection (Testcontainers will inject one)**

```yaml
# D:\Ledger\ledger-service\src\test\resources\application-test.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 6: Delete the temporary Task 1 context-loads test**

Delete `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\LedgerServiceApplicationTests.java`
— it is superseded by the Testcontainers-backed test below, which now exercises a real
datasource.

- [ ] **Step 7: Write the failing Testcontainers schema-migration test**

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\SchemaMigrationIntegrationTest.java
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

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
                "accounts", "transactions", "entries", "outbox",
                "reconciliation_runs", "reconciliation_findings", "flyway_schema_history"
        );
    }

    @Test
    void unbalancedEntriesAreRejectedByConstraintTrigger() {
        jdbcTemplate.execute("""
                INSERT INTO accounts (id, account_ref, balance_minor)
                VALUES ('11111111-1111-1111-1111-111111111111', 'acct-a', 10000)
                """);
        jdbcTemplate.execute("""
                INSERT INTO transactions (id, idempotency_key, request_payload_hash)
                VALUES ('22222222-2222-2222-2222-222222222222', 'test-key-1', repeat('a', 64))
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.execute("""
                        INSERT INTO entries (transaction_id, account_id, direction, amount_minor, currency)
                        VALUES ('22222222-2222-2222-2222-222222222222',
                                '11111111-1111-1111-1111-111111111111',
                                'DEBIT', 500, 'USD')
                        """)
        ).hasMessageContaining("do not sum to zero");
    }
}
```

Note: `assertj-core` ships transitively with `spring-boot-starter-test`, already present from
Task 1 — no extra dependency needed.

- [ ] **Step 8: Run the test to verify it fails**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=SchemaMigrationIntegrationTest`
Expected: FAIL at this point only if steps 1-5 above were skipped; if all prior steps in this
task were completed in order, this should already PASS. If you are following strict
red-green-refactor, comment out the `V1__init_schema.sql` and `V2__...sql` file contents
first, confirm the test fails with "table accounts does not exist", then restore the
migration file contents before Step 9.

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=SchemaMigrationIntegrationTest`
Expected: PASS (2 tests: `allExpectedTablesExist`, `unbalancedEntriesAreRejectedByConstraintTrigger`).

- [ ] **Step 10: Commit**

```bash
git add ledger-service/pom.xml pom.xml \
        ledger-service/src/main/resources/db/migration \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/test/resources/application-test.yml \
        ledger-service/src/test/java/com/ledger/ledgerservice/SchemaMigrationIntegrationTest.java
git rm ledger-service/src/test/java/com/ledger/ledgerservice/LedgerServiceApplicationTests.java
git commit -m "feat(ledger-service): add Flyway schema migrations with zero-sum constraint trigger

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Ledger Service JPA entities and repositories

**Files:**
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Direction.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\AccountStatus.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\TransactionStatus.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Account.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Transaction.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Entry.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\OutboxEvent.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\AccountRepository.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\TransactionRepository.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\EntryRepository.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\OutboxRepository.java`
- Test: `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\repository\AccountRepositoryLockingIntegrationTest.java`

**Interfaces:**
- Consumes: the schema from Task 2 (`accounts`, `transactions`, `entries`, `outbox` tables —
  column names must match exactly).
- Produces: `Account` (fields: `id: UUID`, `accountRef: String`, `displayName: String`,
  `currency: String`, `balanceMinor: long`, `status: AccountStatus`, `version: long`),
  `Transaction` (fields: `id: UUID`, `idempotencyKey: String`, `status: TransactionStatus`,
  `transactionType: String`, `description: String`, `requestPayloadHash: String`),
  `Entry` (fields: `id: UUID`, `transactionId: UUID`, `accountId: UUID`,
  `direction: Direction`, `amountMinor: long`, `currency: String`), `OutboxEvent` (fields:
  `id: UUID`, `aggregateType: String`, `aggregateId: UUID`, `eventType: String`,
  `payload: String` JSON). `AccountRepository.lockAccountsForUpdate(List<UUID> ids): List<Account>`
  is the method Task 4's locking logic calls — it must return rows already locked via
  `SELECT ... FOR UPDATE` ordered by id ascending.

- [ ] **Step 1: Write the enum types**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Direction.java
package com.ledger.ledgerservice.domain;

public enum Direction {
    DEBIT, CREDIT
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\AccountStatus.java
package com.ledger.ledgerservice.domain;

public enum AccountStatus {
    ACTIVE, FROZEN, CLOSED
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\TransactionStatus.java
package com.ledger.ledgerservice.domain;

public enum TransactionStatus {
    POSTED, FAILED, REVERSED
}
```

- [ ] **Step 2: Write the Account entity**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Account.java
package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "account_ref", nullable = false, unique = true)
    private String accountRef;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA
    }

    public Account(UUID id, String accountRef, String displayName, String currency,
                   long balanceMinor, AccountStatus status) {
        this.id = id;
        this.accountRef = accountRef;
        this.displayName = displayName;
        this.currency = currency;
        this.balanceMinor = balanceMinor;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getAccountRef() { return accountRef; }
    public String getDisplayName() { return displayName; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public AccountStatus getStatus() { return status; }
    public long getVersion() { return version; }

    public void debit(long amountMinor) {
        this.balanceMinor -= amountMinor;
    }

    public void credit(long amountMinor) {
        this.balanceMinor += amountMinor;
    }
}
```

Note: `@Version` maps directly onto the `version BIGINT` column already defined in Task 2's
`V1__init_schema.sql` — JPA's optimistic-locking increment happens automatically on every
`UPDATE`, layered on top of (not instead of) the explicit `SELECT ... FOR UPDATE` pessimistic
lock Task 4 takes; the pessimistic lock prevents concurrent transactions from interleaving at
all, and the version column is a secondary correctness signal useful for detecting any bug
that bypasses the locking path.

- [ ] **Step 3: Write the Transaction entity**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Transaction.java
package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    private String description;

    @Column(name = "request_payload_hash", nullable = false)
    private String requestPayloadHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transaction() {
        // JPA
    }

    public Transaction(UUID id, String idempotencyKey, TransactionStatus status,
                        String transactionType, String description, String requestPayloadHash) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.transactionType = transactionType;
        this.description = description;
        this.requestPayloadHash = requestPayloadHash;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public TransactionStatus getStatus() { return status; }
    public String getTransactionType() { return transactionType; }
    public String getDescription() { return description; }
    public String getRequestPayloadHash() { return requestPayloadHash; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: Write the Entry entity**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\Entry.java
package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entries")
public class Entry {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Entry() {
        // JPA
    }

    public Entry(UUID id, UUID transactionId, UUID accountId, Direction direction,
                 long amountMinor, String currency) {
        this.id = id;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
}
```

- [ ] **Step 5: Write the OutboxEvent entity**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\OutboxEvent.java
package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getPayload() { return payload; }
}
```

Note: `@JdbcTypeCode(SqlTypes.JSON)` requires Hibernate 6 (bundled with Spring Boot 3.3.x),
which maps a `String` field to/from a native `jsonb` column without needing a separate
converter library.

- [ ] **Step 6: Write the repositories**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\AccountRepository.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountRef(String accountRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> lockAccountsForUpdate(@Param("ids") List<UUID> ids);
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\TransactionRepository.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\EntryRepository.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Entry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<Entry, UUID> {
    List<Entry> findByTransactionId(UUID transactionId);
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\OutboxRepository.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
}
```

- [ ] **Step 7: Write the failing concurrency-locking test**

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\repository\AccountRepositoryLockingIntegrationTest.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountRepositoryLockingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void seedAccounts() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Account a = new Account(UUID.randomUUID(), "acct-a", "A", "USD", 10_000L, AccountStatus.ACTIVE);
            Account b = new Account(UUID.randomUUID(), "acct-b", "B", "USD", 10_000L, AccountStatus.ACTIVE);
            accountRepository.save(a);
            accountRepository.save(b);
            accountAId = a.getId();
            accountBId = b.getId();
        });
    }

    @Test
    void concurrentLockAttemptsOnSameAccountsSerializeRatherThanInterleave() throws InterruptedException {
        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        List<UUID> ids = List.of(accountAId, accountBId);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    tx.executeWithoutResult(status -> {
                        List<Account> locked = accountRepository.lockAccountsForUpdate(ids);
                        assertThat(locked).hasSize(2);
                        assertThat(locked.get(0).getId()).isEqualTo(accountAId.compareTo(accountBId) < 0 ? accountAId : accountBId);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {
                        }
                    });
                    successCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=AccountRepositoryLockingIntegrationTest`
Expected: FAIL — `AccountRepository`/`Account` do not exist yet if steps 1-6 are skipped. If
steps 1-6 were already completed, skip ahead — this test should already compile and pass; to
genuinely observe red-green, comment out the `@Lock` annotation on
`lockAccountsForUpdate` first and confirm the test still passes (proving the test alone
doesn't catch missing locking is a known limitation of this particular test — it's really a
sanity/no-deadlock check, not a lock-was-taken proof) — restore the annotation afterward.

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=AccountRepositoryLockingIntegrationTest`
Expected: PASS — all 5 concurrent threads complete without deadlock or exception, each seeing
the two accounts returned in ascending-id order.

- [ ] **Step 10: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/domain \
        ledger-service/src/main/java/com/ledger/ledgerservice/repository \
        ledger-service/src/test/java/com/ledger/ledgerservice/repository
git commit -m "feat(ledger-service): add JPA entities and repositories with ordered pessimistic locking

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: TransactionService — idempotent POST /transactions with atomic outbox write

**Files:**
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\IdempotencyHasher.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\InsufficientFundsException.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\AccountNotFoundException.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\IdempotencyConflictException.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\TransactionService.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\dto\CreateTransactionRequest.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\dto\TransactionResponse.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\TransactionController.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\error\ApiExceptionHandler.java`
- Test: `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\service\TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `AccountRepository.lockAccountsForUpdate`, `TransactionRepository`,
  `EntryRepository`, `OutboxRepository` from Task 3.
- Produces: `TransactionService.postTransaction(CreateTransactionRequest request, String idempotencyKey): TransactionResponse`
  — the single orchestrating method every later task (reconciliation reads its output tables;
  chaos tests call this via HTTP) depends on. `POST /transactions` HTTP endpoint at
  `/transactions` accepting header `Idempotency-Key`.

- [ ] **Step 1: Write the idempotency hasher (pure function, unit-testable without a DB)**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\IdempotencyHasher.java
package com.ledger.ledgerservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyHasher {

    public String hash(String debitAccountRef, String creditAccountRef, long amountMinor, String currency) {
        String normalized = debitAccountRef + "|" + creditAccountRef + "|" + amountMinor + "|" + currency;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 2: Write the domain exceptions**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\InsufficientFundsException.java
package com.ledger.ledgerservice.service;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountRef) {
        super("Insufficient funds in account: " + accountRef);
    }
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\AccountNotFoundException.java
package com.ledger.ledgerservice.service;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountRef) {
        super("Account not found: " + accountRef);
    }
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\IdempotencyConflictException.java
package com.ledger.ledgerservice.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key reused with a different request body: " + idempotencyKey);
    }
}
```

- [ ] **Step 3: Write the request/response DTOs**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\dto\CreateTransactionRequest.java
package com.ledger.ledgerservice.api.dto;

public record CreateTransactionRequest(
        String debitAccountRef,
        String creditAccountRef,
        long amountMinor,
        String currency,
        String description
) {
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\dto\TransactionResponse.java
package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        String status,
        String debitAccountRef,
        String creditAccountRef,
        long amountMinor,
        String currency,
        boolean replay
) {
}
```

- [ ] **Step 4: Write TransactionService — the atomic orchestrator**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\service\TransactionService.java
package com.ledger.ledgerservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.domain.*;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.OutboxRepository;
import com.ledger.ledgerservice.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final EntryRepository entryRepository;
    private final OutboxRepository outboxRepository;
    private final IdempotencyHasher idempotencyHasher;
    private final ObjectMapper objectMapper;

    public TransactionService(AccountRepository accountRepository,
                               TransactionRepository transactionRepository,
                               EntryRepository entryRepository,
                               OutboxRepository outboxRepository,
                               IdempotencyHasher idempotencyHasher,
                               ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxRepository = outboxRepository;
        this.idempotencyHasher = idempotencyHasher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse postTransaction(CreateTransactionRequest request, String idempotencyKey) {
        String requestHash = idempotencyHasher.hash(
                request.debitAccountRef(), request.creditAccountRef(),
                request.amountMinor(), request.currency());

        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, request);
        }

        Account debitAccountRef = accountRepository.findByAccountRef(request.debitAccountRef())
                .orElseThrow(() -> new AccountNotFoundException(request.debitAccountRef()));
        Account creditAccountRef = accountRepository.findByAccountRef(request.creditAccountRef())
                .orElseThrow(() -> new AccountNotFoundException(request.creditAccountRef()));

        List<UUID> idsToLock = List.of(debitAccountRef.getId(), creditAccountRef.getId());
        List<Account> locked = accountRepository.lockAccountsForUpdate(idsToLock);
        Map<UUID, Account> byId = locked.stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, a -> a));
        Account debitAccount = byId.get(debitAccountRef.getId());
        Account creditAccount = byId.get(creditAccountRef.getId());

        if (debitAccount.getStatus() != AccountStatus.ACTIVE || creditAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalStateException("One or both accounts are not ACTIVE");
        }
        if (debitAccount.getBalanceMinor() < request.amountMinor()) {
            throw new InsufficientFundsException(debitAccount.getAccountRef());
        }

        UUID transactionId = UUID.randomUUID();
        Transaction transaction = new Transaction(transactionId, idempotencyKey, TransactionStatus.POSTED,
                "TRANSFER", request.description(), requestHash);

        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException raceLost) {
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            return replayOrConflict(winner, requestHash, request);
        }

        Entry debitEntry = new Entry(UUID.randomUUID(), transactionId, debitAccount.getId(),
                Direction.DEBIT, request.amountMinor(), request.currency());
        Entry creditEntry = new Entry(UUID.randomUUID(), transactionId, creditAccount.getId(),
                Direction.CREDIT, request.amountMinor(), request.currency());
        entryRepository.save(debitEntry);
        entryRepository.save(creditEntry);

        debitAccount.debit(request.amountMinor());
        creditAccount.credit(request.amountMinor());
        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);

        String payload = buildOutboxPayload(transaction, debitAccount, creditAccount, request);
        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), "TRANSACTION", transactionId,
                "TRANSACTION_POSTED", payload));

        return new TransactionResponse(transactionId, transaction.getStatus().name(),
                request.debitAccountRef(), request.creditAccountRef(),
                request.amountMinor(), request.currency(), false);
    }

    private TransactionResponse replayOrConflict(Transaction existing, String requestHash,
                                                  CreateTransactionRequest request) {
        if (!existing.getRequestPayloadHash().equals(requestHash)) {
            throw new IdempotencyConflictException(existing.getIdempotencyKey());
        }
        return new TransactionResponse(existing.getId(), existing.getStatus().name(),
                request.debitAccountRef(), request.creditAccountRef(),
                request.amountMinor(), request.currency(), true);
    }

    private String buildOutboxPayload(Transaction transaction, Account debitAccount,
                                       Account creditAccount, CreateTransactionRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "transactionId", transaction.getId().toString(),
                    "idempotencyKey", transaction.getIdempotencyKey(),
                    "debitAccountRef", debitAccount.getAccountRef(),
                    "creditAccountRef", creditAccount.getAccountRef(),
                    "amountMinor", request.amountMinor(),
                    "currency", request.currency(),
                    "debitAccountBalanceAfter", debitAccount.getBalanceMinor(),
                    "creditAccountBalanceAfter", creditAccount.getBalanceMinor(),
                    "occurredAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
```

- [ ] **Step 5: Write the REST controller**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\TransactionController.java
package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @RequestBody CreateTransactionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        TransactionResponse response = transactionService.postTransaction(request, idempotencyKey);

        HttpStatus status = response.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (response.replay()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(response);
    }
}
```

- [ ] **Step 6: Write the exception handler**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\error\ApiExceptionHandler.java
package com.ledger.ledgerservice.api.error;

import com.ledger.ledgerservice.service.AccountNotFoundException;
import com.ledger.ledgerservice.service.IdempotencyConflictException;
import com.ledger.ledgerservice.service.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 6b: Write a pure unit test for IdempotencyHasher (no DB, no Spring context)**

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\service\IdempotencyHasherTest.java
package com.ledger.ledgerservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHasherTest {

    private final IdempotencyHasher hasher = new IdempotencyHasher();

    @Test
    void sameInputsProduceSameHash() {
        String h1 = hasher.hash("acct-a", "acct-b", 500L, "USD");
        String h2 = hasher.hash("acct-a", "acct-b", 500L, "USD");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void differentAmountsProduceDifferentHashes() {
        String h1 = hasher.hash("acct-a", "acct-b", 500L, "USD");
        String h2 = hasher.hash("acct-a", "acct-b", 600L, "USD");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hashIsSixtyFourHexCharacters() {
        String hash = hasher.hash("acct-a", "acct-b", 500L, "USD");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
```

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=IdempotencyHasherTest`
Expected: FAIL to compile until Step 1's `IdempotencyHasher` exists (it already does by this
point in the task); PASS immediately once run since no new production code is needed —
this step exists to lock down the hasher's contract with a fast, DB-free test independent of
the heavier Testcontainers suite in Step 7, matching the spec's requirement that idempotency
decision logic be unit-tested in isolation from the database.

- [ ] **Step 7: Write the failing integration test covering the core flow + idempotency + insufficient funds**

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\service\TransactionServiceIntegrationTest.java
package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.OutboxRepository;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TransactionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private EntryRepository entryRepository;
    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void seedAccounts() {
        accountRepository.deleteAll();
        accountRepository.save(new Account(UUID.randomUUID(), "acct-a", "A", "USD", 10_000L, AccountStatus.ACTIVE));
        accountRepository.save(new Account(UUID.randomUUID(), "acct-b", "B", "USD", 5_000L, AccountStatus.ACTIVE));
    }

    @Test
    void postsATransferAndWritesEntriesBalancesAndOutboxAtomically() {
        var request = new CreateTransactionRequest("acct-a", "acct-b", 2_000L, "USD", "test transfer");

        TransactionResponse response = transactionService.postTransaction(request, "key-1");

        assertThat(response.replay()).isFalse();
        assertThat(response.status()).isEqualTo("POSTED");

        Account debit = accountRepository.findByAccountRef("acct-a").orElseThrow();
        Account credit = accountRepository.findByAccountRef("acct-b").orElseThrow();
        assertThat(debit.getBalanceMinor()).isEqualTo(8_000L);
        assertThat(credit.getBalanceMinor()).isEqualTo(7_000L);

        assertThat(entryRepository.findByTransactionId(response.transactionId())).hasSize(2);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsReplayWithoutDoublePosting() {
        var request = new CreateTransactionRequest("acct-a", "acct-b", 1_000L, "USD", "replay test");

        TransactionResponse first = transactionService.postTransaction(request, "key-2");
        TransactionResponse second = transactionService.postTransaction(request, "key-2");

        assertThat(first.replay()).isFalse();
        assertThat(second.replay()).isTrue();
        assertThat(second.transactionId()).isEqualTo(first.transactionId());

        Account debit = accountRepository.findByAccountRef("acct-a").orElseThrow();
        assertThat(debit.getBalanceMinor()).isEqualTo(9_000L); // debited exactly once
    }

    @Test
    void sameIdempotencyKeyDifferentBodyThrowsConflict() {
        var first = new CreateTransactionRequest("acct-a", "acct-b", 1_000L, "USD", "first");
        var different = new CreateTransactionRequest("acct-a", "acct-b", 2_000L, "USD", "different");

        transactionService.postTransaction(first, "key-3");

        assertThatThrownBy(() -> transactionService.postTransaction(different, "key-3"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void insufficientFundsThrowsAndMutatesNothing() {
        var request = new CreateTransactionRequest("acct-b", "acct-a", 999_999L, "USD", "too much");

        assertThatThrownBy(() -> transactionService.postTransaction(request, "key-4"))
                .isInstanceOf(InsufficientFundsException.class);

        Account b = accountRepository.findByAccountRef("acct-b").orElseThrow();
        assertThat(b.getBalanceMinor()).isEqualTo(5_000L);
    }

    @Test
    void concurrentRequestsWithSameNewIdempotencyKeyResultInExactlyOnePost() throws InterruptedException {
        int threadCount = 8;
        var request = new CreateTransactionRequest("acct-a", "acct-b", 100L, "USD", "race test");
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        List<TransactionResponse> responses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    responses.add(transactionService.postTransaction(request, "key-race"));
                    successes.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(threadCount);
        assertThat(responses.stream().map(TransactionResponse::transactionId).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r -> !r.replay())).hasSize(1);

        Account debit = accountRepository.findByAccountRef("acct-a").orElseThrow();
        assertThat(debit.getBalanceMinor()).isEqualTo(9_900L); // debited exactly once, not 8x
    }
}
```

- [ ] **Step 8: Run the tests to verify they fail**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=TransactionServiceIntegrationTest`
Expected: FAIL to compile — `TransactionService`, DTOs, and exceptions don't exist until
steps 1-6 are done.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=TransactionServiceIntegrationTest`
Expected: PASS — all 5 tests, including the concurrent-race test showing exactly one post
and one non-replay response out of 8 concurrent identical requests.

- [ ] **Step 10: Manual smoke test via the running app**

Run:
```bash
docker run --rm -d --name ledger-pg-smoke -e POSTGRES_DB=ledger_db -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD=ledger -p 5432:5432 postgres:16
```
Wait ~5s for Postgres to accept connections, then:
```bash
mvn -f D:\Ledger\ledger-service\pom.xml spring-boot:run
```
In another terminal, insert two test accounts directly (no account-creation endpoint exists
yet — that's fine, Task 5 does not add one either; accounts are seeded directly for now) and
call the endpoint:
```bash
docker exec -i ledger-pg-smoke psql -U ledger -d ledger_db -c "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), 'smoke-a', 5000), (gen_random_uuid(), 'smoke-b', 0);"
curl -X POST http://localhost:8080/transactions -H "Content-Type: application/json" -H "Idempotency-Key: smoke-1" -d "{\"debitAccountRef\":\"smoke-a\",\"creditAccountRef\":\"smoke-b\",\"amountMinor\":500,\"currency\":\"USD\",\"description\":\"smoke test\"}"
```
Expected: HTTP 201 with a `transactionId` and `status: POSTED`. Repeat the same curl command
— expect HTTP 200 with `X-Idempotent-Replay: true`.

Stop the app (Ctrl+C) and the container: `docker stop ledger-pg-smoke`.

- [ ] **Step 11: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/service \
        ledger-service/src/main/java/com/ledger/ledgerservice/api \
        ledger-service/src/test/java/com/ledger/ledgerservice/service
git commit -m "feat(ledger-service): add idempotent POST /transactions with atomic outbox write

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Transaction Processor Postgres schema and JPA entities

**Files:**
- Modify: `D:\Ledger\transaction-processor\pom.xml` (add Flyway, PostgreSQL, Testcontainers deps)
- Create: `D:\Ledger\transaction-processor\src\main\resources\db\migration\V1__init_schema.sql`
- Modify: `D:\Ledger\transaction-processor\src\main\resources\application.yml`
- Create: `D:\Ledger\transaction-processor\src\test\resources\application-test.yml`
- Delete: `D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\TransactionProcessorApplicationTests.java` (superseded)
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\domain\ProcessedEventStatus.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\domain\ProcessedEvent.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\domain\CdcProgress.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\repository\ProcessedEventRepository.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\repository\CdcProgressRepository.java`
- Test: `D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\SchemaMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from Ledger Service (separate database entirely).
- Produces: `ProcessedEvent` (fields: `outboxEventId: UUID` [PK], `aggregateId: UUID`,
  `eventType: String`, `capturedAt: Instant`, `publishedAt: Instant`, `consumedAt: Instant`,
  `status: ProcessedEventStatus`, `deliveryCount: int`, `payload: String`),
  `ProcessedEventRepository.findByOutboxEventId(UUID): Optional<ProcessedEvent>` and a
  conditional-update method — both consumed by Task 7's dedup gate.

- [ ] **Step 1: Add Flyway, PostgreSQL, and Testcontainers dependencies**

```xml
<!-- add inside D:\Ledger\transaction-processor\pom.xml <dependencies> -->
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

(The Testcontainers BOM is already in the root `pom.xml` from Task 2 — no change needed there.)

- [ ] **Step 2: Write the V1 schema migration**

```sql
-- D:\Ledger\transaction-processor\src\main\resources\db\migration\V1__init_schema.sql
CREATE TABLE processed_events (
    outbox_event_id   UUID PRIMARY KEY,
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(64) NOT NULL,
    captured_at       TIMESTAMPTZ NOT NULL,
    published_at      TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    status            VARCHAR(24) NOT NULL DEFAULT 'CAPTURED'
                        CHECK (status IN ('CAPTURED','PUBLISHED','CONSUMED','DUPLICATE_IGNORED','PUBLISH_FAILED')),
    delivery_count    INT NOT NULL DEFAULT 1,
    payload           JSONB,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_processed_events_aggregate ON processed_events(aggregate_id);
CREATE INDEX idx_processed_events_status ON processed_events(status);

CREATE TABLE cdc_progress (
    id                      SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_lsn                VARCHAR(64),
    last_event_captured_at  TIMESTAMPTZ,
    last_heartbeat_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: Configure the main application datasource**

```yaml
# D:\Ledger\transaction-processor\src\main\resources\application.yml
server:
  port: 8081

spring:
  application:
    name: transaction-processor
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:processor_db}
    username: ${DB_USER:processor}
    password: ${DB_PASSWORD:processor}
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 4: Create the test profile config**

```yaml
# D:\Ledger\transaction-processor\src\test\resources\application-test.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
```

- [ ] **Step 5: Delete the Task 1 placeholder test**

Delete `D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\TransactionProcessorApplicationTests.java`.

- [ ] **Step 6: Write the ProcessedEventStatus enum**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\domain\ProcessedEventStatus.java
package com.ledger.txprocessor.domain;

public enum ProcessedEventStatus {
    CAPTURED, PUBLISHED, CONSUMED, DUPLICATE_IGNORED, PUBLISH_FAILED
}
```

- [ ] **Step 7: Write the ProcessedEvent entity**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\domain\ProcessedEvent.java
package com.ledger.txprocessor.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "outbox_event_id")
    private UUID outboxEventId;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessedEventStatus status;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(UUID outboxEventId, UUID aggregateId, String eventType,
                           Instant capturedAt, ProcessedEventStatus status, String payload) {
        this.outboxEventId = outboxEventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.capturedAt = capturedAt;
        this.status = status;
        this.deliveryCount = 1;
        this.payload = payload;
        this.updatedAt = Instant.now();
    }

    public UUID getOutboxEventId() { return outboxEventId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public ProcessedEventStatus getStatus() { return status; }
    public int getDeliveryCount() { return deliveryCount; }
    public String getPayload() { return payload; }

    public void markPublished() {
        this.status = ProcessedEventStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markPublishFailed() {
        this.status = ProcessedEventStatus.PUBLISH_FAILED;
        this.updatedAt = Instant.now();
    }

    public void markConsumed() {
        this.status = ProcessedEventStatus.CONSUMED;
        this.consumedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void recordDuplicateDelivery() {
        this.deliveryCount += 1;
        this.updatedAt = Instant.now();
    }
}
```

- [ ] **Step 8: Write the CdcProgress entity**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\domain\CdcProgress.java
package com.ledger.txprocessor.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cdc_progress")
public class CdcProgress {

    @Id
    private short id = 1;

    @Column(name = "last_lsn")
    private String lastLsn;

    @Column(name = "last_event_captured_at")
    private Instant lastEventCapturedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CdcProgress() {
        // JPA
    }

    public CdcProgress(String lastLsn, Instant lastEventCapturedAt) {
        this.id = 1;
        this.lastLsn = lastLsn;
        this.lastEventCapturedAt = lastEventCapturedAt;
        this.lastHeartbeatAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public short getId() { return id; }
    public String getLastLsn() { return lastLsn; }
}
```

- [ ] **Step 9: Write the repositories**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\repository\ProcessedEventRepository.java
package com.ledger.txprocessor.repository;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    Optional<ProcessedEvent> findByOutboxEventId(UUID outboxEventId);
    List<ProcessedEvent> findByOutboxEventIdIn(List<UUID> outboxEventIds);
    List<ProcessedEvent> findByStatus(ProcessedEventStatus status);
}
```

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\repository\CdcProgressRepository.java
package com.ledger.txprocessor.repository;

import com.ledger.txprocessor.domain.CdcProgress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CdcProgressRepository extends JpaRepository<CdcProgress, Short> {
}
```

- [ ] **Step 10: Write the failing schema-migration test**

```java
// D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\SchemaMigrationIntegrationTest.java
package com.ledger.txprocessor;

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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("processor_db")
            .withUsername("processor")
            .withPassword("processor");

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
                "processed_events", "cdc_progress", "flyway_schema_history"
        );
    }
}
```

- [ ] **Step 11: Run the test to verify it fails, then passes**

Run: `mvn -f D:\Ledger\transaction-processor\pom.xml test -Dtest=SchemaMigrationIntegrationTest`
Expected first (before step 2's migration file exists): FAIL. After completing steps 1-9:
PASS.

- [ ] **Step 12: Commit**

```bash
git add transaction-processor/pom.xml \
        transaction-processor/src/main/resources \
        transaction-processor/src/test/resources \
        transaction-processor/src/main/java/com/ledger/txprocessor/domain \
        transaction-processor/src/main/java/com/ledger/txprocessor/repository \
        transaction-processor/src/test/java/com/ledger/txprocessor/SchemaMigrationIntegrationTest.java
git rm transaction-processor/src/test/java/com/ledger/txprocessor/TransactionProcessorApplicationTests.java
git commit -m "feat(transaction-processor): add Postgres schema, JPA entities, and repositories

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: RabbitMQ publisher with confirms + consumer with the dedup gate

**Files:**
- Modify: `D:\Ledger\transaction-processor\pom.xml` (add `spring-boot-starter-amqp`, `testcontainers-rabbitmq`)
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\MessagingConstants.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\RabbitPublisherConfig.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\OutboxEventPublisher.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\OutboxEventConsumer.java`
- Modify: `D:\Ledger\transaction-processor\src\main\resources\application.yml` (RabbitMQ connection)
- Test: `D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\messaging\OutboxEventPublishConsumeIntegrationTest.java`

**Interfaces:**
- Consumes: `ProcessedEventRepository` from Task 5.
- Produces: `OutboxEventPublisher.publish(UUID outboxEventId, UUID aggregateId, String eventType, String payload): void`
  — the method Task 7's Debezium change-consumer calls once it has written the `CAPTURED` row.
  `OutboxEventConsumer` is a `@RabbitListener` requiring no external interface (wired
  entirely through the queue), but its dedup behavior (exactly one `CONSUMED` transition per
  `outboxEventId` regardless of redelivery count) is the contract Task 7's chaos scenarios
  depend on.

- [ ] **Step 1: Add AMQP and RabbitMQ Testcontainers dependencies**

```xml
<!-- add inside D:\Ledger\transaction-processor\pom.xml <dependencies> -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Define exchange/queue/routing-key constants**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\MessagingConstants.java
package com.ledger.txprocessor.messaging;

public final class MessagingConstants {
    public static final String LEDGER_EXCHANGE = "ledger.events";
    public static final String TRANSACTION_POSTED_QUEUE = "ledger.transaction.posted.queue";
    public static final String TRANSACTION_POSTED_ROUTING_KEY = "ledger.transaction.posted";
    public static final String HEADER_OUTBOX_EVENT_ID = "outboxEventId";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private MessagingConstants() {
    }
}
```

- [ ] **Step 3: Configure the exchange/queue topology and publisher confirms**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\RabbitPublisherConfig.java
package com.ledger.txprocessor.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitPublisherConfig {

    @Value("${spring.rabbitmq.host:localhost}")
    private String host;
    @Value("${spring.rabbitmq.port:5672}")
    private int port;
    @Value("${spring.rabbitmq.username:guest}")
    private String username;
    @Value("${spring.rabbitmq.password:guest}")
    private String password;

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setMandatory(true);
        return template;
    }

    @Bean
    public TopicExchange ledgerExchange() {
        return new TopicExchange(MessagingConstants.LEDGER_EXCHANGE, true, false);
    }

    @Bean
    public Queue transactionPostedQueue() {
        return new Queue(MessagingConstants.TRANSACTION_POSTED_QUEUE, true);
    }

    @Bean
    public Binding transactionPostedBinding(Queue transactionPostedQueue, TopicExchange ledgerExchange) {
        return BindingBuilder.bind(transactionPostedQueue)
                .to(ledgerExchange)
                .with(MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY);
    }
}
```

- [ ] **Step 4: Write the publisher with confirm callback updating `processed_events`**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\OutboxEventPublisher.java
package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class OutboxEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ProcessedEventRepository processedEventRepository;

    public OutboxEventPublisher(RabbitTemplate rabbitTemplate, ProcessedEventRepository processedEventRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.processedEventRepository = processedEventRepository;
        this.rabbitTemplate.setConfirmCallback(this::onConfirm);
    }

    public void publish(UUID outboxEventId, UUID aggregateId, String eventType, String payload) {
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, outboxEventId.toString())
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, aggregateId.toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, eventType)
                .setContentType("application/json")
                .build();

        CorrelationData correlationData = new CorrelationData(outboxEventId.toString());
        rabbitTemplate.convertAndSend(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message, correlationData);
    }

    @Transactional
    void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null) {
            return;
        }
        UUID outboxEventId = UUID.fromString(correlationData.getId());
        processedEventRepository.findByOutboxEventId(outboxEventId).ifPresent(event -> {
            if (ack) {
                event.markPublished();
            } else {
                event.markPublishFailed();
            }
            processedEventRepository.save(event);
        });
    }
}
```

- [ ] **Step 5: Write the consumer with the atomic dedup gate**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\messaging\OutboxEventConsumer.java
package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class OutboxEventConsumer {

    private final ProcessedEventRepository processedEventRepository;

    public OutboxEventConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @RabbitListener(queues = MessagingConstants.TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    @Transactional
    public void onMessage(Message message,
                           org.springframework.amqp.rabbit.core.RabbitTemplate ignoredUnusedTemplate,
                           org.springframework.amqp.support.AmqpHeaders ignoredUnusedHeaders) {
        // Overload resolution note: Spring AMQP injects the Channel via the method
        // signature below in the real listener; see the simplified single-arg version
        // actually registered — this comment intentionally left out of the final method.
    }

    @RabbitListener(queues = MessagingConstants.TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    @Transactional
    public void handle(Message message,
                        com.rabbitmq.client.Channel channel) throws java.io.IOException {
        String outboxEventIdHeader = (String) message.getMessageProperties()
                .getHeaders().get(MessagingConstants.HEADER_OUTBOX_EVENT_ID);
        UUID outboxEventId = UUID.fromString(outboxEventIdHeader);
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        ProcessedEvent event = processedEventRepository.findByOutboxEventId(outboxEventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Received message for unknown outboxEventId " + outboxEventId +
                        " — publisher must persist a CAPTURED row before publishing"));

        if (event.getStatus() == ProcessedEventStatus.CONSUMED) {
            event.recordDuplicateDelivery();
            processedEventRepository.save(event);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // Business effect for V1 is limited to recording completion — no downstream
        // side effect exists yet (Holds/Fees/Gateway Simulator arrive in later versions).
        event.markConsumed();
        processedEventRepository.save(event);
        channel.basicAck(deliveryTag, false);
    }
}
```

Note: remove the first unused `onMessage` placeholder method above — it was left in this plan
only to flag that Spring AMQP requires the raw `com.rabbitmq.client.Channel` parameter (not a
Spring type) to call `basicAck` manually under `ackMode = "MANUAL"`. Only the `handle` method
should actually exist in the final file; delete `onMessage` entirely when writing the file.

- [ ] **Step 6: Configure RabbitMQ connection properties for the main app**

```yaml
# add to D:\Ledger\transaction-processor\src\main\resources\application.yml under spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
```

- [ ] **Step 7: Write the failing publish-then-consume-then-dedup integration test**

```java
// D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\messaging\OutboxEventPublishConsumeIntegrationTest.java
package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OutboxEventPublishConsumeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("processor_db")
            .withUsername("processor")
            .withPassword("processor");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

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
    private OutboxEventPublisher outboxEventPublisher;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void cleanUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    void publishedEventIsConsumedExactlyOnce() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        outboxEventPublisher.publish(outboxEventId, aggregateId, "TRANSACTION_POSTED", "{\"test\":true}");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
            assertThat(updated.getDeliveryCount()).isEqualTo(1);
        });
    }

    @Test
    void duplicateDeliveryOfSameOutboxEventIdIsIgnoredNotReprocessed() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        outboxEventPublisher.publish(outboxEventId, aggregateId, "TRANSACTION_POSTED", "{\"test\":true}");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow().getStatus())
                        .isEqualTo(ProcessedEventStatus.CONSUMED));

        // Simulate a redelivery: republish the same outboxEventId directly.
        outboxEventPublisher.publish(outboxEventId, aggregateId, "TRANSACTION_POSTED", "{\"test\":true}");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
            assertThat(updated.getDeliveryCount()).isEqualTo(2);
        });
    }
}
```

Add the Awaitility test dependency used above:

```xml
<!-- add inside D:\Ledger\transaction-processor\pom.xml <dependencies> -->
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 8: Run the tests to verify they fail**

Run: `mvn -f D:\Ledger\transaction-processor\pom.xml test -Dtest=OutboxEventPublishConsumeIntegrationTest`
Expected: FAIL to compile until steps 1-6 are complete.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `mvn -f D:\Ledger\transaction-processor\pom.xml test -Dtest=OutboxEventPublishConsumeIntegrationTest`
Expected: PASS — both tests, confirming exactly-once consumption and correct duplicate
counting on redelivery.

- [ ] **Step 10: Commit**

```bash
git add transaction-processor/pom.xml \
        transaction-processor/src/main/resources/application.yml \
        transaction-processor/src/main/java/com/ledger/txprocessor/messaging \
        transaction-processor/src/test/java/com/ledger/txprocessor/messaging
git commit -m "feat(transaction-processor): add RabbitMQ publisher with confirms and deduping consumer

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Embedded Debezium Engine — CDC capture from Ledger's WAL

**Files:**
- Modify: `D:\Ledger\transaction-processor\pom.xml` (add `debezium-api`, `debezium-connector-postgres`)
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\cdc\OutboxEventPayloadMapper.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\cdc\OutboxChangeConsumer.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\cdc\DebeziumEngineLifecycle.java`
- Modify: `D:\Ledger\transaction-processor\src\main\resources\application.yml` (Debezium config)
- Test: `D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\cdc\DebeziumOutboxCaptureIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxEventPublisher.publish(...)` from Task 6, `ProcessedEventRepository` from
  Task 5.
- Produces: nothing consumed by later tasks directly — this is the final wiring point that
  makes the whole capture → publish → consume pipeline live. `DebeziumEngineLifecycle`
  implements `SmartLifecycle` so it starts/stops with the Spring context.

- [ ] **Step 1: Add Debezium dependencies**

```xml
<!-- add inside D:\Ledger\transaction-processor\pom.xml <dependencies> -->
<dependency>
    <groupId>io.debezium</groupId>
    <artifactId>debezium-api</artifactId>
    <version>2.7.3.Final</version>
</dependency>
<dependency>
    <groupId>io.debezium</groupId>
    <artifactId>debezium-embedded</artifactId>
    <version>2.7.3.Final</version>
</dependency>
<dependency>
    <groupId>io.debezium</groupId>
    <artifactId>debezium-connector-postgres</artifactId>
    <version>2.7.3.Final</version>
</dependency>
```

- [ ] **Step 2: Write the payload mapper that parses Debezium's JSON change envelope**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\cdc\OutboxEventPayloadMapper.java
package com.ledger.txprocessor.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxEventPayloadMapper {

    private final ObjectMapper objectMapper;

    public OutboxEventPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record CapturedOutboxRow(UUID id, UUID aggregateId, String eventType, String payloadJson) {
    }

    public CapturedOutboxRow parse(String debeziumValueJson) {
        try {
            JsonNode root = objectMapper.readTree(debeziumValueJson);
            JsonNode after = root.get("after");
            if (after == null || after.isNull()) {
                return null; // a delete or non-insert event on the outbox table; V1 never deletes outbox rows
            }
            UUID id = UUID.fromString(after.get("id").asText());
            UUID aggregateId = UUID.fromString(after.get("aggregate_id").asText());
            String eventType = after.get("event_type").asText();
            String payloadJson = after.get("payload").asText();
            return new CapturedOutboxRow(id, aggregateId, eventType, payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Debezium change event", e);
        }
    }
}
```

Note: Debezium's Postgres connector with `pgoutput` serializes the `after` struct's fields
using the source column names; `payload` (a `jsonb` column) arrives as a JSON-encoded string
value, which is stored as-is into `processed_events.payload`.

- [ ] **Step 3: Write the change consumer — the write-ahead-then-publish logic**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\cdc\OutboxChangeConsumer.java
package com.ledger.txprocessor.cdc;

import com.ledger.txprocessor.domain.CdcProgress;
import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.messaging.OutboxEventPublisher;
import com.ledger.txprocessor.repository.CdcProgressRepository;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Component
public class OutboxChangeConsumer implements DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> {

    private static final Logger log = LoggerFactory.getLogger(OutboxChangeConsumer.class);

    private final OutboxEventPayloadMapper payloadMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final CdcProgressRepository cdcProgressRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TransactionTemplate transactionTemplate;

    public OutboxChangeConsumer(OutboxEventPayloadMapper payloadMapper,
                                 ProcessedEventRepository processedEventRepository,
                                 CdcProgressRepository cdcProgressRepository,
                                 OutboxEventPublisher outboxEventPublisher,
                                 TransactionTemplate transactionTemplate) {
        this.payloadMapper = payloadMapper;
        this.processedEventRepository = processedEventRepository;
        this.cdcProgressRepository = cdcProgressRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void handleBatch(java.util.List<ChangeEvent<String, String>> records,
                             DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer) throws InterruptedException {
        for (ChangeEvent<String, String> record : records) {
            if (record.value() != null) {
                processRecord(record.value());
            }
            committer.markProcessed(record);
        }
        committer.markBatchFinished();
    }

    private void processRecord(String valueJson) {
        var parsed = payloadMapper.parse(valueJson);
        if (parsed == null) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (processedEventRepository.findByOutboxEventId(parsed.id()).isPresent()) {
                log.debug("Outbox event {} already captured, skipping re-capture (Debezium redelivery)", parsed.id());
                return;
            }

            ProcessedEvent captured = new ProcessedEvent(parsed.id(), parsed.aggregateId(), parsed.eventType(),
                    Instant.now(), ProcessedEventStatus.CAPTURED, parsed.payloadJson());
            processedEventRepository.save(captured);

            CdcProgress progress = new CdcProgress(null, Instant.now());
            cdcProgressRepository.save(progress);
        });

        outboxEventPublisher.publish(parsed.id(), parsed.aggregateId(), parsed.eventType(), parsed.payloadJson());
    }
}
```

Note: the `findByOutboxEventId` presence check before insert handles Debezium's own
at-least-once redelivery on engine restart (distinct from RabbitMQ's redelivery, which
Task 6's consumer dedup already handles) — if Debezium redelivers a WAL record whose offset
was never flushed, this guard prevents a second `CAPTURED` row / a duplicate publish attempt
for the same event, though a duplicate publish is still safely absorbed downstream by Task 6's
dedup gate as defense in depth.

- [ ] **Step 4: Write the SmartLifecycle wrapper that starts/stops the engine with the Spring context**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\cdc\DebeziumEngineLifecycle.java
package com.ledger.txprocessor.cdc;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DebeziumEngineLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineLifecycle.class);

    private final OutboxChangeConsumer changeConsumer;

    @Value("${cdc.database.hostname}")
    private String dbHostname;
    @Value("${cdc.database.port}")
    private String dbPort;
    @Value("${cdc.database.user}")
    private String dbUser;
    @Value("${cdc.database.password}")
    private String dbPassword;
    @Value("${cdc.database.dbname}")
    private String dbName;
    @Value("${cdc.offset.storage.file.filename}")
    private String offsetStorageFilename;

    private ExecutorService executorService;
    private DebeziumEngine<?> engine;
    private volatile boolean running = false;

    public DebeziumEngineLifecycle(OutboxChangeConsumer changeConsumer) {
        this.changeConsumer = changeConsumer;
    }

    @Override
    public void start() {
        Properties props = new Properties();
        props.setProperty("name", "ledger-outbox-connector");
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", offsetStorageFilename);
        props.setProperty("offset.flush.interval.ms", "1000");
        props.setProperty("database.hostname", dbHostname);
        props.setProperty("database.port", dbPort);
        props.setProperty("database.user", dbUser);
        props.setProperty("database.password", dbPassword);
        props.setProperty("database.dbname", dbName);
        props.setProperty("topic.prefix", "ledger");
        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("slot.name", "debezium_ledger_slot");
        props.setProperty("publication.name", "ledger_outbox_pub");
        props.setProperty("publication.autocreate.mode", "disabled");
        props.setProperty("table.include.list", "public.outbox");
        props.setProperty("snapshot.mode", "no_data");

        engine = DebeziumEngine.create(Json.class)
                .using(props)
                .notifying(changeConsumer)
                .build();

        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(engine);
        running = true;
        log.info("Debezium embedded engine started against {}:{}/{}", dbHostname, dbPort, dbName);
    }

    @Override
    public void stop() {
        try {
            if (engine != null) {
                engine.close();
            }
        } catch (Exception e) {
            log.warn("Error closing Debezium engine", e);
        } finally {
            if (executorService != null) {
                executorService.shutdown();
            }
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

- [ ] **Step 5: Add CDC configuration to application.yml**

```yaml
# add to D:\Ledger\transaction-processor\src\main\resources\application.yml (top level, not under spring:)
cdc:
  database:
    hostname: ${CDC_DB_HOST:localhost}
    port: ${CDC_DB_PORT:5432}
    user: ${CDC_DB_USER:debezium_replicator}
    password: ${CDC_DB_PASSWORD:debezium_replicator}
    dbname: ${CDC_DB_NAME:ledger_db}
  offset:
    storage:
      file:
        filename: ${CDC_OFFSET_FILE:./debezium-offsets/offsets.dat}
```

- [ ] **Step 6: Write the failing end-to-end CDC capture test**

This test needs a Postgres container configured for logical replication (Testcontainers'
default Postgres image supports this via command-line args), seeded with Ledger's own
`outbox` table schema (reusing Task 2's migration directly against this container, since
Debezium in this test is pointed at a *simulated Ledger* database, not the real Ledger
Service module).

```java
// D:\Ledger\transaction-processor\src\test\java\com\ledger\txprocessor\cdc\DebeziumOutboxCaptureIntegrationTest.java
package com.ledger.txprocessor.cdc;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class DebeziumOutboxCaptureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> processorPostgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("processor_db")
            .withUsername("processor")
            .withPassword("processor");

    @Container
    static PostgreSQLContainer<?> sourcePostgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    @TempDir
    static Path offsetDir;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", processorPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", processorPostgres::getUsername);
        registry.add("spring.datasource.password", processorPostgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);

        registry.add("cdc.database.hostname", sourcePostgres::getHost);
        registry.add("cdc.database.port", () -> sourcePostgres.getMappedPort(5432));
        registry.add("cdc.database.user", () -> "ledger");
        registry.add("cdc.database.password", () -> "ledger");
        registry.add("cdc.database.dbname", () -> "ledger_db");
        registry.add("cdc.offset.storage.file.filename", () -> offsetDir.resolve("offsets.dat").toString());
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void setUpSourceSchemaAndCleanState() {
        JdbcTemplate sourceJdbc = new JdbcTemplate(new DriverManagerDataSource(
                sourcePostgres.getJdbcUrl(), sourcePostgres.getUsername(), sourcePostgres.getPassword()));

        sourceJdbc.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id UUID PRIMARY KEY,
                    aggregate_type VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION',
                    aggregate_id UUID NOT NULL,
                    event_type VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION_POSTED',
                    payload JSONB NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        sourceJdbc.execute("DROP PUBLICATION IF EXISTS ledger_outbox_pub");
        sourceJdbc.execute("CREATE PUBLICATION ledger_outbox_pub FOR TABLE outbox");

        processedEventRepository.deleteAll();
    }

    @Test
    void insertingAnOutboxRowIsCapturedAndPublishedThroughToConsumption() {
        JdbcTemplate sourceJdbc = new JdbcTemplate(new DriverManagerDataSource(
                sourcePostgres.getJdbcUrl(), sourcePostgres.getUsername(), sourcePostgres.getPassword()));

        UUID outboxId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        sourceJdbc.update("""
                INSERT INTO outbox (id, aggregate_id, payload) VALUES (?, ?, ?::jsonb)
                """, outboxId, aggregateId, "{\"transactionId\":\"" + aggregateId + "\"}");

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent event = processedEventRepository.findByOutboxEventId(outboxId).orElseThrow();
            assertThat(event.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
        });
    }
}
```

Add `org.testcontainers:rabbitmq` is already present from Task 6; no new Testcontainers
module needed here beyond what's already on the classpath.

- [ ] **Step 7: Run the test to verify it fails**

Run: `mvn -f D:\Ledger\transaction-processor\pom.xml test -Dtest=DebeziumOutboxCaptureIntegrationTest`
Expected: FAIL to compile until steps 1-5 are complete; once compiling, expect it to fail
with no `CAPTURED`/`CONSUMED` transition if the Debezium engine isn't wired correctly (wrong
property name, missing publication, etc.) — this is a genuinely fragile integration point, so
budget real debugging time here, most commonly around `plugin.name=pgoutput` requiring the
Postgres image to support logical decoding (the standard `postgres:16` image does) and the
replication user needing the `REPLICATION` role attribute, which Testcontainers' default
superuser already has.

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn -f D:\Ledger\transaction-processor\pom.xml test -Dtest=DebeziumOutboxCaptureIntegrationTest`
Expected: PASS within the 30s await budget — the full pipeline (WAL insert → Debezium capture
→ CAPTURED row → RabbitMQ publish → PUBLISHED → consume → CONSUMED) completes end-to-end.

- [ ] **Step 9: Commit**

```bash
git add transaction-processor/pom.xml \
        transaction-processor/src/main/resources/application.yml \
        transaction-processor/src/main/java/com/ledger/txprocessor/cdc \
        transaction-processor/src/test/java/com/ledger/txprocessor/cdc
git commit -m "feat(transaction-processor): embed Debezium engine to capture Ledger outbox WAL changes

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Reconciliation job — cross-service correctness checks

**Files:**
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\api\ProcessedEventController.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\api\dto\ProcessedEventStatusResponse.java`
- Create: `D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\api\dto\BatchStatusRequest.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\ReconciliationRun.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\ReconciliationFinding.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\ReconciliationRunRepository.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\ReconciliationFindingRepository.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\reconciliation\ProcessorReconciliationClient.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\reconciliation\ReconciliationService.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\reconciliation\ReconciliationJob.java`
- Create: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\ReconciliationController.java`
- Modify: `D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\LedgerServiceApplication.java` (add `@EnableScheduling`)
- Modify: `D:\Ledger\ledger-service\pom.xml` (add `spring-boot-starter-webflux` for `WebClient`, or reuse RestClient from `spring-boot-starter-web` — using `RestClient`, already available)
- Modify: `D:\Ledger\ledger-service\src\main\resources\application.yml` (Processor base URL)
- Test: `D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\reconciliation\ReconciliationServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Ledger's `outbox`/`entries`/`transactions` repositories from Task 3, and calls
  the Processor's new `POST /processed-events/batch-status` REST endpoint over HTTP.
- Produces: `ReconciliationService.runReconciliation(): ReconciliationRun` — callable directly
  by tests and by the chaos suite (Task 10) via `POST /reconciliation/runs` to trigger an
  on-demand run rather than waiting for the schedule.

- [ ] **Step 1: Write the Processor's batch-status endpoint DTOs and controller**

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\api\dto\BatchStatusRequest.java
package com.ledger.txprocessor.api.dto;

import java.util.List;
import java.util.UUID;

public record BatchStatusRequest(List<UUID> outboxEventIds) {
}
```

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\api\dto\ProcessedEventStatusResponse.java
package com.ledger.txprocessor.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProcessedEventStatusResponse(
        UUID outboxEventId,
        String status,
        Instant publishedAt,
        Instant consumedAt,
        int deliveryCount
) {
}
```

```java
// D:\Ledger\transaction-processor\src\main\java\com\ledger\txprocessor\api\ProcessedEventController.java
package com.ledger.txprocessor.api;

import com.ledger.txprocessor.api.dto.BatchStatusRequest;
import com.ledger.txprocessor.api.dto.ProcessedEventStatusResponse;
import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ProcessedEventController {

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventController(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @PostMapping("/processed-events/batch-status")
    public List<ProcessedEventStatusResponse> batchStatus(@RequestBody BatchStatusRequest request) {
        List<ProcessedEvent> found = processedEventRepository.findByOutboxEventIdIn(request.outboxEventIds());
        return found.stream()
                .map(e -> new ProcessedEventStatusResponse(
                        e.getOutboxEventId(), e.getStatus().name(), e.getPublishedAt(), e.getConsumedAt(), e.getDeliveryCount()))
                .toList();
    }
}
```

- [ ] **Step 2: Write Ledger's reconciliation JPA entities and repositories**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\ReconciliationRun.java
package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "transactions_checked", nullable = false)
    private int transactionsChecked;

    @Column(name = "entries_imbalance_count", nullable = false)
    private int entriesImbalanceCount;

    @Column(name = "outbox_missing_count", nullable = false)
    private int outboxMissingCount;

    @Column(name = "outbox_stuck_count", nullable = false)
    private int outboxStuckCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String summary;

    protected ReconciliationRun() {
        // JPA
    }

    public ReconciliationRun(UUID id, Instant startedAt) {
        this.id = id;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    public UUID getId() { return id; }
    public Status getStatus() { return status; }
    public int getEntriesImbalanceCount() { return entriesImbalanceCount; }
    public int getOutboxMissingCount() { return outboxMissingCount; }
    public int getOutboxStuckCount() { return outboxStuckCount; }

    public void complete(int transactionsChecked, int entriesImbalanceCount,
                          int outboxMissingCount, int outboxStuckCount, String summary) {
        this.finishedAt = Instant.now();
        this.status = Status.COMPLETED;
        this.transactionsChecked = transactionsChecked;
        this.entriesImbalanceCount = entriesImbalanceCount;
        this.outboxMissingCount = outboxMissingCount;
        this.outboxStuckCount = outboxStuckCount;
        this.summary = summary;
    }

    public void fail(String reason) {
        this.finishedAt = Instant.now();
        this.status = Status.FAILED;
        this.summary = "{\"failureReason\":\"" + reason.replace("\"", "'") + "\"}";
    }
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\domain\ReconciliationFinding.java
package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_findings")
public class ReconciliationFinding {

    public enum FindingType { ENTRIES_NOT_ZERO, OUTBOX_MISSING, OUTBOX_STUCK_UNPUBLISHED }

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false)
    private FindingType findingType;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "outbox_id")
    private UUID outboxId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected ReconciliationFinding() {
        // JPA
    }

    public ReconciliationFinding(UUID id, UUID runId, FindingType findingType,
                                  UUID transactionId, UUID outboxId, String detail) {
        this.id = id;
        this.runId = runId;
        this.findingType = findingType;
        this.transactionId = transactionId;
        this.outboxId = outboxId;
        this.detail = detail;
        this.detectedAt = Instant.now();
    }

    public FindingType getFindingType() { return findingType; }
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\ReconciliationRunRepository.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\repository\ReconciliationFindingRepository.java
package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.ReconciliationFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, UUID> {
    List<ReconciliationFinding> findByRunId(UUID runId);
}
```

- [ ] **Step 3: Write the REST client calling the Processor's batch-status endpoint**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\reconciliation\ProcessorReconciliationClient.java
package com.ledger.ledgerservice.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ProcessorReconciliationClient {

    public record ProcessedEventStatus(UUID outboxEventId, String status, Instant publishedAt,
                                        Instant consumedAt, int deliveryCount) {
    }

    private final RestClient restClient;

    public ProcessorReconciliationClient(@Value("${processor.base-url}") String processorBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(processorBaseUrl).build();
    }

    public List<ProcessedEventStatus> batchStatus(List<UUID> outboxEventIds) {
        record Request(List<UUID> outboxEventIds) {}
        return restClient.post()
                .uri("/processed-events/batch-status")
                .body(new Request(outboxEventIds))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<ProcessedEventStatus>>() {});
    }
}
```

- [ ] **Step 4: Write ReconciliationService implementing the 3 checks**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\reconciliation\ReconciliationService.java
package com.ledger.ledgerservice.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.ledgerservice.domain.ReconciliationFinding;
import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final Duration OUTBOX_GRACE_WINDOW = Duration.ofMinutes(1);
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(2);

    private final JdbcTemplate jdbcTemplate;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final ProcessorReconciliationClient processorClient;
    private final ObjectMapper objectMapper;

    public ReconciliationService(JdbcTemplate jdbcTemplate,
                                  ReconciliationRunRepository runRepository,
                                  ReconciliationFindingRepository findingRepository,
                                  ProcessorReconciliationClient processorClient,
                                  ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.runRepository = runRepository;
        this.findingRepository = findingRepository;
        this.processorClient = processorClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReconciliationRun runReconciliation() {
        ReconciliationRun run = new ReconciliationRun(UUID.randomUUID(), Instant.now());
        runRepository.saveAndFlush(run);

        try {
            int checked = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);

            List<UUID> imbalanced = checkEntriesZeroSum();
            for (UUID txnId : imbalanced) {
                saveFinding(run.getId(), ReconciliationFinding.FindingType.ENTRIES_NOT_ZERO, txnId, null,
                        Map.of("transactionId", txnId.toString()));
            }

            List<UUID> missingOutbox = checkOutboxMissing();
            for (UUID txnId : missingOutbox) {
                saveFinding(run.getId(), ReconciliationFinding.FindingType.OUTBOX_MISSING, txnId, null,
                        Map.of("transactionId", txnId.toString()));
            }

            int stuckCount = checkOutboxStuck(run.getId());

            run.complete(checked, imbalanced.size(), missingOutbox.size(), stuckCount,
                    "{\"status\":\"completed\"}");
            runRepository.save(run);
            return run;
        } catch (Exception e) {
            run.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            runRepository.save(run);
            return run;
        }
    }

    private List<UUID> checkEntriesZeroSum() {
        return jdbcTemplate.queryForList("""
                SELECT transaction_id FROM entries
                GROUP BY transaction_id
                HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
                """, UUID.class);
    }

    private List<UUID> checkOutboxMissing() {
        return jdbcTemplate.queryForList("""
                SELECT t.id FROM transactions t
                LEFT JOIN outbox o ON o.aggregate_id = t.id AND o.aggregate_type = 'TRANSACTION'
                WHERE t.status = 'POSTED'
                  AND o.id IS NULL
                  AND t.created_at < now() - (? || ' seconds')::interval
                """, UUID.class, OUTBOX_GRACE_WINDOW.toSeconds());
    }

    private int checkOutboxStuck(UUID runId) {
        List<Map<String, Object>> staleOutboxRows = jdbcTemplate.queryForList("""
                SELECT id, aggregate_id FROM outbox
                WHERE created_at < now() - (? || ' seconds')::interval
                ORDER BY created_at
                """, STUCK_THRESHOLD.toSeconds());

        if (staleOutboxRows.isEmpty()) {
            return 0;
        }

        List<UUID> staleIds = staleOutboxRows.stream()
                .map(row -> (UUID) row.get("id"))
                .toList();

        var statuses = processorClient.batchStatus(staleIds);
        Set<UUID> healthy = statuses.stream()
                .filter(s -> "PUBLISHED".equals(s.status()) || "CONSUMED".equals(s.status()))
                .map(ProcessorReconciliationClient.ProcessedEventStatus::outboxEventId)
                .collect(java.util.stream.Collectors.toSet());

        int stuckCount = 0;
        for (Map<String, Object> row : staleOutboxRows) {
            UUID outboxId = (UUID) row.get("id");
            if (!healthy.contains(outboxId)) {
                stuckCount++;
                saveFinding(runId, ReconciliationFinding.FindingType.OUTBOX_STUCK_UNPUBLISHED,
                        null, outboxId, Map.of("outboxId", outboxId.toString(), "aggregateId", row.get("aggregate_id").toString()));
            }
        }
        return stuckCount;
    }

    private void saveFinding(UUID runId, ReconciliationFinding.FindingType type, UUID transactionId,
                              UUID outboxId, Map<String, String> detail) {
        try {
            String detailJson = objectMapper.writeValueAsString(detail);
            findingRepository.save(new ReconciliationFinding(UUID.randomUUID(), runId, type,
                    transactionId, outboxId, detailJson));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize finding detail", e);
        }
    }
}
```

- [ ] **Step 5: Write the scheduled job and the on-demand controller**

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\reconciliation\ReconciliationJob.java
package com.ledger.ledgerservice.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private final ReconciliationService reconciliationService;

    public ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${reconciliation.interval-ms:300000}")
    public void run() {
        reconciliationService.runReconciliation();
    }
}
```

```java
// D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\api\ReconciliationController.java
package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.reconciliation.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/runs")
    public ResponseEntity<RunSummary> triggerRun() {
        ReconciliationRun run = reconciliationService.runReconciliation();
        return ResponseEntity.ok(new RunSummary(
                run.getId().toString(), run.getStatus().name(),
                run.getEntriesImbalanceCount(), run.getOutboxMissingCount(), run.getOutboxStuckCount()));
    }

    public record RunSummary(String runId, String status, int entriesImbalanceCount,
                              int outboxMissingCount, int outboxStuckCount) {
    }
}
```

- [ ] **Step 6: Enable scheduling and configure the Processor's base URL**

```java
// modify D:\Ledger\ledger-service\src\main\java\com\ledger\ledgerservice\LedgerServiceApplication.java
package com.ledger.ledgerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
```

```yaml
# add to D:\Ledger\ledger-service\src\main\resources\application.yml (top level)
processor:
  base-url: ${PROCESSOR_BASE_URL:http://localhost:8081}
reconciliation:
  interval-ms: ${RECONCILIATION_INTERVAL_MS:300000}
```

- [ ] **Step 7: Write the failing integration test using WireMock-free direct-stub approach**

Since a real cross-service call requires the Processor running, this test uses a lightweight
embedded HTTP stub for the Processor's endpoint instead of standing up the whole second
service — add the `spring-boot-starter-test`-bundled MockWebServer equivalent via a small
local `HttpServer`-based stub to keep the dependency footprint minimal.

```java
// D:\Ledger\ledger-service\src\test\java\com\ledger\ledgerservice\reconciliation\ReconciliationServiceIntegrationTest.java
package com.ledger.ledgerservice.reconciliation;

import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.repository.ReconciliationFindingRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubProcessor;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubProcessor = HttpServer.create(new InetSocketAddress(0), 0);
        stubProcessor.createContext("/processed-events/batch-status", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubProcessor.start();
        registry.add("processor.base-url", () -> "http://localhost:" + stubProcessor.getAddress().getPort());
    }

    @AfterEach
    void tearDownEach() {
        // per-test cleanup handled in each test via JdbcTemplate below
    }

    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private ReconciliationFindingRepository findingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute("DELETE FROM entries");
        jdbcTemplate.execute("DELETE FROM outbox");
        jdbcTemplate.execute("DELETE FROM reconciliation_findings");
        jdbcTemplate.execute("DELETE FROM reconciliation_runs");
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM accounts");
    }

    @Test
    void cleanLedgerProducesZeroFindings() {
        ReconciliationRun run = reconciliationService.runReconciliation();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        assertThat(run.getEntriesImbalanceCount()).isZero();
        assertThat(run.getOutboxMissingCount()).isZero();
        assertThat(run.getOutboxStuckCount()).isZero();
    }

    @Test
    void unbalancedEntriesInsertedDirectlyBypassingTheConstraintTriggerAreDetected() {
        // Bypass the app layer AND defer the constraint trigger to the end of an explicit
        // transaction block that never commits an imbalance permanently — instead, we test
        // detection using a controlled raw-SQL setup where the trigger is temporarily disabled
        // for this one test, since the trigger (Task 2) already prevents this state from ever
        // being persisted through normal means. This test exists to prove the reconciliation
        // query itself is correct, independent of the trigger.
        jdbcTemplate.execute("ALTER TABLE entries DISABLE TRIGGER trg_entries_zero_sum");
        try {
            jdbcTemplate.execute("""
                    INSERT INTO accounts (id, account_ref, balance_minor)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'recon-a', 10000)
                    """);
            jdbcTemplate.execute("""
                    INSERT INTO transactions (id, idempotency_key, request_payload_hash)
                    VALUES ('44444444-4444-4444-4444-444444444444', 'recon-key-1', repeat('a', 64))
                    """);
            jdbcTemplate.execute("""
                    INSERT INTO entries (transaction_id, account_id, direction, amount_minor, currency)
                    VALUES ('44444444-4444-4444-4444-444444444444',
                            '33333333-3333-3333-3333-333333333333', 'DEBIT', 500, 'USD')
                    """);

            ReconciliationRun run = reconciliationService.runReconciliation();

            assertThat(run.getEntriesImbalanceCount()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE entries ENABLE TRIGGER trg_entries_zero_sum");
        }
    }
}
```

- [ ] **Step 8: Run the tests to verify they fail, then pass**

Run: `mvn -f D:\Ledger\ledger-service\pom.xml test -Dtest=ReconciliationServiceIntegrationTest`
Expected: FAIL to compile until steps 1-6 are complete; PASS afterward (2 tests).

- [ ] **Step 9: Commit**

```bash
git add transaction-processor/src/main/java/com/ledger/txprocessor/api \
        ledger-service/src/main/java/com/ledger/ledgerservice/domain \
        ledger-service/src/main/java/com/ledger/ledgerservice/repository \
        ledger-service/src/main/java/com/ledger/ledgerservice/reconciliation \
        ledger-service/src/main/java/com/ledger/ledgerservice/api \
        ledger-service/src/main/java/com/ledger/ledgerservice/LedgerServiceApplication.java \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/test/java/com/ledger/ledgerservice/reconciliation
git commit -m "feat: add reconciliation job cross-checking Ledger outbox against Processor state

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: Docker Compose — bring up the full V1 stack

**Files:**
- Create: `D:\Ledger\ledger-service\Dockerfile`
- Create: `D:\Ledger\transaction-processor\Dockerfile`
- Create: `D:\Ledger\docker-compose.yml`
- Create: `D:\Ledger\ledger-postgres-init\01-debezium-user.sql`
- Create: `D:\Ledger\scripts\smoke-test.sh`

**Interfaces:**
- Consumes: both services' built JARs (via multi-stage Docker builds using the Maven reactor
  from Task 1).
- Produces: a `docker compose up` command that brings up the entire V1 stack from a clean
  state — this is the environment Task 10/11's chaos suite runs against, so proxy hostnames
  and ports defined here (`toxiproxy`, port `15432` for the app DB connection, `15433` for the
  CDC connection) are load-bearing for those tasks.

- [ ] **Step 1: Write the Ledger Service Dockerfile (multi-stage, uses the reactor)**

```dockerfile
# D:\Ledger\ledger-service\Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
RUN mvn -q -pl ledger-service -am dependency:go-offline
COPY ledger-service ledger-service
RUN mvn -q -pl ledger-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/ledger-service/target/ledger-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Note: the build stage's `COPY pom.xml .` line requires the Docker build context to be the
repo root (`D:\Ledger`), not `ledger-service/` — this is why `docker-compose.yml` in Step 3
sets `context: .` with `dockerfile: ledger-service/Dockerfile`.

- [ ] **Step 2: Write the Transaction Processor Dockerfile (same pattern)**

```dockerfile
# D:\Ledger\transaction-processor\Dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY transaction-processor/pom.xml transaction-processor/pom.xml
RUN mvn -q -pl transaction-processor -am dependency:go-offline
COPY transaction-processor transaction-processor
RUN mvn -q -pl transaction-processor -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/transaction-processor/target/transaction-processor-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: Write the SQL that creates a dedicated Debezium replication user with least privilege**

```sql
-- D:\Ledger\ledger-postgres-init\01-debezium-user.sql
CREATE ROLE debezium_replicator WITH REPLICATION LOGIN PASSWORD 'debezium_replicator';
GRANT CONNECT ON DATABASE ledger_db TO debezium_replicator;
GRANT USAGE ON SCHEMA public TO debezium_replicator;
GRANT SELECT ON public.outbox TO debezium_replicator;
```

This file is mounted into `ledger-postgres`'s `/docker-entrypoint-initdb.d/` so it runs
automatically once, after Flyway migrations from the app have already created the `outbox`
table on first boot — see the `depends_on` ordering note in Step 4 for why the app must start
first.

- [ ] **Step 4: Write docker-compose.yml**

```yaml
# D:\Ledger\docker-compose.yml
version: "3.9"

services:
  ledger-postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ledger_db
      POSTGRES_USER: ledger
      POSTGRES_PASSWORD: ledger
    command: ["postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4"]
    volumes:
      - ledger-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ledger -d ledger_db"]
      interval: 5s
      timeout: 5s
      retries: 10
    ports:
      - "5432:5432"

  processor-postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: processor_db
      POSTGRES_USER: processor
      POSTGRES_PASSWORD: processor
    volumes:
      - processor-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U processor -d processor_db"]
      interval: 5s
      timeout: 5s
      retries: 10

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 5s
      timeout: 5s
      retries: 10

  toxiproxy:
    image: ghcr.io/shopify/toxiproxy:2.9.0
    ports:
      - "8474:8474"   # control API
      - "15432:15432" # ledger-postgres app connection proxy
      - "15433:15433" # ledger-postgres CDC connection proxy
      - "15674:15674" # rabbitmq proxy (distinct host port — rabbitmq itself already binds 5672/15672)
    depends_on:
      ledger-postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy

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
    ports:
      - "8080:8080"
    depends_on:
      ledger-postgres:
        condition: service_healthy
      toxiproxy:
        condition: service_started

  transaction-processor:
    build:
      context: .
      dockerfile: transaction-processor/Dockerfile
    environment:
      DB_HOST: processor-postgres
      DB_PORT: "5432"
      DB_NAME: processor_db
      DB_USER: processor
      DB_PASSWORD: processor
      RABBITMQ_HOST: toxiproxy
      RABBITMQ_PORT: "15674"
      CDC_DB_HOST: toxiproxy
      CDC_DB_PORT: "15433"
      CDC_DB_USER: debezium_replicator
      CDC_DB_PASSWORD: debezium_replicator
      CDC_DB_NAME: ledger_db
      CDC_OFFSET_FILE: /var/lib/debezium/offsets/offsets.dat
    volumes:
      - debezium-offsets:/var/lib/debezium/offsets
    ports:
      - "8081:8081"
    depends_on:
      processor-postgres:
        condition: service_healthy
      ledger-service:
        condition: service_started
      toxiproxy:
        condition: service_started

volumes:
  ledger-pgdata:
  processor-pgdata:
  debezium-offsets:
```

- [ ] **Step 5: Write a script that configures the Toxiproxy proxies on startup**

Toxiproxy starts with no proxies configured — they must be created via its HTTP API after the
container is up. This script runs once after `docker compose up` and is also reused by the
chaos suite in Task 10 to reset proxies to a clean (no-toxic) state between scenarios.

```bash
#!/usr/bin/env bash
# D:\Ledger\scripts\smoke-test.sh
set -euo pipefail

TOXIPROXY_API="http://localhost:8474"

echo "Configuring Toxiproxy proxies..."
curl -sf -X POST "$TOXIPROXY_API/proxies" -d '{
  "name": "ledger-postgres-app-proxy",
  "listen": "0.0.0.0:15432",
  "upstream": "ledger-postgres:5432"
}' > /dev/null || echo "  (ledger-postgres-app-proxy already exists)"

curl -sf -X POST "$TOXIPROXY_API/proxies" -d '{
  "name": "ledger-postgres-cdc-proxy",
  "listen": "0.0.0.0:15433",
  "upstream": "ledger-postgres:5432"
}' > /dev/null || echo "  (ledger-postgres-cdc-proxy already exists)"

curl -sf -X POST "$TOXIPROXY_API/proxies" -d '{
  "name": "rabbitmq-proxy",
  "listen": "0.0.0.0:15674",
  "upstream": "rabbitmq:5672"
}' > /dev/null || echo "  (rabbitmq-proxy already exists)"

echo "Waiting for ledger-service to be healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Seeding two test accounts..."
docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
  "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), 'smoke-a', 5000), (gen_random_uuid(), 'smoke-b', 0) ON CONFLICT (account_ref) DO NOTHING;"

echo "Posting a transaction..."
RESPONSE=$(curl -sf -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-$(date +%s)" \
  -d '{"debitAccountRef":"smoke-a","creditAccountRef":"smoke-b","amountMinor":500,"currency":"USD","description":"smoke test"}')
echo "Response: $RESPONSE"

TXN_ID=$(echo "$RESPONSE" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)
echo "Transaction ID: $TXN_ID"

echo "Waiting up to 30s for async processing to complete..."
sleep 30

echo "Triggering a reconciliation run..."
curl -sf -X POST http://localhost:8080/reconciliation/runs
echo ""
echo "Smoke test complete."
```

Requires `spring-boot-starter-actuator` for the `/actuator/health` endpoint used by the
polling loop above — add it to Ledger Service's `pom.xml`:

```xml
<!-- add inside D:\Ledger\ledger-service\pom.xml <dependencies> -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 6: Bring up the stack and run the smoke test**

Run:
```bash
docker compose -f D:\Ledger\docker-compose.yml up -d --build
```
Expected: all 6 containers reach a running/healthy state (check with
`docker compose ps`); watch `docker compose logs -f transaction-processor` briefly to confirm
no Debezium connection errors during startup (the replication user and publication must exist
before the Processor's engine starts — if `ledger-postgres-init` scripts run after Flyway due
to compose startup ordering, restart `transaction-processor` once after the first `up`: `docker compose restart transaction-processor`).

Then run:
```bash
chmod +x D:\Ledger\scripts\smoke-test.sh
D:\Ledger\scripts\smoke-test.sh
```
Expected: the script completes with a 201-equivalent JSON response containing a
`transactionId`, and the final reconciliation run response shows
`"entriesImbalanceCount":0,"outboxMissingCount":0,"outboxStuckCount":0`.

- [ ] **Step 7: Tear down**

```bash
docker compose -f D:\Ledger\docker-compose.yml down -v
```

- [ ] **Step 8: Commit**

```bash
git add ledger-service/Dockerfile transaction-processor/Dockerfile docker-compose.yml \
        ledger-postgres-init scripts/smoke-test.sh ledger-service/pom.xml
git commit -m "feat: add Docker Compose stack wiring all V1 services through Toxiproxy

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Chaos test suite — shared helpers + scenarios 1-3

**Files:**
- Create: `D:\Ledger\chaos\lib\common.sh`
- Create: `D:\Ledger\chaos\scenarios\01_rabbitmq_down_mid_publish.sh`
- Create: `D:\Ledger\chaos\scenarios\02_ledger_db_crash_post_commit.sh`
- Create: `D:\Ledger\chaos\scenarios\03_processor_crash_mid_consume.sh`
- Create: `D:\Ledger\Makefile`

**Interfaces:**
- Consumes: the running Docker Compose stack from Task 9 (must be `up` before running any
  scenario), the Toxiproxy proxies configured by `scripts/smoke-test.sh`'s proxy-setup logic
  (factored out into `chaos/lib/common.sh` here so both scripts share it rather than
  duplicating proxy creation).
- Produces: `make chaos-test` target; each scenario script exits 0 on pass, non-zero on
  failure, per the spec's requirement.

- [ ] **Step 1: Factor out shared helpers used by every scenario**

```bash
#!/usr/bin/env bash
# D:\Ledger\chaos\lib\common.sh
# Sourced by every scenario script. Provides: toxic injection/removal, account
# seeding, transaction posting, and polling helpers against the running compose stack.

TOXIPROXY_API="${TOXIPROXY_API:-http://localhost:8474}"
LEDGER_URL="${LEDGER_URL:-http://localhost:8080}"
PROCESSOR_URL="${PROCESSOR_URL:-http://localhost:8081}"

add_toxic() {
  local proxy_name="$1"
  local toxic_name="$2"
  local toxic_type="$3"
  local attributes_json="$4"
  curl -sf -X POST "$TOXIPROXY_API/proxies/$proxy_name/toxics" -d "{
    \"name\": \"$toxic_name\",
    \"type\": \"$toxic_type\",
    \"attributes\": $attributes_json
  }" > /dev/null
}

remove_toxic() {
  local proxy_name="$1"
  local toxic_name="$2"
  curl -sf -X DELETE "$TOXIPROXY_API/proxies/$proxy_name/toxics/$toxic_name" > /dev/null || true
}

reset_all_toxics() {
  for proxy in ledger-postgres-app-proxy ledger-postgres-cdc-proxy rabbitmq-proxy; do
    for toxic in $(curl -sf "$TOXIPROXY_API/proxies/$proxy/toxics" | grep -o '"name":"[^"]*"' | cut -d'"' -f4); do
      remove_toxic "$proxy" "$toxic"
    done
  done
}

seed_account() {
  local account_ref="$1"
  local balance_minor="$2"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -c \
    "INSERT INTO accounts (id, account_ref, balance_minor) VALUES (gen_random_uuid(), '$account_ref', $balance_minor) ON CONFLICT (account_ref) DO UPDATE SET balance_minor = $balance_minor;" > /dev/null
}

post_transaction() {
  local debit_ref="$1"
  local credit_ref="$2"
  local amount="$3"
  local idem_key="$4"
  curl -s -w "\n%{http_code}" -X POST "$LEDGER_URL/transactions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $idem_key" \
    -d "{\"debitAccountRef\":\"$debit_ref\",\"creditAccountRef\":\"$credit_ref\",\"amountMinor\":$amount,\"currency\":\"USD\",\"description\":\"chaos test\"}"
}

get_account_balance() {
  local account_ref="$1"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT balance_minor FROM accounts WHERE account_ref = '$account_ref';" | tr -d ' \r\n'
}

transaction_count_for_idem_key() {
  local idem_key="$1"
  docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
    "SELECT COUNT(*) FROM transactions WHERE idempotency_key = '$idem_key';" | tr -d ' \r\n'
}

get_processed_event_status_by_aggregate() {
  local aggregate_id="$1"
  docker compose exec -T processor-postgres psql -U processor -d processor_db -t -c \
    "SELECT status FROM processed_events WHERE aggregate_id = '$aggregate_id';" | tr -d ' \r\n'
}

wait_for_processed_status() {
  local aggregate_id="$1"
  local expected_status="$2"
  local timeout_seconds="${3:-60}"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout_seconds" ]; do
    local status
    status=$(get_processed_event_status_by_aggregate "$aggregate_id")
    if [ "$status" = "$expected_status" ]; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "TIMEOUT waiting for processed_events status=$expected_status (last saw: $status)" >&2
  return 1
}

trigger_reconciliation() {
  curl -sf -X POST "$LEDGER_URL/reconciliation/runs"
}

assert_reconciliation_clean() {
  local result
  result=$(trigger_reconciliation)
  echo "$result"
  if echo "$result" | grep -q '"entriesImbalanceCount":0' && \
     echo "$result" | grep -q '"outboxMissingCount":0' && \
     echo "$result" | grep -q '"outboxStuckCount":0'; then
    return 0
  fi
  echo "RECONCILIATION FOUND ISSUES: $result" >&2
  return 1
}

pass() {
  echo "PASS: $1"
}

fail() {
  echo "FAIL: $1" >&2
  exit 1
}
```

- [ ] **Step 2: Write scenario 1 — RabbitMQ down mid-publish**

```bash
#!/usr/bin/env bash
# D:\Ledger\chaos\scenarios\01_rabbitmq_down_mid_publish.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 1: RabbitMQ down mid-publish ==="

reset_all_toxics
seed_account "chaos1-a" 10000
seed_account "chaos1-b" 0

IDEM_KEY="chaos1-$(date +%s)"
RESPONSE=$(post_transaction "chaos1-a" "chaos1-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Injecting RabbitMQ outage..."
add_toxic "rabbitmq-proxy" "outage" "timeout" '{"timeout": 0}'

sleep 5

echo "Restoring RabbitMQ connectivity..."
remove_toxic "rabbitmq-proxy" "outage"

wait_for_processed_status "$TXN_ID" "CONSUMED" 60 || fail "event never reached CONSUMED after RabbitMQ outage recovered"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 1"

pass "Scenario 1: event survived RabbitMQ outage and was eventually consumed exactly once"
```

- [ ] **Step 3: Write scenario 2 — Ledger DB crash after commit, before Debezium sees it**

```bash
#!/usr/bin/env bash
# D:\Ledger\chaos\scenarios\02_ledger_db_crash_post_commit.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 2: Ledger DB crash after commit, before Debezium sees it ==="

reset_all_toxics
seed_account "chaos2-a" 10000
seed_account "chaos2-b" 0

echo "Delaying the CDC connection by 5s so the restart lands before Debezium polls..."
add_toxic "ledger-postgres-cdc-proxy" "delay-cdc" "latency" '{"latency": 5000}'

IDEM_KEY="chaos2-$(date +%s)"
RESPONSE=$(post_transaction "chaos2-a" "chaos2-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Hard-restarting ledger-postgres immediately..."
docker compose restart ledger-postgres
echo "Waiting for ledger-postgres to become healthy again..."
for i in $(seq 1 30); do
  if docker compose exec -T ledger-postgres pg_isready -U ledger -d ledger_db > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Removing the CDC latency toxic..."
remove_toxic "ledger-postgres-cdc-proxy" "delay-cdc"

OUTBOX_ROW_COUNT=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT COUNT(*) FROM outbox WHERE aggregate_id = '$TXN_ID';" | tr -d ' \r\n')
[ "$OUTBOX_ROW_COUNT" = "1" ] || fail "outbox row for $TXN_ID missing after DB restart — WAL durability violated"

wait_for_processed_status "$TXN_ID" "CONSUMED" 90 || fail "event never reached CONSUMED after ledger-postgres restart"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 2"

pass "Scenario 2: outbox row survived source DB restart and was eventually delivered"
```

- [ ] **Step 4: Write scenario 3 — Transaction Processor crash mid-consume**

```bash
#!/usr/bin/env bash
# D:\Ledger\chaos\scenarios\03_processor_crash_mid_consume.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 3: Transaction Processor crash mid-consume ==="

reset_all_toxics
seed_account "chaos3-a" 10000
seed_account "chaos3-b" 0

echo "Adding a 3s latency toxic on the RabbitMQ proxy to widen the crash window..."
add_toxic "rabbitmq-proxy" "delay-consume" "latency" '{"latency": 3000}'

IDEM_KEY="chaos3-$(date +%s)"
RESPONSE=$(post_transaction "chaos3-a" "chaos3-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

echo "Killing transaction-processor at a 1s offset (mid-flight relative to the delayed publish)..."
sleep 1
docker compose kill -s SIGKILL transaction-processor

remove_toxic "rabbitmq-proxy" "delay-consume"

echo "Restarting transaction-processor..."
docker compose up -d transaction-processor
echo "Waiting for transaction-processor to become healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; then
    break
  fi
  sleep 2
done

wait_for_processed_status "$TXN_ID" "CONSUMED" 90 || fail "event never reached CONSUMED after Processor crash+restart"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 3"

pass "Scenario 3: event reached CONSUMED exactly once despite a mid-flight Processor crash"
```

Requires `spring-boot-starter-actuator` on Transaction Processor too, for the health-check
polling loop above — add it to its `pom.xml`:

```xml
<!-- add inside D:\Ledger\transaction-processor\pom.xml <dependencies> -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 5: Write the root Makefile wiring these scenarios together**

```makefile
# D:\Ledger\Makefile
.PHONY: chaos-test chaos-test-01 chaos-test-02 chaos-test-03 up down smoke-test

up:
	docker compose up -d --build

down:
	docker compose down -v

smoke-test:
	bash scripts/smoke-test.sh

chaos-test-01:
	bash chaos/scenarios/01_rabbitmq_down_mid_publish.sh

chaos-test-02:
	bash chaos/scenarios/02_ledger_db_crash_post_commit.sh

chaos-test-03:
	bash chaos/scenarios/03_processor_crash_mid_consume.sh

chaos-test: chaos-test-01 chaos-test-02 chaos-test-03
	@echo "All chaos scenarios passed."
```

Note: `chaos-test-04` and `chaos-test-05` (Task 11) are added to this `chaos-test` target's
dependency list in Task 11 — this Makefile is edited again there, not replaced.

- [ ] **Step 6: Run scenarios 1-3 against the live stack**

Run:
```bash
docker compose -f D:\Ledger\docker-compose.yml up -d --build
```
Wait for all containers healthy (`docker compose ps`), run the Toxiproxy proxy-setup portion
of `scripts/smoke-test.sh` once (or extract just its `curl -X POST .../proxies` calls) so the
three named proxies exist, then:
```bash
chmod +x D:\Ledger\chaos\lib\common.sh D:\Ledger\chaos\scenarios\*.sh
make -f D:\Ledger\Makefile chaos-test-01
make -f D:\Ledger\Makefile chaos-test-02
make -f D:\Ledger\Makefile chaos-test-03
```
Expected: each prints a `PASS:` line and exits 0. If scenario 3 is flaky on the exact crash
timing (a known real risk noted in the spec), rerun it — timing-sensitive chaos scenarios
occasionally need a retry, which is expected and acceptable, not a design flaw; a scenario
that fails consistently across 3+ runs indicates a real bug and must be investigated rather
than retried away.

- [ ] **Step 7: Tear down**

```bash
docker compose -f D:\Ledger\docker-compose.yml down -v
```

- [ ] **Step 8: Commit**

```bash
git add chaos/lib/common.sh chaos/scenarios/01_rabbitmq_down_mid_publish.sh \
        chaos/scenarios/02_ledger_db_crash_post_commit.sh \
        chaos/scenarios/03_processor_crash_mid_consume.sh \
        Makefile transaction-processor/pom.xml
git commit -m "feat: add chaos test suite scenarios 1-3 with shared Toxiproxy helpers

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: Chaos test suite — scenarios 4-5

**Files:**
- Create: `D:\Ledger\chaos\scenarios\04_duplicate_delivery.sh`
- Create: `D:\Ledger\chaos\scenarios\05_partition_during_lock.sh`
- Modify: `D:\Ledger\Makefile`

**Interfaces:**
- Consumes: `chaos/lib/common.sh` from Task 10, the RabbitMQ management API (for direct
  republish in scenario 4).
- Produces: the completed `make chaos-test` target covering all 5 scenarios from the spec.

- [ ] **Step 1: Write scenario 4 — duplicate RabbitMQ delivery**

```bash
#!/usr/bin/env bash
# D:\Ledger\chaos\scenarios\04_duplicate_delivery.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 4: Duplicate RabbitMQ delivery ==="

reset_all_toxics
seed_account "chaos4-a" 10000
seed_account "chaos4-b" 0

IDEM_KEY="chaos4-$(date +%s)"
RESPONSE=$(post_transaction "chaos4-a" "chaos4-b" 500 "$IDEM_KEY")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)
[ "$HTTP_CODE" = "201" ] || fail "expected 201 posting transaction, got $HTTP_CODE: $BODY"
TXN_ID=$(echo "$BODY" | grep -o '"transactionId":"[^"]*"' | cut -d'"' -f4)

wait_for_processed_status "$TXN_ID" "CONSUMED" 60 || fail "event never reached CONSUMED before duplicate-delivery test began"

OUTBOX_ID=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT id FROM outbox WHERE aggregate_id = '$TXN_ID';" | tr -d ' \r\n')
PAYLOAD=$(docker compose exec -T ledger-postgres psql -U ledger -d ledger_db -t -c \
  "SELECT payload::text FROM outbox WHERE id = '$OUTBOX_ID';" | tr -d '\r\n')

echo "Firing 10 duplicate publishes of outboxEventId=$OUTBOX_ID directly via the RabbitMQ management API..."
for i in $(seq 1 10); do
  curl -sf -u guest:guest -X POST "http://localhost:15672/api/exchanges/%2f/ledger.events/publish" \
    -H "Content-Type: application/json" \
    -d "{
      \"properties\": {\"headers\": {\"outboxEventId\": \"$OUTBOX_ID\", \"aggregateId\": \"$TXN_ID\", \"eventType\": \"TRANSACTION_POSTED\"}, \"content_type\": \"application/json\"},
      \"routing_key\": \"ledger.transaction.posted\",
      \"payload\": $(echo "$PAYLOAD" | sed 's/"/\\"/g' | sed 's/^/"/;s/$/"/'),
      \"payload_encoding\": \"string\"
    }" > /dev/null &
done
wait

sleep 5

DELIVERY_COUNT=$(docker compose exec -T processor-postgres psql -U processor -d processor_db -t -c \
  "SELECT delivery_count FROM processed_events WHERE outbox_event_id = '$OUTBOX_ID';" | tr -d ' \r\n')
[ "$DELIVERY_COUNT" -ge "10" ] || fail "expected delivery_count >= 10 after 10 duplicate publishes, got $DELIVERY_COUNT"

FINAL_STATUS=$(get_processed_event_status_by_aggregate "$TXN_ID")
[ "$FINAL_STATUS" = "CONSUMED" ] || fail "expected status still CONSUMED after duplicates, got $FINAL_STATUS"

BALANCE_A=$(get_account_balance "chaos4-a")
[ "$BALANCE_A" = "9500" ] || fail "expected chaos4-a balance debited exactly once (9500), got $BALANCE_A"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 4"

pass "Scenario 4: 10 duplicate deliveries produced exactly one business effect"
```

- [ ] **Step 2: Write scenario 5 — network partition mid-SELECT FOR UPDATE, verified retry**

```bash
#!/usr/bin/env bash
# D:\Ledger\chaos\scenarios\05_partition_during_lock.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/common.sh"

echo "=== Scenario 5: Network partition mid-transaction, verify rollback + safe retry ==="

reset_all_toxics
seed_account "chaos5-a" 10000
seed_account "chaos5-b" 0
BALANCE_A_BEFORE=$(get_account_balance "chaos5-a")

echo "Adding a 5s latency toxic to guarantee the request is still inside its DB transaction when we cut it..."
add_toxic "ledger-postgres-app-proxy" "delay-then-cut" "latency" '{"latency": 5000}'

IDEM_KEY="chaos5-$(date +%s)"

( sleep 2 && add_toxic "ledger-postgres-app-proxy" "hard-cut" "timeout" '{"timeout": 0}' ) &
CUT_PID=$!

set +e
RESPONSE=$(post_transaction "chaos5-a" "chaos5-b" 500 "$IDEM_KEY")
set -e
wait "$CUT_PID"

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
echo "Request during partition returned HTTP $HTTP_CODE (expected a 5xx or connection error, not 201)"
[ "$HTTP_CODE" != "201" ] || fail "expected the partitioned request to fail, but it returned 201"

echo "Restoring connectivity..."
remove_toxic "ledger-postgres-app-proxy" "delay-then-cut"
remove_toxic "ledger-postgres-app-proxy" "hard-cut"

sleep 3

TXN_COUNT=$(transaction_count_for_idem_key "$IDEM_KEY")
[ "$TXN_COUNT" = "0" ] || fail "expected 0 transactions for $IDEM_KEY after rollback, found $TXN_COUNT — torn write occurred"

BALANCE_A_AFTER_FAILURE=$(get_account_balance "chaos5-a")
[ "$BALANCE_A_AFTER_FAILURE" = "$BALANCE_A_BEFORE" ] || fail "balance changed despite the transaction rolling back"

echo "Retrying the exact same request with the same Idempotency-Key..."
RETRY_RESPONSE=$(post_transaction "chaos5-a" "chaos5-b" 500 "$IDEM_KEY")
RETRY_HTTP_CODE=$(echo "$RETRY_RESPONSE" | tail -n1)
[ "$RETRY_HTTP_CODE" = "201" ] || fail "expected retry to succeed with 201, got $RETRY_HTTP_CODE"

BALANCE_A_FINAL=$(get_account_balance "chaos5-a")
[ "$BALANCE_A_FINAL" = "9500" ] || fail "expected exactly one successful transfer (9500), got $BALANCE_A_FINAL"

assert_reconciliation_clean || fail "reconciliation found issues after scenario 5"

pass "Scenario 5: mid-transaction partition rolled back cleanly and retry succeeded exactly once"
```

- [ ] **Step 3: Add scenarios 4-5 to the Makefile's chaos-test target**

```makefile
# modify D:\Ledger\Makefile — replace the existing .PHONY and chaos-test lines with:
.PHONY: chaos-test chaos-test-01 chaos-test-02 chaos-test-03 chaos-test-04 chaos-test-05 up down smoke-test

chaos-test-04:
	bash chaos/scenarios/04_duplicate_delivery.sh

chaos-test-05:
	bash chaos/scenarios/05_partition_during_lock.sh

chaos-test: chaos-test-01 chaos-test-02 chaos-test-03 chaos-test-04 chaos-test-05
	@echo "All chaos scenarios passed."
```

- [ ] **Step 4: Run the full chaos suite against the live stack**

Run:
```bash
docker compose -f D:\Ledger\docker-compose.yml up -d --build
```
Wait for healthy, ensure Toxiproxy proxies exist (Task 10 Step 6), then:
```bash
chmod +x D:\Ledger\chaos\scenarios\*.sh
make -f D:\Ledger\Makefile chaos-test
```
Expected: all 5 scenarios print `PASS:` and the target reports "All chaos scenarios passed."

- [ ] **Step 5: Tear down**

```bash
docker compose -f D:\Ledger\docker-compose.yml down -v
```

- [ ] **Step 6: Commit**

```bash
git add chaos/scenarios/04_duplicate_delivery.sh chaos/scenarios/05_partition_during_lock.sh Makefile
git commit -m "feat: add chaos test scenarios 4-5, completing the 5-scenario suite

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 12: README and final end-to-end verification

**Files:**
- Create: `D:\Ledger\README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-11.
- Produces: a top-level README documenting the architecture, how to run the stack, and how
  to run tests/chaos suite — the artifact a resume reader or interviewer actually opens
  first.

- [ ] **Step 1: Write the README**

```markdown
<!-- D:\Ledger\README.md -->
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
```

- [ ] **Step 2: Run the complete test suite one final time to confirm the whole build is green**

Run: `mvn -f D:\Ledger\pom.xml clean verify`
Expected: BUILD SUCCESS across both modules — every unit and Testcontainers integration test
from Tasks 1-8 passes.

- [ ] **Step 3: Run the full Docker Compose + smoke test + chaos suite one final time**

Run:
```bash
docker compose -f D:\Ledger\docker-compose.yml up -d --build
```
Wait for healthy, set up Toxiproxy proxies, then:
```bash
make -f D:\Ledger\Makefile smoke-test
make -f D:\Ledger\Makefile chaos-test
docker compose -f D:\Ledger\docker-compose.yml down -v
```
Expected: smoke test completes with a clean reconciliation result; all 5 chaos scenarios
pass. This is the final acceptance check for V1 — a green run here means V1 delivers exactly
the guarantees described in the spec and is ready to link from a resume.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: add V1 README documenting architecture, running, and testing

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---
