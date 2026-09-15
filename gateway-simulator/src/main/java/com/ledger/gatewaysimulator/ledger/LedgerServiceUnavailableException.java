package com.ledger.gatewaysimulator.ledger;

public class LedgerServiceUnavailableException extends RuntimeException {
    public LedgerServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
