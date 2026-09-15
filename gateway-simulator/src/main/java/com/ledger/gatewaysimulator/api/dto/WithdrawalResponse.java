package com.ledger.gatewaysimulator.api.dto;

import java.util.UUID;

public record WithdrawalResponse(UUID id, UUID sourceTransactionId, String accountRef, long amountMinor,
                                  String currency, String status, UUID reversalTransactionId) {
}
