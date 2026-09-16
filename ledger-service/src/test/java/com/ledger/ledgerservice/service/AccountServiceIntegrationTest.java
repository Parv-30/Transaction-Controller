package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.api.dto.AccountResponse;
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

    @Autowired
    com.ledger.ledgerservice.repository.AccountRepository accountRepository;

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
    void createAccountRefusesTheReservedFxClearingPrefix() {
        // TransactionPoster lets any account whose ref starts with "fx-clearing-" be debited
        // below zero. If a client could claim such a ref through this customer-facing endpoint,
        // it could mint money by debiting its own clearing account without limit.
        assertThatThrownBy(() -> accountService.createAccount(
                new CreateAccountRequest("fx-clearing-evil", "USD", null)))
                .isInstanceOf(ReservedAccountRefException.class);

        // ...and nothing was persisted, so the ref cannot be used afterwards either.
        assertThat(accountRepository.findByAccountRef("fx-clearing-evil")).isEmpty();
    }

    @Test
    void createAccountRefusesTheReservedPrefixEvenForARealClearingCurrency() {
        // The guard is a prefix rule, not an allowlist of "suspicious" names: even the exact
        // refs the FX saga itself uses are unavailable to clients through this endpoint.
        assertThatThrownBy(() -> accountService.createAccount(
                new CreateAccountRequest("fx-clearing-USD", "USD", null)))
                .isInstanceOf(ReservedAccountRefException.class);
    }

    @Test
    void creatingAnAccountWithTheExternalClearingPrefixIsRejected() {
        // Mirrors createAccountRefusesTheReservedFxClearingPrefix: TransactionPoster grants the
        // "external-clearing-" prefix the same unlimited-overdraft privilege (Task 2), so this
        // customer-facing endpoint must refuse it too.
        assertThatThrownBy(() -> accountService.createAccount(
                new CreateAccountRequest("external-clearing-USD", "USD", null)))
                .isInstanceOf(ReservedAccountRefException.class);
    }

    @Test
    void externalClearingUsdAccountExistsFromMigrationAndAllowsUnlimitedOverdraft() {
        Account seeded = accountRepository.findByAccountRef("external-clearing-USD").orElseThrow();
        assertThat(seeded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(seeded.getCurrency()).isEqualTo("USD");
    }

    @Test
    void createAccountAllowsRefsThatMerelyContainTheReservedWordElsewhere() {
        // Regression guard: the check is startsWith, case-sensitive -- it must not over-reject
        // ordinary customer wallet refs.
        assertThat(accountService.createAccount(
                new CreateAccountRequest("wallet-fx-clearing-lookalike", "USD", null)).accountRef())
                .isEqualTo("wallet-fx-clearing-lookalike");
        assertThat(accountService.createAccount(
                new CreateAccountRequest("FX-CLEARING-upper", "USD", null)).accountRef())
                .isEqualTo("FX-CLEARING-upper");
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

    @Test
    void listAccountsWithNoFiltersReturnsAllAccounts() {
        accountService.createAccount(new CreateAccountRequest("list-test-a", "USD", null));
        accountService.createAccount(new CreateAccountRequest("list-test-b", "USD", null));

        List<AccountResponse> results = accountService.listAccounts(null, null);

        assertThat(results).extracting(AccountResponse::accountRef)
                .contains("list-test-a", "list-test-b");
    }

    @Test
    void listAccountsFiltersByAccountRefSubstringCaseInsensitively() {
        accountService.createAccount(new CreateAccountRequest("filter-target-1", "USD", null));
        accountService.createAccount(new CreateAccountRequest("unrelated-2", "USD", null));

        List<AccountResponse> results = accountService.listAccounts("FILTER-TARGET", null);

        assertThat(results).extracting(AccountResponse::accountRef).containsExactly("filter-target-1");
    }

    @Test
    void listAccountsFiltersByStatus() {
        accountService.createAccount(new CreateAccountRequest("status-filter-active", "USD", null));

        List<AccountResponse> activeResults = accountService.listAccounts(null, "ACTIVE");
        List<AccountResponse> closedResults = accountService.listAccounts(null, "CLOSED");

        assertThat(activeResults).extracting(AccountResponse::accountRef).contains("status-filter-active");
        assertThat(closedResults).extracting(AccountResponse::accountRef).doesNotContain("status-filter-active");
    }

    @Test
    void getAccountByRefReturnsTheAccount() {
        accountService.createAccount(new CreateAccountRequest("get-by-ref-test", "EUR", null));

        AccountResponse response = accountService.getAccount("get-by-ref-test");

        assertThat(response.accountRef()).isEqualTo("get-by-ref-test");
        assertThat(response.currency()).isEqualTo("EUR");
    }

    @Test
    void getAccountByRefThrowsWhenNotFound() {
        assertThatThrownBy(() -> accountService.getAccount("does-not-exist-ref"))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
