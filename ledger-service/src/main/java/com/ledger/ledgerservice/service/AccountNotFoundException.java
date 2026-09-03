package com.ledger.ledgerservice.service;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountRef) {
        super("Account not found: " + accountRef);
    }
}
