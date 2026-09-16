package com.ledger.ledgerservice.reconciliation;

import com.ledger.ledgerservice.api.dto.ReconciliationRunResponse;
import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.repository.ReconciliationRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates a single reconciliation run: creates the run row, runs the read-only DB checks
 * (entries zero-sum, outbox missing), cross-checks any stale outbox rows against the
 * Transaction Processor's {@code processed_events} via HTTP, then records findings and marks
 * the run COMPLETED -- or, if any step throws, marks it FAILED with the reason.
 *
 * <p>Deliberately NOT {@code @Transactional} itself, and deliberately does not delegate to a
 * single {@code @Transactional} method that also performs the HTTP call. See
 * {@link ReconciliationChecks} and {@link ReconciliationFindingsWriter} for why the DB work is
 * split into two short-lived transactions with the blocking HTTP call to the Processor
 * sandwiched between them, not enclosing them.
 *
 * <p><b>Concurrent runs:</b> this method carries no locking against overlapping executions
 * (e.g. the scheduled {@link ReconciliationJob} firing again before a slow prior run
 * finishes, or an on-demand {@code POST /reconciliation/runs} landing mid-schedule). This is a
 * deliberate decision, not an oversight: every run gets its own {@code run_id}, and this job
 * never mutates ledger data (accounts/transactions/entries/outbox) -- it only reads them and
 * writes to its own {@code reconciliation_runs}/{@code reconciliation_findings} rows, which are
 * scoped by run_id and never updated by a different run. Two overlapping runs can only produce
 * duplicate/overlapping *diagnostic* findings for the same underlying issue (e.g. the same
 * stuck outbox row reported by both runs), which is a harmless, self-correcting redundancy for
 * a read-only auditor -- not a correctness hazard the way a lost update on shared mutable state
 * would be. Adding a mutex/advisory lock would only protect against wasted work and duplicate
 * rows, at the cost of making on-demand runs (needed by the Task 10 chaos suite) block or fail
 * during a slow scheduled run; that tradeoff is not worth it for a purely additive audit trail.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationChecks checks;
    private final ReconciliationFindingsWriter writer;
    private final ProcessorReconciliationClient processorClient;
    private final ReconciliationRunRepository reconciliationRunRepository;

    private final AtomicLong entriesImbalanceGauge = new AtomicLong(0);
    private final AtomicLong outboxMissingGauge = new AtomicLong(0);
    private final AtomicLong outboxStuckGauge = new AtomicLong(0);

    public ReconciliationService(ReconciliationChecks checks,
                                  ReconciliationFindingsWriter writer,
                                  ProcessorReconciliationClient processorClient,
                                  ReconciliationRunRepository reconciliationRunRepository,
                                  MeterRegistry meterRegistry) {
        this.checks = checks;
        this.writer = writer;
        this.processorClient = processorClient;
        this.reconciliationRunRepository = reconciliationRunRepository;
        meterRegistry.gauge("ledger.reconciliation.entries_imbalance", entriesImbalanceGauge);
        meterRegistry.gauge("ledger.reconciliation.outbox_missing", outboxMissingGauge);
        meterRegistry.gauge("ledger.reconciliation.outbox_stuck", outboxStuckGauge);
    }

    public ReconciliationRun runReconciliation() {
        ReconciliationRun run = writer.createRun(new ReconciliationRun(UUID.randomUUID(), Instant.now()));
        UUID runId = run.getId();

        try {
            ReconciliationChecks.CheckResults results = checks.runDbChecks();

            List<ReconciliationChecks.StaleOutboxRow> stuck = crossCheckStaleOutboxRows(results.staleOutboxRows());

            entriesImbalanceGauge.set(results.imbalancedTransactionIds().size());
            outboxMissingGauge.set(results.missingOutboxTransactionIds().size());
            outboxStuckGauge.set(stuck.size());

            return writer.completeRun(runId, results.transactionsChecked(), results.imbalancedTransactionIds(),
                    results.missingOutboxTransactionIds(), stuck);
        } catch (Exception e) {
            log.warn("Reconciliation run {} failed: {}", runId, e.toString(), e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return writer.failRun(runId, reason);
        }
    }

    public List<ReconciliationRunResponse> listRuns() {
        return reconciliationRunRepository.findAllByOrderByStartedAtDesc().stream()
                .map(run -> new ReconciliationRunResponse(run.getId(), run.getStatus().name(),
                        run.getStartedAt(), run.getFinishedAt(), run.getTransactionsChecked(),
                        run.getEntriesImbalanceCount(), run.getOutboxMissingCount(), run.getOutboxStuckCount()))
                .toList();
    }

    /**
     * Calls the Processor's batch-status endpoint for every stale outbox row and returns the
     * subset that is NOT PUBLISHED/CONSUMED there -- i.e. genuinely stuck. Runs outside any DB
     * transaction. If this HTTP call throws (connection refused, timeout, non-2xx, deserialize
     * failure, etc.), the exception propagates to {@link #runReconciliation()}'s catch block,
     * which marks the whole run FAILED with the reason recorded -- it must NOT be caught here
     * and silently treated as "zero stuck findings", since that would misreport a
     * cross-service outage as a clean reconciliation result.
     */
    private List<ReconciliationChecks.StaleOutboxRow> crossCheckStaleOutboxRows(
            List<ReconciliationChecks.StaleOutboxRow> staleOutboxRows) {
        if (staleOutboxRows.isEmpty()) {
            return List.of();
        }

        List<UUID> staleIds = staleOutboxRows.stream()
                .map(ReconciliationChecks.StaleOutboxRow::outboxId)
                .toList();

        var statuses = processorClient.batchStatus(staleIds);
        var healthy = statuses.stream()
                .filter(s -> "PUBLISHED".equals(s.status()) || "CONSUMED".equals(s.status()))
                .map(ProcessorReconciliationClient.ProcessedEventStatus::outboxEventId)
                .collect(java.util.stream.Collectors.toSet());

        return staleOutboxRows.stream()
                .filter(row -> !healthy.contains(row.outboxId()))
                .toList();
    }
}
