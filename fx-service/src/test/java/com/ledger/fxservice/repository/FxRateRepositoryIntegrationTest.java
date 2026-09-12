package com.ledger.fxservice.repository;

import com.ledger.fxservice.domain.FxRate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class FxRateRepositoryIntegrationTest {

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
    FxRateRepository fxRateRepository;

    @Test
    void findsTheMostRecentlyFetchedRateForAPair() {
        Instant older = Instant.now().minus(2, ChronoUnit.HOURS);
        Instant newer = Instant.now().minus(1, ChronoUnit.HOURS);

        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.90000000"), "frankfurter", older));
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", newer));

        var latest = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("USD", "EUR");

        assertThat(latest).isPresent();
        assertThat(latest.get().getRate()).isEqualByComparingTo("0.92000000");
        // Allow millisecond precision loss from DB round-trip
        assertThat(latest.get().getFetchedAt().toEpochMilli())
            .isEqualTo(newer.toEpochMilli());
    }

    @Test
    void returnsEmptyForAnUnknownPair() {
        var result = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("XXX", "YYY");
        assertThat(result).isEmpty();
    }
}
