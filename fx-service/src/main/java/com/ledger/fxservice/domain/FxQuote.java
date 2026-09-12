package com.ledger.fxservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_quotes")
public class FxQuote {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, columnDefinition = "char(3)")
    private String baseCurrency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency", nullable = false, columnDefinition = "char(3)")
    private String quoteCurrency;

    @Column(name = "rate_used", nullable = false, precision = 18, scale = 8)
    private BigDecimal rateUsed;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected FxQuote() {
        // JPA
    }

    public FxQuote(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rateUsed,
                   Instant lockedAt, Instant expiresAt) {
        this.id = id;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rateUsed = rateUsed;
        this.lockedAt = lockedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRateUsed() { return rateUsed; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }

    public void markConsumed() {
        this.consumedAt = Instant.now();
    }
}
