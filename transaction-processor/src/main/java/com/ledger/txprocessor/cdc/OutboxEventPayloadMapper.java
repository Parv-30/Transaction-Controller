package com.ledger.txprocessor.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxEventPayloadMapper {

    private final ObjectMapper objectMapper;

    public OutboxEventPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record CapturedOutboxRow(UUID id, UUID aggregateId, String eventType, String payloadJson) {
    }

    public CapturedOutboxRow parse(String debeziumValueJson) {
        try {
            JsonNode root = objectMapper.readTree(debeziumValueJson);
            JsonNode after = root.get("after");
            if (after == null || after.isNull()) {
                return null; // a delete or non-insert event on the outbox table; V1 never deletes outbox rows
            }
            UUID id = UUID.fromString(after.get("id").asText());
            UUID aggregateId = UUID.fromString(after.get("aggregate_id").asText());
            String eventType = after.get("event_type").asText();
            String payloadJson = after.get("payload").asText();
            return new CapturedOutboxRow(id, aggregateId, eventType, payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Debezium change event", e);
        }
    }
}
