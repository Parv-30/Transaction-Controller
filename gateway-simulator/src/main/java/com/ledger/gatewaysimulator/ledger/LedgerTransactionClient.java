package com.ledger.gatewaysimulator.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class LedgerTransactionClient {

    private final RestClient restClient;

    public LedgerTransactionClient(@Value("${ledger.base-url}") String ledgerBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(ledgerBaseUrl).build();
    }

    /**
     * Posts a transaction to Ledger Service's {@code POST /transactions}, used by Gateway
     * Simulator to credit deposits and post withdrawal reversals. Neither of those is itself a
     * client-initiated external debit, so no {@code transactionType} is sent -- Ledger Service
     * defaults it to {@code "TRANSFER"} server-side.
     *
     * @return the created (or, on idempotent replay, previously created) transaction's id
     * @throws LedgerServiceUnavailableException if Ledger Service cannot be reached (connection
     *                                            failure or timeout)
     */
    public UUID postTransaction(String debitAccountRef, String creditAccountRef, long amountMinor,
                                 String currency, String description, String idempotencyKey) {
        record Request(String debitAccountRef, String creditAccountRef, long amountMinor,
                        String currency, String description) {
        }
        record Response(UUID transactionId, String status) {
        }

        try {
            Response response = restClient.post()
                    .uri("/transactions")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new Request(debitAccountRef, creditAccountRef, amountMinor, currency, description))
                    .retrieve()
                    .body(Response.class);

            return response.transactionId();
        } catch (ResourceAccessException e) {
            throw new LedgerServiceUnavailableException(
                    "Could not reach Ledger Service to post transaction (idempotencyKey=" + idempotencyKey + ")", e);
        }
    }
}
