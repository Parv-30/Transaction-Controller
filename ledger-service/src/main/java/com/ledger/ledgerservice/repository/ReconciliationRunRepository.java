package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    List<ReconciliationRun> findAllByOrderByStartedAtDesc();
}
