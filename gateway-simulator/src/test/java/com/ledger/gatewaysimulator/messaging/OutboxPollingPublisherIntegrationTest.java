package com.ledger.gatewaysimulator.messaging;

import com.ledger.gatewaysimulator.domain.OutboxEvent;
import com.ledger.gatewaysimulator.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
            .withDatabaseName("gateway_sim_db").withUsername("gatewaysim").withPassword("gatewaysim");

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
        registry.add("gateway-sim.outbox-poll.interval-ms", () -> "500");
    }

    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    private static final String TEST_QUEUE = "gateway-sim.test.deposit-credited.queue";

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();

        // Declare a throwaway queue bound to this service's own exchange so the test can
        // observe messages actually landing on MessagingConstants.GATEWAY_SIM_EXCHANGE,
        // without this service needing a permanent consumer of its own events yet.
        TopicExchange exchange = new TopicExchange(MessagingConstants.GATEWAY_SIM_EXCHANGE, true, false);
        Queue queue = new Queue(TEST_QUEUE, true);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with("deposit.credited");
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(binding);
        rabbitAdmin.purgeQueue(TEST_QUEUE);
    }

    @Test
    void unpublishedOutboxRowIsPolledAndPublishedToRabbitMq() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), aggregateId, "deposit.credited",
                "{\"depositId\":\"" + aggregateId + "\"}");
        outboxRepository.save(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
            assertThat(updated.getPublishedAt()).isNotNull();
        });

        Message received = rabbitTemplate.receive(TEST_QUEUE, 5000);
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getHeaders())
                .containsEntry(MessagingConstants.HEADER_OUTBOX_EVENT_ID, event.getId().toString())
                .containsEntry(MessagingConstants.HEADER_AGGREGATE_ID, aggregateId.toString())
                .containsEntry(MessagingConstants.HEADER_EVENT_TYPE, "deposit.credited");
    }

    @Test
    void eventRoutedWithDifferentTypeDoesNotReachUnrelatedBinding() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), aggregateId, "withdrawal.reversed",
                "{\"withdrawalId\":\"" + aggregateId + "\"}");
        outboxRepository.save(event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEvent updated = outboxRepository.findById(event.getId()).orElseThrow();
            assertThat(updated.getPublishedAt()).isNotNull();
        });

        // TEST_QUEUE is bound only to "deposit.credited" — a "withdrawal.reversed" event must
        // not be delivered there.
        Message received = rabbitTemplate.receive(TEST_QUEUE, 200);
        assertThat(received).isNull();
    }
}
