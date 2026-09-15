package com.ledger.gatewaysimulator.repository;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalWithdrawalRepository extends JpaRepository<ExternalWithdrawal, UUID> {
    Optional<ExternalWithdrawal> findBySourceTransactionId(UUID sourceTransactionId);
    List<ExternalWithdrawal> findByStatusAndSubmittedAtBefore(WithdrawalStatus status, Instant cutoff);
}
