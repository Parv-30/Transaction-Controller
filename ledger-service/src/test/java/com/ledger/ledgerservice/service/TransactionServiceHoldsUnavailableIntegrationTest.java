package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.holds.HoldsServiceUnavailableException;
import com.ledger.ledgerservice.repository.AccountRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolated from {@link TransactionServiceIntegrationTest} deliberately: this class points
 * {@code holds.base-url} at a fixed, never-listening port (mirroring
 * {@code HoldsServiceClientIntegrationTest}'s {@code http://localhost:1} connection-refused
 * technique) for its entire lifetime, rather than starting and stopping a real stub server --
 * which would make test order matter for whichever other tests share a stub server instance.
 */
@Testcontainers
@SpringBootTest(properties = {
        "holds.base-url=http://localhost:1",
        "holds.held-balance-timeout-ms=500"
})
@ActiveProfiles("test")
class TransactionServiceHoldsUnavailableIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("ledger_db")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TransactionService transactionService;

    @Autowired
    AccountRepository accountRepository;

    @BeforeEach
    void seedAccounts() {
        if (accountRepository.findByAccountRef("holds-down-a").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "holds-down-a", "A", "USD",
                    10_000L, AccountStatus.ACTIVE, null));
        }
        if (accountRepository.findByAccountRef("holds-down-b").isEmpty()) {
            accountRepository.save(new Account(UUID.randomUUID(), "holds-down-b", "B", "USD",
                    0L, AccountStatus.ACTIVE, null));
        }
    }

    @Test
    void postTransactionFailsClosedWhenHoldsServiceIsUnreachable() {
        assertThatThrownBy(() -> transactionService.postTransaction(
                new CreateTransactionRequest("holds-down-a", "holds-down-b", 100L, "USD",
                        "should fail closed"),
                "holds-unavailable-test-1"))
                .isInstanceOf(HoldsServiceUnavailableException.class);
    }
}
