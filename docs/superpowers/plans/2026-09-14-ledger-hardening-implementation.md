# Ledger Hardening (Hold Atomicity, Quote Expiry, Observability) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close two real correctness gaps (holds not respected by direct transaction posting; FX quote expiry never enforced) and add Prometheus/Grafana observability across all 5 services, before starting V5.

**Architecture:** (1) Ledger Service's `TransactionPoster` gains a synchronous, in-transaction call to a new Holds Service endpoint returning just the held amount, replacing the posted-balance-only funds check. (2) `pending_fx_transfers` gains an `expires_at` column, checked by `CrossCurrencyTransferPoster.postLeg2` before posting, flowing into the saga's existing compensate-on-failure path. (3) Micrometer + Actuator on all 5 services, two new Docker Compose containers (Prometheus, Grafana), 7 additive metrics.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA, `RestClient`, Flyway, Micrometer + `micrometer-registry-prometheus`, Prometheus, Grafana, Testcontainers, JUnit 5.

**Spec:** [docs/superpowers/specs/2026-09-14-ledger-hardening-hold-atomicity-quote-expiry-observability.md](../specs/2026-09-14-ledger-hardening-hold-atomicity-quote-expiry-observability.md)

## Global Constraints

- Money is always integer minor units (`long`), never floating point. `rateUsed` is the sole `BigDecimal` (a ratio, correctly not this constraint's concern).
- `@Transactional` methods must never be called via self-invocation. Any new `@Transactional` method must be on a bean called from a *different* bean.
- This project's Maven config lacks the `-parameters` compiler flag — any new `@PathVariable`/`@RequestParam` needs an explicit name argument (e.g. `@PathVariable("accountRef") String accountRef`).
- Testcontainers on this dev machine requires `DOCKER_HOST=tcp://127.0.0.1:2375`, `DOCKER_API_VERSION=1.44`, and Maven flag `-Dapi.version=1.44` (not `tcp://localhost:2375`, which fails DNS resolution in this shell). Plain `docker`/`docker compose` CLI commands work without these.
- Existing ports, do not collide: API Gateway `8080`, Transaction Processor `8081`, Holds Service `8082`, FX Service `8083`, Ledger Service `8090`, Keycloak `8180`.
- `TransactionPoster.FX_CLEARING_ACCOUNT_REF_PREFIX = "fx-clearing-"` is the existing shared constant for identifying platform-internal clearing accounts — reuse it, never duplicate the literal string.
- All new HTTP client classes follow the existing `RestClient`-based pattern (see `LedgerTransactionClient`/`FxServiceClient`), not `RestTemplate` or `WebClient`.

---

### Task 1: Holds Service — `GET /accounts/{accountRef}/held-balance` endpoint

**Files:**
- Create: `holds-service/src/main/java/com/ledger/holdsservice/api/dto/HeldBalanceResponse.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/api/HoldController.java`
- Create: `holds-service/src/test/java/com/ledger/holdsservice/service/HeldBalanceIntegrationTest.java`

**Interfaces:**
- Consumes: `AccountBalanceCacheRepository` (existing, `findById(String): Optional<AccountBalanceCache>`).
- Produces: `HoldService.getHeldBalance(String accountRef): HeldBalanceResponse` — Task 4 (Ledger Service's client) depends on the exact response shape `HeldBalanceResponse(String accountRef, long heldBalanceMinor)`. New route `GET /accounts/{accountRef}/held-balance`.

- [ ] **Step 1: Write the failing test**

Model on `holds-service/src/test/java/com/ledger/holdsservice/service/HoldServiceIntegrationTest.java` (read it first for the exact Testcontainers/`@SpringBootTest` pattern this module uses).

```java
package com.ledger.holdsservice.service;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
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

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class HeldBalanceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    HoldService holdService;

    @Autowired
    AccountBalanceCacheRepository accountBalanceCacheRepository;

    @Test
    void getHeldBalanceReturnsTheCurrentlyHeldAmountForAKnownAccount() {
        accountBalanceCacheRepository.save(new AccountBalanceCache("held-test-acct-1", 10_000L, 3_000L));

        var response = holdService.getHeldBalance("held-test-acct-1");

        assertThat(response.accountRef()).isEqualTo("held-test-acct-1");
        assertThat(response.heldBalanceMinor()).isEqualTo(3_000L);
    }

    @Test
    void getHeldBalanceReturnsZeroForAnUnknownAccountWithoutPersistingAnything() {
        var response = holdService.getHeldBalance("held-test-acct-never-seen");

        assertThat(response.accountRef()).isEqualTo("held-test-acct-never-seen");
        assertThat(response.heldBalanceMinor()).isEqualTo(0L);
        assertThat(accountBalanceCacheRepository.findById("held-test-acct-never-seen")).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`):
```bash
mvn -pl holds-service -am test -Dtest=HeldBalanceIntegrationTest -Dapi.version=1.44
```
Expected: FAIL — compile error, `HeldBalanceResponse`/`getHeldBalance` don't exist.

- [ ] **Step 3: Write `HeldBalanceResponse`**

```java
package com.ledger.holdsservice.api.dto;

public record HeldBalanceResponse(String accountRef, long heldBalanceMinor) {
}
```

- [ ] **Step 4: Add `getHeldBalance` to `HoldService`**

Read the existing `getAvailableBalance` method in `HoldService.java` first — this follows the exact same pattern, deliberately not persisting a row for an unknown account (a read should never have a write side effect):

```java
public HeldBalanceResponse getHeldBalance(String accountRef) {
    long heldBalanceMinor = accountBalanceCacheRepository.findById(accountRef)
            .map(AccountBalanceCache::getHeldBalanceMinor)
            .orElse(0L);
    return new HeldBalanceResponse(accountRef, heldBalanceMinor);
}
```

Add the import: `import com.ledger.holdsservice.api.dto.HeldBalanceResponse;`

- [ ] **Step 5: Add the route to `HoldController`**

```java
@GetMapping("/accounts/{accountRef}/held-balance")
public ResponseEntity<HeldBalanceResponse> heldBalance(@PathVariable("accountRef") String accountRef) {
    return ResponseEntity.ok(holdService.getHeldBalance(accountRef));
}
```
Add the import: `import com.ledger.holdsservice.api.dto.HeldBalanceResponse;`

- [ ] **Step 6: Run to verify it passes**

Run: `mvn -pl holds-service -am test -Dtest=HeldBalanceIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 2, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full holds-service suite**

Run: `mvn -pl holds-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors (this task is purely additive — no existing behavior changed).

- [ ] **Step 8: Commit**

```bash
git add holds-service/src/main/java/com/ledger/holdsservice/api/dto/HeldBalanceResponse.java \
        holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java \
        holds-service/src/main/java/com/ledger/holdsservice/api/HoldController.java \
        holds-service/src/test/java/com/ledger/holdsservice/service/HeldBalanceIntegrationTest.java
git commit -m "feat(holds-service): add GET /accounts/{accountRef}/held-balance endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Ledger Service — `HoldsServiceClient` with a bounded timeout

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/holds/HoldsServiceClient.java`
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/holds/HoldsServiceUnavailableException.java`
- Modify: `ledger-service/src/main/resources/application.yml`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/holds/HoldsServiceClientIntegrationTest.java`

**Interfaces:**
- Consumes: `holds.base-url`, `holds.held-balance-timeout-ms` config (new).
- Produces: `HoldsServiceClient.getHeldBalance(String accountRef): long` — Task 3 (`TransactionPoster`) depends on this exact signature. Throws `HoldsServiceUnavailableException` (unchecked) on any HTTP failure, timeout, or malformed response.

- [ ] **Step 1: Add `holds.base-url` and timeout config to `application.yml`**

Add to `ledger-service/src/main/resources/application.yml` (as a sibling of the existing `fx:`/`processor:` top-level keys — do NOT create a second `holds:` block if one already exists; there is none yet, so this is a new top-level key):

```yaml
holds:
  base-url: ${HOLDS_SERVICE_URL:http://localhost:8082}
  held-balance-timeout-ms: ${HOLDS_HELD_BALANCE_TIMEOUT_MS:2000}
```

- [ ] **Step 2: Write `HoldsServiceUnavailableException`**

```java
package com.ledger.ledgerservice.holds;

public class HoldsServiceUnavailableException extends RuntimeException {
    public HoldsServiceUnavailableException(String accountRef, Throwable cause) {
        super("Could not reach Holds Service to check held balance for account: " + accountRef, cause);
    }
}
```

- [ ] **Step 3: Write the failing test for `HoldsServiceClient`**

Model the stub server on `holds-service`'s `HoldCaptureIntegrationTest`'s raw `com.sun.net.httpserver.HttpServer` pattern (no Testcontainers/Spring context needed — this is a plain unit-style test of an HTTP client class), same as `FxServiceClientIntegrationTest` already does in this exact module.

```java
package com.ledger.ledgerservice.holds;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldsServiceClientIntegrationTest {

    HttpServer stubHoldsService;

    @AfterEach
    void stopStub() {
        if (stubHoldsService != null) {
            stubHoldsService.stop(0);
        }
    }

    @Test
    void getHeldBalanceParsesTheResponseCorrectly() throws Exception {
        stubHoldsService = HttpServer.create(new InetSocketAddress(0), 0);
        stubHoldsService.createContext("/accounts/held-test-acct/held-balance", exchange -> {
            String body = "{\"accountRef\":\"held-test-acct\",\"heldBalanceMinor\":4200}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubHoldsService.start();

        HoldsServiceClient client = new HoldsServiceClient(
                "http://localhost:" + stubHoldsService.getAddress().getPort(), 2000);

        long heldBalance = client.getHeldBalance("held-test-acct");

        assertThat(heldBalance).isEqualTo(4200L);
    }

    @Test
    void getHeldBalanceThrowsHoldsServiceUnavailableWhenTheServerIsUnreachable() {
        // Nothing is listening on this port -- connection refused.
        HoldsServiceClient client = new HoldsServiceClient("http://localhost:1", 500);

        assertThatThrownBy(() -> client.getHeldBalance("held-test-acct"))
                .isInstanceOf(HoldsServiceUnavailableException.class);
    }

    @Test
    void getHeldBalanceThrowsHoldsServiceUnavailableOnTimeout() throws Exception {
        stubHoldsService = HttpServer.create(new InetSocketAddress(0), 0);
        stubHoldsService.createContext("/accounts/slow-acct/held-balance", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] response = "{\"accountRef\":\"slow-acct\",\"heldBalanceMinor\":0}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubHoldsService.start();

        HoldsServiceClient client = new HoldsServiceClient(
                "http://localhost:" + stubHoldsService.getAddress().getPort(), 200);

        assertThatThrownBy(() -> client.getHeldBalance("slow-acct"))
                .isInstanceOf(HoldsServiceUnavailableException.class);
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=HoldsServiceClientIntegrationTest -Dapi.version=1.44`
Expected: FAIL — compile error, `HoldsServiceClient` does not exist.

- [ ] **Step 5: Write `HoldsServiceClient`**

Uses Spring's `ClientHttpRequestFactorySettings` to apply an explicit connect+read timeout — this codebase has no prior timeout-configuration precedent, so this introduces the pattern cleanly for the first time.

```java
package com.ledger.ledgerservice.holds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactories;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class HoldsServiceClient {

    private final RestClient restClient;

    public HoldsServiceClient(@Value("${holds.base-url}") String holdsBaseUrl,
                               @Value("${holds.held-balance-timeout-ms}") long timeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(timeoutMs))
                .withReadTimeout(Duration.ofMillis(timeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
                .baseUrl(holdsBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public long getHeldBalance(String accountRef) {
        record Response(String accountRef, long heldBalanceMinor) {
        }

        try {
            Response response = restClient.get()
                    .uri("/accounts/{accountRef}/held-balance", accountRef)
                    .retrieve()
                    .body(Response.class);
            return response.heldBalanceMinor();
        } catch (RestClientException e) {
            throw new HoldsServiceUnavailableException(accountRef, e);
        }
    }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=HoldsServiceClientIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 3, Failures: 0, Errors: 0`, `BUILD SUCCESS`. If the timeout test is flaky (a 200ms timeout is tight), increase the stub's sleep to 2000ms and the client's timeout to 300ms to widen the margin — verify the actual test machine's timing rather than guessing.

- [ ] **Step 7: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors — this task adds a new, unused-so-far client; Task 3 wires it in.

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/holds \
        ledger-service/src/main/resources/application.yml \
        ledger-service/src/test/java/com/ledger/ledgerservice/holds/HoldsServiceClientIntegrationTest.java
git commit -m "feat(ledger-service): add HoldsServiceClient with a bounded timeout

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: `TransactionPoster` — respect held balance when checking funds

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java`
- Modify: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceHoldsUnavailableIntegrationTest.java`

**Interfaces:**
- Consumes: `HoldsServiceClient.getHeldBalance(String): long` (Task 2), `HoldsServiceUnavailableException` (Task 2), `TransactionPoster.FX_CLEARING_ACCOUNT_REF_PREFIX` (existing).
- Produces: nothing new consumed by later tasks — this is the load-bearing correctness fix itself.

- [ ] **Step 1: Read the existing insufficient-funds check**

Re-read `TransactionPoster.postInTransaction` (already shown in full during plan-writing exploration) — the check currently reads:
```java
boolean debitAccountAllowsNegativeBalance =
        debitAccount.getAccountRef().startsWith(FX_CLEARING_ACCOUNT_REF_PREFIX);
if (!debitAccountAllowsNegativeBalance && debitAccount.getBalanceMinor() < request.amountMinor()) {
    throw new InsufficientFundsException(debitAccount.getAccountRef());
}
```

- [ ] **Step 2: Write the failing test proving a hold blocks an otherwise-fundable transfer**

Add to `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`. This test needs a stub Holds Service — model the stub setup on the existing `CrossCurrencyTransferServiceIntegrationTest`'s `com.sun.net.httpserver.HttpServer` pattern for stubbing an external service in this same test style, registered via `@DynamicPropertySource`.

Read the top of `TransactionServiceIntegrationTest.java` first to see its exact `@Testcontainers`/`@SpringBootTest`/`@DynamicPropertySource` setup before adding to it — the stub needs to be added alongside the existing Postgres container registration, not replacing it.

```java
// Add to the existing @DynamicPropertySource static block (alongside spring.datasource.* registrations):
static com.sun.net.httpserver.HttpServer stubHoldsService;
static final java.util.concurrent.atomic.AtomicLong stubbedHeldBalance = new java.util.concurrent.atomic.AtomicLong(0L);

// Inside the existing @DynamicPropertySource method, after registering datasource properties:
stubHoldsService = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
stubHoldsService.createContext("/", exchange -> {
    String path = exchange.getRequestURI().getPath();
    String accountRef = path.substring("/accounts/".length(), path.length() - "/held-balance".length());
    String body = "{\"accountRef\":\"" + accountRef + "\",\"heldBalanceMinor\":" + stubbedHeldBalance.get() + "}";
    byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
});
stubHoldsService.start();
registry.add("holds.base-url", () -> "http://localhost:" + stubHoldsService.getAddress().getPort());
registry.add("holds.held-balance-timeout-ms", () -> "2000");
```

Then add the test itself:

```java
@Test
void aHeldAmountReducesAvailableFundsEvenThoughPostedBalanceWouldCoverIt() {
    // acct-a has 10,000 posted (seeded in @BeforeEach per this test class's existing pattern --
    // check the actual seeded amount and adjust this test's numbers to match reality rather than
    // guessing) with 9,500 held, leaving only 500 truly available.
    stubbedHeldBalance.set(9_500L);

    assertThatThrownBy(() -> transactionService.postTransaction(
            new CreateTransactionRequest("acct-a", "acct-b", 1_000L, "USD", "should fail: held funds"),
            "held-funds-test-1"))
            .isInstanceOf(InsufficientFundsException.class);

    stubbedHeldBalance.set(0L);
}

@Test
void aTransferWithinTheHeldAdjustedAvailableBalanceSucceeds() {
    stubbedHeldBalance.set(9_500L);

    var response = transactionService.postTransaction(
            new CreateTransactionRequest("acct-a", "acct-b", 500L, "USD", "should succeed: within available"),
            "held-funds-test-2");

    assertThat(response.status()).isEqualTo("POSTED");
    stubbedHeldBalance.set(0L);
}

@Test
void fxClearingAccountsSkipTheHeldBalanceCheckEntirely() {
    // Even if the stub were to report a huge held balance, fx-clearing- accounts must never
    // call out to Holds Service at all -- set an impossibly large held amount to prove the
    // check is skipped, not merely satisfied.
    stubbedHeldBalance.set(Long.MAX_VALUE / 2);

    var response = transactionService.postTransaction(
            new CreateTransactionRequest("fx-clearing-USD", "acct-a", 500L, "USD", "clearing account bypass"),
            "held-funds-test-3");

    assertThat(response.status()).isEqualTo("POSTED");
    stubbedHeldBalance.set(0L);
}

```

**The "Holds Service unavailable" case belongs in its own test file, not in the shared `TransactionServiceIntegrationTest` class.** Stopping the shared static stub HTTP server used by the tests above would break every other test in that class that runs afterward (JUnit does not guarantee method execution order by default). Instead, create a new file:

`ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceHoldsUnavailableIntegrationTest.java`:

```java
package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.holds.HoldsServiceUnavailableException;
import com.ledger.ledgerservice.repository.AccountRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolated from {@link TransactionServiceIntegrationTest} deliberately: this class points
 * {@code holds.base-url} at a fixed, never-listening port (mirroring
 * {@code HoldsServiceClientIntegrationTest}'s {@code http://localhost:1} connection-refused
 * technique) for its entire lifetime, rather than starting and stopping a real stub server --
 * which would make test order matter for whichever other tests share a stub server instance.
 */
@Testcontainers
@SpringBootTest(properties = {
        "holds.base-url=http://localhost:1",
        "holds.held-balance-timeout-ms=500"
})
@ActiveProfiles("test")
class TransactionServiceHoldsUnavailableIntegrationTest {

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
    TransactionService transactionService;

    @Autowired
    AccountRepository accountRepository;

    @BeforeEach
    void seedAccounts() {
        if (accountRepository.findByAccountRef("holds-down-a").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "holds-down-a", "A", "USD",
                    10_000L, AccountStatus.ACTIVE, null));
        }
        if (accountRepository.findByAccountRef("holds-down-b").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "holds-down-b", "B", "USD",
                    0L, AccountStatus.ACTIVE, null));
        }
    }

    @Test
    void postTransactionFailsClosedWhenHoldsServiceIsUnreachable() {
        assertThatThrownBy(() -> transactionService.postTransaction(
                new CreateTransactionRequest("holds-down-a", "holds-down-b", 100L, "USD",
                        "should fail closed"),
                "holds-unavailable-test-1"))
                .isInstanceOf(HoldsServiceUnavailableException.class);
    }
}
```

- [ ] **Step 3: Run to verify the tests fail**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — the held-balance check doesn't exist yet, so the "held funds reduce availability" test fails (transaction posts when it shouldn't), and `HoldsServiceUnavailableException` isn't referenced/thrown anywhere yet.

