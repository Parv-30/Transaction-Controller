package com.ledger.fxservice.service;

import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxQuoteRepository;
import com.ledger.fxservice.repository.FxRateRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = "fx.rate-sync.interval-ms=1000")
@ActiveProfiles("test")
class RateServiceIntegrationTest {

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
    RateService rateService;

    @Autowired
    FxRateRepository fxRateRepository;

    @Autowired
    FxQuoteRepository fxQuoteRepository;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Test
    void getLatestRateReturnsFreshRateWhenRecentlySynced() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var response = rateService.getLatestRate("USD", "EUR");

        assertThat(response.rate()).isEqualByComparingTo("0.92000000");
        assertThat(response.stale()).isFalse();
    }

    @Test
    void getLatestRateFlagsStaleWhenLastSyncIsOlderThanTwoIntervals() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "GBP",
                new BigDecimal("0.79000000"), "frankfurter",
                Instant.now().minus(1, ChronoUnit.HOURS)));

        var response = rateService.getLatestRate("USD", "GBP");

        assertThat(response.stale()).isTrue();
    }

    @Test
    void getLatestRateThrowsForAnUnknownPair() {
        assertThatThrownBy(() -> rateService.getLatestRate("XXX", "YYY"))
                .isInstanceOf(RateNotAvailableException.class);
    }

    @Test
    void lockQuotePersistsAQuoteRowWithComputedExpiry() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var response = rateService.lockQuote("USD", "EUR", 10_000L);

        assertThat(response.rateUsed()).isEqualByComparingTo("0.92000000");
        assertThat(fxQuoteRepository.findById(response.quoteId())).isPresent();
        assertThat(response.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void lockQuoteThrowsForAnUnknownPair() {
        assertThatThrownBy(() -> rateService.lockQuote("XXX", "YYY", 10_000L))
                .isInstanceOf(RateNotAvailableException.class);
    }

    @Test
    void lockQuoteFailureForAnUnknownPairIncrementsTheFailureCounter() {
        assertThatThrownBy(() -> rateService.lockQuote("XXX", "YYY", 10_000L))
                .isInstanceOf(RateNotAvailableException.class);

        var counter = meterRegistry.find("fx.quote.failure").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }
}
