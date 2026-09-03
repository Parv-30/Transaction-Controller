package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "transactions_checked", nullable = false)
    private int transactionsChecked;

    @Column(name = "entries_imbalance_count", nullable = false)
    private int entriesImbalanceCount;

    @Column(name = "outbox_missing_count", nullable = false)
    private int outboxMissingCount;

    @Column(name = "outbox_stuck_count", nullable = false)
    private int outboxStuckCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String summary;

    protected ReconciliationRun() {
        // JPA
    }

    public ReconciliationRun(UUID id, Instant startedAt) {
        this.id = id;
        this.startedAt = startedAt;
        this.status = Status.RUNNING;
    }

    public UUID getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Status getStatus() { return status; }
    public int getTransactionsChecked() { return transactionsChecked; }
    public int getEntriesImbalanceCount() { return entriesImbalanceCount; }
    public int getOutboxMissingCount() { return outboxMissingCount; }
    public int getOutboxStuckCount() { return outboxStuckCount; }
    public String getSummary() { return summary; }

    public void complete(int transactionsChecked, int entriesImbalanceCount,
                          int outboxMissingCount, int outboxStuckCount, String summary) {
        this.finishedAt = Instant.now();
        this.status = Status.COMPLETED;
        this.transactionsChecked = transactionsChecked;
        this.entriesImbalanceCount = entriesImbalanceCount;
        this.outboxMissingCount = outboxMissingCount;
        this.outboxStuckCount = outboxStuckCount;
        this.summary = summary;
    }

    /**
     * @param summaryJson a pre-serialized JSON object (e.g. {"failureReason": "..."}) --
     *                     callers must use a real JSON serializer (ObjectMapper) rather than
     *                     hand-building this string, since a naive string-replace escape of
     *                     the failure reason (which may come from an exception message
     *                     containing arbitrary characters, including backslashes or control
     *                     characters) can produce invalid JSON that Postgres's jsonb column
     *                     type rejects, which would itself throw while trying to persist the
     *                     FAILED marking.
     */
    public void fail(String summaryJson) {
        this.finishedAt = Instant.now();
        this.status = Status.FAILED;
        this.summary = summaryJson;
    }
}