- [ ] **Step 4: Modify `TransactionPoster` to check held balance**

```java
// Add to the constructor and fields:
private final HoldsServiceClient holdsServiceClient;

public TransactionPoster(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          EntryRepository entryRepository,
                          OutboxRepository outboxRepository,
                          ObjectMapper objectMapper,
                          HoldsServiceClient holdsServiceClient) {
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.entryRepository = entryRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.holdsServiceClient = holdsServiceClient;
}
```

Add the import: `import com.ledger.ledgerservice.holds.HoldsServiceClient;`

Replace the insufficient-funds check block with:
```java
boolean debitAccountAllowsNegativeBalance =
        debitAccount.getAccountRef().startsWith(FX_CLEARING_ACCOUNT_REF_PREFIX);
if (!debitAccountAllowsNegativeBalance) {
    long heldBalanceMinor = holdsServiceClient.getHeldBalance(debitAccount.getAccountRef());
    long availableBalanceMinor = debitAccount.getBalanceMinor() - heldBalanceMinor;
    if (request.amountMinor() > availableBalanceMinor) {
        throw new InsufficientFundsException(debitAccount.getAccountRef());
    }
}
```

Note: `HoldsServiceUnavailableException` is unchecked (`RuntimeException`), so it propagates naturally out of `postInTransaction` without any explicit catch here — it rolls back the transaction the same way any other unhandled exception inside a `@Transactional` method does.

