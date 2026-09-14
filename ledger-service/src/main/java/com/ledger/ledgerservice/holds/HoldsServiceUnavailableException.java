package com.ledger.ledgerservice.holds;

public class HoldsServiceUnavailableException extends RuntimeException {
    public HoldsServiceUnavailableException(String accountRef, Throwable cause) {
        super("Could not reach Holds Service to check held balance for account: " + accountRef, cause);
    }
}
