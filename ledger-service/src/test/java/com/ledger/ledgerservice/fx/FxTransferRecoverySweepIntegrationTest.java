package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.sun.net.httpserver.HttpServer;
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

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "fx.transfer-sweep.stuck-threshold-ms=1000")
@ActiveProfiles("test")
class FxTransferRecoverySweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    static HttpServer stubFxService;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        stubFxService = HttpServer.create(new InetSocketAddress(0), 0);
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
    FxTransferRecoverySweep sweep;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    PendingFxTransferRepository pendingFxTransferRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CrossCurrencyTransferPoster poster;

    @Test
    void sweepResolvesARowStuckInLeg1PostedByPostingLeg2() {
        accountRepository.save(new Account(UUID.randomUUID(), "fx-sweep-source", null, "USD",
                100_000L, AccountStatus.ACTIVE, null));
        accountRepository.save(new Account(UUID.randomUUID(), "fx-sweep-dest", null, "EUR",
                0L, AccountStatus.ACTIVE, null));
        // fx-clearing-USD/EUR are fixed account refs seeded by the V4 migration (which now runs
        // against every Testcontainers Postgres instance, including this one), so they may
        // already exist -- only seed them if they don't, matching the guard used by the other
        // @Test in this class.
        if (accountRepository.findByAccountRef("fx-clearing-USD").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-USD", null, "USD",
                    0L, AccountStatus.ACTIVE, null));
        }
        if (accountRepository.findByAccountRef("fx-clearing-EUR").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-EUR", null, "EUR",
                    0L, AccountStatus.ACTIVE, null));
        }

        PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), "sweep-test-1",
                UUID.randomUUID(), "fx-sweep-source", "fx-sweep-dest", 10_000L,
                new BigDecimal("0.92000000"), 9_200L);
        pendingFxTransferRepository.save(transfer);
        poster.postLeg1(transfer.getId());

        // Backdate updated_at past the stuck threshold, simulating a crash between leg 1 and leg 2.
        jdbcTemplate.update("UPDATE pending_fx_transfers SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), transfer.getId());

        sweep.run();

        PendingFxTransfer resolved = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PendingFxTransferStatus.COMPLETED);

        Account dest = accountRepository.findByAccountRef("fx-sweep-dest").orElseThrow();
        assertThat(dest.getBalanceMinor()).isEqualTo(9_200L);
    }

    @Test
    void sweepResolvesARowStuckInCompensatingByReplayingCompensation() {
        accountRepository.save(new Account(UUID.randomUUID(), "fx-sweep-comp-source", null, "USD",
                100_000L, AccountStatus.ACTIVE, null));
        // fx-clearing-USD is a fixed account ref (from fx.clearing-accounts config), so it may
        // already have been created by another @Test in this class sharing the same static
        // container -- only seed it if it doesn't exist yet.
        if (accountRepository.findByAccountRef("fx-clearing-USD").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "fx-clearing-USD", null, "USD",
                    0L, AccountStatus.ACTIVE, null));
        }

        PendingFxTransfer transfer = new PendingFxTransfer(UUID.randomUUID(), "sweep-test-2",
                UUID.randomUUID(), "fx-sweep-comp-source", "fx-sweep-comp-dest", 5_000L,
                new BigDecimal("0.92000000"), 4_600L);
        pendingFxTransferRepository.save(transfer);
        poster.postLeg1(transfer.getId());
        // Simulate a crash inside compensate() itself: mark COMPENSATING but never finish,
        // leaving leg 1's debit still standing (per Task 9's own report, this is the state
        // the sweep must be able to recover, not just PENDING/LEG1_POSTED).
        poster.compensate(transfer.getId());

        jdbcTemplate.update("UPDATE pending_fx_transfers SET status = 'COMPENSATING', updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), transfer.getId());

        sweep.run();

        PendingFxTransfer resolved = pendingFxTransferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PendingFxTransferStatus.COMPENSATED);

        Account source = accountRepository.findByAccountRef("fx-sweep-comp-source").orElseThrow();
        // The debit-then-compensate round trip should net to zero change on the source account,
        // and the sweep's replay via compensate()'s deterministic idempotency key must not
        // double-reverse it.
        assertThat(source.getBalanceMinor()).isEqualTo(100_000L);
    }
}
