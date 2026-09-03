package com.ledger.ledgerservice.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the {@code @Transactional} boundary for the read-only, DB-only portion of a
 * reconciliation run (checks A and B, plus gathering candidate rows for check C). Split out
 * of {@link ReconciliationService} into its own Spring-managed bean for two reasons, mirroring
 * the split already used by {@code TransactionService}/{@code TransactionPoster}:
 *
 * <ol>
 *   <li>A same-class (self-invocation) call to an {@code @Transactional} method silently
 *       bypasses Spring's transactional proxy and runs with no transaction at all.</li>
 *   <li>More importantly here: {@link ReconciliationService#runReconciliation()} also makes a
 *       blocking HTTP call to the Transaction Processor (check C's cross-service lookup) and
 *       writes the run's final COMPLETED/FAILED status. Those must NOT share a transaction
 *       with these DB checks -- an HTTP call inside a Spring transaction would hold a DB
 *       connection open for the full duration of the call (including any timeout), and if the
 *       HTTP call throws, {@code @Transactional}'s default rollback rule would roll back
 *       everything read/written by this method too, which is irrelevant to whether the HTTP
 *       call itself succeeded. Keeping the DB checks in their own short, read-only transaction
 *       that commits (or simply ends, since it is read-only) before the HTTP call starts avoids
 *       both problems.</li>
 * </ol>
 */
@Component
class ReconciliationChecks {

    /**
     * A committed transaction is only expected to have its outbox row present after this
     * grace window, so an in-flight request whose transaction insert and outbox insert land in
     * the same DB transaction (they do, per TransactionPoster) is never flagged just for
     * being freshly created milliseconds ago.
     */
    private static final Duration OUTBOX_GRACE_WINDOW = Duration.ofMinutes(1);

    /** How old an outbox row must be before it is even considered for the stuck check. */
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(2);

    private final JdbcTemplate jdbcTemplate;

    ReconciliationChecks(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    record StaleOutboxRow(UUID outboxId, UUID aggregateId) {
    }

    record CheckResults(int transactionsChecked, List<UUID> imbalancedTransactionIds,
                         List<UUID> missingOutboxTransactionIds, List<StaleOutboxRow> staleOutboxRows) {
    }

    @Transactional(readOnly = true)
    CheckResults runDbChecks() {
        int checked = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        List<UUID> imbalanced = checkEntriesZeroSum();
        List<UUID> missingOutbox = checkOutboxMissing();
        List<StaleOutboxRow> stale = findStaleOutboxRows();
        return new CheckResults(checked, imbalanced, missingOutbox, stale);
    }

    private List<UUID> checkEntriesZeroSum() {
        return jdbcTemplate.queryForList("""
                SELECT transaction_id FROM entries
                GROUP BY transaction_id
                HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
                """, UUID.class);
    }

    private List<UUID> checkOutboxMissing() {
        return jdbcTemplate.queryForList("""
                SELECT t.id FROM transactions t
                LEFT JOIN outbox o ON o.aggregate_id = t.id AND o.aggregate_type = 'TRANSACTION'
                WHERE t.status = 'POSTED'
                  AND o.id IS NULL
                  AND t.created_at < now() - (? || ' seconds')::interval
                """, UUID.class, OUTBOX_GRACE_WINDOW.toSeconds());
    }

    private List<StaleOutboxRow> findStaleOutboxRows() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, aggregate_id FROM outbox
                WHERE created_at < now() - (? || ' seconds')::interval
                ORDER BY created_at
                """, STUCK_THRESHOLD.toSeconds());
        return rows.stream()
                .map(row -> new StaleOutboxRow((UUID) row.get("id"), (UUID) row.get("aggregate_id")))
                .toList();
    }
}
