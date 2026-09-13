package com.ledger.ledgerservice.fx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Crash-recovery sweep for {@link PendingFxTransfer} rows left stuck mid-saga -- e.g. the
 * process died between posting leg 1 and leg 2, or between marking a row {@code COMPENSATING}
 * and finishing the reversal. A row is only considered stuck once it has sat past
 * {@code stuck-threshold-ms} without an update, so an in-flight saga running on another thread
 * (or another instance) is never raced.
 *
 * <p>Recovers three statuses:
 * <ul>
 *     <li>{@code PENDING} -- leg 1 was never posted; post it.</li>
 *     <li>{@code LEG1_POSTED} -- leg 1 posted but leg 2 never did; post leg 2, or compensate if
 *         leg 2 fails again, exactly mirroring {@link CrossCurrencyTransferService#transfer}'s own
 *         try/catch.</li>
 *     <li>{@code COMPENSATING} -- compensation was started (leg 1's debit reversed via clearing)
 *         but the row never reached {@code COMPENSATED}, e.g. the process crashed inside
 *         {@link CrossCurrencyTransferPoster#compensate} itself. Calling {@code compensate} again
 *         is safe: it is idempotent/re-entrant by design (it skips re-marking a row already
 *         {@code COMPENSATING} and posts the reversal under a deterministic
 *         {@code fx-compensate-<id>} key, so a retry replays the already-posted transaction via
 *         {@code TransactionService}'s own idempotency mechanism instead of double-reversing).</li>
 * </ul>
 *
 * <p>Calls {@link CrossCurrencyTransferPoster}'s methods directly, the same non-transactional
 * steps the saga itself calls -- see that class's Javadoc for why they are deliberately not
 * {@code @Transactional}. This sweep must not wrap them in a transaction either, for the same
 * reason: doing so would reintroduce the shared-transaction race that Task 9 removed
 * {@code @Transactional} to fix.
 *
 * <p>Non-goal, by design: a row that fails compensation repeatedly stays {@code COMPENSATING}
 * forever with no dead-letter path. One row's recovery failing must not block the sweep from
 * processing the rest of the batch, so failures are logged and the row is retried on the next run.
 *
 * <p>Because {@link PendingFxTransfer} now carries an {@code @Version} field, a row this sweep
 * picks up as stuck can still turn out to be genuinely in-flight (e.g. its actual processing took
 * longer than {@code stuck-threshold-ms} under load). If the sweep's {@code save()} loses that
 * race to the in-flight caller's own {@code save()}, Hibernate throws
 * {@code ObjectOptimisticLockingFailureException}. That is treated as an expected, benign outcome
 * -- not a real failure -- and logged at INFO rather than WARN before moving on to the next row.
 */
@Component
public class FxTransferRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(FxTransferRecoverySweep.class);

    private final PendingFxTransferRepository pendingFxTransferRepository;
    private final CrossCurrencyTransferPoster poster;
    private final long stuckThresholdMs;

    public FxTransferRecoverySweep(PendingFxTransferRepository pendingFxTransferRepository,
                                    CrossCurrencyTransferPoster poster,
                                    @Value("${fx.transfer-sweep.stuck-threshold-ms:30000}") long stuckThresholdMs) {
        this.pendingFxTransferRepository = pendingFxTransferRepository;
        this.poster = poster;
        this.stuckThresholdMs = stuckThresholdMs;
    }

    @Scheduled(fixedDelayString = "${fx.transfer-sweep.interval-ms:15000}")
    public void run() {
        Instant cutoff = Instant.now().minusMillis(stuckThresholdMs);
        List<PendingFxTransfer> stuck = pendingFxTransferRepository.findByStatusInAndUpdatedAtBefore(
                List.of(PendingFxTransferStatus.PENDING, PendingFxTransferStatus.LEG1_POSTED,
                        PendingFxTransferStatus.COMPENSATING),
                cutoff);

        for (PendingFxTransfer transfer : stuck) {
            try {
                switch (transfer.getStatus()) {
                    case PENDING -> poster.postLeg1(transfer.getId());
                    case LEG1_POSTED -> {
                        try {
                            poster.postLeg2(transfer.getId());
                        } catch (ObjectOptimisticLockingFailureException raceLost) {
                            // Do not treat a lost optimistic-lock race as a genuine leg2 failure --
                            // a concurrent caller already advanced this row, so compensating here
                            // would be wrong. Rethrow to the outer catch, which logs it as the
                            // expected benign outcome it is and moves on.
                            throw raceLost;
                        } catch (Exception leg2Failure) {
                            log.warn("Sweep-driven leg2 retry failed for pending_fx_transfer {}; compensating",
                                    transfer.getId(), leg2Failure);
                            poster.compensate(transfer.getId());
                        }
                    }
                    case COMPENSATING -> poster.compensate(transfer.getId());
                    default -> { /* not stuck-relevant, skip */ }
                }
            } catch (ObjectOptimisticLockingFailureException raceLost) {
                // Expected, benign outcome now that PendingFxTransfer carries @Version: an
                // in-flight HTTP-triggered saga (or another sweep pass) already advanced this
                // exact row between our SELECT and our save(). Whoever won the race already
                // moved the row forward correctly, so this is not a failure needing compensation
                // or a retry -- just skip it and let the next scheduled run re-evaluate its
                // (by-then-updated) status if it still looks stuck.
                log.info("Skipped pending_fx_transfer {} in sweep: lost an optimistic-lock race " +
                                "to a concurrent update (already handled by another caller).",
                        transfer.getId());
            } catch (Exception e) {
                // One row's recovery failing must not block the sweep from processing the
                // rest -- log and move on. This row stays stuck and is retried on the next
                // scheduled run. See the spec's explicit non-goal: a row that fails
                // compensation repeatedly stays COMPENSATING forever with no dead-letter
                // path, by design.
                log.warn("Failed to recover pending_fx_transfer {} from status {}: {}",
                        transfer.getId(), transfer.getStatus(), e.getMessage());
            }
        }
    }
}
