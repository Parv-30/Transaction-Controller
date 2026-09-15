package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.AccountResponse;
import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
import com.ledger.ledgerservice.domain.Account;
import com.ledger.ledgerservice.domain.AccountStatus;
import com.ledger.ledgerservice.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Creates a customer wallet account.
     *
     * <p>Account refs recognized by {@link TransactionPoster#isClearingAccount(String)} are
     * refused: this covers both the {@code fx-clearing-} prefix and the {@code external-clearing-}
     * prefix. {@code TransactionPoster} grants accounts under either prefix an unlimited-overdraft
     * privilege (they are the platform's internal netting/suspense accounts and are expected to
     * run negative). Since this endpoint is customer-facing and the ref is entirely client-chosen,
     * without this guard any authenticated caller could create {@code fx-clearing-<anything>} or
     * {@code external-clearing-<anything>} and then mint money by debiting it arbitrarily far
     * below zero -- a transfer that would still satisfy the zero-sum double-entry invariant and so
     * would not surface as a reconciliation anomaly. The prefix match is case-sensitive, matching
     * the bypass check.
     */
    public AccountResponse createAccount(CreateAccountRequest request) {
        if (request.accountRef() != null && TransactionPoster.isClearingAccount(request.accountRef())) {
            throw new ReservedAccountRefException(request.accountRef());
        }

        if (accountRepository.findByAccountRef(request.accountRef()).isPresent()) {
            throw new AccountRefAlreadyExistsException(request.accountRef());
        }

        UUID groupId = request.accountGroupId() != null ? request.accountGroupId() : UUID.randomUUID();
        Account account = new Account(UUID.randomUUID(), request.accountRef(), null,
                request.currency(), 0L, AccountStatus.ACTIVE, groupId);

        try {
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException raceLost) {
            throw new AccountRefAlreadyExistsException(request.accountRef());
        }

        return toResponse(account);
    }

    public List<AccountResponse> listWalletAccounts(UUID groupId) {
        return accountRepository.findByAccountGroupId(groupId).stream()
                .map(this::toResponse)
                .toList();
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getAccountRef(), account.getCurrency(),
                account.getBalanceMinor(), account.getStatus().name(), account.getAccountGroupId());
    }
}
