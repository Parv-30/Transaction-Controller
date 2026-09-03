package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Owns the transactional dedup-gate transition used by {@link OutboxEventConsumer}. Kept as
 * a separate Spring bean (rather than a private method on the listener) so that
 * {@link #consumeIfNotAlready} is invoked through the Spring AOP transactional proxy —
 * a self-invoked {@code @Transactional} method on the same class would silently run without
 * a transaction, which would defeat the whole point of committing before ack.
 */
@Service
public class DedupGateService {

    private final ProcessedEventRepository processedEventRepository;

    public DedupGateService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Atomically transitions the {@code outboxEventId} row to CONSUMED if (and only if) it
     * has not already been consumed, using a single conditional UPDATE
     * ({@link ProcessedEventRepository#markConsumedIfNotAlready}) as the sole source of truth
     * — never a Java-side read-then-write — so that two concurrent redeliveries of the same
     * message racing on separate threads/connections cannot both believe they are first.
     * On the losing/duplicate path, bumps {@code delivery_count} instead of repeating the
     * (idempotent, no-op-for-V1) business effect.
     */
    @Transactional
    public void consumeIfNotAlready(UUID outboxEventId) {
        if (!processedEventRepository.existsById(outboxEventId)) {
            throw new IllegalStateException(
                    "Received message for unknown outboxEventId " + outboxEventId +
                    " — publisher must persist a CAPTURED row before publishing");
        }

        int rowsTransitioned = processedEventRepository.markConsumedIfNotAlready(outboxEventId, Instant.now());

        if (rowsTransitioned == 0) {
            // Row was already CONSUMED — this is a duplicate/redelivered message.
            processedEventRepository.incrementDeliveryCount(outboxEventId, Instant.now());
        }

        // rowsTransitioned == 1: this call won the dedup gate. Business effect for V1 is
        // limited to recording completion — no downstream side effect exists yet
        // (Holds/Fees/Gateway Simulator arrive in later versions).
    }
}
