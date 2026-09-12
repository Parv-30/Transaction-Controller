package com.ledger.fxservice.repository;

import com.ledger.fxservice.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {
    Optional<FxRate> findTopByBaseCurrencyAndQuoteCurrencyOrderByFetchedAtDesc(
            String baseCurrency, String quoteCurrency);
}
