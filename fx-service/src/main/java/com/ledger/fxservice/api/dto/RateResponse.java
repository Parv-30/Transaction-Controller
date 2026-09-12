package com.ledger.fxservice.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RateResponse(BigDecimal rate, boolean stale, Instant fetchedAt) {
}
