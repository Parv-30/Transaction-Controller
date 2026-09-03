package com.ledger.ledgerservice.service;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountRef) {
        super("Insufficient funds in account: " + accountRef);
    }
}
