package com.ledger.holdsservice.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "account_balance_cache")
public class AccountBalanceCache {

    @Id
    @Column(name = "account_ref")
    private String accountRef;

    @Column(name = "posted_balance_minor", nullable = false)
    private long postedBalanceMinor;

    @Column(name = "held_balance_minor", nullable = false)
    private long heldBalanceMinor;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalanceCache() {
        // JPA
    }

    public AccountBalanceCache(String accountRef, long postedBalanceMinor, long heldBalanceMinor) {
        this.accountRef = accountRef;
        this.postedBalanceMinor = postedBalanceMinor;
        this.heldBalanceMinor = heldBalanceMinor;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getAccountRef() { return accountRef; }
    public long getPostedBalanceMinor() { return postedBalanceMinor; }
    public long getHeldBalanceMinor() { return heldBalanceMinor; }

    public long availableBalanceMinor() {
        return postedBalanceMinor - heldBalanceMinor;
    }

    public void setPostedBalanceMinor(long postedBalanceMinor) {
        this.postedBalanceMinor = postedBalanceMinor;
    }

    public void hold(long amountMinor) {
        this.heldBalanceMinor += amountMinor;
    }

    public void releaseHeld(long amountMinor) {
        this.heldBalanceMinor -= amountMinor;
    }
}
