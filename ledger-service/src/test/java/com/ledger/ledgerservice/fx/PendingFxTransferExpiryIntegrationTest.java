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
