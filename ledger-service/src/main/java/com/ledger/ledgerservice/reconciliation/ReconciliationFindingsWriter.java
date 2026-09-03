package com.ledger.ledgerservice.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.ledgerservice.domain.ReconciliationFinding;
import com.ledger.ledgerservice.domain.ReconciliationFinding.FindingType;
import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.repository.ReconciliationFindingRepository;
import com.ledger.ledgerservice.repository.ReconciliationRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the {@code @Transactional} boundaries for persisting reconciliation state:
 * creating the initial RUNNING row, and later writing findings plus the terminal
 * COMPLETED/FAILED status. Split into its own bean for the same self-invocation-proxy reason
 * as {@link ReconciliationChecks}, and so that {@link ReconciliationService} never holds a DB
 * transaction open across its HTTP call to the Processor.
 */
@Component
class ReconciliationFindingsWriter {

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationFindingRepository findingRepository;
    private final ObjectMapper objectMapper;

    ReconciliationFindingsWriter(ReconciliationRunRepository runRepository,
                                  ReconciliationFindingRepository findingRepository,
                                  ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.findingRepository = findingRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    ReconciliationRun createRun(ReconciliationRun run) {
        return runRepository.saveAndFlush(run);
    }

    /**
     * Persists all findings for this run and marks it COMPLETED, in one transaction so a
     * reader never observes a COMPLETED run with a partial findings set.
     */
    @Transactional
    ReconciliationRun completeRun(UUID runId, int transactionsChecked, List<UUID> imbalancedTransactionIds,
                                   List<UUID> missingOutboxTransactionIds,
                                   List<ReconciliationChecks.StaleOutboxRow> stuckOutboxRows) {
        ReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Reconciliation run not found: " + runId));

        for (UUID txnId : imbalancedTransactionIds) {
            saveFinding(runId, FindingType.ENTRIES_NOT_ZERO, txnId, null,
                    Map.of("transactionId", txnId.toString()));
        }
        for (UUID txnId : missingOutboxTransactionIds) {
            saveFinding(runId, FindingType.OUTBOX_MISSING, txnId, null,
                    Map.of("transactionId", txnId.toString()));
        }
        for (ReconciliationChecks.StaleOutboxRow row : stuckOutboxRows) {
            saveFinding(runId, FindingType.OUTBOX_STUCK_UNPUBLISHED, null, row.outboxId(),
                    Map.of("outboxId", row.outboxId().toString(), "aggregateId", row.aggregateId().toString()));
        }

        String summary = writeSummaryJson(Map.of(
                "status", "completed",
                "transactionsChecked", transactionsChecked,
                "entriesImbalanceCount", imbalancedTransactionIds.size(),
                "outboxMissingCount", missingOutboxTransactionIds.size(),
                "outboxStuckCount", stuckOutboxRows.size()));

        run.complete(transactionsChecked, imbalancedTransactionIds.size(), missingOutboxTransactionIds.size(),
                stuckOutboxRows.size(), summary);
        return runRepository.save(run);
    }

    /**
     * Marks the run FAILED in its own fresh transaction. Called after the HTTP call (or a DB
     * check) throws, so this must never share a transaction with whatever failed -- otherwise
     * a DataAccessException from the DB-checks phase could have already marked that earlier
     * transaction rollback-only, and this write would be silently discarded (or throw
     * UnexpectedRollbackException) instead of durably recording the failure, which is exactly
     * the class of bug this task's brief calls out from Task 4.
     */
    @Transactional
    ReconciliationRun failRun(UUID runId, String reason) {
        ReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Reconciliation run not found: " + runId));
        run.fail(writeSummaryJson(Map.of("failureReason", reason == null ? "unknown error" : reason)));
        return runRepository.save(run);
    }

    private void saveFinding(UUID runId, FindingType type, UUID transactionId, UUID outboxId,
                              Map<String, String> detail) {
        String detailJson = writeSummaryJson(detail);
        findingRepository.save(new ReconciliationFinding(UUID.randomUUID(), runId, type,
                transactionId, outboxId, detailJson));
    }

    private String writeSummaryJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize reconciliation JSON", e);
        }
    }
}
