package com.ledger.txprocessor.messaging;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves that a {@code processed_events} row stuck in {@link ProcessedEventStatus#PUBLISH_FAILED}
 * is not a permanent dead end: {@link PublishRetryService} picks it up and re-publishes it,
 * and it reaches a terminal PUBLISHED/CONSUMED state.
 *
 * <p>Follows the same real-Postgres + real-RabbitMQ Testcontainers pattern as
 * {@link OutboxEventPublishConsumeIntegrationTest}. Rather than waiting for the real
 * {@code @Scheduled} interval to elapse, the sweep is triggered directly by calling
 * {@link PublishRetryService#retryFailedPublishes()}, keeping the test fast and deterministic.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PublishRetryServiceIntegrationTest {

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
        // Disable the service's own scheduled sweep for this test so only the explicit,
        // directly-invoked call below drives retries -- keeps the test deterministic instead
        // of racing the real fixedDelay.
        registry.add("publish.retry.interval-ms", () -> "3600000");
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private PublishRetryService publishRetryService;

    @BeforeEach
    void cleanUp() {
        processedEventRepository.deleteAll();
    }

    @Test
    void publishFailedRowIsRetriedAndReachesTerminalState() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent stuck = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.PUBLISH_FAILED, "{\"test\":true}");
        processedEventRepository.save(stuck);

        publishRetryService.retryFailedPublishes();

        await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
            assertThat(updated.getStatus())
                    .as("a retried publish should be consumed exactly like any other delivery")
                    .isIn(ProcessedEventStatus.PUBLISHED, ProcessedEventStatus.CONSUMED);
        });

        await().atMost(25, TimeUnit.SECONDS).untilAsserted(() -> {
            ProcessedEvent updated = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProcessedEventStatus.CONSUMED);
        });
    }

    @Test
    void sweepWithNoFailedRowsIsANoOp() {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        ProcessedEvent captured = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                Instant.now(), ProcessedEventStatus.CAPTURED, "{}");
        processedEventRepository.save(captured);

        publishRetryService.retryFailedPublishes();

        ProcessedEvent unchanged = processedEventRepository.findByOutboxEventId(outboxEventId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ProcessedEventStatus.CAPTURED);
    }
}
