package com.ledger.ledgerservice.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionDetailResponse(UUID transactionId, String status, String transactionType,
                                         String description, Instant createdAt,
                                         UUID reversalOfTransactionId, List<EntryResponse> entries) {
}
