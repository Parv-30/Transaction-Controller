package com.ledger.ledgerservice.reconciliation;

import com.ledger.ledgerservice.api.dto.ReconciliationRunResponse;
import com.ledger.ledgerservice.domain.ReconciliationFinding;
import com.ledger.ledgerservice.domain.ReconciliationRun;
import com.ledger.ledgerservice.repository.ReconciliationFindingRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Processor's real batch-status endpoint requires the whole Transaction Processor service
 * running, so this test uses a lightweight embedded {@link HttpServer}-based stub instead of
 * standing up the second service -- {@code stubHandler} is swapped per-test (via an
 * AtomicReference so the running HttpServer always dispatches to the latest one) to cover the
 * clean/imbalance/stuck/processor-outage scenarios below.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubProcessor;
    static int stubPort;
    static final AtomicReference<Function<String, String>> stubHandler =
            new AtomicReference<>(requestBody -> "[]");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        startStubOnFixedPort();
        // Spring resolves processor.base-url once at context startup, so the stub's port must
        // stay fixed for the lifetime of the test class -- the "processor unreachable" test
        // stops this server and restarts a new one bound to the SAME port afterwards, rather
        // than picking a new ephemeral port, so the already-configured base URL still points
        // at a live server for any test that runs after it.
        registry.add("processor.base-url", () -> "http://localhost:" + stubPort);
    }

    @AfterAll
    static void stopStub() {
        if (stubProcessor != null) {
            stubProcessor.stop(0);
        }
    }

    private static void startStubOnFixedPort() throws java.io.IOException {
        stubProcessor = HttpServer.create(new InetSocketAddress(stubPort), 0);
        stubProcessor.createContext("/processed-events/batch-status", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = stubHandler.get().apply(requestBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubProcessor.start();
        stubPort = stubProcessor.getAddress().getPort();
    }

    @Autowired
    private ReconciliationService reconciliationService;
    @Autowired
    private ReconciliationFindingRepository findingRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.execute("DELETE FROM entries");
        jdbcTemplate.execute("DELETE FROM outbox");
        jdbcTemplate.execute("DELETE FROM reconciliation_findings");
        jdbcTemplate.execute("DELETE FROM reconciliation_runs");
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM accounts");
        stubHandler.set(requestBody -> "[]");
    }

    @Test
    void cleanLedgerProducesZeroFindings() {
        ReconciliationRun run = reconciliationService.runReconciliation();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        assertThat(run.getEntriesImbalanceCount()).isZero();
        assertThat(run.getOutboxMissingCount()).isZero();
        assertThat(run.getOutboxStuckCount()).isZero();
    }

    @Test
    void aCleanReconciliationRunSetsAllMismatchGaugesToZero() {
        reconciliationService.runReconciliation();

        assertThat(meterRegistry.find("ledger.reconciliation.entries_imbalance").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.find("ledger.reconciliation.outbox_missing").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.find("ledger.reconciliation.outbox_stuck").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void unbalancedEntriesInsertedDirectlyBypassingTheConstraintTriggerAreDetected() {
        // Bypass the app layer AND the DB constraint trigger (Task 2) that would otherwise
        // prevent this state from ever being persisted, to prove the reconciliation query
        // itself is correct independent of the trigger.
        jdbcTemplate.execute("ALTER TABLE entries DISABLE TRIGGER trg_entries_zero_sum");
        try {
            jdbcTemplate.execute("""
                    INSERT INTO accounts (id, account_ref, balance_minor)
                    VALUES ('33333333-3333-3333-3333-333333333333', 'recon-a', 10000)
                    """);
            jdbcTemplate.execute("""
                    INSERT INTO transactions (id, idempotency_key, request_payload_hash)
                    VALUES ('44444444-4444-4444-4444-444444444444', 'recon-key-1', repeat('a', 64))
                    """);
            jdbcTemplate.execute("""
                    INSERT INTO entries (transaction_id, account_id, direction, amount_minor, currency)
                    VALUES ('44444444-4444-4444-4444-444444444444',
                            '33333333-3333-3333-3333-333333333333', 'DEBIT', 500, 'USD')
                    """);

            ReconciliationRun run = reconciliationService.runReconciliation();

            assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
            assertThat(run.getEntriesImbalanceCount()).isEqualTo(1);

            List<ReconciliationFinding> findings = findingRepository.findByRunId(run.getId());
            assertThat(findings).anyMatch(f -> f.getFindingType() == ReconciliationFinding.FindingType.ENTRIES_NOT_ZERO);
        } finally {
            jdbcTemplate.execute("ALTER TABLE entries ENABLE TRIGGER trg_entries_zero_sum");
        }
    }

    @Test
    void transactionWithoutAnOutboxRowPastTheGraceWindowIsDetected() {
        UUID accountId = UUID.randomUUID();
        UUID txnId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO accounts (id, account_ref, balance_minor)
                VALUES (?, ?, 10000)
                """, accountId, "recon-missing-outbox");
        // created_at backdated past the 1-minute grace window used by the OUTBOX_MISSING check.
        jdbcTemplate.update("""
                INSERT INTO transactions (id, idempotency_key, request_payload_hash, status, created_at)
                VALUES (?, ?, repeat('b', 64), 'POSTED', now() - interval '5 minutes')
                """, txnId, "recon-key-missing-outbox");

        ReconciliationRun run = reconciliationService.runReconciliation();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        assertThat(run.getOutboxMissingCount()).isEqualTo(1);

        List<ReconciliationFinding> findings = findingRepository.findByRunId(run.getId());
        assertThat(findings).anyMatch(f -> f.getFindingType() == ReconciliationFinding.FindingType.OUTBOX_MISSING
                && txnId.equals(f.getTransactionId()));
    }

    @Test
    void staleOutboxRowUnknownToProcessorIsReportedAsStuck() {
        UUID txnId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transactions (id, idempotency_key, request_payload_hash, created_at)
                VALUES (?, ?, repeat('c', 64), now() - interval '10 minutes')
                """, txnId, "recon-key-stuck");
        // created_at backdated past the 2-minute STUCK_THRESHOLD.
        jdbcTemplate.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'TRANSACTION', ?, 'TRANSACTION_POSTED', '{}'::jsonb, now() - interval '10 minutes')
                """, outboxId, txnId);

        // Stub reports "no processed_events row at all" for this outbox id -- i.e. the
        // Processor's CDC pipeline never even captured it.
        stubHandler.set(requestBody -> "[]");

        ReconciliationRun run = reconciliationService.runReconciliation();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        assertThat(run.getOutboxStuckCount()).isEqualTo(1);

        List<ReconciliationFinding> findings = findingRepository.findByRunId(run.getId());
        assertThat(findings).anyMatch(f -> f.getFindingType() == ReconciliationFinding.FindingType.OUTBOX_STUCK_UNPUBLISHED
                && outboxId.equals(f.getOutboxId()));
    }

    @Test
    void staleOutboxRowThatTheProcessorReportsAsPublishedIsNotFlaggedStuck() {
        UUID txnId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transactions (id, idempotency_key, request_payload_hash, created_at)
                VALUES (?, ?, repeat('d', 64), now() - interval '10 minutes')
                """, txnId, "recon-key-healthy-stuck");
        jdbcTemplate.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'TRANSACTION', ?, 'TRANSACTION_POSTED', '{}'::jsonb, now() - interval '10 minutes')
                """, outboxId, txnId);

        stubHandler.set(requestBody -> String.format("""
                [{"outboxEventId":"%s","status":"PUBLISHED","publishedAt":"%s","consumedAt":null,"deliveryCount":1}]
                """, outboxId, Instant.now()));

        ReconciliationRun run = reconciliationService.runReconciliation();

        assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.COMPLETED);
        assertThat(run.getOutboxStuckCount()).isZero();
    }

    @Test
    void listRunsReturnsRunsNewestFirst() {
        reconciliationService.runReconciliation();
        reconciliationService.runReconciliation();

        List<ReconciliationRunResponse> runs = reconciliationService.listRuns();

        assertThat(runs.size()).isGreaterThanOrEqualTo(2);
        for (int i = 0; i < runs.size() - 1; i++) {
            assertThat(runs.get(i).startedAt()).isAfterOrEqualTo(runs.get(i + 1).startedAt());
        }
    }

    @Test
    void processorUnreachableFailsTheRunWithReasonRecordedInsteadOfReportingCleanResult() {
        UUID txnId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transactions (id, idempotency_key, request_payload_hash, created_at)
                VALUES (?, ?, repeat('e', 64), now() - interval '10 minutes')
                """, txnId, "recon-key-processor-down");
        jdbcTemplate.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'TRANSACTION', ?, 'TRANSACTION_POSTED', '{}'::jsonb, now() - interval '10 minutes')
                """, outboxId, txnId);

        // Stop the stub server entirely so the HTTP call to the Processor fails with a
        // connection error, simulating the Processor being down.
        stubProcessor.stop(0);
        try {
            ReconciliationRun run = reconciliationService.runReconciliation();

            assertThat(run.getStatus()).isEqualTo(ReconciliationRun.Status.FAILED);
            // Must NOT be silently reported as zero findings -- the run is FAILED, and no
            // OUTBOX_STUCK_UNPUBLISHED findings should have been fabricated from an empty
            // "processor said nothing is stuck" default.
            List<ReconciliationFinding> findings = findingRepository.findByRunId(run.getId());
            assertThat(findings).noneMatch(
                    f -> f.getFindingType() == ReconciliationFinding.FindingType.OUTBOX_STUCK_UNPUBLISHED);
        } finally {
            // Rebind the stub on the SAME fixed port (processor.base-url was resolved once at
            // Spring context startup and won't change), so any test that runs after this one
            // in the same JVM/context still reaches a live stub.
            try {
                startStubOnFixedPort();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
