package com.ledger.ledgerservice.fx;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pending_fx_transfers")
public class PendingFxTransfer {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "quote_id", nullable = false)
    private UUID quoteId;

    @Column(name = "source_account_ref", nullable = false)
    private String sourceAccountRef;

    @Column(name = "dest_account_ref", nullable = false)
    private String destAccountRef;

    @Column(name = "source_amount_minor", nullable = false)
    private long sourceAmountMinor;

    @Column(name = "rate_used", nullable = false, precision = 18, scale = 8)
    private BigDecimal rateUsed;

    @Column(name = "dest_amount_minor", nullable = false)
    private long destAmountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PendingFxTransferStatus status;

    @Column(name = "leg1_transaction_id")
    private UUID leg1TransactionId;

    @Column(name = "leg2_transaction_id")
    private UUID leg2TransactionId;

    @Column(name = "compensation_transaction_id")
    private UUID compensationTransactionId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PendingFxTransfer() {
        // JPA
    }

    public PendingFxTransfer(UUID id, String idempotencyKey, UUID quoteId, String sourceAccountRef,
                             String destAccountRef, long sourceAmountMinor, BigDecimal rateUsed,
                             long destAmountMinor) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.quoteId = quoteId;
        this.sourceAccountRef = sourceAccountRef;
        this.destAccountRef = destAccountRef;
        this.sourceAmountMinor = sourceAmountMinor;
        this.rateUsed = rateUsed;
        this.destAmountMinor = destAmountMinor;
        this.status = PendingFxTransferStatus.PENDING;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getQuoteId() { return quoteId; }
    public String getSourceAccountRef() { return sourceAccountRef; }
    public String getDestAccountRef() { return destAccountRef; }
    public long getSourceAmountMinor() { return sourceAmountMinor; }
    public BigDecimal getRateUsed() { return rateUsed; }
    public long getDestAmountMinor() { return destAmountMinor; }
    public PendingFxTransferStatus getStatus() { return status; }
    public UUID getLeg1TransactionId() { return leg1TransactionId; }
    public UUID getLeg2TransactionId() { return leg2TransactionId; }
    public UUID getCompensationTransactionId() { return compensationTransactionId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markLeg1Posted(UUID transactionId) {
        this.leg1TransactionId = transactionId;
        this.status = PendingFxTransferStatus.LEG1_POSTED;
    }

    public void markLeg2Posted(UUID transactionId) {
        this.leg2TransactionId = transactionId;
        this.status = PendingFxTransferStatus.COMPLETED;
    }

    public void markCompensating() {
        this.status = PendingFxTransferStatus.COMPENSATING;
    }

    public void markCompensated(UUID transactionId) {
        this.compensationTransactionId = transactionId;
        this.status = PendingFxTransferStatus.COMPENSATED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
