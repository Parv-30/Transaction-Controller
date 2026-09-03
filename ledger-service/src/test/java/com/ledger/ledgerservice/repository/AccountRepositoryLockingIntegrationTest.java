package com.ledger.ledgerservice.repository;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountRepositoryLockingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID accountAId;
    private UUID accountBId;

    @BeforeEach
    void seedAccounts() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Account a = new Account(UUID.randomUUID(), "acct-a", "A", "USD", 10_000L, AccountStatus.ACTIVE);
            Account b = new Account(UUID.randomUUID(), "acct-b", "B", "USD", 10_000L, AccountStatus.ACTIVE);
            accountRepository.save(a);
            accountRepository.save(b);
            accountAId = a.getId();
            accountBId = b.getId();
        });
    }

    @Test
    void concurrentLockAttemptsOnSameAccountsSerializeRatherThanInterleave() throws InterruptedException {
        int threadCount = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        List<UUID> ids = List.of(accountAId, accountBId);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Determine the database's actual ascending order for these ids up front.
        // Postgres orders uuid columns byte-wise (unsigned), which does not always
        // agree with java.util.UUID#compareTo (signed long halves) -- so the
        // expected order must come from the database itself, not from Java-side
        // UUID comparison.
        UUID expectedFirstId = tx.execute(status -> accountRepository.lockAccountsForUpdate(ids).get(0).getId());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    tx.executeWithoutResult(status -> {
                        List<Account> locked = accountRepository.lockAccountsForUpdate(ids);
                        assertThat(locked).hasSize(2);
                        assertThat(locked.get(0).getId()).isEqualTo(expectedFirstId);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException ignored) {
                        }
                    });
                    successCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
    }
}
