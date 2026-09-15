package com.ledger.gatewaysimulator;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allFiveTablesExistAfterMigration() {
        for (String table : new String[]{
                "external_deposits", "external_withdrawals", "webhook_dedup", "outbox_events", "processed_events"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table %s should exist", table).isEqualTo(1);
        }
    }

    @Test
    void externalDepositsEnforcesUniqueExternalReference() {
        jdbcTemplate.update(
                "INSERT INTO external_deposits (id, external_reference, account_ref, amount_minor, currency, raw_payload) " +
                        "VALUES (gen_random_uuid(), 'dup-ref-1', 'acct-1', 1000, 'USD', '{}'::jsonb)");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO external_deposits (id, external_reference, account_ref, amount_minor, currency, raw_payload) " +
                        "VALUES (gen_random_uuid(), 'dup-ref-1', 'acct-2', 2000, 'USD', '{}'::jsonb)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void externalWithdrawalsEnforcesUniqueSourceTransactionId() {
        java.util.UUID sourceTxnId = java.util.UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO external_withdrawals (id, source_transaction_id, account_ref, amount_minor, currency) " +
                        "VALUES (gen_random_uuid(), ?, 'acct-1', 1000, 'USD')",
                sourceTxnId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO external_withdrawals (id, source_transaction_id, account_ref, amount_minor, currency) " +
                        "VALUES (gen_random_uuid(), ?, 'acct-2', 2000, 'USD')",
                sourceTxnId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void outboxEventsAndProcessedEventsMatchHoldsServiceShape() {
        java.util.UUID eventId = java.util.UUID.randomUUID();
        java.util.UUID aggregateId = java.util.UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, aggregate_id, event_type, payload) VALUES (?, ?, 'TEST_EVENT', '{}'::jsonb)",
                eventId, aggregateId);

        Integer unpublishedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE id = ? AND published_at IS NULL",
                Integer.class, eventId);
        assertThat(unpublishedCount).isEqualTo(1);

        jdbcTemplate.update("INSERT INTO processed_events (event_id) VALUES (?)", eventId);
        Integer processedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId);
        assertThat(processedCount).isEqualTo(1);
    }
}
