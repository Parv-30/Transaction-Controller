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

import java.sql.Timestamp;
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
                Timestamp.from(Instant.now().minusSeconds(10)), created.holdId());

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
                Timestamp.from(Instant.now().minusSeconds(10)), created.holdId());

        holdExpirySweep.run();

        var stillCaptured = holdRepository.findById(created.holdId()).orElseThrow();
        assertThat(stillCaptured.getStatus()).isEqualTo(HoldStatus.CAPTURED);
    }
}
