package com.ledger.txprocessor.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Consumes transaction-posted events with manual ack and an atomic dedup gate.
 *
 * <p>Dedup-gate design note: the actual gate lives in {@link DedupGateService#consumeIfNotAlready}
 * as a single conditional {@code UPDATE ... WHERE status <> 'CONSUMED'}
 * ({@link com.ledger.txprocessor.repository.ProcessedEventRepository#markConsumedIfNotAlready}),
 * not a Java-side read-then-write. A plain "read the row, branch on its status in Java, then
 * save()" pattern would not be safe here even inside a single {@code @Transactional} method:
 * RabbitMQ can and does redeliver the same message to two different consumer
 * threads/connections concurrently (e.g. a connection blip triggers a requeue while the
 * original delivery is still mid-processing, or prefetch > 1 hands duplicate redeliveries to
 * separate threads). Two such concurrent transactions could each read the pre-update row,
 * both decide in Java "not yet CONSUMED", and then both call save() — since
 * {@code ProcessedEvent} has no {@code @Version} field, the second UPDATE (after blocking on
 * the first transaction's row lock and then being unblocked) would blindly overwrite all
 * columns with its own stale in-memory state, silently losing the first transaction's write
 * (a lost update). The atomic conditional UPDATE closes that gap: Postgres's row-level
 * locking plus WHERE-clause re-evaluation guarantees exactly one of two concurrent
 * transactions can ever flip a given row to CONSUMED, and the affected-row count tells the
 * caller, unambiguously, whether it won that race.
 *
 * <p>Ack-ordering note: the dedup-gate transition is committed by {@link DedupGateService}
 * (a separate, genuinely-proxied {@code @Transactional} bean) before this method calls
 * {@code channel.basicAck}. Manual ack is intentionally the very last statement here, after
 * {@link DedupGateService#consumeIfNotAlready} has returned — and therefore after its
 * transaction has committed — so a crash between DB commit and ack simply causes a harmless
 * redelivery (caught by the dedup gate above), whereas acking before commit could lose a
 * message whose DB state never became durable.
 */
@Component
public class OutboxEventConsumer {

    private final DedupGateService dedupGateService;

    public OutboxEventConsumer(DedupGateService dedupGateService) {
        this.dedupGateService = dedupGateService;
    }

    @RabbitListener(queues = MessagingConstants.TRANSACTION_POSTED_QUEUE, ackMode = "MANUAL")
    public void handle(Message message, com.rabbitmq.client.Channel channel) throws IOException {
        String outboxEventIdHeader = (String) message.getMessageProperties()
                .getHeaders().get(MessagingConstants.HEADER_OUTBOX_EVENT_ID);
        UUID outboxEventId = UUID.fromString(outboxEventIdHeader);
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        dedupGateService.consumeIfNotAlready(outboxEventId);

        channel.basicAck(deliveryTag, false);
    }
}
