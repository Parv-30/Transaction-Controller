package com.ledger.gatewaysimulator.api;

public class DepositNotFoundException extends RuntimeException {
    public DepositNotFoundException(String externalReference) {
        super("No external deposit found for externalReference=" + externalReference);
    }
}
