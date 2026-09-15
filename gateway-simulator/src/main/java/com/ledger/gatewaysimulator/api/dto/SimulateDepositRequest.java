package com.ledger.gatewaysimulator.api.dto;

public record SimulateDepositRequest(String accountRef, long amountMinor, String currency) {
}
