package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.domain.Transaction;
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

    static com.sun.net.httpserver.HttpServer stubHoldsService;
    static final java.util.concurrent.atomic.AtomicLong stubbedHeldBalance = new java.util.concurrent.atomic.AtomicLong(0L);

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubHoldsService = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        stubHoldsService.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String accountRef = path.substring("/accounts/".length(), path.length() - "/held-balance".length());
            String body = "{\"accountRef\":\"" + accountRef + "\",\"heldBalanceMinor\":" + stubbedHeldBalance.get() + "}";
            byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubHoldsService.start();
        registry.add("holds.base-url", () -> "http://localhost:" + stubHoldsService.getAddress().getPort());
        registry.add("holds.held-balance-timeout-ms", () -> "2000");
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
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

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

    @Test
    void aHeldAmountReducesAvailableFundsEvenThoughPostedBalanceWouldCoverIt() {
        // acct-a has 10,000 posted (seeded in @BeforeEach) with 9,500 held, leaving only 500
        // truly available.
        stubbedHeldBalance.set(9_500L);

        assertThatThrownBy(() -> transactionService.postTransaction(
                new CreateTransactionRequest("acct-a", "acct-b", 1_000L, "USD", "should fail: held funds"),
                "held-funds-test-1"))
                .isInstanceOf(InsufficientFundsException.class);

        stubbedHeldBalance.set(0L);
    }

    @Test
    void aTransferWithinTheHeldAdjustedAvailableBalanceSucceeds() {
        stubbedHeldBalance.set(9_500L);

        var response = transactionService.postTransaction(
                new CreateTransactionRequest("acct-a", "acct-b", 500L, "USD", "should succeed: within available"),
                "held-funds-test-2");

        assertThat(response.status()).isEqualTo("POSTED");
        stubbedHeldBalance.set(0L);
    }

    @Test
    void fxClearingAccountsSkipTheHeldBalanceCheckEntirely() {
        // Even if the stub were to report a huge held balance, fx-clearing- accounts must never
        // call out to Holds Service at all -- set an impossibly large held amount to prove the
        // check is skipped, not merely satisfied.
        stubbedHeldBalance.set(Long.MAX_VALUE / 2);

        accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-USD", "FX clearing USD",
                "USD", 0L, AccountStatus.ACTIVE, null));

        var response = transactionService.postTransaction(
                new CreateTransactionRequest("fx-clearing-USD", "acct-a", 500L, "USD", "clearing account bypass"),
                "held-funds-test-3");

        assertThat(response.status()).isEqualTo("POSTED");
        stubbedHeldBalance.set(0L);
    }

    @Test
    void postingATransactionRecordsATransactionLatencyTimer() {
        transactionService.postTransaction(
                new CreateTransactionRequest("acct-a", "acct-b", 100L, "USD", "metrics test"),
                "metrics-latency-test-1");

        var timer = meterRegistry.find("ledger.transaction.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void aFailedTransactionIncrementsTheFailedTransactionsCounterTaggedByExceptionType() {
        assertThatThrownBy(() -> transactionService.postTransaction(
                new CreateTransactionRequest("acct-a", "nonexistent-account-xyz", 100L, "USD", "should fail"),
                "metrics-failure-test-1"))
                .isInstanceOf(AccountNotFoundException.class);

        var counter = meterRegistry.find("ledger.transaction.failed")
                .tag("exception", "AccountNotFoundException")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void retryingTheSameIdempotencyKeyIncrementsTheReplayCounter() {
        var request = new CreateTransactionRequest("acct-a", "acct-b", 100L, "USD", "replay metrics test");
        transactionService.postTransaction(request, "metrics-replay-test-1");
        double before = meterRegistry.find("ledger.idempotency.replay").counter() == null
                ? 0.0 : meterRegistry.find("ledger.idempotency.replay").counter().count();

        var replayResponse = transactionService.postTransaction(request, "metrics-replay-test-1");

        assertThat(replayResponse.replay()).isTrue();
        double after = meterRegistry.find("ledger.idempotency.replay").counter().count();
        assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    void postingWithoutTransactionTypeDefaultsToTransfer() {
        CreateTransactionRequest request = new CreateTransactionRequest(
                "acct-a", "acct-b", 500L, "USD", "no type specified");
        TransactionResponse response = transactionService.postTransaction(request, "txtype-default-key-1");

        Transaction saved = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(saved.getTransactionType()).isEqualTo("TRANSFER");
    }

    @Test
    void postingWithExplicitTransactionTypePersistsIt() {
        CreateTransactionRequest request = new CreateTransactionRequest(
                "acct-a", "acct-b", 500L, "USD", "withdrawal", "WITHDRAWAL_EXTERNAL");
        TransactionResponse response = transactionService.postTransaction(request, "txtype-explicit-key-1");

        Transaction saved = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(saved.getTransactionType()).isEqualTo("WITHDRAWAL_EXTERNAL");
    }
}
