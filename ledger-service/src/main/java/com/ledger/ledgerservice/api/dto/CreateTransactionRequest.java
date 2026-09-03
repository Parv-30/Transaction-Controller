package com.ledger.ledgerservice.api.dto;

public record CreateTransactionRequest(
        String debitAccountRef,
        String creditAccountRef,
        long amountMinor,
        String currency,
        String description
) {
}
