package com.ledger.ledgerservice.service;

import java.util.UUID;

public class TransactionAlreadyReversedException extends RuntimeException {
    public TransactionAlreadyReversedException(UUID transactionId) {
        super("Transaction already reversed: " + transactionId);
    }
}
