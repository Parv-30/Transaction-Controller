package com.ledger.ledgerservice.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PendingFxTransferRepository extends JpaRepository<PendingFxTransfer, UUID> {
    Optional<PendingFxTransfer> findByIdempotencyKey(String idempotencyKey);
}
