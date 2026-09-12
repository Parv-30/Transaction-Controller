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
    private JdbcTemplate jdbcTemplate;

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
