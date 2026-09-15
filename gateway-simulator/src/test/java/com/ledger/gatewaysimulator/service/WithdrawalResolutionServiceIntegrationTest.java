package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.api.dto.ConfirmWithdrawalRequest;
import com.ledger.gatewaysimulator.api.dto.WithdrawalResponse;
import com.ledger.gatewaysimulator.api.error.WithdrawalNotYetSubmittedException;
import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WithdrawalResolutionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    static HttpServer stubLedgerService;
    static AtomicInteger stubLedgerRequestCount = new AtomicInteger(0);
    static List<String> stubLedgerIdempotencyKeys = new CopyOnWriteArrayList<>();
    static List<String> stubLedgerRequestBodies = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);

        try {
            stubLedgerService = HttpServer.create(new InetSocketAddress(0), 0);
            stubLedgerService.createContext("/transactions", exchange -> {
                stubLedgerRequestCount.incrementAndGet();
                stubLedgerIdempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                stubLedgerRequestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String body = "{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"POSTED\"}";
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            stubLedgerService.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start stub Ledger Service", e);
        }
        registry.add("ledger.base-url", () -> "http://localhost:" + stubLedgerService.getAddress().getPort());
    }

    @AfterEach
    void resetStub() {
        stubLedgerRequestCount.set(0);
        stubLedgerIdempotencyKeys.clear();
        stubLedgerRequestBodies.clear();
    }

    @Autowired
    private WithdrawalResolutionService resolutionService;
    @Autowired
    private ExternalWithdrawalRepository withdrawalRepository;
    @Autowired
    private TestRestTemplate restTemplate;
    @LocalServerPort
    private int port;

    private ExternalWithdrawal seedSubmittedWithdrawal(String accountRef, long amountMinor, String currency) {
        ExternalWithdrawal withdrawal = new ExternalWithdrawal(
                UUID.randomUUID(), UUID.randomUUID(), accountRef, amountMinor, currency);
        return withdrawalRepository.save(withdrawal);
    }

    @Test
    void confirmingAnUnknownWithdrawalIdThrowsWithdrawalNotYetSubmittedException() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> resolutionService.confirm(unknownId, "CONFIRMED"))
                .isInstanceOf(WithdrawalNotYetSubmittedException.class);

        assertThat(stubLedgerRequestCount.get()).isEqualTo(0);
    }

    @Test
    void confirmingAWithdrawalAsConfirmedMarksItConfirmedWithNoReversal() {
        ExternalWithdrawal seeded = seedSubmittedWithdrawal("acct-w-1", 4_000L, "USD");

        ExternalWithdrawal resolved = resolutionService.confirm(seeded.getId(), "CONFIRMED");

        assertThat(resolved.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMED);
        assertThat(resolved.getReversalTransactionId()).isNull();
        assertThat(stubLedgerRequestCount.get()).isEqualTo(0);

        ExternalWithdrawal persisted = withdrawalRepository.findById(seeded.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMED);
    }

    @Test
    void confirmingAWithdrawalAsFailedPostsAReversalAndMarksItReversed() {
        ExternalWithdrawal seeded = seedSubmittedWithdrawal("acct-w-2", 2_500L, "EUR");

        ExternalWithdrawal resolved = resolutionService.confirm(seeded.getId(), "FAILED");

        assertThat(resolved.getStatus()).isEqualTo(WithdrawalStatus.REVERSED);
        assertThat(resolved.getReversalTransactionId()).isNotNull();
        assertThat(stubLedgerRequestCount.get()).isEqualTo(1);

        ExternalWithdrawal persisted = withdrawalRepository.findById(seeded.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WithdrawalStatus.REVERSED);
        assertThat(persisted.getReversalTransactionId()).isEqualTo(resolved.getReversalTransactionId());

        assertThat(stubLedgerRequestBodies).hasSize(1);
        String requestBody = stubLedgerRequestBodies.get(0);
        assertThat(requestBody).contains("\"debitAccountRef\":\"external-clearing-EUR\"");
        assertThat(requestBody).contains("\"creditAccountRef\":\"acct-w-2\"");
        assertThat(requestBody).contains("\"amountMinor\":2500");
        assertThat(requestBody).contains("\"currency\":\"EUR\"");
    }

    @Test
    void reversalIdempotencyKeyIsDeterministicFromSourceTransactionId() {
        ExternalWithdrawal seeded = seedSubmittedWithdrawal("acct-w-3", 1_000L, "USD");
        UUID sourceTransactionId = seeded.getSourceTransactionId();

        resolutionService.confirm(seeded.getId(), "FAILED");

        // Simulate a retried confirm call after a lost response by invoking reverse(...) again
        // directly for the same (now-REVERSED) withdrawal, the same way a redelivered request
        // would race with an already-completed resolution.
        ExternalWithdrawal reReadWithdrawal = withdrawalRepository.findById(seeded.getId()).orElseThrow();
        resolutionService.reverse(reReadWithdrawal);

        assertThat(stubLedgerRequestCount.get()).isEqualTo(2);
        assertThat(stubLedgerIdempotencyKeys).hasSize(2);
        assertThat(stubLedgerIdempotencyKeys.get(0)).isEqualTo(stubLedgerIdempotencyKeys.get(1));
        assertThat(stubLedgerIdempotencyKeys.get(0))
                .isEqualTo("external-withdrawal-reversal-" + sourceTransactionId);
    }

    @Test
    void confirmingByAnUnknownSourceTransactionIdReturns409() {
        UUID unknownSourceTransactionId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/simulator/withdrawals/by-transaction/"
                        + unknownSourceTransactionId + "/confirm",
                new ConfirmWithdrawalRequest("CONFIRMED"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void confirmingByAKnownSourceTransactionIdSucceedsIdenticallyToConfirmingByInternalId() {
        ExternalWithdrawal seeded = seedSubmittedWithdrawal("acct-w-4", 6_000L, "USD");
        UUID sourceTransactionId = seeded.getSourceTransactionId();

        ResponseEntity<WithdrawalResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/simulator/withdrawals/by-transaction/"
                        + sourceTransactionId + "/confirm",
                new ConfirmWithdrawalRequest("CONFIRMED"), WithdrawalResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        WithdrawalResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(seeded.getId());
        assertThat(body.status()).isEqualTo("CONFIRMED");
        assertThat(body.reversalTransactionId()).isNull();

        ExternalWithdrawal persisted = withdrawalRepository.findById(seeded.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMED);
    }
}
