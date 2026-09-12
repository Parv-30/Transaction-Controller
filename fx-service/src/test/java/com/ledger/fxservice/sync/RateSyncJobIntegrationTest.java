package com.ledger.fxservice.sync;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.ledger.fxservice.repository.FxRateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class RateSyncJobIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("fx_db")
            .withUsername("fx")
            .withPassword("fx");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        wireMock = new WireMockServer(0);
        wireMock.start();
        registry.add("frankfurter.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    RateSyncJob rateSyncJob;

    @Autowired
    FxRateRepository fxRateRepository;

    @BeforeEach
    void resetStub() {
        wireMock.resetAll();
    }

    @AfterEach
    void stopServer() {
        // Left running across tests in this class; only reset stubs between tests.
        // WireMock stays up for the whole test class -- @AfterEach intentionally does nothing here.
    }

    @Test
    void syncingPersistsRatesForEveryConfiguredPair() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("USD"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"EUR\":0.92,\"GBP\":0.79}}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("EUR"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.09,\"GBP\":0.86}}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("GBP"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"GBP\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.27,\"EUR\":1.16}}")));

        rateSyncJob.syncNow(List.of("USD", "EUR", "GBP"));

        var usdEur = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("USD", "EUR");
        assertThat(usdEur).isPresent();
        assertThat(usdEur.get().getRate()).isEqualByComparingTo("0.92");
    }

    @Test
    void oneBaseCurrencyFailingDoesNotBlockTheOthersFromSyncing() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("USD"))
                .willReturn(serverError()));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("EUR"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.09,\"GBP\":0.86}}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/latest"))
                .withQueryParam("base", equalTo("GBP"))
                .willReturn(okJson("{\"amount\":1.0,\"base\":\"GBP\",\"date\":\"2026-09-12\"," +
                        "\"rates\":{\"USD\":1.27,\"EUR\":1.16}}")));

        rateSyncJob.syncNow(List.of("USD", "EUR", "GBP"));

        var eurUsd = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("EUR", "USD");
        assertThat(eurUsd).isPresent();

        var usdEur = fxRateRepository
                .findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc("USD", "EUR");
        assertThat(usdEur).isEmpty();
    }
}
