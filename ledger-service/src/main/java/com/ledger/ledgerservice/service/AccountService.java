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

    public AccountResponse createAccount(CreateAccountRequest request) {
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
