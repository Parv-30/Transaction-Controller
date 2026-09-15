package com.ledger.gatewaysimulator.api.error;

import java.util.UUID;

public class WithdrawalNotYetSubmittedException extends RuntimeException {
    public WithdrawalNotYetSubmittedException(UUID id) {
        super("No SUBMITTED withdrawal found yet for: " + id);
    }
}
