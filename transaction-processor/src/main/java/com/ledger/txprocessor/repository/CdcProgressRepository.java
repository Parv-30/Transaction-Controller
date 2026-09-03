package com.ledger.txprocessor.repository;

import com.ledger.txprocessor.domain.CdcProgress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CdcProgressRepository extends JpaRepository<CdcProgress, Short> {
}
