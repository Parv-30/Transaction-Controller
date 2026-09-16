package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountRef(String accountRef);

    List<Account> findByAccountGroupId(UUID accountGroupId);

    List<Account> findByAccountRefContainingIgnoreCaseAndStatus(String accountRefPart, AccountStatus status);

    List<Account> findByAccountRefContainingIgnoreCase(String accountRefPart);

    List<Account> findByStatus(AccountStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> lockAccountsForUpdate(@Param("ids") List<UUID> ids);
}
