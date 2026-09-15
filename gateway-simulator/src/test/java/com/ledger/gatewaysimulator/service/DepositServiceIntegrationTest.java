package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.api.dto.DepositResponse;
import com.ledger.gatewaysimulator.api.dto.DepositWebhookRequest;
import com.ledger.gatewaysimulator.api.dto.SimulateDepositRequest;
import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.repository.WebhookDedupRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DepositServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    static HttpServer stubLedgerService;
    static AtomicInteger stubLedgerRequestCount = new AtomicInteger(0);

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
    void resetStubCounter() {
        stubLedgerRequestCount.set(0);
    }

    @Autowired
    private DepositService depositService;
    @Autowired
    private ExternalDepositRepository depositRepository;
    @Autowired
    private WebhookDedupRepository webhookDedupRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestRestTemplate restTemplate;
    @LocalServerPort
    private int port;

    @Test
    void firstWebhookDeliveryCreditsTheAccountAndMarksDepositCredited() {
        String externalReference = "ext-ref-" + UUID.randomUUID();
        DepositWebhookRequest request = new DepositWebhookRequest(externalReference, "acct-1", 5000, "USD");

        DepositResponse response = depositService.handleWebhook(request);

        assertThat(response.status()).isEqualTo("CREDITED");
        assertThat(response.transactionId()).isNotNull();
        assertThat(response.externalReference()).isEqualTo(externalReference);

        ExternalDeposit persisted = depositRepository.findByExternalReference(externalReference).orElseThrow();
        assertThat(persisted.getStatus().name()).isEqualTo("CREDITED");
        assertThat(persisted.getTransactionId()).isEqualTo(response.transactionId());

        assertThat(webhookDedupRepository.findById(externalReference)).isPresent();
        assertThat(webhookDedupRepository.findById(externalReference).orElseThrow().getWebhookCount()).isEqualTo(1);
    }

    @Test
    void duplicateWebhookDeliveryIsIgnoredAndReturnsTheOriginalOutcome() {
        String externalReference = "ext-ref-" + UUID.randomUUID();
        DepositWebhookRequest request = new DepositWebhookRequest(externalReference, "acct-2", 7500, "USD");

        DepositResponse firstResponse = depositService.handleWebhook(request);
        DepositResponse secondResponse = depositService.handleWebhook(request);

        assertThat(secondResponse.status()).isEqualTo(firstResponse.status());
        assertThat(secondResponse.transactionId()).isEqualTo(firstResponse.transactionId());

        Integer webhookCount = jdbcTemplate.queryForObject(
                "SELECT webhook_count FROM webhook_dedup WHERE external_reference = ?",
                Integer.class, externalReference);
        assertThat(webhookCount).isEqualTo(2);

        Integer depositRowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM external_deposits WHERE external_reference = ?",
                Integer.class, externalReference);
        assertThat(depositRowCount).isEqualTo(1);

        assertThat(stubLedgerRequestCount.get()).isEqualTo(1);
    }

    @Test
    void simulateDepositsEndpointGeneratesAReferenceAndInvokesTheRealWebhookPath() {
        SimulateDepositRequest request = new SimulateDepositRequest("acct-3", 12000, "USD");

        ResponseEntity<DepositResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/simulator/deposits", request, DepositResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        DepositResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.externalReference()).isNotBlank();
        assertThat(body.status()).isEqualTo("CREDITED");

        ExternalDeposit persisted = depositRepository.findByExternalReference(body.externalReference())
                .orElseThrow();
        assertThat(persisted.getStatus().name()).isEqualTo("CREDITED");
        assertThat(persisted.getAccountRef()).isEqualTo("acct-3");
        assertThat(persisted.getAmountMinor()).isEqualTo(12000);
    }
}
