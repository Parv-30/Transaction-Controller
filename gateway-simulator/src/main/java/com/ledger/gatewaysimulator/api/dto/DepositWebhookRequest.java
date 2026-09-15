package com.ledger.gatewaysimulator.api.dto;

public record DepositWebhookRequest(String externalReference, String accountRef, long amountMinor, String currency) {
}
