package com.ledger.gatewaysimulator.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.gatewaysimulator.service.WithdrawalSubmissionService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Consumes V1's existing {@code ledger.transaction.posted} event, filtered to
 * {@code transactionType == "WITHDRAWAL_EXTERNAL"}. Field names in the payload
 * ({@code transactionId}, {@code debitAccountRef}, {@code amountMinor}, {@code currency},
 * {@code transactionType}) are verified against the real, current
 * {@code ledger-service/.../TransactionPoster.buildOutboxPayload} as of this task
 * (transactionType added in Task 1 of this plan).
 */
@Component
public class LedgerTransactionPostedConsumer {

    private static final String WITHDRAWAL_EXTERNAL_TYPE = "WITHDRAWAL_EXTERNAL";

    private final WithdrawalSubmissionService submissionService;
    private final ProcessedEventGate processedEventGate;
    private final ObjectMapper objectMapper;

    public LedgerTransactionPostedConsumer(WithdrawalSubmissionService submissionService,
                                            ProcessedEventGate processedEventGate,
                                            ObjectMapper objectMapper) {
        this.submissionService = submissionService;
        this.processedEventGate = processedEventGate;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = MessagingConstants.GATEWAY_SIM_TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    public void handle(Message message, com.rabbitmq.client.Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String outboxEventIdHeader = (String) message.getMessageProperties()
                .getHeaders().get(MessagingConstants.HEADER_OUTBOX_EVENT_ID);

        if (outboxEventIdHeader == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        JsonNode payload = objectMapper.readTree(message.getBody());
        String transactionType = payload.path("transactionType").asText("TRANSFER");

        if (!WITHDRAWAL_EXTERNAL_TYPE.equals(transactionType)) {
            // Not a withdrawal — this service only cares about WITHDRAWAL_EXTERNAL, but every
            // ledger.transaction.posted event flows through this same queue, so ordinary
            // transfers must be acked and ignored, not left unacknowledged.
            channel.basicAck(deliveryTag, false);
            return;
        }

        UUID eventId = UUID.fromString(outboxEventIdHeader);
        boolean firstDelivery = processedEventGate.markProcessedIfNew(eventId);
        if (firstDelivery) {
            UUID transactionId = UUID.fromString(payload.path("transactionId").asText());
            String accountRef = payload.path("debitAccountRef").asText();
            long amountMinor = payload.path("amountMinor").asLong();
            String currency = payload.path("currency").asText();
            submissionService.submit(transactionId, accountRef, amountMinor, currency);
        }
        channel.basicAck(deliveryTag, false);
    }
}
