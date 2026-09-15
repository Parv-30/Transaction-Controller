package com.ledger.gatewaysimulator.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "webhook_dedup")
public class WebhookDedup {

    @Id
    @Column(name = "external_reference")
    private String externalReference;

    @Column(name = "webhook_count", nullable = false)
    private int webhookCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected WebhookDedup() {
    }

    public WebhookDedup(String externalReference) {
        this.externalReference = externalReference;
        this.webhookCount = 1;
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = Instant.now();
    }

    public String getExternalReference() { return externalReference; }
    public int getWebhookCount() { return webhookCount; }

    public void recordRedelivery() {
        this.webhookCount++;
        this.lastSeenAt = Instant.now();
    }
}
