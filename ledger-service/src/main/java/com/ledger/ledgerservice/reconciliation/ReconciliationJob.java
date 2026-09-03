package com.ledger.ledgerservice.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private final ReconciliationService reconciliationService;

    public ReconciliationJob(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelayString = "${reconciliation.interval-ms:300000}")
    public void run() {
        reconciliationService.runReconciliation();
    }
}