- [ ] **Step 5: Map `HoldsServiceUnavailableException` to 503 in `ApiExceptionHandler`**

```java
@ExceptionHandler(com.ledger.ledgerservice.holds.HoldsServiceUnavailableException.class)
public ResponseEntity<Map<String, String>> handleHoldsServiceUnavailable(
        com.ledger.ledgerservice.holds.HoldsServiceUnavailableException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
}
```
(Use a proper top-of-file import rather than the fully-qualified name shown here for readability — `import com.ledger.ledgerservice.holds.HoldsServiceUnavailableException;`.)

- [ ] **Step 6: Run to verify the tests pass**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: all tests in the class pass, including the new ones. If the FX-clearing-account test fails because the stub server was still asked (defeating the "skip entirely" proof), re-check the bypass condition placement — it must guard the `holdsServiceClient.getHeldBalance` call itself, not just the final comparison.

- [ ] **Step 7: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors. Pay close attention to `CrossCurrencyTransferServiceIntegrationTest` and `FxTransferRecoverySweepIntegrationTest` — both post transactions via `TransactionPoster` indirectly through the saga, and now implicitly depend on a reachable Holds Service. If those tests don't already have a Holds Service stub registered, they will now fail with `HoldsServiceUnavailableException`. **This is expected and must be fixed as part of this task**: add the same `holds.base-url` stub-server registration (returning `heldBalanceMinor: 0` for every account, since none of those tests involve holds) to every existing test class in `ledger-service` that exercises `TransactionPoster` indirectly (grep for `transactionService.postTransaction\|TransactionPoster\|CrossCurrencyTransferPoster\|CrossCurrencyTransferService` across `ledger-service/src/test` to find every affected class). Add a shared, reusable stub helper if the same setup needs repeating across 2+ test classes, rather than duplicating the raw `HttpServer` boilerplate — check whether `chaos`/`common.sh`-style shared-helper precedent exists for Java tests in this codebase first (it likely doesn't; if not, a small package-private static utility method in a new `ledger-service/src/test/java/com/ledger/ledgerservice/testsupport/StubHoldsService.java` is reasonable, following whatever minimal shape avoids repeating the same 15 lines in every file).

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/
git commit -m "fix(ledger-service): TransactionPoster respects held balance when checking funds

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Docker Compose wiring for the new Holds Service dependency

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: nothing consumed by later tasks in this plan — this is deployment wiring.

- [ ] **Step 1: Add `HOLDS_SERVICE_URL` to `ledger-service`'s environment**

In `docker-compose.yml`, find `ledger-service`'s existing `environment:` block (it already has `FX_SERVICE_URL`, `PROCESSOR_BASE_URL`, etc. from earlier versions) and add:
```yaml
      HOLDS_SERVICE_URL: http://holds-service:8082
```

- [ ] **Step 2: Add `holds-service` to `ledger-service`'s `depends_on`**

Ledger Service now has a real runtime dependency on Holds Service (every `POST /transactions` call needs it). Add to `ledger-service`'s existing `depends_on:` block:
```yaml
      holds-service:
        condition: service_started
```
(`service_started`, not `service_healthy`, matching the existing convention for other same-tier runtime dependencies in this file, e.g. `fx-service`.)

**Watch for a circular dependency**: `holds-service` already depends on `ledger-service` (it calls `POST /transactions` on capture, and consumes `ledger.transaction.posted` events). Adding `ledger-service` → `holds-service` creates a cycle at the `depends_on` level, which Docker Compose does not allow (`depends_on` must form a DAG). Since `service_started` (not `service_healthy`) is a weak ordering hint rather than a hard health gate, and both services will genuinely need each other at runtime regardless of start order, resolve this by removing the `depends_on` addition from Step 2 and instead relying on the fact that a transient `HoldsServiceUnavailableException`/503 during the brief window before Holds Service finishes starting is already correctly handled (fails closed, safe to retry) — this doesn't need a docker-compose ordering guarantee to be correct, only to be occasionally slower to succeed on a cold start. Do not introduce a dependency cycle to avoid this; document the acceptable cold-start race instead.

- [ ] **Step 3: Verify no cycle exists**

Run: `docker compose config --services` — should succeed without error (a cycle would cause `docker compose up` to fail outright, not just misorder startup). If Step 2's resolution was applied correctly, this command succeeds.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "chore: wire ledger-service's new Holds Service dependency into Docker Compose

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: `pending_fx_transfers` migration + persist `expires_at`

