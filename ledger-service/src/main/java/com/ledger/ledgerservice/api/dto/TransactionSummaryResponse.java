package com.ledger.ledgerservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TransactionSummaryResponse(UUID transactionId, String status, String transactionType,
                                          String debitAccountRef, String creditAccountRef,
                                          long amountMinor, String currency, String description,
                                          Instant createdAt, UUID reversalOfTransactionId) {
}
