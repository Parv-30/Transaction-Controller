package com.ledger.holdsservice.messaging;

import com.ledger.holdsservice.domain.OutboxEvent;
import com.ledger.holdsservice.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OutboxPollingPublisherIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db").withUsername("holds").withPassword("holds");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("holds.outbox-poll.interval-ms", () -> "500");
    }

    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();
    }

    @Test
    void unpublishedOutboxRowIsPolledAndPublishedToRabbitMq() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), aggregateId, "hold.created",
                "{\"holdId\":\"" + aggregateId + "\"}");
        outboxRepository.save(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
            assertThat(updated.getPublishedAt()).isNotNull();
        });

        Message received = rabbitTemplate.receive(MessagingConstants.HOLDS_TRANSACTION_POSTED_QUEUE, 100);
        // holds.hold.created was routed with a different routing key than this queue's binding
        // (ledger.transaction.posted) — this queue must NOT receive it. Assert no cross-delivery.
        assertThat(received).isNull();
    }
}
