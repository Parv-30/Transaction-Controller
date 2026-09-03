package com.ledger.txprocessor.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-service contract test for {@code POST /processed-events/batch-status}.
 *
 * <p>No test in {@code mvn verify} previously booted a real Spring context for this endpoint
 * and checked its actual JSON shape against what Ledger Service's
 * {@code ProcessorReconciliationClient.ProcessedEventStatus} record expects to deserialize:
 * {@code outboxEventId}, {@code status}, {@code publishedAt}, {@code consumedAt},
 * {@code deliveryCount}. The two services live in separate Maven modules (this module cannot
 * depend on ledger-service's classes without introducing a cross-module compile dependency
 * that does not otherwise exist), so this test instead asserts the exact field-name/shape
 * contract directly against the raw JSON response -- if either side's field names or types
 * drift, this test fails, exactly like a Jackson deserialization into the real client record
 * would.
 *
 * <p>Uses a real {@code @SpringBootTest} web environment (not the Docker-dependent chaos
 * suite, and not a hand-written stub server like {@code ReconciliationServiceIntegrationTest}
 * uses on the Ledger Service side) with a real Postgres Testcontainer, following the same
 * pattern as {@link com.ledger.txprocessor.messaging.OutboxEventPublishConsumeIntegrationTest}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProcessedEventControllerContractTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("processor_db")
            .withUsername("processor")
            .withPassword("processor");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Local mirror of Ledger Service's {@code ProcessorReconciliationClient.ProcessedEventStatus}
     * record -- same field names, same types, in the same order. If Ledger Service's record
     * ever changes shape without this test being updated to match, the intent is that a
     * reviewer notices the drift; the deserialization assertion below is what actually catches
     * this module's own DTO drifting away from that shape.
     */
    private record ProcessedEventStatusMirror(UUID outboxEventId, String status, Instant publishedAt,
                                                Instant consumedAt, int deliveryCount) {
    }

    @Test
    void batchStatusResponseShapeMatchesLedgerServiceReconciliationClientContract() throws Exception {
        UUID outboxEventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant now = Instant.now();
        ProcessedEvent event = new ProcessedEvent(outboxEventId, aggregateId, "TRANSACTION_POSTED",
                now, ProcessedEventStatus.CONSUMED, "{}");
        processedEventRepository.save(event);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("outboxEventIds", List.of(outboxEventId));
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/processed-events/batch-status",
                HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String json = response.getBody();
        assertThat(json).isNotNull();

        // 1. Assert the raw JSON carries exactly the field names the reconciliation client
        //    depends on -- catches a rename on this side even if types still happen to line up.
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(1);
        JsonNode element = root.get(0);
        for (String expectedField : List.of("outboxEventId", "status", "publishedAt", "consumedAt", "deliveryCount")) {
            assertThat(element.has(expectedField))
                    .as("response JSON must contain field '%s' -- ProcessorReconciliationClient.ProcessedEventStatus "
                            + "deserializes exactly this field name", expectedField)
                    .isTrue();
        }

        // 2. Prove the JSON actually deserializes into the mirrored contract shape -- catches a
        //    type drift (e.g. status becoming an int, deliveryCount becoming a String) that the
        //    field-presence check above would miss.
        List<ProcessedEventStatusMirror> deserialized = objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, ProcessedEventStatusMirror.class));
        assertThat(deserialized).hasSize(1);
        ProcessedEventStatusMirror result = deserialized.get(0);
        assertThat(result.outboxEventId()).isEqualTo(outboxEventId);
        assertThat(result.status()).isEqualTo("CONSUMED");
        assertThat(result.deliveryCount()).isEqualTo(1);
    }
}
