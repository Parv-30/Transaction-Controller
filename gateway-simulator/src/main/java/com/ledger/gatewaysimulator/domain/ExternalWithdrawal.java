package com.ledger.gatewaysimulator.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_withdrawals")
public class ExternalWithdrawal {

    @Id
    private UUID id;

    @Column(name = "source_transaction_id", nullable = false, unique = true)
    private UUID sourceTransactionId;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    protected ExternalWithdrawal() {
    }

    public ExternalWithdrawal(UUID id, UUID sourceTransactionId, String accountRef, long amountMinor,
                               String currency) {
        this.id = id;
        this.sourceTransactionId = sourceTransactionId;
        this.accountRef = accountRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = WithdrawalStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSourceTransactionId() { return sourceTransactionId; }
    public String getAccountRef() { return accountRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public WithdrawalStatus getStatus() { return status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getReversalTransactionId() { return reversalTransactionId; }

    public void markConfirmed() {
        this.status = WithdrawalStatus.CONFIRMED;
        this.resolvedAt = Instant.now();
    }

    public void markFailed() {
        this.status = WithdrawalStatus.FAILED;
        this.resolvedAt = Instant.now();
    }

    public void markTimedOut() {
        this.status = WithdrawalStatus.TIMED_OUT;
        this.resolvedAt = Instant.now();
    }

    public void markReversed(UUID reversalTransactionId) {
        this.status = WithdrawalStatus.REVERSED;
        this.reversalTransactionId = reversalTransactionId;
    }
}
