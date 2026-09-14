package com.ledger.ledgerservice.fx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Proves {@link FxTransferRecoverySweep#run()}'s catch-and-continue behavior for the race this
 * branch's final review flagged: a row the sweep picks up as "stuck" can actually still be
 * in-flight (its real processing simply took longer than {@code stuck-threshold-ms}), so the
 * sweep's own {@code save()} can lose an optimistic-lock race to the in-flight caller's
 * {@code save()} now that {@link PendingFxTransfer} carries {@code @Version}.
 *
 * <p>{@link CrossCurrencyTransferPoster} is mocked here (unlike the other sweep tests, which use
 * the real bean) specifically so the race's outcome -- an
 * {@link ObjectOptimisticLockingFailureException} thrown from inside {@code postLeg1} -- can be
 * injected deterministically, without depending on real thread-timing luck to land two
 * concurrent transactions on the exact same row. This isolates the one thing this test needs to
 * prove: given that exception, does the sweep log it and move on to the next row, rather than
 * letting it propagate and abort the whole batch (which {@link FxTransferRecoverySweepIntegrationTest}
 * already proves the sweep must never do for any other kind of per-row failure).
 */
@Testcontainers
@SpringBootTest(properties = "fx.transfer-sweep.stuck-threshold-ms=1000")
@ActiveProfiles("test")
class FxTransferRecoverySweepOptimisticLockRaceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    FxTransferRecoverySweep sweep;

    @Autowired
    PendingFxTransferRepository pendingFxTransferRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockBean
    CrossCurrencyTransferPoster poster;

    @BeforeEach
    void cleanUp() {
        pendingFxTransferRepository.deleteAll();
    }

    @Test
    void sweepLogsAndContinuesWhenARowLosesAnOptimisticLockRaceInsteadOfCrashingTheWholeRun() {
        // Row A: simulates the row that a concurrent in-flight request wins the race on -- the
        // sweep's attempt to recover it collides with the winner's own save() and Hibernate
        // throws ObjectOptimisticLockingFailureException.
        PendingFxTransfer raced = new PendingFxTransfer(UUID.randomUUID(), "sweep-race-1",
                UUID.randomUUID(), "race-source", "race-dest", 10_000L,
                new BigDecimal("0.92000000"), 9_200L, Instant.now().plusSeconds(60));
        pendingFxTransferRepository.save(raced);
        backdate(raced.getId());

        // Row B: an ordinary stuck row with no contention, seeded to prove the raced row does not
        // block the rest of the batch from being processed.
        PendingFxTransfer ordinary = new PendingFxTransfer(UUID.randomUUID(), "sweep-race-2",
                UUID.randomUUID(), "ordinary-source", "ordinary-dest", 5_000L,
                new BigDecimal("0.92000000"), 4_600L, Instant.now().plusSeconds(60));
        pendingFxTransferRepository.save(ordinary);
        backdate(ordinary.getId());

        doThrow(new ObjectOptimisticLockingFailureException(PendingFxTransfer.class, raced.getId()))
                .when(poster).postLeg1(raced.getId());
        doNothing().when(poster).postLeg1(ordinary.getId());

        // Must not throw: the raced row's exception must be swallowed inside the sweep's loop.
        sweep.run();

        verify(poster).postLeg1(raced.getId());
        // The ordinary row must still have been processed in the same run -- proof the raced
        // row's exception did not abort the batch.
        verify(poster).postLeg1(ordinary.getId());
    }

    private void backdate(UUID id) {
        jdbcTemplate.update("UPDATE pending_fx_transfers SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), id);
    }
}
