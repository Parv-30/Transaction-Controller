package com.ledger.ledgerservice.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key reused with a different request body: " + idempotencyKey);
    }
}
