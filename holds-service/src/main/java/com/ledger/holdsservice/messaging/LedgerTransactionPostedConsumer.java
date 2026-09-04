package com.ledger.holdsservice.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Consumes V1's existing {@code ledger.transaction.posted} event (published by Transaction
 * Processor) to keep {@code account_balance_cache.posted_balance_minor} fresh. Field names in
 * the payload ({@code debitAccountRef}, {@code creditAccountRef}, {@code debitAccountBalanceAfter},
 * {@code creditAccountBalanceAfter}) are verified against V1's real
 * {@code ledger-service/.../TransactionPoster.buildOutboxPayload} as of this task.
 */
@Component
public class LedgerTransactionPostedConsumer {

    private final LedgerTransactionPostedApplier applier;
    private final ProcessedEventGate processedEventGate;

    public LedgerTransactionPostedConsumer(LedgerTransactionPostedApplier applier,
                                            ProcessedEventGate processedEventGate) {
        this.applier = applier;
        this.processedEventGate = processedEventGate;
    }

    @RabbitListener(queues = MessagingConstants.HOLDS_TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    public void handle(Message message, com.rabbitmq.client.Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String outboxEventIdHeader = (String) message.getMessageProperties()
                .getHeaders().get(MessagingConstants.HEADER_OUTBOX_EVENT_ID);

        if (outboxEventIdHeader == null) {
            // Defensive: a malformed/foreign message on this queue. Ack and drop rather than
            // poison-loop forever with no DLQ configured in V2.
            channel.basicAck(deliveryTag, false);
            return;
        }

        UUID eventId = UUID.fromString(outboxEventIdHeader);
        boolean firstDelivery = processedEventGate.markProcessedIfNew(eventId);
        if (firstDelivery) {
            applier.applyBalanceUpdate(message.getBody());
        }
        channel.basicAck(deliveryTag, false);
    }
}
