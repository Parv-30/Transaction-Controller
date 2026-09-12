package com.ledger.ledgerservice.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FxQuote(UUID quoteId, BigDecimal rateUsed, Instant expiresAt, boolean stale) {
}
