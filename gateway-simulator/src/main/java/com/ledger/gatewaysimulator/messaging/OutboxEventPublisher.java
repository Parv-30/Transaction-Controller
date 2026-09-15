package com.ledger.gatewaysimulator.messaging;

import com.ledger.gatewaysimulator.domain.OutboxEvent;
import com.ledger.gatewaysimulator.repository.OutboxRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * Publishes a single outbox event and marks it published, in its own transaction. Kept as a
 * separate bean (rather than a private method on {@link OutboxPollingPublisher}) so that
 * {@code @Transactional} is applied via Spring's proxy — a self-invocation from within the
 * same class would silently bypass the proxy and run with no transaction.
 */
@Component
public class OutboxEventPublisher {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxEventPublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public void publishOne(OutboxEvent event) {
        Message message = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, event.getId().toString())
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, event.getAggregateId().toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, event.getEventType())
                .setContentType("application/json")
                .build();

        String routingKey = event.getEventType();
        rabbitTemplate.send(MessagingConstants.GATEWAY_SIM_EXCHANGE, routingKey, message);

        event.markPublished();
        outboxRepository.save(event);
    }
}
