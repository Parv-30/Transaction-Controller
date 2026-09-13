package com.ledger.ledgerservice.service;

/**
 * Thrown when an account-creation request asks for an {@code accountRef} inside a prefix the
 * platform reserves for its own internal accounts. The {@code fx-clearing-} prefix in particular
 * carries a privilege -- {@link TransactionPoster} lets accounts with that prefix be debited
 * below zero -- so allowing a client to choose it would let any caller mint money by debiting a
 * self-created clearing account without limit. Rejecting the ref at creation time keeps that
 * privilege unreachable by client-chosen data.
 */
public class ReservedAccountRefException extends RuntimeException {
    public ReservedAccountRefException(String accountRef) {
        super("Account ref uses a reserved prefix and cannot be created: " + accountRef);
    }
}
