package com.ledger.holdsservice.service;

public class InsufficientAvailableBalanceException extends RuntimeException {
    public InsufficientAvailableBalanceException(String accountRef) {
        super("Insufficient available balance for account: " + accountRef);
    }
}
