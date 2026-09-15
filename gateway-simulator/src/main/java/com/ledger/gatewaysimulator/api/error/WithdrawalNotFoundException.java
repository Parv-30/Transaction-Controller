package com.ledger.gatewaysimulator.api.error;

import java.util.UUID;

public class WithdrawalNotFoundException extends RuntimeException {
    public WithdrawalNotFoundException(UUID withdrawalId) {
        super("Withdrawal not found: " + withdrawalId);
    }
}
