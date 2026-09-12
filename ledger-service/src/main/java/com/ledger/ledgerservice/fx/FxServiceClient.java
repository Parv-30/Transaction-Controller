package com.ledger.ledgerservice.fx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FxServiceClient {

    private final RestClient restClient;

    public FxServiceClient(@Value("${fx.base-url}") String fxBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(fxBaseUrl).build();
    }

    public FxQuote lockQuote(String baseCurrency, String quoteCurrency, long amountMinor) {
        record Request(String baseCurrency, String quoteCurrency, long amountMinor) {
        }

        return restClient.post()
                .uri("/conversions/quote")
                .body(new Request(baseCurrency, quoteCurrency, amountMinor))
                .retrieve()
                .body(FxQuote.class);
    }
}
