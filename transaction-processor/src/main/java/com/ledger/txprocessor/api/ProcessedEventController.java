package com.ledger.txprocessor.api;

import com.ledger.txprocessor.api.dto.BatchStatusRequest;
import com.ledger.txprocessor.api.dto.ProcessedEventStatusResponse;
import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.repository.ProcessedEventRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only endpoint consumed by Ledger's reconciliation job (Ledger's
 * ProcessorReconciliationClient) to cross-check whether outbox rows Ledger considers "stuck
 * unpublished" have actually been captured/published/consumed by this service's CDC
 * pipeline. Never mutates state.
 */
@RestController
public class ProcessedEventController {

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventController(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @PostMapping("/processed-events/batch-status")
    public List<ProcessedEventStatusResponse> batchStatus(@RequestBody BatchStatusRequest request) {
        List<ProcessedEvent> found = processedEventRepository.findByOutboxEventIdIn(request.outboxEventIds());
        return found.stream()
                .map(e -> new ProcessedEventStatusResponse(
                        e.getOutboxEventId(), e.getStatus().name(), e.getPublishedAt(), e.getConsumedAt(), e.getDeliveryCount()))
                .toList();
    }
}
