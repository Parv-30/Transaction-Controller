package com.ledger.txprocessor.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "outbox_event_id")
    private UUID outboxEventId;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessedEventStatus status;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(UUID outboxEventId, UUID aggregateId, String eventType,
                           Instant capturedAt, ProcessedEventStatus status, String payload) {
        this.outboxEventId = outboxEventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.capturedAt = capturedAt;
        this.status = status;
        this.deliveryCount = 1;
        this.payload = payload;
        this.updatedAt = Instant.now();
    }

    public UUID getOutboxEventId() { return outboxEventId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public ProcessedEventStatus getStatus() { return status; }
    public int getDeliveryCount() { return deliveryCount; }
    public String getPayload() { return payload; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void markPublished() {
        this.status = ProcessedEventStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markPublishFailed() {
        this.status = ProcessedEventStatus.PUBLISH_FAILED;
        this.updatedAt = Instant.now();
    }

    public void markConsumed() {
        this.status = ProcessedEventStatus.CONSUMED;
        this.consumedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void recordDuplicateDelivery() {
        this.deliveryCount += 1;
        this.updatedAt = Instant.now();
    }
}
