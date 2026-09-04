package com.ledger.holdsservice.service;

import java.util.UUID;

public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(UUID holdId) {
        super("Hold not found: " + holdId);
    }
}
