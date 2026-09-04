package com.ledger.holdsservice;

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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

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
                "holds", "account_balance_cache", "outbox_events", "processed_events",
                "flyway_schema_history"
        );
    }

    @Test
    void idempotencyKeyUniqueConstraintIsEnforced() {
        jdbcTemplate.update("""
                INSERT INTO holds (id, account_ref, destination_account_ref, amount_minor,
                                    currency, status, idempotency_key, expires_at)
                VALUES (gen_random_uuid(), 'acct-a', 'acct-merchant', 500, 'USD', 'ACTIVE',
                        'dup-key', now() + interval '1 hour')
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        INSERT INTO holds (id, account_ref, destination_account_ref, amount_minor,
                                            currency, status, idempotency_key, expires_at)
                        VALUES (gen_random_uuid(), 'acct-a', 'acct-merchant', 500, 'USD', 'ACTIVE',
                                'dup-key', now() + interval '1 hour')
                        """)
        ).hasMessageContaining("uq_holds_idem_key");
    }
}
