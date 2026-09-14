package com.ledger.ledgerservice.holds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class HoldsServiceClient {

    private final RestClient restClient;

    public HoldsServiceClient(@Value("${holds.base-url}") String holdsBaseUrl,
                               @Value("${holds.held-balance-timeout-ms}") long timeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(timeoutMs))
                .withReadTimeout(Duration.ofMillis(timeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
                .baseUrl(holdsBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public long getHeldBalance(String accountRef) {
        record Response(String accountRef, long heldBalanceMinor) {
        }

        try {
            Response response = restClient.get()
                    .uri("/accounts/{accountRef}/held-balance", accountRef)
                    .retrieve()
                    .body(Response.class);
            return response.heldBalanceMinor();
        } catch (RestClientException e) {
            throw new HoldsServiceUnavailableException(accountRef, e);
        }
    }
}
