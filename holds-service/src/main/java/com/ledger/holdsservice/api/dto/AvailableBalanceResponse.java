package com.ledger.holdsservice.api.dto;

public record AvailableBalanceResponse(
        String accountRef,
        long postedBalanceMinor,
        long heldBalanceMinor,
        long availableBalanceMinor
) {
}
