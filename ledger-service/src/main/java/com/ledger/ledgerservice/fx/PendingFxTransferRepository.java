package com.ledger.ledgerservice.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingFxTransferRepository extends JpaRepository<PendingFxTransfer, UUID> {
    Optional<PendingFxTransfer> findByIdempotencyKey(String idempotencyKey);

    List<PendingFxTransfer> findByStatusInAndUpdatedAtBefore(
            List<PendingFxTransferStatus> statuses, Instant cutoff);
}
