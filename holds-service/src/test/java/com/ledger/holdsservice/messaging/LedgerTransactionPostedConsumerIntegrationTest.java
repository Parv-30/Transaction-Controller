package com.ledger.holdsservice.messaging;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class LedgerTransactionPostedConsumerIntegrationTest {

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
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanUp() {
        accountBalanceCacheRepository.deleteAll();
    }

    @Test
    void consumingATransactionPostedEventUpdatesPostedBalanceForBothAccounts() {
        String outboxEventId = UUID.randomUUID().toString();
        String payload = "{\"debitAccountRef\":\"acct-consumer-a\",\"creditAccountRef\":\"acct-consumer-b\"," +
                "\"debitAccountBalanceAfter\":5000,\"creditAccountBalanceAfter\":15000}";

        var message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, outboxEventId)
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, UUID.randomUUID().toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, "TRANSACTION_POSTED")
                .setContentType("application/json")
                .build();

        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AccountBalanceCache debitCache = accountBalanceCacheRepository.findById("acct-consumer-a").orElseThrow();
            AccountBalanceCache creditCache = accountBalanceCacheRepository.findById("acct-consumer-b").orElseThrow();
            assertThat(debitCache.getPostedBalanceMinor()).isEqualTo(5000L);
            assertThat(creditCache.getPostedBalanceMinor()).isEqualTo(15000L);
        });
    }

    @Test
    void duplicateDeliveryOfSameOutboxEventIdIsIgnoredNotReprocessed() {
        String outboxEventId = UUID.randomUUID().toString();
        String payload = "{\"debitAccountRef\":\"acct-dup-a\",\"creditAccountRef\":\"acct-dup-b\"," +
                "\"debitAccountBalanceAfter\":7000,\"creditAccountBalanceAfter\":9000}";

        var message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, outboxEventId)
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, UUID.randomUUID().toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, "TRANSACTION_POSTED")
                .setContentType("application/json")
                .build();

        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AccountBalanceCache debitCache = accountBalanceCacheRepository.findById("acct-dup-a").orElseThrow();
            assertThat(debitCache.getPostedBalanceMinor()).isEqualTo(7000L);
        });

        // Simulate a redelivery: resend the same message (same outboxEventId) again.
        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var counter = meterRegistry.find("holds.rabbitmq.redelivery").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
        });

        // Balance must not be reprocessed/reapplied by the duplicate delivery.
        AccountBalanceCache debitCache = accountBalanceCacheRepository.findById("acct-dup-a").orElseThrow();
        assertThat(debitCache.getPostedBalanceMinor()).isEqualTo(7000L);
    }
}