**Files:**
- Create: `ledger-service/src/main/resources/db/migration/V6__add_expires_at_to_pending_fx_transfers.sql`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransfer.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferService.java`
- Create: `ledger-service/src/test/java/com/ledger/ledgerservice/fx/PendingFxTransferExpiryIntegrationTest.java`

**Interfaces:**
- Consumes: `FxQuote.expiresAt(): Instant` (existing).
- Produces: `PendingFxTransfer.getExpiresAt(): Instant` — Task 6 depends on this exact getter.

- [ ] **Step 1: Write the migration**

```sql
ALTER TABLE pending_fx_transfers ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT now();
```

(`DEFAULT now()` only matters for backfilling any pre-existing rows in a running database — every row created going forward always supplies a real value explicitly. `NOT NULL` with a default is safe on Postgres without a table rewrite lock concern at this table's expected size.)

- [ ] **Step 2: Write the failing test**

```java
package com.ledger.ledgerservice.fx;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PendingFxTransferExpiryIntegrationTest {

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

    @Autowired
    PendingFxTransferRepository pendingFxTransferRepository;

    @Test
    void pendingFxTransfersTableAcceptsAnExplicitExpiresAt() {
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(60, ChronoUnit.SECONDS);
        jdbcTemplate.update(
                "INSERT INTO pending_fx_transfers " +
                        "(id, idempotency_key, quote_id, source_account_ref, dest_account_ref, " +
                        "source_amount_minor, rate_used, dest_amount_minor, expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, "expiry-test-key-1", UUID.randomUUID(), "acct-a", "acct-b",
                10_000L, new BigDecimal("0.92000000"), 9_200L, java.sql.Timestamp.from(expiresAt));

        java.sql.Timestamp stored = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM pending_fx_transfers WHERE id = ?", java.sql.Timestamp.class, id);
        assertThat(stored.toInstant()).isCloseTo(expiresAt, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    }

    @Test
    void entityExposesGetExpiresAt() {
        Instant expiresAt = Instant.now().plus(60, ChronoUnit.SECONDS);
        PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), "expiry-test-key-2",
                UUID.randomUUID(), "acct-a", "acct-b", 10_000L, new BigDecimal("0.92000000"),
                9_200L, expiresAt);
        pendingFxTransferRepository.save(transfer);

        PendingFxTransfer reloaded = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reloaded.getExpiresAt()).isCloseTo(expiresAt, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=PendingFxTransferExpiryIntegrationTest -Dapi.version=1.44`
Expected: FAIL — the migration doesn't exist yet (first test) and the constructor doesn't accept `expiresAt` yet (second test, compile error).

- [ ] **Step 4: Add `expiresAt` to the `PendingFxTransfer` entity**

Modify the field list, constructor, and getter list:

```java
@Column(name = "expires_at", nullable = false)
private Instant expiresAt;
```
(add after `destAmountMinor`, before `status`)

Change the constructor from:
```java
public PendingFxTransfer(UUID id, String idempotencyKey, UUID quoteId, String sourceAccountRef,
                         String destAccountRef, long sourceAmountMinor, BigDecimal rateUsed,
                         long destAmountMinor) {
```
to:
```java
public PendingFxTransfer(UUID id, String idempotencyKey, UUID quoteId, String sourceAccountRef,
                         String destAccountRef, long sourceAmountMinor, BigDecimal rateUsed,
                         long destAmountMinor, Instant expiresAt) {
    this.id = id;
    this.idempotencyKey = idempotencyKey;
    this.quoteId = quoteId;
    this.sourceAccountRef = sourceAccountRef;
    this.destAccountRef = destAccountRef;
    this.sourceAmountMinor = sourceAmountMinor;
    this.rateUsed = rateUsed;
    this.destAmountMinor = destAmountMinor;
    this.expiresAt = expiresAt;
    this.status = PendingFxTransferStatus.PENDING;
}
```

Add the getter: `public Instant getExpiresAt() { return expiresAt; }`

This is a breaking constructor change (7 args → 8). Grep the whole repo for `new PendingFxTransfer(` (`grep -rn "new PendingFxTransfer(" --include="*.java" .`) and update every call site to pass the quote's `expiresAt` as the trailing argument. The only production call site is `CrossCurrencyTransferService.transfer()` (Step 5 below); test call sites are in `CrossCurrencyTransferServiceIntegrationTest`, `FxTransferRecoverySweepIntegrationTest`, `FxTransferRecoverySweepOptimisticLockRaceIntegrationTest`, and `PendingFxTransferOptimisticLockingIntegrationTest` — pass `Instant.now().plusSeconds(60)` as a reasonable non-expired default in each existing test call site unless that specific test is about expiry (none of the existing ones are).

- [ ] **Step 5: Wire `expiresAt` through in `CrossCurrencyTransferService.transfer()`**

Change:
```java
PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), request.idempotencyKey(),
        quote.quoteId(), request.sourceAccountRef(), request.destAccountRef(),
        request.sourceAmountMinor(), quote.rateUsed(), destAmountMinor);
```
to:
```java
PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), request.idempotencyKey(),
        quote.quoteId(), request.sourceAccountRef(), request.destAccountRef(),
        request.sourceAmountMinor(), quote.rateUsed(), destAmountMinor, quote.expiresAt());
```

- [ ] **Step 6: Run to verify it compiles and passes**

Run: `mvn -pl ledger-service -am compile` first to confirm every call site was fixed (a missed one is a compile error, not a silent bug — fix all before proceeding).

Then: `mvn -pl ledger-service -am test -Dtest=PendingFxTransferExpiryIntegrationTest -Dapi.version=1.44`
Expected: `Tests run: 2, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors — confirms every existing `new PendingFxTransfer(` call site was correctly updated.

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/resources/db/migration/V6__add_expires_at_to_pending_fx_transfers.sql \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/PendingFxTransfer.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferService.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/
git commit -m "feat(ledger-service): persist FX quote expiresAt on pending_fx_transfers

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Enforce quote expiry before posting leg 2

**Files:**
- Create: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxQuoteExpiredException.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferPoster.java`
- Modify: `ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `PendingFxTransfer.getExpiresAt()` (Task 5).
- Produces: nothing consumed by later tasks — this is the load-bearing correctness fix itself.

- [ ] **Step 1: Write `FxQuoteExpiredException`**

```java
package com.ledger.ledgerservice.fx;

import java.util.UUID;

public class FxQuoteExpiredException extends RuntimeException {
    public FxQuoteExpiredException(UUID pendingTransferId) {
        super("FX quote expired before leg 2 could be posted for transfer: " + pendingTransferId);
    }
}
```

- [ ] **Step 2: Write the failing test**

Add to `CrossCurrencyTransferServiceIntegrationTest.java`. This test needs a transfer whose quote has already expired by the time leg 2 would post — the cleanest way is to directly construct/save a `PendingFxTransfer` with an already-past `expiresAt` and a `LEG1_POSTED` status (simulating leg 1 having posted while the quote was still valid, with leg 2 delayed past expiry), then call `poster.postLeg2` directly rather than going through the full `transfer()` flow (which posts both legs back-to-back too fast to naturally hit an expiry window in a test).

```java
@Test
void postLeg2ThrowsAndTriggersCompensationWhenTheQuoteHasExpired() {
    seedAccountIfAbsent("fx-expiry-source", "USD", 100_000L);
    seedAccountIfAbsent("fx-expiry-dest", "EUR", 0L);

    PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), "expiry-saga-test-1",
            UUID.randomUUID(), "fx-expiry-source", "fx-expiry-dest", 5_000L,
            new BigDecimal("0.92000000"), 4_600L, Instant.now().minusSeconds(5));
    pendingFxTransferRepository.save(transfer);
    poster.postLeg1(transfer.getId());

    assertThatThrownBy(() -> poster.postLeg2(transfer.getId()))
            .isInstanceOf(FxQuoteExpiredException.class);

    // Confirm leg 2 never actually posted -- dest balance untouched.
    Account dest = accountRepository.findByAccountRef("fx-expiry-dest").orElseThrow();
    assertThat(dest.getBalanceMinor()).isEqualTo(0L);
}

@Test
void transferEndToEndCompensatesWhenTheSagaHitsAnExpiredQuoteDuringLeg2() {
    // Exercises the full transfer() path's catch-and-compensate handling of
    // FxQuoteExpiredException specifically, not just postLeg2 in isolation.
    seedAccountIfAbsent("fx-expiry-e2e-source", "USD", 100_000L);
    seedAccountIfAbsent("fx-expiry-e2e-dest", "EUR", 0L);

    // Manually drive the same sequence transfer() would, but inject an already-expired
    // quote by constructing PendingFxTransfer directly (transfer() itself always sets a
    // fresh, non-expired expiresAt from the quote it just locked, so this scenario can only
    // be exercised by simulating a delayed leg2 -- which is exactly what the crash-recovery
    // sweep's retry path does in production; this test proves the underlying mechanism
    // works, the sweep integration test in a later step proves the sweep drives it).
    PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), "expiry-saga-test-2",
            UUID.randomUUID(), "fx-expiry-e2e-source", "fx-expiry-e2e-dest", 5_000L,
            new BigDecimal("0.92000000"), 4_600L, Instant.now().minusSeconds(5));
    pendingFxTransferRepository.save(transfer);
    poster.postLeg1(transfer.getId());

    // Simulate what CrossCurrencyTransferService.transfer()'s try/catch does when postLeg2 throws:
    assertThatThrownBy(() -> poster.postLeg2(transfer.getId()))
            .isInstanceOf(FxQuoteExpiredException.class);
    poster.compensate(transfer.getId());

    PendingFxTransfer finalState = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
    assertThat(finalState.getStatus()).isEqualTo(PendingFxTransferStatus.COMPENSATED);

    Account source = accountRepository.findByAccountRef("fx-expiry-e2e-source").orElseThrow();
    assertThat(source.getBalanceMinor()).isEqualTo(100_000L); // fully reversed
}
```

