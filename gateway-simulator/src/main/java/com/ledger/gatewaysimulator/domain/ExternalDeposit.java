package com.ledger.gatewaysimulator.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_deposits")
public class ExternalDeposit {

    @Id
    private UUID id;

    @Column(name = "external_reference", nullable = false, unique = true)
    private String externalReference;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepositStatus status;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExternalDeposit() {
    }

    public ExternalDeposit(UUID id, String externalReference, String accountRef, long amountMinor,
                            String currency, DepositStatus status, String rawPayload) {
        this.id = id;
        this.externalReference = externalReference;
        this.accountRef = accountRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.rawPayload = rawPayload;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getExternalReference() { return externalReference; }
    public String getAccountRef() { return accountRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public DepositStatus getStatus() { return status; }
    public UUID getTransactionId() { return transactionId; }
    public String getRawPayload() { return rawPayload; }
    public Instant getCreatedAt() { return createdAt; }

    public void markCredited(UUID transactionId) {
        this.status = DepositStatus.CREDITED;
        this.transactionId = transactionId;
        this.updatedAt = Instant.now();
    }

    public void markRejected() {
        this.status = DepositStatus.REJECTED;
        this.updatedAt = Instant.now();
    }
}
