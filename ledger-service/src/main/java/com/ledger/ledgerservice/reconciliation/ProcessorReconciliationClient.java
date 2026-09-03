package com.ledger.ledgerservice.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP client for the Transaction Processor's read-only
 * {@code POST /processed-events/batch-status} endpoint. Deliberately makes a plain
 * synchronous, un-transactional call: this must never be invoked from inside a Spring
 * {@code @Transactional} method, since an HTTP call that blocks (or fails) while holding a DB
 * transaction open would needlessly extend lock/connection hold time and would risk mixing
 * "the HTTP call failed" with "the surrounding transaction should roll back" in a way this
 * job's caller ({@link ReconciliationService}) is specifically structured to avoid.
 */
@Component
public class ProcessorReconciliationClient {

    public record ProcessedEventStatus(UUID outboxEventId, String status, Instant publishedAt,
                                        Instant consumedAt, int deliveryCount) {
    }

    private final RestClient restClient;

    public ProcessorReconciliationClient(@Value("${processor.base-url}") String processorBaseUrl) {
        this.restClient = RestClient.builder().baseUrl(processorBaseUrl).build();
    }

    public List<ProcessedEventStatus> batchStatus(List<UUID> outboxEventIds) {
        record Request(List<UUID> outboxEventIds) {}
        return restClient.post()
                .uri("/processed-events/batch-status")
                .body(new Request(outboxEventIds))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<ProcessedEventStatus>>() {});
    }
}
