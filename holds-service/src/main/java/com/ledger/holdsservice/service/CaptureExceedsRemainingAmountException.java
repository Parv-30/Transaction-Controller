package com.ledger.holdsservice.service;

import java.util.UUID;

public class CaptureExceedsRemainingAmountException extends RuntimeException {
    public CaptureExceedsRemainingAmountException(UUID holdId, long requested, long remaining) {
        super("Capture amount " + requested + " exceeds remaining hold amount " + remaining + " for hold " + holdId);
    }
}
