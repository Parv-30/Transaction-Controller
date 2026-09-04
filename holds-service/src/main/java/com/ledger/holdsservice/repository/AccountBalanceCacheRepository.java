package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountBalanceCacheRepository extends JpaRepository<AccountBalanceCache, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AccountBalanceCache c WHERE c.accountRef = :accountRef")
    Optional<AccountBalanceCache> lockByAccountRef(@Param("accountRef") String accountRef);
}
