package com.ledger.ledgerservice.service;

/**
 * Thrown when a transaction is posted against an account that is not ACTIVE. A dedicated type
 * (rather than a generic {@link IllegalStateException}) so {@code ApiExceptionHandler} can map
 * exactly this business condition to HTTP 422, without also catching unrelated internal faults
 * (e.g. outbox payload serialization failures in {@code TransactionPoster}) that happen to be
 * raised as {@code IllegalStateException} too and should surface as a 500 instead.
 */
public class AccountNotActiveException extends RuntimeException {
    public AccountNotActiveException(String message) {
        super(message);
    }
}
