package com.ledger.fxservice.api.dto;

public record QuoteRequest(String baseCurrency, String quoteCurrency, long amountMinor) {
}
