package com.ledger.ledgerservice.fx;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that {@link PendingFxTransfer}'s {@code @Version} field (added to close the finding from
 * the final whole-branch review: the entity's own field updates had no concurrency guard, so a
 * crash-recovery-sweep row could silently race an in-flight saga's update to the same row) is a
 * real, working optimistic lock -- not just a compiling annotation.
 *
 * <p>The proof loads the SAME row via two independent {@link PendingFxTransferRepository} calls,
 * simulating two "threads" that each hold their own in-memory view of the entity (exactly what
 * happens when {@link FxTransferRecoverySweep} reads a row at the same time an in-flight
 * {@link CrossCurrencyTransferService#transfer} call is processing it). The first copy is mutated
 * and saved successfully. The second copy is then stale -- its {@code @Version} no longer matches
 * the row in the database -- so mutating and saving it must fail with
 * {@link ObjectOptimisticLockingFailureException} rather than silently overwriting the first
 * writer's update.
 *
 * <p>Each save happens in its own explicit transaction via {@link TransactionTemplate}: Spring
 * Data's version check runs at flush/commit time, so both the mutation and the save must be
 * inside a transaction boundary that actually commits (or, for the second one, attempts to) for
 * the version conflict to surface the way it does in production.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PendingFxTransferOptimisticLockingIntegrationTest {

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
    PendingFxTransferRepository pendingFxTransferRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void secondSaveOfAStaleCopyFailsWithOptimisticLockException() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            PendingFxTransfer transfer = new PendingFxTransfer(id, "opt-lock-test-1", UUID.randomUUID(),
                    "opt-lock-source", "opt-lock-dest", 10_000L, new BigDecimal("0.92000000"), 9_200L);
            pendingFxTransferRepository.save(transfer);
        });

        // Two independent reads of the same row -- each stands in for a separate "thread"
        // (the sweep and an in-flight HTTP request) with its own view of the entity.
        PendingFxTransfer firstView = tx.execute(status -> pendingFxTransferRepository.findById(id).orElseThrow());
        PendingFxTransfer secondView = tx.execute(status -> pendingFxTransferRepository.findById(id).orElseThrow());

        assertThat(firstView.getVersion()).isEqualTo(secondView.getVersion());

        // First writer commits successfully, advancing the row's version. save() returns the
        // merged/managed instance carrying the DB-incremented version -- the original firstView
        // reference stays detached at its pre-save version, so it is NOT used for the
        // post-save version comparison below.
        tx.executeWithoutResult(status -> {
            firstView.markLeg1Posted(UUID.randomUUID());
            pendingFxTransferRepository.save(firstView);
        });

        PendingFxTransfer reloaded = pendingFxTransferRepository.findById(id).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PendingFxTransferStatus.LEG1_POSTED);
        assertThat(reloaded.getVersion()).isEqualTo(firstView.getVersion() + 1);

        // Second writer's copy is now stale: its @Version no longer matches the committed row.
        // Hibernate must detect this at commit time and refuse to silently overwrite the first
        // writer's update.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            secondView.markLeg1Posted(UUID.randomUUID());
            pendingFxTransferRepository.save(secondView);
            pendingFxTransferRepository.flush();
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The row must reflect only the first writer's update -- proving the second writer's
        // attempted overwrite never took effect.
        PendingFxTransfer finalState = pendingFxTransferRepository.findById(id).orElseThrow();
        assertThat(finalState.getLeg1TransactionId()).isEqualTo(reloaded.getLeg1TransactionId());
    }
}
