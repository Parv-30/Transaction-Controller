package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class WithdrawalTimeoutSweepIntegrationTest {

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
        // Disable the real timer during the test; the sweep is invoked manually instead.
        registry.add("gateway-sim.withdrawal-sweep.interval-ms", () -> "3600000");
        registry.add("gateway-sim.withdrawal-timeout-seconds", () -> "60");

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

    @Autowired
    private WithdrawalTimeoutSweep withdrawalTimeoutSweep;
    @Autowired
    private ExternalWithdrawalRepository withdrawalRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void resetStub() {
        stubLedgerRequestCount.set(0);
    }

    @AfterEach
    void resetStubAfter() {
        stubLedgerRequestCount.set(0);
    }

    @Test
    void sweepTimesOutStuckSubmittedWithdrawalsAndReversesThem() {
        ExternalWithdrawal withdrawal = new ExternalWithdrawal(
                UUID.randomUUID(), UUID.randomUUID(), "acct-timeout-1", 6_000L, "USD");
        withdrawalRepository.save(withdrawal);

        // Backdate submitted_at directly via SQL to simulate a row stuck older than the
        // configured timeout, since the entity only sets submitted_at to "now" on construction.
        jdbcTemplate.update("UPDATE external_withdrawals SET submitted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), withdrawal.getId());

        double counterBefore = meterRegistry.counter("gateway_sim.withdrawal.timeout").count();

        withdrawalTimeoutSweep.run();

        ExternalWithdrawal resolved = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(WithdrawalStatus.REVERSED);
        assertThat(resolved.getReversalTransactionId()).isNotNull();
        assertThat(stubLedgerRequestCount.get()).isEqualTo(1);

        double counterAfter = meterRegistry.counter("gateway_sim.withdrawal.timeout").count();
        assertThat(counterAfter - counterBefore).isEqualTo(1.0);
    }
}
