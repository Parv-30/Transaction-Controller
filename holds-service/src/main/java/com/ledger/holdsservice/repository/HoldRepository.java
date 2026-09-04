package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
    Optional<Hold> findByIdempotencyKey(String idempotencyKey);
    List<Hold> findByStatusAndExpiresAtBefore(HoldStatus status, Instant threshold);
}
