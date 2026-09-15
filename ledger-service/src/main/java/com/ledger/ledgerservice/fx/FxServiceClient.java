package com.ledger.ledgerservice.fx;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class FxServiceClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public FxServiceClient(@Value("${fx.base-url}") String fxBaseUrl, MeterRegistry meterRegistry) {
        this.restClient = RestClient.builder().baseUrl(fxBaseUrl).build();
        this.meterRegistry = meterRegistry;
    }

    public FxQuote lockQuote(String baseCurrency, String quoteCurrency, long amountMinor) {
        record Request(String baseCurrency, String quoteCurrency, long amountMinor) {
        }

        try {
            return restClient.post()
                    .uri("/conversions/quote")
                    .body(new Request(baseCurrency, quoteCurrency, amountMinor))
                    .retrieve()
                    .body(FxQuote.class);
        } catch (RestClientException e) {
            meterRegistry.counter("ledger.fx.quote.failure").increment();
            throw e;
        }
    }
}
