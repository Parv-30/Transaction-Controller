package com.ledger.ledgerservice.service;

import java.util.UUID;

public class CannotReverseAReversalException extends RuntimeException {
    public CannotReverseAReversalException(UUID transactionId) {
        super("Cannot reverse a transaction that is itself a reversal: " + transactionId);
    }
}
