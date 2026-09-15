package com.ledger.gatewaysimulator.messaging;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
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
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ExternalWithdrawalRepository withdrawalRepository;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanUp() {
        withdrawalRepository.deleteAll();
    }

    private static org.springframework.amqp.core.Message buildMessage(String outboxEventId, String payload) {
        return MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                .setHeader(MessagingConstants.HEADER_OUTBOX_EVENT_ID, outboxEventId)
                .setHeader(MessagingConstants.HEADER_AGGREGATE_ID, UUID.randomUUID().toString())
                .setHeader(MessagingConstants.HEADER_EVENT_TYPE, "TRANSACTION_POSTED")
                .setContentType("application/json")
                .build();
    }

    @Test
    void firstDeliveryOfWithdrawalExternalEventCreatesExternalWithdrawalRow() {
        String outboxEventId = UUID.randomUUID().toString();
        UUID transactionId = UUID.randomUUID();
        String payload = "{\"transactionId\":\"" + transactionId + "\"," +
                "\"debitAccountRef\":\"acct-withdrawal-source\"," +
                "\"creditAccountRef\":\"external-clearing-usd\"," +
                "\"amountMinor\":25000," +
                "\"currency\":\"USD\"," +
                "\"transactionType\":\"WITHDRAWAL_EXTERNAL\"}";

        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, buildMessage(outboxEventId, payload));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            ExternalWithdrawal withdrawal = withdrawalRepository.findBySourceTransactionId(transactionId)
                    .orElseThrow();
            assertThat(withdrawal.getAccountRef()).isEqualTo("acct-withdrawal-source");
            assertThat(withdrawal.getAmountMinor()).isEqualTo(25000L);
            assertThat(withdrawal.getCurrency()).isEqualTo("USD");
        });
    }

    @Test
    void transferTypedEventIsAckedAndIgnoredWithNoRowCreated() {
        String outboxEventId = UUID.randomUUID().toString();
        UUID transactionId = UUID.randomUUID();
        String payload = "{\"transactionId\":\"" + transactionId + "\"," +
                "\"debitAccountRef\":\"acct-transfer-source\"," +
                "\"creditAccountRef\":\"acct-transfer-dest\"," +
                "\"amountMinor\":5000," +
                "\"currency\":\"USD\"," +
                "\"transactionType\":\"TRANSFER\"}";

        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, buildMessage(outboxEventId, payload));

        // There is no positive signal to await for a message that should be ignored, so send a
        // second, distinguishable WITHDRAWAL_EXTERNAL message afterward on the same queue and
        // wait for its row to appear — because RabbitMQ delivers to a single queue consumer in
        // order, that row appearing proves the TRANSFER message was already consumed and acked
        // (not stuck or requeued ahead of it).
        String secondOutboxEventId = UUID.randomUUID().toString();
        UUID secondTransactionId = UUID.randomUUID();
        String secondPayload = "{\"transactionId\":\"" + secondTransactionId + "\"," +
                "\"debitAccountRef\":\"acct-transfer-marker\"," +
                "\"creditAccountRef\":\"external-clearing-usd\"," +
                "\"amountMinor\":100," +
                "\"currency\":\"USD\"," +
                "\"transactionType\":\"WITHDRAWAL_EXTERNAL\"}";
        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, buildMessage(secondOutboxEventId, secondPayload));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(withdrawalRepository.findBySourceTransactionId(secondTransactionId)).isPresent());

        assertThat(withdrawalRepository.findBySourceTransactionId(transactionId)).isEmpty();
    }

    @Test
    void redeliveringSameWithdrawalExternalEventTwiceCreatesExactlyOneRow() {
        String outboxEventId = UUID.randomUUID().toString();
        UUID transactionId = UUID.randomUUID();
        String payload = "{\"transactionId\":\"" + transactionId + "\"," +
                "\"debitAccountRef\":\"acct-dup-source\"," +
                "\"creditAccountRef\":\"external-clearing-usd\"," +
                "\"amountMinor\":8000," +
                "\"currency\":\"USD\"," +
                "\"transactionType\":\"WITHDRAWAL_EXTERNAL\"}";
        var message = buildMessage(outboxEventId, payload);

        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(withdrawalRepository.findBySourceTransactionId(transactionId)).isPresent());

        // Simulate a redelivery: resend the same message (same outboxEventId) again.
        rabbitTemplate.send(MessagingConstants.LEDGER_EXCHANGE,
                MessagingConstants.TRANSACTION_POSTED_ROUTING_KEY, message);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var counter = meterRegistry.find("gateway_sim.rabbitmq.redelivery").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
        });

        long matchingRows = withdrawalRepository.findAll().stream()
                .filter(w -> w.getSourceTransactionId().equals(transactionId))
                .count();
        assertThat(matchingRows).isEqualTo(1);
    }
}
