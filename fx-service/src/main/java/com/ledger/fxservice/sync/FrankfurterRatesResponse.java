package com.ledger.fxservice.sync;

import java.math.BigDecimal;
import java.util.Map;

public record FrankfurterRatesResponse(
        BigDecimal amount,
        String base,
        String date,
        Map<String, BigDecimal> rates) {
}
