package com.ledger.holdsservice.service;

import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
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

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class HeldBalanceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("holds_db")
            .withUsername("holds")
            .withPassword("holds");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    HoldService holdService;

    @Autowired
    AccountBalanceCacheRepository accountBalanceCacheRepository;

    @Test
    void getHeldBalanceReturnsTheCurrentlyHeldAmountForAKnownAccount() {
        accountBalanceCacheRepository.save(new AccountBalanceCache("held-test-acct-1", 10_000L, 3_000L));

        var response = holdService.getHeldBalance("held-test-acct-1");

        assertThat(response.accountRef()).isEqualTo("held-test-acct-1");
        assertThat(response.heldBalanceMinor()).isEqualTo(3_000L);
    }

    @Test
    void getHeldBalanceReturnsZeroForAnUnknownAccountWithoutPersistingAnything() {
        var response = holdService.getHeldBalance("held-test-acct-never-seen");

        assertThat(response.accountRef()).isEqualTo("held-test-acct-never-seen");
        assertThat(response.heldBalanceMinor()).isEqualTo(0L);
        assertThat(accountBalanceCacheRepository.findById("held-test-acct-never-seen")).isEmpty();
    }
}