(These tests assume `seedAccountIfAbsent`, `poster`, `pendingFxTransferRepository`, and `accountRepository` are already available as autowired fields/helper methods in this test class per its existing setup — read the current file to confirm the exact helper method name and adjust if it differs from `seedAccountIfAbsent`.)

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=CrossCurrencyTransferServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — `postLeg2` doesn't check expiry yet, so leg 2 posts successfully instead of throwing.

- [ ] **Step 4: Add the expiry check to `postLeg2`**

Modify `CrossCurrencyTransferPoster.postLeg2`:

```java
public void postLeg2(UUID pendingTransferId) {
    PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
    if (Instant.now().isAfter(transfer.getExpiresAt())) {
        throw new FxQuoteExpiredException(pendingTransferId);
    }
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
```

Add the import: `import java.time.Instant;` (if not already present in this file).

Note: the check happens *before* any posting attempt, so a genuinely expired quote never even calls `TransactionService.postTransaction` — no wasted work, no partial state to clean up. `CrossCurrencyTransferService.transfer()`'s existing `catch (Exception legFailure) { ...; poster.compensate(...); }` and `FxTransferRecoverySweep`'s existing `LEG1_POSTED` inner try/catch both already catch generic `Exception`, so `FxQuoteExpiredException` flows into the existing compensate-on-failure paths in both places with zero additional code changes there — this is the payoff of the spec's decision to make expiry "just another failure reason."

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=CrossCurrencyTransferServiceIntegrationTest -Dapi.version=1.44`
Expected: all tests in the class pass, including the two new ones.

- [ ] **Step 6: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors — confirms the happy-path saga (whose legs post well within the 60s window) is unaffected.

- [ ] **Step 7: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxQuoteExpiredException.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferPoster.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java
git commit -m "feat(ledger-service): enforce FX quote expiry before posting leg 2

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Add Micrometer/Actuator to `holds-service`, `api-gateway`, `fx-service`

**Files:**
- Modify: `holds-service/pom.xml`
- Modify: `holds-service/src/main/resources/application.yml`
- Modify: `api-gateway/pom.xml`
- Modify: `api-gateway/src/main/resources/application.yml`
- Modify: `fx-service/pom.xml`
- Modify: `fx-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: `/actuator/prometheus` reachable on all 5 services (ledger-service and transaction-processor already have it from V1) — Task 13 (Prometheus scrape config) depends on this endpoint existing on every service.

- [ ] **Step 1: Check what `ledger-service`'s `pom.xml` already declares for actuator/micrometer**

Run: `grep -A2 "actuator\|micrometer" ledger-service/pom.xml`

Use the exact same dependency block (group/artifact/version pattern) in the three services below — do not introduce a different Micrometer registry or version.

- [ ] **Step 2: Add the dependencies to `holds-service/pom.xml`, `api-gateway/pom.xml`, `fx-service/pom.xml`**

Add to each pom's `<dependencies>` block:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <runtime>runtime</runtime>
</dependency>
```
(Correct the `<runtime>runtime</runtime>` typo to `<scope>runtime</scope>` if `ledger-service/pom.xml`'s actual existing block uses `<scope>`, matching whatever it actually has — read it first, don't guess the exact tag.)

- [ ] **Step 3: Expose the Prometheus endpoint in each service's `application.yml`**

Add to `holds-service/src/main/resources/application.yml`, `api-gateway/src/main/resources/application.yml`, and `fx-service/src/main/resources/application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus,health
  metrics:
    tags:
      application: ${spring.application.name}
```
(`application: ${spring.application.name}` tags every metric from every service with which service emitted it — essential once Prometheus is scraping all 5 and Grafana needs to distinguish them. Check whether `ledger-service`'s `application.yml` already has this `management:` block from V1 — if so, match its exact shape rather than diverging.)

- [ ] **Step 4: Verify each service starts and exposes the endpoint**

For each of the three services, run (substituting the module name and its own port from the Global Constraints table):
```bash
mvn -pl holds-service -am spring-boot:run &
sleep 15
curl -sf http://localhost:8082/actuator/prometheus | head -5
kill %1
```
Expected: real Prometheus-format metric lines (e.g. `# HELP jvm_memory_used_bytes ...`) — confirms the endpoint is live, not just configured. Repeat for `api-gateway` (port 8080) and `fx-service` (port 8083). This step requires each service's own datasource to be reachable (holds-service and fx-service need their Postgres containers up) — if a service fails to start for a reason unrelated to actuator/micrometer (e.g. no DB), that's expected outside a full Docker Compose context; confirm via the startup log that actuator initialized correctly (look for `Exposing N endpoint(s) beneath base path '/actuator'` in the log) even if the app doesn't fully start standalone.

- [ ] **Step 5: Run each service's existing test suite to confirm no regression**

```bash
mvn -pl holds-service -am test -Dapi.version=1.44
mvn -pl api-gateway -am test -Dapi.version=1.44
mvn -pl fx-service -am test -Dapi.version=1.44
```
Expected: all three `BUILD SUCCESS`, same test counts as before this task (18, 4, 14 respectively as of the last full V3 run) — this task is purely additive infrastructure, no test should be affected.

- [ ] **Step 6: Commit**

```bash
git add holds-service/pom.xml holds-service/src/main/resources/application.yml \
        api-gateway/pom.xml api-gateway/src/main/resources/application.yml \
        fx-service/pom.xml fx-service/src/main/resources/application.yml
git commit -m "chore: add Actuator/Micrometer Prometheus endpoint to holds-service, api-gateway, fx-service

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Metric — transaction latency + failed transactions

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionService.java`
- Modify: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `io.micrometer.core.instrument.MeterRegistry` (auto-configured Spring bean once Actuator/Micrometer is on the classpath — already true for `ledger-service` since V1).
- Produces: nothing consumed by later tasks — this and Tasks 9-11 are independent, parallel metric additions that could in principle be done in any order; sequenced here only for reviewability.

- [ ] **Step 1: Write the failing test for transaction latency**

```java
// Add to TransactionServiceIntegrationTest.java -- requires MeterRegistry autowired.
@Autowired
io.micrometer.core.instrument.MeterRegistry meterRegistry;

@Test
void postingATransactionRecordsATransactionLatencyTimer() {
    transactionService.postTransaction(
            new CreateTransactionRequest("acct-a", "acct-b", 100L, "USD", "metrics test"),
            "metrics-latency-test-1");

    var timer = meterRegistry.find("ledger.transaction.latency").timer();
    assertThat(timer).isNotNull();
    assertThat(timer.count()).isGreaterThanOrEqualTo(1L);
}

@Test
void aFailedTransactionIncrementsTheFailedTransactionsCounterTaggedByExceptionType() {
    assertThatThrownBy(() -> transactionService.postTransaction(
            new CreateTransactionRequest("acct-a", "nonexistent-account-xyz", 100L, "USD", "should fail"),
            "metrics-failure-test-1"))
            .isInstanceOf(AccountNotFoundException.class);

    var counter = meterRegistry.find("ledger.transaction.failed")
            .tag("exception", "AccountNotFoundException")
            .counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — `meterRegistry.find("ledger.transaction.latency").timer()` returns null (no such metric registered yet).

- [ ] **Step 3: Instrument `TransactionPoster.postInTransaction` with a `Timer`**

Add the dependency and wrap the method body. Modify the constructor:
```java
private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

public TransactionPoster(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          EntryRepository entryRepository,
                          OutboxRepository outboxRepository,
                          ObjectMapper objectMapper,
                          HoldsServiceClient holdsServiceClient,
                          io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.accountRepository = accountRepository;
    this.transactionRepository = transactionRepository;
    this.entryRepository = entryRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.holdsServiceClient = holdsServiceClient;
    this.meterRegistry = meterRegistry;
}
```

Wrap the existing method body (everything currently inside `postInTransaction`) with a `Timer.Sample`:
```java
@Transactional
public TransactionResponse postInTransaction(CreateTransactionRequest request,
                                               String idempotencyKey, String requestHash) {
    var sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
    try {
        return doPostInTransaction(request, idempotencyKey, requestHash);
    } finally {
        sample.stop(meterRegistry.timer("ledger.transaction.latency"));
    }
}

