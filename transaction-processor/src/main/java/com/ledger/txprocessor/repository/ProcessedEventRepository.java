package com.ledger.txprocessor.repository;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    Optional<ProcessedEvent> findByOutboxEventId(UUID outboxEventId);
    List<ProcessedEvent> findByOutboxEventIdIn(List<UUID> outboxEventIds);
    List<ProcessedEvent> findByStatus(ProcessedEventStatus status);

    /**
     * Atomic dedup-gate transition: flips a row to CONSUMED only if it is not already
     * CONSUMED, in a single conditional UPDATE. The WHERE clause (not a prior Java-side
     * read) is the sole source of truth for "has this outboxEventId already been consumed" —
     * this is what makes the gate safe against two concurrent redeliveries of the same
     * message racing on separate threads/connections, since Postgres serializes concurrent
     * UPDATEs to the same row and the second one's WHERE predicate is re-evaluated against
     * the first transaction's committed result before it can match.
     *
     * @return number of rows updated: 1 if this call won the race and transitioned the row
     *         to CONSUMED, 0 if the row was already CONSUMED (by this or another delivery).
     */
    @Modifying
    @Query("UPDATE ProcessedEvent e SET e.status = com.ledger.txprocessor.domain.ProcessedEventStatus.CONSUMED, " +
           "e.consumedAt = :consumedAt, e.updatedAt = :consumedAt " +
           "WHERE e.outboxEventId = :outboxEventId AND e.status <> com.ledger.txprocessor.domain.ProcessedEventStatus.CONSUMED")
    int markConsumedIfNotAlready(@Param("outboxEventId") UUID outboxEventId, @Param("consumedAt") Instant consumedAt);

    /**
     * Atomic increment of delivery_count for the duplicate-delivery path, guarded by the
     * same predicate style as {@link #markConsumedIfNotAlready} so it never races with a
     * concurrent transition away from CONSUMED (there is none in this state machine, but
     * keeping the WHERE as the sole gate avoids any read-then-write step here too).
     */
    @Modifying
    @Query("UPDATE ProcessedEvent e SET e.deliveryCount = e.deliveryCount + 1, e.updatedAt = :now " +
           "WHERE e.outboxEventId = :outboxEventId")
    int incrementDeliveryCount(@Param("outboxEventId") UUID outboxEventId, @Param("now") Instant now);

    /**
     * Atomic, narrowly-scoped transition for the publisher-confirm ack path: sets only
     * {@code status = PUBLISHED} and {@code published_at}/{@code updated_at}, guarded by
     * {@code status <> CONSUMED}. This must NOT be a Java-side read-then-{@code save()} of
     * the whole entity: the RabbitMQ broker can (and in practice does, even for a
     * same-process publish/consume round trip) deliver the message to
     * {@link com.ledger.txprocessor.messaging.OutboxEventConsumer} and fire this confirm
     * callback close enough together that a full-entity {@code save()} here — built from a
     * row read before the consumer's {@link #markConsumedIfNotAlready} commits — would
     * blindly overwrite every column, including a just-committed {@code status = CONSUMED},
     * back to {@code PUBLISHED} (a lost update). Scoping the UPDATE to only the publish-ack
     * columns, with the same conditional-WHERE pattern as the dedup gate, makes it safe
     * regardless of ordering: if CONSUMED has already landed, this is a harmless no-op.
     *
     * @return number of rows updated: 0 means the row was already CONSUMED, so the
     *         publish-ack columns were intentionally left untouched.
     */
    @Modifying
    @Query("UPDATE ProcessedEvent e SET e.status = com.ledger.txprocessor.domain.ProcessedEventStatus.PUBLISHED, " +
           "e.publishedAt = :publishedAt, e.updatedAt = :publishedAt " +
           "WHERE e.outboxEventId = :outboxEventId AND e.status <> com.ledger.txprocessor.domain.ProcessedEventStatus.CONSUMED")
    int markPublishedIfNotConsumed(@Param("outboxEventId") UUID outboxEventId, @Param("publishedAt") Instant publishedAt);

    /**
     * Companion to {@link #markPublishedIfNotConsumed} for the publisher-confirm nack path,
     * scoped and guarded the same way (never overwrites a row already CONSUMED).
     */
    @Modifying
    @Query("UPDATE ProcessedEvent e SET e.status = com.ledger.txprocessor.domain.ProcessedEventStatus.PUBLISH_FAILED, " +
           "e.updatedAt = :now " +
           "WHERE e.outboxEventId = :outboxEventId AND e.status <> com.ledger.txprocessor.domain.ProcessedEventStatus.CONSUMED")
    int markPublishFailedIfNotConsumed(@Param("outboxEventId") UUID outboxEventId, @Param("now") Instant now);
}
