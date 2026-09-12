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
                "reconciliation_runs", "reconciliation_findings", "pending_fx_transfers", "flyway_schema_history"
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