private TransactionResponse doPostInTransaction(CreateTransactionRequest request,
                                                  String idempotencyKey, String requestHash) {
    // ... existing method body, unchanged, just renamed and made private ...
}
```
This keeps the `@Transactional` annotation on the public `postInTransaction` entry point (unchanged from before — Spring's proxy still wraps the same public method callers already invoke) while the actual logic moves to a private helper the timer wraps around. This does NOT reintroduce the self-invocation bug: `doPostInTransaction` is a private method called from within the SAME already-proxied public method, not a separate `@Transactional` boundary being bypassed — the transaction is still owned entirely by `postInTransaction`, wrapping both the timer measurement and the delegated logic.

- [ ] **Step 4: Instrument `TransactionService.postTransaction`'s catch block with a failure `Counter`**

Read the current `TransactionService.postTransaction` method (shown in full during Task 9/V3's earlier exploration — it catches `DataIntegrityViolationException | ObjectOptimisticLockingFailureException` for the idempotency-race path, which is NOT a real failure, just a replay). The failure counter belongs where exceptions actually propagate out to the caller as real errors: `AccountNotFoundException`, `InsufficientFundsException`, `AccountNotActiveException`, `IdempotencyConflictException`, and the new `HoldsServiceUnavailableException` all propagate up through `postInTransaction`/`postTransaction` uncaught (they're not part of the race-handling try/catch). Rather than adding a counter increment at every individual throw site (5+ places, easy to miss one), add a single instrumentation point in `ApiExceptionHandler` instead — every one of these exceptions already flows through a dedicated `@ExceptionHandler` method there, which is the one place all of them are guaranteed to pass through exactly once.

Modify `ApiExceptionHandler` to accept `MeterRegistry` and increment inside each handler:
```java
private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

