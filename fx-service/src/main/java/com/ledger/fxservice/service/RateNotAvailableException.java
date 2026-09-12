package com.ledger.fxservice.service;

public class RateNotAvailableException extends RuntimeException {
    public RateNotAvailableException(String baseCurrency, String quoteCurrency) {
        super("No exchange rate available for pair: " + baseCurrency + "/" + quoteCurrency);
    }
}
