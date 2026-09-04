package com.ledger.holdsservice.api.dto;

public record CreateHoldRequest(
        String accountRef,
        String destinationAccountRef,
        long amountMinor,
        String currency,
        long expiresInSeconds
) {
}
