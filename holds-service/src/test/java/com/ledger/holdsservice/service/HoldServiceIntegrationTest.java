package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.AvailableBalanceResponse;
import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
import com.ledger.holdsservice.security.CallerContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class HoldServiceIntegrationTest {

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
    private HoldService holdService;
    @Autowired
    private HoldRepository holdRepository;
    @Autowired
    private AccountBalanceCacheRepository accountBalanceCacheRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @BeforeEach
    void seedBalance() {
        holdRepository.deleteAll();
        accountBalanceCacheRepository.deleteAll();
        outboxRepository.deleteAll();
        accountBalanceCacheRepository.save(new AccountBalanceCache("acct-holds-a", 10_000L, 0L));
        accountBalanceCacheRepository.save(new AccountBalanceCache("list-holds-a", 10_000L, 0L));
        accountBalanceCacheRepository.save(new AccountBalanceCache("filter-holds-source", 10_000L, 0L));
        accountBalanceCacheRepository.save(new AccountBalanceCache("status-holds-a", 10_000L, 0L));
    }

    @Test
    void createsAHoldAndDecrementsAvailableBalance() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 3_000L, "USD", 3600);

        HoldResponse response = holdService.createHold(request, "hold-key-1");

        assertThat(response.replay()).isFalse();
        assertThat(response.status()).isEqualTo("ACTIVE");

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(7_000L);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyReturnsReplayWithoutDoubleHolding() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 2_000L, "USD", 3600);

        HoldResponse first = holdService.createHold(request, "hold-key-2");
        HoldResponse second = holdService.createHold(request, "hold-key-2");

        assertThat(second.replay()).isTrue();
        assertThat(second.holdId()).isEqualTo(first.holdId());

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.getHeldBalanceMinor()).isEqualTo(2_000L);
    }

    @Test
    void retryingTheSameIdempotencyKeyIncrementsTheReplayCounter() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 1_000L, "USD", 3600);
        holdService.createHold(request, "hold-key-metrics-1");
        double before = meterRegistry.find("holds.idempotency.replay").counter() == null
                ? 0.0 : meterRegistry.find("holds.idempotency.replay").counter().count();

        HoldResponse replay = holdService.createHold(request, "hold-key-metrics-1");

        assertThat(replay.replay()).isTrue();
        double after = meterRegistry.find("holds.idempotency.replay").counter().count();
        assertThat(after).isEqualTo(before + 1.0);
    }

    @Test
    void insufficientAvailableBalanceThrowsAndMutatesNothing() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 999_999L, "USD", 3600);

        assertThatThrownBy(() -> holdService.createHold(request, "hold-key-3"))
                .isInstanceOf(InsufficientAvailableBalanceException.class);

        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.getHeldBalanceMinor()).isZero();
    }

    @Test
    void releaseReturnsHeldFundsToAvailableBalance() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 4_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "hold-key-4");

        HoldResponse released = holdService.release(created.holdId());

        assertThat(released.status()).isEqualTo("RELEASED");
        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L);
    }

    @Test
    void releasingAnAlreadyTerminalHoldIsANoOp() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 1_000L, "USD", 3600);
        HoldResponse created = holdService.createHold(request, "hold-key-5");
        holdService.release(created.holdId());

        HoldResponse secondRelease = holdService.release(created.holdId());

        assertThat(secondRelease.status()).isEqualTo("RELEASED");
        AccountBalanceCache cache = accountBalanceCacheRepository.findById("acct-holds-a").orElseThrow();
        assertThat(cache.availableBalanceMinor()).isEqualTo(10_000L); // not double-released
    }

    @Test
    void availableBalanceReflectsPostedAndHeldAmountsForKnownAccount() {
        var request = new CreateHoldRequest("acct-holds-a", "acct-merchant", 4_000L, "USD", 3600);
        holdService.createHold(request, "hold-key-6");

        AvailableBalanceResponse response = holdService.getAvailableBalance("acct-holds-a");

        assertThat(response.accountRef()).isEqualTo("acct-holds-a");
        assertThat(response.postedBalanceMinor()).isEqualTo(10_000L);
        assertThat(response.heldBalanceMinor()).isEqualTo(4_000L);
        assertThat(response.availableBalanceMinor()).isEqualTo(6_000L);
    }

    @Test
    void availableBalanceForUnknownAccountIsZeroRatherThanNotFound() {
        AvailableBalanceResponse response = holdService.getAvailableBalance("acct-never-seen");

        assertThat(response.accountRef()).isEqualTo("acct-never-seen");
        assertThat(response.postedBalanceMinor()).isZero();
        assertThat(response.heldBalanceMinor()).isZero();
        assertThat(response.availableBalanceMinor()).isZero();
        assertThat(accountBalanceCacheRepository.findById("acct-never-seen")).isEmpty();
    }

    @Test
    void listHoldsWithNoFiltersReturnsAllHolds() {
        holdService.createHold(new CreateHoldRequest("list-holds-a", "list-holds-b", 100L, "USD", 3600L),
                "list-holds-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<HoldResponse> results = holdService.listHolds(null, null, admin);

        assertThat(results).extracting(HoldResponse::accountRef).contains("list-holds-a");
    }

    @Test
    void listHoldsFiltersByAccountRefOnEitherSide() {
        holdService.createHold(new CreateHoldRequest("filter-holds-source", "filter-holds-dest", 50L, "USD", 3600L),
                "filter-holds-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<HoldResponse> sourceResults = holdService.listHolds("filter-holds-source", null, admin);
        List<HoldResponse> destResults = holdService.listHolds("filter-holds-dest", null, admin);

        assertThat(sourceResults).extracting(HoldResponse::accountRef).contains("filter-holds-source");
        assertThat(destResults).extracting(HoldResponse::destinationAccountRef).contains("filter-holds-dest");
    }

    @Test
    void listHoldsFiltersByStatus() {
        holdService.createHold(new CreateHoldRequest("status-holds-a", "status-holds-b", 25L, "USD", 3600L),
                "status-holds-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<HoldResponse> activeResults = holdService.listHolds(null, "ACTIVE", admin);
        List<HoldResponse> releasedResults = holdService.listHolds(null, "RELEASED", admin);

        assertThat(activeResults).extracting(HoldResponse::accountRef).contains("status-holds-a");
        assertThat(releasedResults).extracting(HoldResponse::accountRef).doesNotContain("status-holds-a");
    }

    @Test
    void nonAdminCallerWithAccountRefCanListTheirOwnHolds() {
        holdService.createHold(new CreateHoldRequest("list-holds-a", "acct-merchant", 100L, "USD", 3600),
                "self-scope-key-1");

        CallerContext nonAdmin = new CallerContext(java.util.Set.of("user"));
        List<HoldResponse> results = holdService.listHolds("list-holds-a", null, nonAdmin);

        assertThat(results).extracting(HoldResponse::accountRef).contains("list-holds-a");
    }

    @Test
    void nonAdminCallerWithBlankAccountRefIsRejected() {
        CallerContext nonAdmin = new CallerContext(java.util.Set.of("user"));

        assertThatThrownBy(() -> holdService.listHolds(null, null, nonAdmin))
                .isInstanceOf(AccountRefRequiredForNonAdminException.class);
        assertThatThrownBy(() -> holdService.listHolds("", null, nonAdmin))
                .isInstanceOf(AccountRefRequiredForNonAdminException.class);
    }

    @Test
    void adminCallerCanListAllHoldsWithNoAccountRef() {
        holdService.createHold(new CreateHoldRequest("list-holds-a", "acct-merchant", 100L, "USD", 3600),
                "admin-scope-key-1");

        CallerContext admin = new CallerContext(java.util.Set.of("user", "admin"));
        List<HoldResponse> results = holdService.listHolds(null, null, admin);

        assertThat(results).extracting(HoldResponse::accountRef).contains("list-holds-a");
    }

    @Test
    void callerWithNoRolesAtAllIsTreatedAsNonAdmin() {
        assertThatThrownBy(() -> holdService.listHolds(null, null, CallerContext.NONE))
                .isInstanceOf(AccountRefRequiredForNonAdminException.class);
    }
}
