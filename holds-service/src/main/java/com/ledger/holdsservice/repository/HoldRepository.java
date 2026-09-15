package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
    Optional<Hold> findByIdempotencyKey(String idempotencyKey);
    List<Hold> findByStatusAndExpiresAtBefore(HoldStatus status, Instant threshold);

    /**
     * Backs {@code GET /holds?accountRef=&status=}. Written as a single explicit query rather
     * than a derived {@code findByAccountRefOrDestinationAccountRefAndStatus} method name: Spring
     * Data parses {@code findByXOrYAndZ} left-to-right as {@code (X) OR (Y AND Z)}, not the
     * intended {@code (X OR Y) AND Z}, so a derived method here would silently return holds on
     * the destination side regardless of status whenever a status filter is supplied. The explicit
     * parenthesization below avoids that ambiguity. Both filters are optional (null = don't filter).
     */
    @Query("SELECT h FROM Hold h WHERE (:accountRef IS NULL OR h.accountRef = :accountRef OR h.destinationAccountRef = :accountRef) "
            + "AND (:status IS NULL OR h.status = :status)")
    List<Hold> search(@Param("accountRef") String accountRef, @Param("status") HoldStatus status);
}