public ApiExceptionHandler(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
}
```

Add one line to each existing `@ExceptionHandler` method, e.g.:
```java
@ExceptionHandler(AccountNotFoundException.class)
public ResponseEntity<Map<String, String>> handleNotFound(AccountNotFoundException e) {
    meterRegistry.counter("ledger.transaction.failed", "exception", "AccountNotFoundException").increment();
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
}
```
Apply the same one-line addition to `handleInsufficientFunds`, `handleAccountNotActive`, `handleConflict` (tag `"IdempotencyConflictException"`), and `handleHoldsServiceUnavailable` (tag `"HoldsServiceUnavailableException"`, added in Task 3). Do NOT add this to `handleAccountRefAlreadyExists`/`handleReservedAccountRef` — those are account-creation failures, not transaction failures, and would pollute this specific metric's meaning.

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: all tests pass, including the two new ones.

- [ ] **Step 6: Run the full ledger-service suite**

Run: `mvn -pl ledger-service -am test -Dapi.version=1.44`
Expected: all tests pass, 0 failures/errors.

- [ ] **Step 7: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/api/error/ApiExceptionHandler.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java
git commit -m "feat(ledger-service): add transaction latency timer and failed-transaction counter

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: Metric — idempotency replays

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferService.java`
- Modify: `ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `MeterRegistry` (already injected into `TransactionPoster` per Task 8; needs adding fresh to `HoldService`).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

```java
// Add to TransactionServiceIntegrationTest.java
@Test
void retryingTheSameIdempotencyKeyIncrementsTheReplayCounter() {
    var request = new CreateTransactionRequest("acct-a", "acct-b", 100L, "USD", "replay metrics test");
    transactionService.postTransaction(request, "metrics-replay-test-1");
    double before = meterRegistry.find("ledger.idempotency.replay").counter() == null
            ? 0.0 : meterRegistry.find("ledger.idempotency.replay").counter().count();

    var replayResponse = transactionService.postTransaction(request, "metrics-replay-test-1");

    assertThat(replayResponse.replay()).isTrue();
    double after = meterRegistry.find("ledger.idempotency.replay").counter().count();
    assertThat(after).isEqualTo(before + 1.0);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — no such counter exists yet.

- [ ] **Step 3: Increment the counter at the exact point `replay=true` is decided**

`TransactionPoster.replayOrConflict` is the single method that decides `replay=true` (read it — shown in full earlier: it's called from both the fast-path-within-transaction case and the lost-race case). Add the `MeterRegistry` field (already added to this class in Task 8) and increment inside this method:

```java
TransactionResponse replayOrConflict(Transaction existing, String requestHash,
                                      CreateTransactionRequest request) {
    if (!existing.getRequestPayloadHash().equals(requestHash)) {
        throw new IdempotencyConflictException(existing.getIdempotencyKey());
    }
    meterRegistry.counter("ledger.idempotency.replay").increment();
    return new TransactionResponse(existing.getId(), existing.getStatus().name(),
            request.debitAccountRef(), request.creditAccountRef(),
            request.amountMinor(), request.currency(), true);
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=TransactionServiceIntegrationTest -Dapi.version=1.44`
Expected: all tests pass.

- [ ] **Step 5: Apply the same pattern to Holds Service's hold-creation idempotency replay**

Read `HoldService.java`'s existing idempotency-replay logic (`POST /holds` also has a `replay` boolean in its response, per V2). Add a `MeterRegistry` constructor dependency to `HoldService` and increment `meterRegistry.counter("holds.idempotency.replay").increment()` at the exact point that class decides `replay=true` — find this by reading the class's existing method that mirrors `TransactionPoster.replayOrConflict`'s shape.

Write a test in `HoldServiceIntegrationTest.java` mirroring the pattern in Step 1 above (autowire `MeterRegistry`, create a hold, retry with the same idempotency key, assert the counter incremented).

- [ ] **Step 6: Apply the same pattern to the cross-currency transfer's idempotency replay**

`CrossCurrencyTransferService.transfer()`'s `if (existing.isPresent()) { return toResponse(existing.get(), null); }` branch (shown in full earlier) is its replay path. Add a `MeterRegistry` constructor dependency and increment `meterRegistry.counter("ledger.fx.transfer.idempotency.replay").increment()` there.

Write a test in `CrossCurrencyTransferServiceIntegrationTest.java` — the existing
`retryingWithTheSameIdempotencyKeyReturnsTheExistingResultWithoutDoublePosting` test already exercises this exact code path; add an assertion to that same test rather than writing a new one, checking the counter incremented after the second call.

- [ ] **Step 7: Run the full suites for both affected modules**

```bash
mvn -pl ledger-service -am test -Dapi.version=1.44
mvn -pl holds-service -am test -Dapi.version=1.44
```
Expected: both `BUILD SUCCESS`, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/service/TransactionPoster.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferService.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/service/TransactionServiceIntegrationTest.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java \
        holds-service/src/main/java/com/ledger/holdsservice/service/HoldService.java \
        holds-service/src/test/java/com/ledger/holdsservice/service/HoldServiceIntegrationTest.java
git commit -m "feat: add idempotency-replay counters across transaction, hold, and FX transfer paths

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 10: Metric — RabbitMQ redeliveries

**Files:**
- Modify: `transaction-processor/src/main/java/com/ledger/txprocessor/messaging/DedupGateService.java`
- Modify: `holds-service/src/main/java/com/ledger/holdsservice/messaging/ProcessedEventGate.java`
- Modify: `transaction-processor/src/test/java/com/ledger/txprocessor/messaging/OutboxEventPublishConsumeIntegrationTest.java`

**Interfaces:**
- Consumes: `MeterRegistry`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test for Transaction Processor's dedup gate**

Read `OutboxEventPublishConsumeIntegrationTest.java` first (or the specific test method in it, if a separate class, that already proves duplicate delivery is deduped — V1's Task on this exact dedup gate should have one) to find the exact mechanism used to simulate a redelivery. Add an assertion to that existing test (don't write a new test class) that the redelivery counter incremented after the second delivery of the same `outboxEventId`:

```java
// Add near the end of the existing duplicate-delivery test in this file:
var counter = meterRegistry.find("processor.rabbitmq.redelivery").counter();
assertThat(counter).isNotNull();
assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
```
(Requires `@Autowired MeterRegistry meterRegistry;` added to the test class if not already present.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl transaction-processor -am test -Dtest=OutboxEventPublishConsumeIntegrationTest -Dapi.version=1.44`
Expected: FAIL — no such counter registered.

- [ ] **Step 3: Increment the counter in `DedupGateService.consumeIfNotAlready`**

Modify the constructor to accept `MeterRegistry`, then in the `rowsTransitioned == 0` branch (already shown in full earlier — this is the exact "redelivery detected" branch):
```java
if (rowsTransitioned == 0) {
    // Row was already CONSUMED — this is a duplicate/redelivered message.
    processedEventRepository.incrementDeliveryCount(outboxEventId, Instant.now());
    meterRegistry.counter("processor.rabbitmq.redelivery").increment();
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl transaction-processor -am test -Dtest=OutboxEventPublishConsumeIntegrationTest -Dapi.version=1.44`
Expected: passes.

- [ ] **Step 5: Apply the identical pattern to Holds Service's `ProcessedEventGate`**

Modify `ProcessedEventGate`'s constructor to accept `MeterRegistry`, and in the `catch (DataIntegrityViolationException duplicateKey) { return false; }` branch (the exact "this event ID was already processed" signal, shown in full earlier), add:
```java
meterRegistry.counter("holds.rabbitmq.redelivery").increment();
```
before `return false;`.

Add a test asserting this to `holds-service`'s existing consumer-dedup test (find it by reading `holds-service/src/test/java/com/ledger/holdsservice/messaging/` for whichever test already proves redelivery is deduped — likely `LedgerTransactionPostedConsumerIntegrationTest`).

- [ ] **Step 6: Run the full suites for both affected modules**

```bash
mvn -pl transaction-processor -am test -Dapi.version=1.44
mvn -pl holds-service -am test -Dapi.version=1.44
```
Expected: both `BUILD SUCCESS`, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add transaction-processor/src/main/java/com/ledger/txprocessor/messaging/DedupGateService.java \
        transaction-processor/src/test/java/com/ledger/txprocessor/messaging/OutboxEventPublishConsumeIntegrationTest.java \
        holds-service/src/main/java/com/ledger/holdsservice/messaging/ProcessedEventGate.java \
        holds-service/src/test/java/com/ledger/holdsservice/messaging/
git commit -m "feat: add RabbitMQ redelivery counters to both dedup gates

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: Metrics — reconciliation mismatches + saga compensation count + FX quote failures

**Files:**
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/reconciliation/ReconciliationService.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferPoster.java`
- Modify: `fx-service/src/main/java/com/ledger/fxservice/service/RateService.java`
- Modify: `ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxServiceClient.java`
- Modify: `ledger-service/src/test/java/com/ledger/ledgerservice/reconciliation/ReconciliationServiceIntegrationTest.java`
- Modify: `ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java`
- Modify: `fx-service/src/test/java/com/ledger/fxservice/service/RateServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `MeterRegistry`.
- Produces: nothing consumed by later tasks — this is the last metrics task.

- [ ] **Step 1: Write the failing test for reconciliation-mismatch gauges**

```java
// Add to ReconciliationServiceIntegrationTest.java
@Autowired
io.micrometer.core.instrument.MeterRegistry meterRegistry;

@Test
void aCleanReconciliationRunSetsAllMismatchGaugesToZero() {
    reconciliationService.runReconciliation();

    assertThat(meterRegistry.find("ledger.reconciliation.entries_imbalance").gauge().value()).isEqualTo(0.0);
    assertThat(meterRegistry.find("ledger.reconciliation.outbox_missing").gauge().value()).isEqualTo(0.0);
    assertThat(meterRegistry.find("ledger.reconciliation.outbox_stuck").gauge().value()).isEqualTo(0.0);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ledger-service -am test -Dtest=ReconciliationServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — the gauges don't exist yet.

- [ ] **Step 3: Register gauges backed by `AtomicLong` fields in `ReconciliationService`**

A `Gauge` in Micrometer reads a live value from a reference you hand it at registration time — it does not accept "set this value now" calls like a `Counter`. Use three `AtomicLong` fields, registered as gauges once in the constructor, updated after each run:

```java
private final java.util.concurrent.atomic.AtomicLong entriesImbalanceGauge = new java.util.concurrent.atomic.AtomicLong(0);
private final java.util.concurrent.atomic.AtomicLong outboxMissingGauge = new java.util.concurrent.atomic.AtomicLong(0);
private final java.util.concurrent.atomic.AtomicLong outboxStuckGauge = new java.util.concurrent.atomic.AtomicLong(0);

public ReconciliationService(/* existing params */, io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    // ... existing assignments ...
    meterRegistry.gauge("ledger.reconciliation.entries_imbalance", entriesImbalanceGauge);
    meterRegistry.gauge("ledger.reconciliation.outbox_missing", outboxMissingGauge);
    meterRegistry.gauge("ledger.reconciliation.outbox_stuck", outboxStuckGauge);
}
```

Find the point where `runReconciliation()` computes the final counts (right before or after `writer.completeRun(...)` — read the method's current body) and set the gauges there:
```java
entriesImbalanceGauge.set(results.imbalancedTransactionIds().size());
outboxMissingGauge.set(results.missingOutboxTransactionIds().size());
outboxStuckGauge.set(stuck.size());
```
(Adjust the exact variable names to whatever `runReconciliation()`'s real local variables are called — read the method body directly rather than guessing.)

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=ReconciliationServiceIntegrationTest -Dapi.version=1.44`
Expected: passes.

- [ ] **Step 5: Write the failing test for saga compensation count**

```java
// Add an assertion to the EXISTING leg2FailureTriggersCompensationAndRestoresSourceBalance
// test in CrossCurrencyTransferServiceIntegrationTest.java, at the end:
var counter = meterRegistry.find("ledger.fx.saga.compensation").counter();
assertThat(counter).isNotNull();
assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
```

- [ ] **Step 6: Increment the counter in `CrossCurrencyTransferPoster.compensate`**

Add `MeterRegistry` to the constructor, increment once at the top of `compensate`:
```java
public void compensate(UUID pendingTransferId) {
    meterRegistry.counter("ledger.fx.saga.compensation").increment();
    PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
    // ... rest of existing method body, unchanged ...
```
(Placed once at the top rather than only on the "fresh" compensation path, so a sweep-driven replay of an already-`COMPENSATING` row is also counted — both are real compensation activity worth seeing on a dashboard, and this avoids the same "which branch actually ran" ambiguity Task 13 (chaos scenario 6) already accepted for its own pass/fail reporting.)

- [ ] **Step 7: Run to verify it passes**

Run: `mvn -pl ledger-service -am test -Dtest=CrossCurrencyTransferServiceIntegrationTest -Dapi.version=1.44`
Expected: passes.

- [ ] **Step 8: Write the failing test for FX quote failures (both sides)**

```java
// Add to fx-service's RateServiceIntegrationTest.java
@Autowired
io.micrometer.core.instrument.MeterRegistry meterRegistry;

@Test
void lockQuoteFailureForAnUnknownPairIncrementsTheFailureCounter() {
    assertThatThrownBy(() -> rateService.lockQuote("XXX", "YYY", 10_000L))
            .isInstanceOf(RateNotAvailableException.class);

    var counter = meterRegistry.find("fx.quote.failure").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
}
```

- [ ] **Step 9: Run to verify it fails**

Run: `mvn -pl fx-service -am test -Dtest=RateServiceIntegrationTest -Dapi.version=1.44`
Expected: FAIL — no such counter.

- [ ] **Step 10: Increment the counter in `RateService`'s `findLatestOrThrow`**

Read `RateService.java`'s private helper that both `getLatestRate` and `lockQuote` call to look up and throw `RateNotAvailableException` (established during V3's Task 5 — a single shared private method). Add `MeterRegistry` to the constructor and increment inside that shared helper, at the throw site:
```java
private FxRate findLatestOrThrow(String baseCurrency, String quoteCurrency) {
    return fxRateRepository
            .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc(baseCurrency, quoteCurrency)
            .orElseThrow(() -> {
                meterRegistry.counter("fx.quote.failure").increment();
                return new RateNotAvailableException(baseCurrency, quoteCurrency);
            });
}
```

- [ ] **Step 11: Run to verify it passes**

Run: `mvn -pl fx-service -am test -Dtest=RateServiceIntegrationTest -Dapi.version=1.44`
Expected: passes.

- [ ] **Step 12: Also count FX quote failures on the calling (Ledger Service) side**

`FxServiceClient.lockQuote` (Ledger Service) can fail for a reason FX Service's own counter never sees — FX Service unreachable entirely, a network timeout, a malformed response. Read the current `FxServiceClient.lockQuote` method (shown in full earlier during V3 exploration) and wrap the existing call in a try/catch that increments a Ledger-Service-side counter before rethrowing:

```java
public FxQuote lockQuote(String baseCurrency, String quoteCurrency, long amountMinor) {
    record Request(String baseCurrency, String quoteCurrency, long amountMinor) {
    }
    try {
        return restClient.post()
                .uri("/conversions/quote")
                .body(new Request(baseCurrency, quoteCurrency, amountMinor))
                .retrieve()
                .body(FxQuote.class);
    } catch (org.springframework.web.client.RestClientException e) {
        meterRegistry.counter("ledger.fx.quote.failure").increment();
        throw e;
    }
}
```
Add `MeterRegistry` to the constructor. Note this uses a distinctly-named metric (`ledger.fx.quote.failure`, not `fx.quote.failure`) since it's tracking a different thing — Ledger Service's view of failed quote requests (including network-level failures FX Service's own metric can't see), not FX Service's own view of "no rate available for this pair."

Add a test to `FxServiceClientIntegrationTest.java` (the raw-`HttpServer`-stub test from V3) asserting this counter increments when the stub returns a 5xx or the connection fails, following the same pattern as `HoldsServiceClientIntegrationTest`'s unreachable-server test from Task 2 of this plan.

- [ ] **Step 13: Run the full suites for all three affected modules**

```bash
mvn -pl ledger-service -am test -Dapi.version=1.44
mvn -pl fx-service -am test -Dapi.version=1.44
```
Expected: both `BUILD SUCCESS`, all tests pass.

- [ ] **Step 14: Commit**

```bash
git add ledger-service/src/main/java/com/ledger/ledgerservice/reconciliation/ReconciliationService.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferPoster.java \
        ledger-service/src/main/java/com/ledger/ledgerservice/fx/FxServiceClient.java \
        fx-service/src/main/java/com/ledger/fxservice/service/RateService.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/reconciliation/ReconciliationServiceIntegrationTest.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/CrossCurrencyTransferServiceIntegrationTest.java \
        ledger-service/src/test/java/com/ledger/ledgerservice/fx/FxServiceClientIntegrationTest.java \
        fx-service/src/test/java/com/ledger/fxservice/service/RateServiceIntegrationTest.java
git commit -m "feat: add reconciliation-mismatch gauges, saga-compensation counter, FX quote-failure counters

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 12: Prometheus + Grafana Docker Compose wiring

**Files:**
- Create: `prometheus/prometheus.yml`
- Create: `grafana/provisioning/datasources/prometheus.yml`
- Create: `grafana/provisioning/dashboards/dashboard.yml`
- Create: `grafana/dashboards/ledger-platform.json`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: `/actuator/prometheus` on all 5 app services (Tasks 7-11).
- Produces: nothing consumed by later tasks — this is the last task before final verification.

- [ ] **Step 1: Write `prometheus/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ledger-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['ledger-service:8090']
  - job_name: 'transaction-processor'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['transaction-processor:8081']
  - job_name: 'holds-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['holds-service:8082']
  - job_name: 'api-gateway'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['api-gateway:8080']
  - job_name: 'fx-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['fx-service:8083']
```
(Container-network hostnames/ports, matching each service's `docker-compose.yml` service name and internal port — not the host-published ports.)

- [ ] **Step 2: Write Grafana's datasource provisioning file**

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```

- [ ] **Step 3: Write Grafana's dashboard provisioning file**

```yaml
apiVersion: 1
providers:
  - name: 'Ledger Platform'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 4: Write a minimal dashboard JSON with panels for all 7 metrics**

Create `grafana/dashboards/ledger-platform.json` with one panel per metric from the spec's table. Use Grafana's standard dashboard JSON schema (schemaVersion 39 or the version matching the Grafana image pinned in Step 5) with 7 `timeseries`-type panels, each querying its metric by name (e.g. `rate(ledger_transaction_failed_total[1m])` for failed transactions — Micrometer's Prometheus registry converts dots to underscores and appends `_total` to counters, `_seconds` to timers by convention; verify the exact exported names by checking a live `/actuator/prometheus` response from Task 7's verification step rather than guessing). Keep this dashboard minimal and functional — one row of 7 panels is sufficient; elaborate dashboard design is out of scope.

Given the JSON verbosity involved, at implementation time: bring up one service locally with actuator/micrometer already added (from Task 7), hit `/actuator/prometheus`, and use the *exact* metric names observed there when writing each panel's query — do not guess Micrometer's naming-convention transformation from the Java-side dotted names used in Tasks 8-11.

- [ ] **Step 5: Add `prometheus` and `grafana` services to `docker-compose.yml`**

```yaml
  prometheus:
    image: prom/prometheus:v2.55.1
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    depends_on:
      ledger-service:
        condition: service_started
      transaction-processor:
        condition: service_started
      holds-service:
        condition: service_started
      api-gateway:
        condition: service_started
      fx-service:
        condition: service_started

  grafana:
    image: grafana/grafana:11.3.1
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
```
(`GF_AUTH_ANONYMOUS_ENABLED` lets anyone open `http://localhost:3000` and immediately see the dashboard without a login step — appropriate for a local demo/portfolio project, not appropriate if this were ever exposed beyond localhost. Pin exact image versions rather than `:latest`, matching this project's existing convention for every other image in `docker-compose.yml` — check `postgres:16.4`/`rabbitmq:3.13` etc. for the pattern.)

- [ ] **Step 6: Verify `docker compose config` is valid**

Run: `docker compose config --services` — should list all services including the 2 new ones (14 total now), with no YAML errors.

- [ ] **Step 7: Bring up the full stack and verify the dashboard populates with real data**

```bash
docker compose down -v
docker compose up -d --build
bash scripts/provision.sh
bash scripts/smoke-test.sh
```
Then open `http://localhost:3000` (or use `curl` to check Grafana's health/API rather than a browser, if running headless) and confirm the dashboard shows non-zero data for at least transaction latency and idempotency replays (both exercised by `smoke-test.sh`). Query Prometheus directly to confirm scraping is working before checking Grafana: `curl -s 'http://localhost:9090/api/v1/query?query=up' | grep -o '"job":"[a-z-]*"' ` should list all 5 job names with `up` status.

Tear down: `docker compose down -v`.

- [ ] **Step 8: Commit**

```bash
git add prometheus/ grafana/ docker-compose.yml
git commit -m "feat: add Prometheus + Grafana with a dashboard for all 7 platform metrics

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: README update and final full-platform acceptance verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1-12.
- Produces: the final, complete README — the last task of this plan.

- [ ] **Step 1: Update the README**

Read the current `README.md` in full first. Update it to reflect this hardening work:

- **Architecture**: note that `POST /transactions` now checks Holds Service's held balance atomically (closing the previously-documented overdraw gap), with the new cross-service coupling and its fail-closed behavior named explicitly as a deliberate tradeoff. Add Prometheus (`:9090`) and Grafana (`:3000`) to the service list.
- **What this demonstrates**: add "Cross-service atomicity for hold-aware transaction posting, with an explicit fail-closed availability tradeoff" and "Prometheus + Grafana observability across all 5 services: transaction latency, failure/replay/redelivery counters, reconciliation-mismatch gauges, saga-compensation and FX-quote-failure counters."
- **Known limitations**: update the previously-documented overdraw gap entry to note it is now closed for the direct `POST /transactions` path (a hold now genuinely blocks an overdraw), while adding the new limitation this introduces — Ledger Service's write availability now depends on Holds Service's availability, and the FX quote expiry gap moves from "known limitation" to "fixed, quote expiry is now enforced before leg 2." Add a note on Grafana's anonymous-viewer access being appropriate only for local/demo use.
- **Running locally**: mention the stack is now 14 containers, and that Grafana is reachable at `http://localhost:3000` with anonymous viewer access (no login needed for local use).

- [ ] **Step 2: Run the complete Maven test suite one final time**

Run: `mvn clean verify -Dapi.version=1.44` (with `DOCKER_HOST=tcp://127.0.0.1:2375 DOCKER_API_VERSION=1.44`) from the repo root.
Expected: `BUILD SUCCESS` across all 5 modules.

- [ ] **Step 3: Run the full Docker Compose acceptance sequence, including all 6 existing chaos scenarios**

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
Expected: all green, including the 5 V1 scenarios and the V3 FX-saga scenario — this hardening work touches `TransactionPoster` (used by every chaos scenario) and `CrossCurrencyTransferPoster` (used by scenario 6), so a real regression here would likely surface as a chaos-scenario failure, not just a unit-test failure.

- [ ] **Step 4: Manually verify the hold-aware atomicity fix works live through the gateway**

```bash
TOKEN=$(bash scripts/get-token.sh client)
# Create a fresh account, fund it, place a hold for most of the balance, then attempt an
# ordinary transfer that would overdraw the account given the hold -- confirm it is now
# rejected (was previously allowed before this plan's Task 3 fix).
curl -s -X POST http://localhost:8080/accounts -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"accountRef":"hold-atomicity-check","currency":"USD"}'
# Fund it via an ordinary transfer from smoke-a, then:
curl -s -X POST http://localhost:8080/holds -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "Idempotency-Key: hold-check-1" \
  -d '{"accountRef":"hold-atomicity-check","destinationAccountRef":"smoke-b","amountMinor":4000,"currency":"USD","expiresInSeconds":3600}'
curl -s -X POST http://localhost:8080/transactions -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "Idempotency-Key: overdraw-check-1" \
  -d '{"debitAccountRef":"hold-atomicity-check","creditAccountRef":"smoke-b","amountMinor":4500,"currency":"USD","description":"should be rejected due to hold"}'
```
Expected: the final `POST /transactions` call returns `422` (`InsufficientFundsException`), proving the held amount was correctly deducted from available funds before this direct transfer was allowed — the exact gap this plan's Priority 1 closes. Fund the account with enough for the numbers above to make sense (e.g. 5000 posted, 4000 held, 4500 attempted transfer exceeds the 1000 truly available) — verify the actual numbers work out before running, don't just copy them blindly.

Tear down: `docker compose down -v`.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: update README for hold-aware atomicity, FX quote expiry, and observability

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
