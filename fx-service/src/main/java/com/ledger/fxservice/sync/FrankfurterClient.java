package com.ledger.fxservice.sync;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class FrankfurterClient {

    private final RestClient restClient;

    public FrankfurterClient(RestClient frankfurterRestClient) {
        this.restClient = frankfurterRestClient;
    }

    public FrankfurterRatesResponse fetchLatestRates(String baseCurrency, List<String> targetCurrencies) {
        String symbols = String.join(",", targetCurrencies);
        return restClient.get()
                .uri("/v1/latest?base={base}&symbols={symbols}", baseCurrency, symbols)
                .retrieve()
                .body(FrankfurterRatesResponse.class);
    }
}
