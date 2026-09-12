package com.ledger.fxservice.api;

import com.ledger.fxservice.domain.FxRate;
import com.ledger.fxservice.repository.FxRateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RateControllerIntegrationTest {

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

    @LocalServerPort
    int port;

    @Autowired
    FxRateRepository fxRateRepository;

    TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void getRateReturns200ForAKnownPair() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var response = restTemplate.getForEntity(
                "http://localhost:" + port + "/rates/USD/EUR", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("rate");
    }

    @Test
    void getRateReturns422ForAnUnknownPair() {
        var response = restTemplate.getForEntity(
                "http://localhost:" + port + "/rates/XXX/YYY", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void postConversionsQuoteReturns200AndAQuoteIdForAKnownPair() {
        fxRateRepository.save(new FxRate(UUID.randomUUID(), "USD", "EUR",
                new BigDecimal("0.92000000"), "frankfurter", Instant.now()));

        var request = Map.of("baseCurrency", "USD", "quoteCurrency", "EUR", "amountMinor", 10_000);
        var response = restTemplate.postForEntity(
                "http://localhost:" + port + "/conversions/quote", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("quoteId");
    }
}
