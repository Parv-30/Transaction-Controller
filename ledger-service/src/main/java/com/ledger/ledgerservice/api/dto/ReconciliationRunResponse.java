package com.ledger.ledgerservice.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunResponse(UUID runId, String status, Instant startedAt, Instant finishedAt,
                                         int transactionsChecked, int entriesImbalanceCount,
                                         int outboxMissingCount, int outboxStuckCount) {
}
