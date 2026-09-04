package com.ledger.holdsservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "holds")
public class Hold {

    @Id
    private UUID id;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "destination_account_ref", nullable = false)
    private String destinationAccountRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "created_transaction_id")
    private UUID createdTransactionId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Hold() {
        // JPA
    }

    public Hold(UUID id, String accountRef, String destinationAccountRef, long amountMinor,
                String currency, HoldStatus status, String idempotencyKey, Instant expiresAt) {
        this.id = id;
        this.accountRef = accountRef;
        this.destinationAccountRef = destinationAccountRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.capturedAmountMinor = 0L;
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

    public UUID getId() { return id; }
    public String getAccountRef() { return accountRef; }
    public String getDestinationAccountRef() { return destinationAccountRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public HoldStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getCapturedAmountMinor() { return capturedAmountMinor; }
    public UUID getCreatedTransactionId() { return createdTransactionId; }
    public long getVersion() { return version; }

    public long remainingAmountMinor() {
        return amountMinor - capturedAmountMinor;
    }

    public void markCaptured(long capturedNowMinor, UUID transactionId) {
        this.capturedAmountMinor += capturedNowMinor;
        this.createdTransactionId = transactionId;
        this.status = HoldStatus.CAPTURED;
    }

    public void markReleased() {
        this.status = HoldStatus.RELEASED;
    }

    public void markExpired() {
        this.status = HoldStatus.EXPIRED;
    }
}
