package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT DISTINCT t FROM Transaction t JOIN Entry e ON e.transactionId = t.id " +
           "JOIN Account a ON a.id = e.accountId WHERE a.accountRef = :accountRef")
    List<Transaction> findByAccountRef(@Param("accountRef") String accountRef);
}
