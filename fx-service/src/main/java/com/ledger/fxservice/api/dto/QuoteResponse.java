package com.ledger.fxservice.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteResponse(UUID quoteId, BigDecimal rateUsed, Instant expiresAt, boolean stale) {
}
