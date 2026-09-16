package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    private String description;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_payload_hash", nullable = false, columnDefinition = "char(64)")
    private String requestPayloadHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reversal_of_transaction_id")
    private UUID reversalOfTransactionId;

    protected Transaction() {
        // JPA
    }

    public Transaction(UUID id, String idempotencyKey, TransactionStatus status,
                        String transactionType, String description, String requestPayloadHash) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.transactionType = transactionType;
        this.description = description;
        this.requestPayloadHash = requestPayloadHash;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public TransactionStatus getStatus() { return status; }
    public String getTransactionType() { return transactionType; }
    public String getDescription() { return description; }
    public String getRequestPayloadHash() { return requestPayloadHash; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getReversalOfTransactionId() { return reversalOfTransactionId; }

    public void setReversalOfTransactionId(UUID reversalOfTransactionId) {
        this.reversalOfTransactionId = reversalOfTransactionId;
    }

    public void markReversed() {
        this.status = TransactionStatus.REVERSED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
