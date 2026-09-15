package com.ledger.gatewaysimulator.messaging;

import com.ledger.gatewaysimulator.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lightweight polling relay from {@code outbox_events} to RabbitMQ — deliberately simpler than
 * V1's embedded-Debezium CDC pipeline, per the spec's Global Constraint for secondary services.
 *
 * Note: unlike V1's Transaction Processor (which used RabbitMQ publisher-confirms to know
 * whether a broker ack succeeded before marking a row published), this simpler polling
 * publisher marks {@code published_at} immediately after a successful synchronous {@code send()}
 * call without waiting for a broker confirm. This is an accepted, deliberate simplification
 * consistent with the spec's "lighter" outbox-relay decision for secondary services — a message
 * lost between {@code send()} and broker receipt (e.g. a connection drop mid-send) would leave
 * the event's {@code published_at} set despite the message never truly landing. Since nothing
 * currently consumes Gateway Simulator's own published events, this gap has no functional impact
 * today; if a future consumer is added, revisit whether publisher-confirms are needed then.
 */
@Component
public class OutboxPollingPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingPublisher.class);

    private final OutboxRepository outboxRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public OutboxPollingPublisher(OutboxRepository outboxRepository, OutboxEventPublisher outboxEventPublisher) {
        this.outboxRepository = outboxRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Scheduled(fixedDelayString = "${gateway-sim.outbox-poll.interval-ms:2000}")
    public void pollAndPublish() {
        var unpublished = outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc();
        for (var event : unpublished) {
            try {
                outboxEventPublisher.publishOne(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage(), e);
                // leave published_at null; picked up again next poll
            }
        }
    }
}
