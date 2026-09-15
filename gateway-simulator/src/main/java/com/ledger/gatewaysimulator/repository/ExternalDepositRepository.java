package com.ledger.gatewaysimulator.repository;

import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExternalDepositRepository extends JpaRepository<ExternalDeposit, UUID> {
    Optional<ExternalDeposit> findByExternalReference(String externalReference);
}
