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
        registry.add("ledger.base-url", () -> "http://localhost:" + stubProcessor.getAddress().getPort());
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
