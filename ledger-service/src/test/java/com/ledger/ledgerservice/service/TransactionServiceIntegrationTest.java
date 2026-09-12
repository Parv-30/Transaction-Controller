package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.OutboxRepository;
import com.ledger.ledgerservice.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TransactionServiceIntegrationTest {

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
    private TransactionService transactionService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private EntryRepository entryRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void seedAccounts() {
        // Delete in FK-safe child-to-parent order: entries and outbox rows reference
        // transactions/accounts from prior tests in this class (same Postgres container),
        // so accounts can't be cleared first without violating entries_account_id_fkey.
        entryRepository.deleteAll();
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        accountRepository.save(new Account(UUID.randomUUID(), "acct-a", "A", "USD", 10_000L, AccountStatus.ACTIVE, null));
        accountRepository.save(new Account(UUID.randomUUID(), "acct-b", "B", "USD", 5_000L, AccountStatus.ACTIVE, null));
    }

    @Test
    void postsATransferAndWritesEntriesBalancesAndOutboxAtomically() {
        var request = new CreateTransactionRequest("acct-a", "acct-b", 2_000L, "USD", "test transfer");

        TransactionResponse response = transactionService.postTransaction(request, "key-1");

        assertThat(response.replay()).isFalse();
        assertThat(response.status()).isEqualTo("POSTED");

        Account debit = accountRepository.findByAccountRef("acct-a").orElseThrow();
        Account credit = accountRepository.findByAccountRef("acct-b").orElseThrow();
        assertThat(debit.getBalanceMinor()).isEqualTo(8_000L);
        assertThat(credit.getBalanceMinor()).isEqualTo(7_000L);

        assertThat(entryRepository.findByTransactionId(response.transactionId())).hasSize(2);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyAndBodyReturnsReplayWithoutDoublePosting() {
        var request = new CreateTransactionRequest("acct-a", "acct-b", 1_000L, "USD", "replay test");

        TransactionResponse first = transactionService.postTransaction(request, "key-2");
        TransactionResponse second = transactionService.postTransaction(request, "key-2");

        assertThat(first.replay()).isFalse();
        assertThat(second.replay()).isTrue();
        assertThat(second.transactionId()).isEqualTo(first.transactionId());

        Account debit = accountRepository.findByAccountRef("acct-a").orElseThrow();
        assertThat(debit.getBalanceMinor()).isEqualTo(9_000L); // debited exactly once
    }

    @Test
    void sameIdempotencyKeyDifferentBodyThrowsConflict() {
        var first = new CreateTransactionRequest("acct-a", "acct-b", 1_000L, "USD", "first");
        var different = new CreateTransactionRequest("acct-a", "acct-b", 2_000L, "USD", "different");

        transactionService.postTransaction(first, "key-3");

        assertThatThrownBy(() -> transactionService.postTransaction(different, "key-3"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void insufficientFundsThrowsAndMutatesNothing() {
        var request = new CreateTransactionRequest("acct-b", "acct-a", 999_999L, "USD", "too much");

        assertThatThrownBy(() -> transactionService.postTransaction(request, "key-4"))
                .isInstanceOf(InsufficientFundsException.class);

        Account b = accountRepository.findByAccountRef("acct-b").orElseThrow();
        assertThat(b.getBalanceMinor()).isEqualTo(5_000L);
    }

    @Test
    void fxClearingAccountMayBeDebitedBelowZero() {
        accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-USD", "FX clearing USD",
                "USD", 0L, AccountStatus.ACTIVE, null));
        var request = new CreateTransactionRequest("fx-clearing-USD", "acct-a", 7_500L, "USD",
                "clearing payout");

        TransactionResponse response = transactionService.postTransaction(request, "key-clearing-negative");

        assertThat(response.status()).isEqualTo("POSTED");
        Account clearing = accountRepository.findByAccountRef("fx-clearing-USD").orElseThrow();
        assertThat(clearing.getBalanceMinor()).isEqualTo(-7_500L);
        Account credited = accountRepository.findByAccountRef("acct-a").orElseThrow();
        assertThat(credited.getBalanceMinor()).isEqualTo(17_500L);
    }

    @Test
    void ordinaryAccountStillCannotBeDebitedBelowZero() {
        // Regression guard: the fx-clearing- bypass must not weaken the check for real accounts.
        var request = new CreateTransactionRequest("acct-b", "acct-a", 5_001L, "USD", "one minor unit too much");

        assertThatThrownBy(() -> transactionService.postTransaction(request, "key-ordinary-negative"))
                .isInstanceOf(InsufficientFundsException.class);

        Account b = accountRepository.findByAccountRef("acct-b").orElseThrow();
        assertThat(b.getBalanceMinor()).isEqualTo(5_000L);
    }

    @Test
    void concurrentRequestsWithSameNewIdempotencyKeyResultInExactlyOnePost() throws InterruptedException {
        int threadCount = 8;
        var request = new CreateTransactionRequest("acct-a", "acct-b", 100L, "USD", "race test");
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        List<TransactionResponse> responses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    responses.add(transactionService.postTransaction(request, "key-race"));
                    successes.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(threadCount);
        assertThat(responses.stream().map(TransactionResponse::transactionId).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r -> !r.replay())).hasSize(1);

        Account debit = accountRepository.findByAccountRef("acct-a").orElseThrow();
        assertThat(debit.getBalanceMinor()).isEqualTo(9_900L); // debited exactly once, not 8x
    }
}
