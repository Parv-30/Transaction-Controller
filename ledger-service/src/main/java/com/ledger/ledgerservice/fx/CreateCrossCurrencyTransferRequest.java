package com.ledger.ledgerservice.fx;

public record CreateCrossCurrencyTransferRequest(
        String sourceAccountRef, String destAccountRef, long sourceAmountMinor, String idempotencyKey) {
}
