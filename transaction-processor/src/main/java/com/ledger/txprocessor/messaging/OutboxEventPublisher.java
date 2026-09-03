package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Component
public class OutboxEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ProcessedEventRepository processedEventRepository;

    public OutboxEventPublisher(RabbitTemplate rabbitTemplate, ProcessedEventRepository processedEventRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.processedEventRepository = processedEventRepository;
        this.rabbitTemplate.setConfirmCallback(this::onConfirm);
    }

    public void publish(UUID outboxEventId, UUID aggregateId, String eventType, String payload) {
        Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, outboxEventId.toString())
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, aggregateId.toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, eventType)
                .setContentType("application/json")
                .build();

        CorrelationData correlationData = new CorrelationData(outboxEventId.toString());
        rabbitTemplate.convertAndSend(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message, correlationData);
    }

    /**
     * Publisher-confirm callback. This is invoked exactly once per {@link #publish} call
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
    void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
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
