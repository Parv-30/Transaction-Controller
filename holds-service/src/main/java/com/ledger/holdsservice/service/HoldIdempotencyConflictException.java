package com.ledger.holdsservice.service;

public class HoldIdempotencyConflictException extends RuntimeException {
    public HoldIdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key reused with a different request body: " + idempotencyKey);
    }
}
