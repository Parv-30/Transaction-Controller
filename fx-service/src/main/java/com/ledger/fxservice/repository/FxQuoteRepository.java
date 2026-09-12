package com.ledger.fxservice.repository;

import com.ledger.fxservice.domain.FxQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FxQuoteRepository extends JpaRepository<FxQuote, UUID> {
}
