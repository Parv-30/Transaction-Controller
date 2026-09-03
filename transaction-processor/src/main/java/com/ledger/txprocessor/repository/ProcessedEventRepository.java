package com.ledger.txprocessor.repository;

import com.ledger.txprocessor.domain.ProcessedEvent;
import com.ledger.txprocessor.domain.ProcessedEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    Optional<ProcessedEvent> findByOutboxEventId(UUID outboxEventId);
    List<ProcessedEvent> findByOutboxEventIdIn(List<UUID> outboxEventIds);
    List<ProcessedEvent> findByStatus(ProcessedEventStatus status);
}
