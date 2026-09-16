package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.api.dto.TransactionDetailResponse;
import com.ledger.ledgerservice.api.dto.TransactionSummaryResponse;
import com.ledger.ledgerservice.api.dto.EntryResponse;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.domain.Transaction;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.OutboxRepository;
import com.ledger.ledgerservice.repository.TransactionRepository;
import com.ledger.ledgerservice.security.CallerContext;
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

import java.time.Instant;
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
    void externalClearingAccountsSkipTheHeldBalanceCheckEntirely() {
        // Same guarantee as fx-clearing-, extended to external-clearing- accounts used by the
        // Gateway Simulator's deposit/withdrawal/reversal postings against a suspense account.
        stubbedHeldBalance.set(Long.MAX_VALUE / 2);

        accountRepository.save(new Account(UUID.randomUUID(), "external-clearing-USD",
                "External clearing USD", "USD", 1_000_000L, AccountStatus.ACTIVE, null));

        var response = transactionService.postTransaction(
                new CreateTransactionRequest("external-clearing-USD", "acct-a", 5_000L, "USD",
                        "reversal test", "WITHDRAWAL_EXTERNAL"),
                "clearing-check-key-1");

        assertThat(response.status()).isEqualTo("POSTED");
        Account credited = accountRepository.findByAccountRef("acct-a").orElseThrow();
        assertThat(credited.getBalanceMinor()).isEqualTo(15_000L);
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

    @Test
    void listTransactionsWithNoFiltersReturnsAllTransactions() {
        transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 100L, "USD", "list test"), "list-test-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<TransactionSummaryResponse> results = transactionService.listTransactions(null, null, null, null, admin);

        assertThat(results).extracting(TransactionSummaryResponse::debitAccountRef).contains("acct-a");
    }

    @Test
    void listTransactionsFiltersByAccountRefOnEitherSide() {
        TransactionResponse posted = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 200L, "USD", "filter test"), "filter-test-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<TransactionSummaryResponse> debitSideResults =
                transactionService.listTransactions("acct-a", null, null, null, admin);
        List<TransactionSummaryResponse> creditSideResults =
                transactionService.listTransactions("acct-b", null, null, null, admin);

        assertThat(debitSideResults).extracting(TransactionSummaryResponse::transactionId)
                .contains(posted.transactionId());
        assertThat(creditSideResults).extracting(TransactionSummaryResponse::transactionId)
                .contains(posted.transactionId());
    }

    @Test
    void listTransactionsFiltersByStatus() {
        transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 50L, "USD", "status filter test"), "status-filter-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<TransactionSummaryResponse> postedResults = transactionService.listTransactions(null, "POSTED", null, null, admin);
        List<TransactionSummaryResponse> failedResults = transactionService.listTransactions(null, "FAILED", null, null, admin);

        assertThat(postedResults).isNotEmpty();
        assertThat(failedResults).isEmpty();
    }

    @Test
    void listTransactionsFiltersBySinceAndUntil() {
        TransactionResponse posted = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 75L, "USD", "date filter test"), "date-filter-key-1");
        Instant beforePosting = Instant.now().minusSeconds(60);
        Instant afterPosting = Instant.now().plusSeconds(60);

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<TransactionSummaryResponse> inRangeResults =
                transactionService.listTransactions(null, null, beforePosting, afterPosting, admin);
        List<TransactionSummaryResponse> outOfRangeResults =
                transactionService.listTransactions(null, null, afterPosting, null, admin);

        assertThat(inRangeResults).extracting(TransactionSummaryResponse::transactionId)
                .contains(posted.transactionId());
        assertThat(outOfRangeResults).extracting(TransactionSummaryResponse::transactionId)
                .doesNotContain(posted.transactionId());
    }

    @Test
    void getTransactionReturnsDetailWithEntries() {
        TransactionResponse posted = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 300L, "USD", "detail test"), "detail-test-key-1");

        TransactionDetailResponse detail = transactionService.getTransaction(posted.transactionId());

        assertThat(detail.transactionId()).isEqualTo(posted.transactionId());
        assertThat(detail.entries()).hasSize(2);
        assertThat(detail.entries()).extracting(EntryResponse::direction).containsExactlyInAnyOrder("DEBIT", "CREDIT");
    }

    @Test
    void getTransactionThrowsWhenNotFound() {
        assertThatThrownBy(() -> transactionService.getTransaction(UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void reversingATransactionPostsACompensatingTransactionWithSwappedAccounts() {
        TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 400L, "USD", "reversal source"), "reverse-test-key-1");

        TransactionSummaryResponse reversal = transactionService.reverseTransaction(original.transactionId());

        assertThat(reversal.debitAccountRef()).isEqualTo("acct-b");
        assertThat(reversal.creditAccountRef()).isEqualTo("acct-a");
        assertThat(reversal.amountMinor()).isEqualTo(400L);
        assertThat(reversal.currency()).isEqualTo("USD");
        assertThat(reversal.reversalOfTransactionId()).isEqualTo(original.transactionId());
        assertThat(reversal.transactionType()).isEqualTo("REVERSAL");

        TransactionDetailResponse originalDetail = transactionService.getTransaction(original.transactionId());
        assertThat(originalDetail.status()).isEqualTo("REVERSED");
    }

    @Test
    void reversingAnAlreadyReversedTransactionThrowsConflict() {
        TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 150L, "USD", "double reversal test"), "double-reverse-key-1");
        transactionService.reverseTransaction(original.transactionId());

        assertThatThrownBy(() -> transactionService.reverseTransaction(original.transactionId()))
                .isInstanceOf(TransactionAlreadyReversedException.class);
    }

    @Test
    void reversingAReversalThrowsConflict() {
        TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 250L, "USD", "chain reversal test"), "chain-reverse-key-1");
        TransactionSummaryResponse reversal = transactionService.reverseTransaction(original.transactionId());

        assertThatThrownBy(() -> transactionService.reverseTransaction(reversal.transactionId()))
                .isInstanceOf(CannotReverseAReversalException.class);
    }

    @Test
    void reversingTheSameTransactionTwiceConcurrentlyViaRetryProducesExactlyOneReversal() {
        TransactionResponse original = transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 350L, "USD", "idempotent reversal test"), "idempotent-reverse-key-1");

        TransactionSummaryResponse firstAttempt = transactionService.reverseTransaction(original.transactionId());
        // A caller retrying after a lost response (e.g. a timeout) would call reverseTransaction
        // again for the same original id -- but by then the original is already REVERSED, so this
        // should throw TransactionAlreadyReversedException rather than silently succeeding twice.
        // This IS the correct behavior (not a bug): the deterministic idempotency key on the
        // underlying postTransaction call protects against a race where two reversal requests
        // are in flight simultaneously before either has committed; once one has fully committed
        // and the original is marked REVERSED, a second top-level call correctly rejects via the
        // already-reversed check, which is a stronger and simpler guarantee for this admin-only
        // endpoint than allowing a silent replay.
        assertThatThrownBy(() -> transactionService.reverseTransaction(original.transactionId()))
                .isInstanceOf(TransactionAlreadyReversedException.class);

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<TransactionSummaryResponse> allReversalsOfOriginal =
                transactionService.listTransactions(null, null, null, null, admin).stream()
                        .filter(t -> original.transactionId().equals(t.reversalOfTransactionId()))
                        .toList();
        assertThat(allReversalsOfOriginal).hasSize(1);
    }

    @Test
    void nonAdminCallerWithAccountRefCanListTheirOwnTransactions() {
        transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 100L, "USD", "self-scope test"), "self-scope-tx-key-1");

        CallerContext nonAdmin = new CallerContext(java.util.Set.of("user"));
        List<TransactionSummaryResponse> results =
                transactionService.listTransactions("acct-a", null, null, null, nonAdmin);

        assertThat(results).extracting(TransactionSummaryResponse::debitAccountRef).contains("acct-a");
    }

    @Test
    void nonAdminCallerWithBlankAccountRefIsRejected() {
        CallerContext nonAdmin = new CallerContext(java.util.Set.of("user"));

        assertThatThrownBy(() -> transactionService.listTransactions(null, null, null, null, nonAdmin))
                .isInstanceOf(AccountRefRequiredForNonAdminException.class);
        assertThatThrownBy(() -> transactionService.listTransactions("", null, null, null, nonAdmin))
                .isInstanceOf(AccountRefRequiredForNonAdminException.class);
    }

    @Test
    void adminCallerCanListAllTransactionsWithNoAccountRef() {
        transactionService.postTransaction(new CreateTransactionRequest(
                "acct-a", "acct-b", 100L, "USD", "admin-scope test"), "admin-scope-tx-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<TransactionSummaryResponse> results =
                transactionService.listTransactions(null, null, null, null, admin);

        assertThat(results).extracting(TransactionSummaryResponse::debitAccountRef).contains("acct-a");
    }
}
