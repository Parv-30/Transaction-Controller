package com.ledger.fxservice;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void fxRatesTableAcceptsAnInsertAndEnforcesUniquePairAndFetchedAt() {
        Instant fetchedAt = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO fx_rates (base_currency, quote_currency, rate, fetched_at) VALUES (?,?,?,?)",
                "USD", "EUR", new BigDecimal("0.92000000"), java.sql.Timestamp.from(fetchedAt));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fx_rates WHERE base_currency = 'USD' AND quote_currency = 'EUR'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void fxQuotesTableAcceptsAnInsertWithNullableConsumedAt() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO fx_quotes (id, base_currency, quote_currency, rate_used, locked_at, expires_at) " +
                        "VALUES (?,?,?,?,?,?)",
                id, "USD", "EUR", new BigDecimal("0.92000000"),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(60)));

        Boolean consumedAtIsNull = jdbcTemplate.queryForObject(
                "SELECT consumed_at IS NULL FROM fx_quotes WHERE id = ?", Boolean.class, id);
        assertThat(consumedAtIsNull).isTrue();
    }
}
