package com.ledger.holdsservice.service;

import com.ledger.holdsservice.domain.HoldStatus;

import java.util.UUID;

public class HoldNotActiveException extends RuntimeException {
    public HoldNotActiveException(UUID holdId, HoldStatus status) {
        super("Hold " + holdId + " is not ACTIVE (status: " + status + ")");
    }
}
