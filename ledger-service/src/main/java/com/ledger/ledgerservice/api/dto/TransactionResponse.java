package com.ledger.ledgerservice.api.dto;

import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        String status,
        String debitAccountRef,
        String creditAccountRef,
        long amountMinor,
        String currency,
        boolean replay
) {
}
