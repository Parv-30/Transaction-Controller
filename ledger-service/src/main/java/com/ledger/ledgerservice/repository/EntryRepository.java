package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Entry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<Entry, UUID> {
    List<Entry> findByTransactionId(UUID transactionId);
}
