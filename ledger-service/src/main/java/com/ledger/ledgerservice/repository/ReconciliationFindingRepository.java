package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.ReconciliationFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, UUID> {
    List<ReconciliationFinding> findByRunId(UUID runId);
}
