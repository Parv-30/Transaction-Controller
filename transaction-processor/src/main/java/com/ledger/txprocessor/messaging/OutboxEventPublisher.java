package com.ledger.txprocessor.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Publishes transaction-posted events to RabbitMQ with publisher confirms.
 *
 * <p>Confirm-callback design note: the actual publish-confirm bookkeeping lives in
 * {@link OutboxConfirmHandler#onConfirm} — a separate, genuinely-injected {@code @Service}
 * bean — not in a method on this class. Earlier, this constructor registered
 * {@code this::onConfirm} against a package-private {@code @Transactional} method declared
 * right here on {@code OutboxEventPublisher}. That is a classic Spring AOP self-invocation
 * bug: {@code this::onConfirm} captures a direct method reference on the actual object
 * instance, never passing through the CGLIB/JDK dynamic proxy that Spring's transaction
 * interceptor relies on. When the RabbitMQ client library's confirm-listener thread later
 * invoked that callback, it called straight into the raw instance, {@code @Transactional} was
 * silently ignored, and every {@code @Modifying} repository call inside the handler threw
 * {@code jakarta.persistence.TransactionRequiredException} — confirmed by a Docker Compose
 * smoke test, where the publisher's own PUBLISHED/PUBLISH_FAILED bookkeeping was found to
 * fail on every single confirm. Registering {@code outboxConfirmHandler::onConfirm} instead
 * (a method reference to a distinct bean, injected through the constructor like everywhere
 * else in this codebase) resolves through the real Spring-managed proxy, so the
 * {@code @Transactional} on {@link OutboxConfirmHandler#onConfirm} actually takes effect. This
 * mirrors the {@link OutboxEventConsumer} / {@link DedupGateService} split used for the same
 * class of problem on the consume side of this exact publisher/consumer pair.
 */
@Component
public class OutboxEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public OutboxEventPublisher(RabbitTemplate rabbitTemplate, OutboxConfirmHandler outboxConfirmHandler) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTemplate.setConfirmCallback(outboxConfirmHandler::onConfirm);
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
}
