package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.api.error.WithdrawalNotYetSubmittedException;
import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.ledger.LedgerTransactionClient;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves a pending outbound withdrawal to its terminal outcome: {@code CONFIRMED} (no further
 * action) or {@code FAILED} (compensated by posting a reversing transaction that credits the
 * original account back and debits the external-clearing account -- see {@link #reverse}).
 */
@Service
public class WithdrawalResolutionService {

    private static final String EXTERNAL_CLEARING_PREFIX = "external-clearing-";

    private final ExternalWithdrawalRepository withdrawalRepository;
    private final LedgerTransactionClient ledgerTransactionClient;
    private final MeterRegistry meterRegistry;

    public WithdrawalResolutionService(ExternalWithdrawalRepository withdrawalRepository,
                                        LedgerTransactionClient ledgerTransactionClient,
                                        MeterRegistry meterRegistry) {
        this.withdrawalRepository = withdrawalRepository;
        this.ledgerTransactionClient = ledgerTransactionClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Resolves a SUBMITTED withdrawal as CONFIRMED or FAILED.
     *
     * <p>A withdrawal row is created asynchronously by {@code WithdrawalSubmissionService} after
     * the RabbitMQ event that triggers it is consumed (Task 8), so a confirm call can legitimately
     * race ahead of that consumption and find no row at all yet. That is indistinguishable, from
     * this method's caller's perspective, from a row that exists but hasn't reached SUBMITTED --
     * both are retryable, not permanent failures -- so {@code findById} returning empty throws
     * {@link WithdrawalNotYetSubmittedException} rather than a "not found" error. Nothing else
     * writes to this table except this method and {@link WithdrawalTimeoutSweep}, and every row is
     * created in SUBMITTED status, so an existing row is always SUBMITTED at this point in the flow
     * -- a separate status re-check below would be redundant.
     */
    @Transactional
    public ExternalWithdrawal confirm(UUID withdrawalId, String outcome) {
        ExternalWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> new WithdrawalNotYetSubmittedException(withdrawalId));

        if ("CONFIRMED".equals(outcome)) {
            withdrawal.markConfirmed();
            withdrawalRepository.save(withdrawal);
            return withdrawal;
        }

        withdrawal.markFailed();
        withdrawalRepository.save(withdrawal);
        reverse(withdrawal);
        return withdrawal;
    }

    /**
     * Posts a compensating reversal for a failed or timed-out withdrawal: debits the
     * external-clearing account for the withdrawal's currency and credits the original account
     * back. The idempotency key is derived deterministically from the withdrawal's source
     * transaction id, so a retried resolution (e.g. a redelivered confirm call, or the timeout
     * sweep re-processing a row after a previous partial failure) never posts a second reversal.
     *
     * <p>Unlike a deposit's own downstream ledger call -- where a REJECTED status is an acceptable
     * terminal outcome -- a failed reversal here is a serious condition that must not be silently
     * swallowed: if {@link LedgerTransactionClient#postTransaction} throws, that exception
     * propagates to the caller (the confirm request fails with a 5xx, or the sweep's per-row
     * try/catch logs and retries the row on the next sweep) rather than leaving the withdrawal
     * marked FAILED/TIMED_OUT with no reversal ever posted.
     */
    void reverse(ExternalWithdrawal withdrawal) {
        UUID reversalTransactionId = ledgerTransactionClient.postTransaction(
                EXTERNAL_CLEARING_PREFIX + withdrawal.getCurrency(), withdrawal.getAccountRef(),
                withdrawal.getAmountMinor(), withdrawal.getCurrency(), "external withdrawal reversal",
                "external-withdrawal-reversal-" + withdrawal.getSourceTransactionId());
        withdrawal.markReversed(reversalTransactionId);
        withdrawalRepository.save(withdrawal);
        meterRegistry.counter("gateway_sim.withdrawal.reversal").increment();
    }
}
