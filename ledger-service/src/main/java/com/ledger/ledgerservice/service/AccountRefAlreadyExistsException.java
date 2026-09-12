package com.ledger.ledgerservice.service;

public class AccountRefAlreadyExistsException extends RuntimeException {
    public AccountRefAlreadyExistsException(String accountRef) {
        super("Account already exists: " + accountRef);
    }
}
