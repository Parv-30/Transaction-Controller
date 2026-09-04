package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
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

    @BeforeEach
    void seedBalance() {
        holdRepository.deleteAll();
        accountBalanceCacheRepository.deleteAll();
        outboxRepository.deleteAll();
        accountBalanceCacheRepository.save(new AccountBalanceCache("acct-holds-a", 10_000L, 0L));
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
}
