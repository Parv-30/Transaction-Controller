package com.ledger.gatewaysimulator.api.dto;

import java.util.UUID;

public record DepositResponse(String externalReference, String accountRef, long amountMinor,
                               String currency, String status, UUID transactionId) {
}
