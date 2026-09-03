package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Owns the transactional publish-confirm bookkeeping used by {@link OutboxEventPublisher}.
 * Kept as a separate Spring bean (rather than a private/package method on the publisher, and
 * rather than the publisher registering {@code this::onConfirm} as its own RabbitMQ confirm
 * callback) for the same reason {@link DedupGateService} is split out of
 * {@link OutboxEventConsumer}: a {@code RabbitTemplate.ConfirmCallback} method reference
 * captured via {@code this::method} points directly at the target object, bypassing Spring's
 * CGLIB/JDK dynamic proxy entirely. A self-invoked {@code @Transactional} method reached that
 * way runs with no transaction whatsoever — the annotation is silently ignored — which is
 * exactly what happened here: {@link OutboxEventPublisher} used to register
 * {@code this::onConfirm} directly, so every confirm callback invocation (fired by the
 * RabbitMQ client's own I/O thread, calling straight into the raw
 * {@code OutboxEventPublisher} instance) threw
 * {@code jakarta.persistence.TransactionRequiredException} out of the {@code @Modifying}
 * repository calls below, caught nowhere but the confirm-listener's own exception logging.
 * Registering {@code outboxConfirmHandler::onConfirm} instead — a method reference to a
 * distinct, genuinely-injected {@code @Service} bean — resolves through the real Spring
 * transactional proxy, so {@code @Transactional} actually takes effect.
 */
@Service
public class OutboxConfirmHandler {

    private final ProcessedEventRepository processedEventRepository;

    public OutboxConfirmHandler(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Publisher-confirm callback body. This is invoked exactly once per {@code publish} call
     * (the broker acks or nacks a given correlated publish exactly once), so there is no
     * concurrent race between two confirm callbacks for the same outboxEventId. However,
     * there IS a race against {@link OutboxEventConsumer} / {@link DedupGateService}: the
     * broker can (and observably does, even for a same-process publish/consume round trip
     * over a local connection) route the message to the consumer and fire this confirm
     * callback close enough together that a naive read-then-write here — reading the row
     * before the consumer's atomic dedup-gate UPDATE commits, then blindly saving every
     * column back — would silently overwrite an already-committed {@code CONSUMED} status
     * back to {@code PUBLISHED} (a lost update, confirmed by reproducing it under Hibernate
     * SQL logging: the confirm thread's full-entity UPDATE landed after the consumer
     * thread's conditional UPDATE and clobbered it). So this uses the same narrowly-scoped,
     * conditional-UPDATE pattern as the dedup gate
     * ({@link ProcessedEventRepository#markPublishedIfNotConsumed} /
     * {@link ProcessedEventRepository#markPublishFailedIfNotConsumed}): it touches only the
     * publish-ack columns and is a no-op if the row is already CONSUMED.
     */
    @Transactional
    public void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null) {
            return;
        }
        UUID outboxEventId = UUID.fromString(correlationData.getId());
        Instant now = Instant.now();
        if (ack) {
            processedEventRepository.markPublishedIfNotConsumed(outboxEventId, now);
        } else {
            processedEventRepository.markPublishFailedIfNotConsumed(outboxEventId, now);
        }
    }
}
