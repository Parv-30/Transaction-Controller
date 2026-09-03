package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_findings")
public class ReconciliationFinding {

    public enum FindingType { ENTRIES_NOT_ZERO, OUTBOX_MISSING, OUTBOX_STUCK_UNPUBLISHED }

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false)
    private FindingType findingType;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "outbox_id")
    private UUID outboxId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected ReconciliationFinding() {
        // JPA
    }

    public ReconciliationFinding(UUID id, UUID runId, FindingType findingType,
                                  UUID transactionId, UUID outboxId, String detail) {
        this.id = id;
        this.runId = runId;
        this.findingType = findingType;
        this.transactionId = transactionId;
        this.outboxId = outboxId;
        this.detail = detail;
        this.detectedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public FindingType getFindingType() { return findingType; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getOutboxId() { return outboxId; }
    public String getDetail() { return detail; }
    public Instant getDetectedAt() { return detectedAt; }
}
