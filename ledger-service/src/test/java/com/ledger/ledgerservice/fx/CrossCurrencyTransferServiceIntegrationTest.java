package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.OutboxRepository;
import com.ledger.ledgerservice.repository.TransactionRepository;
import com.ledger.ledgerservice.testsupport.StubHoldsService;
import com.sun.net.httpserver.HttpServer;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class CrossCurrencyTransferServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubFxService;
    static HttpServer stubHoldsService;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubHoldsService = StubHoldsService.startAlwaysZero(registry);

        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
        // Pair-agnostic on purpose: every /conversions/quote call (USD->EUR and USD->JPY alike)
        // gets the same locked rate, so the leg-2-failure test needs no extra stub case.
        stubFxService.createContext("/conversions/quote", exchange -> {
            String body = "{\"quoteId\":\"" + UUID.randomUUID() + "\","
                    + "\"rateUsed\":0.92000000,\"expiresAt\":\"2099-01-01T00:00:00Z\",\"stale\":false}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        stubFxService.start();
        registry.add("fx.base-url", () -> "http://localhost:" + stubFxService.getAddress().getPort());
    }

    @Autowired
    CrossCurrencyTransferService crossCurrencyTransferService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    PendingFxTransferRepository pendingFxTransferRepository;

    @Autowired
    EntryRepository entryRepository;

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    TransactionRepository transactionRepository;

    /**
     * The @Container Postgres is static and shared across every @Test in this class, so balance
     * mutations would otherwise leak between tests. Truncate in FK-safe child-to-parent order,
     * exactly as TransactionServiceIntegrationTest does, then re-seed fixed-name accounts --
     * every test therefore starts from identical balances and is order-independent.
     */
    @BeforeEach
    void resetAndSeedAccounts() {
        pendingFxTransferRepository.deleteAll();
        entryRepository.deleteAll();
        outboxRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        seedAccount("fx-saga-source-usd", "USD", 100_000L);
        seedAccount("fx-saga-dest-eur", "EUR", 0L);
        seedAccount("fx-clearing-USD", "USD", 0L);
        seedAccount("fx-clearing-EUR", "EUR", 0L);
    }

    private void seedAccount(String ref, String currency, long balance) {
        accountRepository.save(new Account(UUID.randomUUID(), ref, null, currency,
                balance, AccountStatus.ACTIVE, null));
    }

    @Test
    void happyPathCompletesBothLegsAndUpdatesBalancesCorrectly() {
        var request = new CreateCrossCurrencyTransferRequest(
                "fx-saga-source-usd", "fx-saga-dest-eur", 10_000L, "cc-transfer-happy-1");

        var response = crossCurrencyTransferService.transfer(request);

        assertThat(response.status()).isEqualTo(PendingFxTransferStatus.COMPLETED.name());
        assertThat(response.destAmountMinor()).isEqualTo(9_200L);
        // Nothing went wrong, so no error is reported.
        assertThat(response.errorMessage()).isNull();

        Account source = accountRepository.findByAccountRef("fx-saga-source-usd").orElseThrow();
        Account dest = accountRepository.findByAccountRef("fx-saga-dest-eur").orElseThrow();
        Account clearingUsd = accountRepository.findByAccountRef("fx-clearing-USD").orElseThrow();
        Account clearingEur = accountRepository.findByAccountRef("fx-clearing-EUR").orElseThrow();

        // leg 1: debit source USD 10,000 -> credit fx-clearing-USD 10,000
        // leg 2: debit fx-clearing-EUR 9,200 (10,000 * 0.92) -> credit dest EUR 9,200
        assertThat(source.getBalanceMinor()).isEqualTo(90_000L);
        assertThat(dest.getBalanceMinor()).isEqualTo(9_200L);
        assertThat(clearingUsd.getBalanceMinor()).isEqualTo(10_000L);
        assertThat(clearingEur.getBalanceMinor()).isEqualTo(-9_200L);
    }

    @Test
    void retryingWithTheSameIdempotencyKeyReturnsTheExistingResultWithoutDoublePosting() {
        var request = new CreateCrossCurrencyTransferRequest(
                "fx-saga-source-usd", "fx-saga-dest-eur", 5_000L, "cc-transfer-idem-1");

        var first = crossCurrencyTransferService.transfer(request);
        var second = crossCurrencyTransferService.transfer(request);

        assertThat(second.pendingTransferId()).isEqualTo(first.pendingTransferId());

        Account source = accountRepository.findByAccountRef("fx-saga-source-usd").orElseThrow();
        // Only ONE 5,000 debit should have happened, not two -- this is the idempotency
        // proof, not just a status-field check.
        assertThat(source.getBalanceMinor()).isEqualTo(95_000L);
    }

    @Test
    void leg2FailureTriggersCompensationAndRestoresSourceBalance() {
        // CrossCurrencyTransferService.transfer() resolves both accounts' currencies (via
        // resolveCurrency) BEFORE creating the PendingFxTransfer row or calling postLeg1 at all --
        // so a dest account that doesn't exist at all would fail at that early resolveCurrency
        // call, never reaching postLeg2, and this test would never see a COMPENSATED result.
        // Instead, force failure specifically inside postLeg2 by giving the dest account a real,
        // resolvable currency that has NO configured clearing account (fx.clearing-accounts only
        // configures USD/EUR/GBP) -- postLeg2's clearingAccounts.get(destCurrency) then returns
        // null, and passing a null account ref to TransactionService.postTransaction fails inside
        // leg 2's own posting attempt, which is the actual failure mode this test exercises.
        seedAccount("fx-saga-dest-jpy", "JPY", 0L);

        var request = new CreateCrossCurrencyTransferRequest(
                "fx-saga-source-usd", "fx-saga-dest-jpy", 5_000L, "cc-transfer-fail-1");

        var response = crossCurrencyTransferService.transfer(request);

        assertThat(response.status()).isEqualTo(PendingFxTransferStatus.COMPENSATED.name());
        // The failure that forced compensation must reach the caller, not be swallowed.
        assertThat(response.errorMessage()).isNotBlank();

        Account source = accountRepository.findByAccountRef("fx-saga-source-usd").orElseThrow();
        // The debit-then-compensate round trip should net to zero change on the source account.
        assertThat(source.getBalanceMinor()).isEqualTo(100_000L);

        Account clearingUsd = accountRepository.findByAccountRef("fx-clearing-USD").orElseThrow();
        // Clearing received 5,000 in leg 1 and paid it straight back out in compensation.
        assertThat(clearingUsd.getBalanceMinor()).isEqualTo(0L);

        Account destJpy = accountRepository.findByAccountRef("fx-saga-dest-jpy").orElseThrow();
        assertThat(destJpy.getBalanceMinor()).isEqualTo(0L);
    }
}
