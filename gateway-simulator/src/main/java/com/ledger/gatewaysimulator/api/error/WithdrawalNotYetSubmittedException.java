package com.ledger.gatewaysimulator.api.error;

import java.util.UUID;

public class WithdrawalNotYetSubmittedException extends RuntimeException {
    public WithdrawalNotYetSubmittedException(UUID withdrawalId) {
        super("No SUBMITTED withdrawal found yet for id: " + withdrawalId);
    }
}
