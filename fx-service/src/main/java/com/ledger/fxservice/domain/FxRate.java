package com.ledger.fxservice.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_currency", nullable = false, columnDefinition = "char(3)")
    private String baseCurrency;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_currency", nullable = false, columnDefinition = "char(3)")
    private String quoteCurrency;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false)
    private String source;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected FxRate() {
        // JPA
    }

    public FxRate(UUID id, String baseCurrency, String quoteCurrency, BigDecimal rate,
                  String source, Instant fetchedAt) {
        this.id = id;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.source = source;
        this.fetchedAt = fetchedAt;
    }

    public UUID getId() { return id; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public BigDecimal getRate() { return rate; }
    public String getSource() { return source; }
    public Instant getFetchedAt() { return fetchedAt; }
}
