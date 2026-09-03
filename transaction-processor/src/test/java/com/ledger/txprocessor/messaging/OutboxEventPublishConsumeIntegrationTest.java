package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OutboxEventPublishConsumeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("processor_db")
            .withUsername("processor")
            .withPassword("processor");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management-alpine");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private DedupGateService dedupGateService;
    @Autowired
    private OutboxConfirmHandler outboxConfirmHandler;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void cleanUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    void publishedEventIsConsumedExactlyOnce() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        outboxEventPublisher.publish(outboxEventId, aggregateId, "TRANSACTION_POSTED", "{\"test\":true}");

        // 25s (not 10s) gives room for one AMQP connection-recovery cycle: Docker Desktop's
        // forwarded-port proxy can drop the listener's connection shortly after a burst of
        // activity, and the listener container needs a reconnect + requeue + redelivery
        // round-trip (see application-test.yml's shortened recovery-interval) to catch up.
        await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
            assertThat(updated.getDeliveryCount()).isEqualTo(1);
        });

        // Note: publishedAt is NOT asserted here as always-non-null in the final row state.
        // The publisher confirm callback (OutboxConfirmHandler#onConfirm) and the consumer's
        // dedup-gate transition race on the same row, and both legitimate orderings are
        // possible: if the confirm callback commits first, publishedAt is populated before
        // the CONSUMED transition; if the consumer wins first (as it validly can — the broker
        // can deliver the message and fire the publisher confirm in either order),
        // ProcessedEventRepository's markPublishedIfNotConsumed is a guarded no-op and
        // publishedAt is intentionally left null forever, since the row has already reached
        // its terminal CONSUMED state. Asserting publishedAt is always non-null in the FINAL
        // row would assert a specific race outcome that this system deliberately does not
        // guarantee. What IS guaranteed, and IS asserted by publishConfirmActuallyMarksRowPublished
        // below, is that the confirm callback is capable of reaching PUBLISHED at all — i.e.
        // that markPublishedIfNotConsumed actually executes inside a real transaction.
    }

    /**
     * Directly proves the publish-confirm handler's {@code @Transactional} method actually
     * runs inside a transaction and commits {@code status = PUBLISHED} /
     * {@code published_at != null} — the regression test for the self-invocation bug.
     * {@link OutboxEventPublisher} used to register {@code this::onConfirm} — a direct method
     * reference on itself — as the RabbitMQ confirm callback. That bypassed Spring's
     * CGLIB/JDK transactional proxy entirely: {@code @Transactional} was silently ignored, and
     * every real confirm callback invocation threw {@code TransactionRequiredException} out of
     * the {@code @Modifying} repository call, so {@code processed_events.status} could NEVER
     * reach PUBLISHED via the confirm path — only ever CONSUMED, via the
     * separately-transactional consumer path. The test above
     * (publishedEventIsConsumedExactlyOnce) would not have caught this: it only asserts the
     * final CONSUMED state, which the consumer reaches independently of whether the confirm
     * callback's own transaction ever ran, and its own comment rationalized away a
     * never-populated publishedAt as "a legitimate race outcome" rather than a broken
     * transaction.
     *
     * <p>This test avoids that same race entirely (rather than asserting on a value that a
     * live consumer could legitimately leave null) by calling
     * {@link OutboxConfirmHandler#onConfirm} directly, through its Spring-managed bean
     * reference — exactly the same call shape the production confirm-callback registration
     * uses ({@code outboxConfirmHandler::onConfirm}) — without ever publishing/consuming a
     * message. If {@code @Transactional} is not actually taking effect (e.g. the
     * self-invocation bug is reintroduced by routing this through {@code this::onConfirm}
     * again), {@code markPublishedIfNotConsumed}'s {@code @Modifying} query throws
     * {@code TransactionRequiredException} and this test fails with that exception, rather
     * than a misleadingly-swallowed no-op.
     */
    @Test
    void publishConfirmActuallyMarksRowPublished() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        outboxConfirmHandler.onConfirm(new CorrelationData(outboxEventId.toString()), true, null);

        ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.PUBLISHED);
        assertThat(updated.getPublishedAt())
                .as("publishedAt must be set by the confirm handler's own transaction; if this is null, "
                        + "the confirm handler's @Transactional method never actually committed "
                        + "(the self-invocation bug)")
                .isNotNull();
    }

    @Test
    void duplicateDeliveryOfSameOutboxEventIdIsIgnoredNotReprocessed() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        outboxEventPublisher.publish(outboxEventId, aggregateId, "TRANSACTION_POSTED", "{\"test\":true}");
        await().atMost(25, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow().getStatus())
                        .isEqualTo(ProcessedEventStatus.CONSUMED));

        // Simulate a redelivery: republish the same outboxEventId directly.
        outboxEventPublisher.publish(outboxEventId, aggregateId, "TRANSACTION_POSTED", "{\"test\":true}");

        await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
            assertThat(updated.getDeliveryCount()).isEqualTo(2);
        });
    }

    /**
     * Proves the dedup gate is safe against genuinely concurrent redelivery — not just
     * sequential redelivery. Two threads race to call {@link DedupGateService#consumeIfNotAlready}
     * for the *same* outboxEventId at (as close to) the same instant, simulating two
     * redeliveries landing on separate consumer threads/connections. The atomic conditional
     * UPDATE (WHERE status <> 'CONSUMED') must ensure exactly one of them transitions the row,
     * with no lost update — regardless of which thread's DB transaction commits first.
     */
    @Test
    void concurrentRedeliveryOfSameOutboxEventIdTransitionsExactlyOnce() throws InterruptedException {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        int concurrentDeliveries = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentDeliveries);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(concurrentDeliveries);

        try {
            for (int i = 0; i < concurrentDeliveries; i++) {
                executor.submit(() -> {
                    try {
                        startLine.await();
                        dedupGateService.consumeIfNotAlready(outboxEventId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLine.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(finishLine.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
        // Exactly one delivery wins the gate (no delivery-count bump); the other
        // (concurrentDeliveries - 1) all lose the race and each increments delivery_count by 1.
        assertThat(updated.getDeliveryCount()).isEqualTo(concurrentDeliveries);
    }
}
