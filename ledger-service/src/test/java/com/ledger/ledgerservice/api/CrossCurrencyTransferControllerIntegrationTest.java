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
import org.springframework.boot.test.web.server.LocalServerPort;
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
        seedIfAbsent("cc-ctrl-source-bad", "USD", 50_000L);
        seedIfAbsent("cc-ctrl-dest-frozen", "EUR", 0L, AccountStatus.FROZEN);
        seedIfAbsent("fx-clearing-USD", "USD", 0L);
        seedIfAbsent("fx-clearing-EUR", "EUR", 0L);
    }

    private void seedIfAbsent(String ref, String currency, long balance) {
        seedIfAbsent(ref, currency, balance, AccountStatus.ACTIVE);
    }

    private void seedIfAbsent(String ref, String currency, long balance, AccountStatus status) {
        if (accountRepository.findByAccountRef(ref).isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), ref, null, currency,
                    balance, status, null));
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

    @Test
    void postTransfersCrossCurrencyReturns422WhenDestAccountIsNotActive() {
        // Dest account "cc-ctrl-dest-frozen" exists (so the up-front currency lookup succeeds
        // and a PendingFxTransfer row is created) but is FROZEN, so leg2 posting fails with
        // AccountNotActiveException and the saga compensates leg1. The transfer is processed
        // (the API did what it safely could) but did not complete, so the caller should see a
        // non-2xx-success-implying status: 422.
        var request = new CreateCrossCurrencyTransferRequest(
                "cc-ctrl-source-bad", "cc-ctrl-dest-frozen", 5_000L, "cc-ctrl-test-2");

        var response = restTemplate.postForEntity(
                "http://localhost:" + port + "/transfers/cross-currency", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("status", "COMPENSATED");
        assertThat(response.getBody()).containsKey("errorMessage");
    }
}
