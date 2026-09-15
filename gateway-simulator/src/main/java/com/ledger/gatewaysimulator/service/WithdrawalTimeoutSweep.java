package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.domain.WithdrawalStatus;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sweeps SUBMITTED withdrawals that have sat unresolved past the configured timeout and marks
 * them TIMED_OUT, reversing each one via {@link WithdrawalResolutionService#reverse}. Follows the
 * same structure as Holds Service's {@code HoldExpirySweep}: a per-row try/catch so one failing
 * row does not block the rest of the sweep (a failed row is picked up again next run), and a
 * status re-check inside the transactional method to guard against a race with a concurrent
 * confirm call between the initial query and the update.
 */
@Component
public class WithdrawalTimeoutSweep {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalTimeoutSweep.class);

    private final ExternalWithdrawalRepository withdrawalRepository;
    private final WithdrawalResolutionService resolutionService;
    private final MeterRegistry meterRegistry;
    private final long timeoutSeconds;

    public WithdrawalTimeoutSweep(ExternalWithdrawalRepository withdrawalRepository,
                                   WithdrawalResolutionService resolutionService,
                                   MeterRegistry meterRegistry,
                                   @Value("${gateway-sim.withdrawal-timeout-seconds:60}") long timeoutSeconds) {
        this.withdrawalRepository = withdrawalRepository;
        this.resolutionService = resolutionService;
        this.meterRegistry = meterRegistry;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${gateway-sim.withdrawal-sweep.interval-ms:15000}")
    public void run() {
        Instant cutoff = Instant.now().minusSeconds(timeoutSeconds);
        List<ExternalWithdrawal> stuck = withdrawalRepository
                .findByStatusAndSubmittedAtBefore(WithdrawalStatus.SUBMITTED, cutoff);
        for (ExternalWithdrawal withdrawal : stuck) {
            try {
                markTimedOutAndReverse(withdrawal.getId());
                meterRegistry.counter("gateway_sim.withdrawal.timeout").increment();
            } catch (Exception e) {
                log.error("Failed to time out withdrawal {}: {}", withdrawal.getId(), e.getMessage(), e);
                // continue sweeping the rest; a failed row is picked up again next run
            }
        }
    }

    @Transactional
    void markTimedOutAndReverse(UUID withdrawalId) {
        ExternalWithdrawal withdrawal = withdrawalRepository.findById(withdrawalId).orElseThrow();
        if (withdrawal.getStatus() != WithdrawalStatus.SUBMITTED) {
            return; // already resolved by a racing confirm call between the query and this transaction
        }
        withdrawal.markTimedOut();
        withdrawalRepository.save(withdrawal);
        resolutionService.reverse(withdrawal);
    }
}
