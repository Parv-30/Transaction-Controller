package com.ledger.ledgerservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "account_ref", nullable = false, unique = true)
    private String accountRef;

    @Column(name = "display_name")
    private String displayName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA
    }

    public Account(UUID id, String accountRef, String displayName, String currency,
                   long balanceMinor, AccountStatus status) {
        this.id = id;
        this.accountRef = accountRef;
        this.displayName = displayName;
        this.currency = currency;
        this.balanceMinor = balanceMinor;
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getAccountRef() { return accountRef; }
    public String getDisplayName() { return displayName; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public AccountStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

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

    public void debit(long amountMinor) {
        this.balanceMinor -= amountMinor;
    }

    public void credit(long amountMinor) {
        this.balanceMinor += amountMinor;
    }
}
