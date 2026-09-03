package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sweeps {@code processed_events} rows stuck in {@link ProcessedEventStatus#PUBLISH_FAILED}
 * and retries the RabbitMQ publish for each. Without this, a row that reaches
 * PUBLISH_FAILED (the publisher-confirm nack path in {@link OutboxConfirmHandler}) is a
 * permanent dead end: {@code OutboxChangeConsumer} commits the Debezium offset unconditionally
 * regardless of publish outcome and never re-captures an already-CAPTURED row, so nothing else
 * in the system would ever look at that row again.
 *
 * <p>Retrying is safe to do by simply calling {@link OutboxEventPublisher#publish} again with
 * the same {@code outboxEventId}, for two reasons:
 * <ul>
 *   <li>No new {@code processed_events} row is created — the row already exists (in
 *       PUBLISH_FAILED) from the original CAPTURED write, and this service never inserts;
 *       it only triggers another publish attempt referencing the same id.</li>
 *   <li>A duplicate publish is safe on the consume side: {@link DedupGateService}'s
 *       {@code consumeIfNotAlready} (backed by
 *       {@link ProcessedEventRepository#markConsumedIfNotAlready}'s
 *       {@code WHERE status <> CONSUMED} guard) means a redelivery of the same
 *       outboxEventId — whether from an actual broker redelivery or, as here, a deliberate
 *       republish — is only ever consumed once; any extra delivery is counted via
 *       {@code incrementDeliveryCount} and otherwise ignored. Symmetrically, the confirm
 *       path ({@code markPublishedIfNotConsumed} / {@code markPublishFailedIfNotConsumed})
 *       is guarded the same way, so a retry's own confirm callback can never clobber a row
 *       that has already reached CONSUMED.</li>
 * </ul>
 * If the retried publish nacks again, {@code markPublishFailedIfNotConsumed} simply leaves the
 * row in PUBLISH_FAILED (a harmless same-state UPDATE), and it is picked up again on the next
 * sweep — the intended retry-until-success behavior.
 */
@Component
public class PublishRetryService {

    private static final Logger log = LoggerFactory.getLogger(PublishRetryService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public PublishRetryService(ProcessedEventRepository processedEventRepository,
                                OutboxEventPublisher outboxEventPublisher) {
        this.processedEventRepository = processedEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Scheduled(fixedDelayString = "${publish.retry.interval-ms:30000}")
    public void retryFailedPublishes() {
        List<ProcessedEvent> failed = processedEventRepository.findByStatus(ProcessedEventStatus.PUBLISH_FAILED);
        if (failed.isEmpty()) {
            return;
        }
        log.info("PublishRetryService: retrying {} row(s) in PUBLISH_FAILED", failed.size());
        for (ProcessedEvent row : failed) {
            try {
                outboxEventPublisher.publish(row.getOutboxEventId(), row.getAggregateId(),
                        row.getEventType(), row.getPayload());
            } catch (Exception e) {
                log.warn("PublishRetryService: retry publish threw for outboxEventId={}",
                        row.getOutboxEventId(), e);
            }
        }
    }
}
