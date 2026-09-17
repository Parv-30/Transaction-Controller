package com.ledger.holdsservice.service;

public class AccountRefRequiredForNonAdminException extends RuntimeException {
    public AccountRefRequiredForNonAdminException() {
        super("A non-admin caller must supply a non-blank accountRef to list holds");
    }
}
