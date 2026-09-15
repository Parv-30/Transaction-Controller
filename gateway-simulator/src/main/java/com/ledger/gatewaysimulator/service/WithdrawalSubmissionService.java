package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WithdrawalSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalSubmissionService.class);

    private final ExternalWithdrawalRepository withdrawalRepository;

    public WithdrawalSubmissionService(ExternalWithdrawalRepository withdrawalRepository) {
        this.withdrawalRepository = withdrawalRepository;
    }

    public void submit(UUID sourceTransactionId, String accountRef, long amountMinor, String currency) {
        ExternalWithdrawal withdrawal = new ExternalWithdrawal(
                UUID.randomUUID(), sourceTransactionId, accountRef, amountMinor, currency);
        withdrawalRepository.save(withdrawal);
        submitToSimulatedRail(withdrawal);
    }

    /**
     * Stands in for a real payment rail's submission call. No real external call, no
     * synchronous outcome — resolution always arrives later via the confirm endpoint
     * (Task 9) or the timeout sweep (Task 11), never from this method, so the async
     * resolution path is always exercised uniformly.
     */
    private void submitToSimulatedRail(ExternalWithdrawal withdrawal) {
        log.info("Submitted withdrawal {} for account {} ({} {}) to simulated rail",
                withdrawal.getId(), withdrawal.getAccountRef(), withdrawal.getAmountMinor(), withdrawal.getCurrency());
    }
}
