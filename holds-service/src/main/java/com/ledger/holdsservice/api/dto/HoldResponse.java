package com.ledger.holdsservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record HoldResponse(
        UUID holdId,
        String status,
        String accountRef,
        String destinationAccountRef,
        long amountMinor,
        long capturedAmountMinor,
        String currency,
        Instant expiresAt,
        boolean replay
) {
}
