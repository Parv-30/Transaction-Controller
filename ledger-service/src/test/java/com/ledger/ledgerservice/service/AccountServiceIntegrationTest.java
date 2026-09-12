package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceIntegrationTest {

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
    AccountService accountService;

    @Test
    void createAccountGeneratesAGroupIdWhenNoneIsGiven() {
        var response = accountService.createAccount(
                new CreateAccountRequest("wallet-test-solo", "USD", null));

        assertThat(response.accountGroupId()).isNotNull();
    }

    @Test
    void createAccountJoinsAnExistingGroupWhenOneIsGiven() {
        UUID groupId = UUID.randomUUID();
        var response = accountService.createAccount(
                new CreateAccountRequest("wallet-test-joined", "EUR", groupId));

        assertThat(response.accountGroupId()).isEqualTo(groupId);
    }

    @Test
    void createAccountThrowsWhenAccountRefAlreadyExists() {
        accountService.createAccount(new CreateAccountRequest("wallet-test-dup", "USD", null));

        assertThatThrownBy(() -> accountService.createAccount(
                new CreateAccountRequest("wallet-test-dup", "EUR", null)))
                .isInstanceOf(AccountRefAlreadyExistsException.class);
    }

    @Test
    void listWalletAccountsReturnsAllAccountsSharingAGroup() {
        UUID groupId = UUID.randomUUID();
        accountService.createAccount(new CreateAccountRequest("wallet-test-usd", "USD", groupId));
        accountService.createAccount(new CreateAccountRequest("wallet-test-eur", "EUR", groupId));

        var accounts = accountService.listWalletAccounts(groupId);

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting("accountRef")
                .containsExactlyInAnyOrder("wallet-test-usd", "wallet-test-eur");
    }

    @Test
    void listWalletAccountsReturnsEmptyListForAnUnknownGroup() {
        var accounts = accountService.listWalletAccounts(UUID.randomUUID());

        assertThat(accounts).isEmpty();
    }
}
