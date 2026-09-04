package com.ledger.holdsservice.repository;

import com.ledger.holdsservice.domain.AccountBalanceCache;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountBalanceCacheLockingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedCache() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status ->
                accountBalanceCacheRepository.save(new AccountBalanceCache("acct-lock-test", 10_000L, 0L)));
    }

    @Test
    void concurrentHoldAttemptsOnSameAccountSerializeAndNeverOversubscribe() throws InterruptedException {
        int threadCount = 10;
        long holdAmount = 1_500L; // 10 * 1500 = 15,000 > 10,000 available -> some must fail
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    boolean won = Boolean.TRUE.equals(tx.execute(status -> {
                        AccountBalanceCache cache = accountBalanceCacheRepository
                                .lockByAccountRef("acct-lock-test").orElseThrow();
                        if (cache.availableBalanceMinor() < holdAmount) {
                            return false;
                        }
                        cache.hold(holdAmount);
                        accountBalanceCacheRepository.save(cache);
                        return true;
                    }));
                    if (won) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Exactly 6 of 10 can succeed: 6 * 1500 = 9000 <= 10000, 7 * 1500 = 10500 > 10000
        assertThat(successes.get()).isEqualTo(6);

        AccountBalanceCache finalCache = accountBalanceCacheRepository.findById("acct-lock-test").orElseThrow();
        assertThat(finalCache.getHeldBalanceMinor()).isEqualTo(6 * holdAmount);
    }
}
