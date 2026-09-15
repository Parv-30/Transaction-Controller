package com.ledger.ledgerservice.api.dto;

public record CreateTransactionRequest(
        String debitAccountRef,
        String creditAccountRef,
        long amountMinor,
        String currency,
        String description,
        String transactionType
) {
    public CreateTransactionRequest {
        if (transactionType == null || transactionType.isBlank()) {
            transactionType = "TRANSFER";
        }
    }

    public CreateTransactionRequest(String debitAccountRef, String creditAccountRef, long amountMinor,
                                     String currency, String description) {
        this(debitAccountRef, creditAccountRef, amountMinor, currency, description, "TRANSFER");
    }
}
