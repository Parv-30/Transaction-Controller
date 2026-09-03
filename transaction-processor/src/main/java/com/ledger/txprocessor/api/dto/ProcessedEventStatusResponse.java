package com.ledger.txprocessor.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProcessedEventStatusResponse(
        UUID outboxEventId,
        String status,
        Instant publishedAt,
        Instant consumedAt,
        int deliveryCount
) {
}
