package com.ledger.holdsservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class LedgerTransactionClient {

    public record PostTransactionResult(UUID transactionId, String status) {
    }

    private final RestClient restClient;

    public LedgerTransactionClient(@Value("${processor.base-url}") String processorBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(processorBaseUrl).build();
    }

    public PostTransactionResult postTransaction(String debitAccountRef, String creditAccountRef,
                                                  long amountMinor, String currency,
                                                  String description, String idempotencyKey) {
        record Request(String debitAccountRef, String creditAccountRef, long amountMinor,
                        String currency, String description) {
        }
        record Response(UUID transactionId, String status) {
        }

        Response response = restClient.post()
                .uri("/transactions")
                .header("Idempotency-Key", idempotencyKey)
                .body(new Request(debitAccountRef, creditAccountRef, amountMinor, currency, description))
                .retrieve()
                .body(Response.class);

        return new PostTransactionResult(response.transactionId(), response.status());
    }
}
