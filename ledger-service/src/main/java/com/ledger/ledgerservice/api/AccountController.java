package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.AccountResponse;
import com.ledger.ledgerservice.api.dto.CreateAccountRequest;
import com.ledger.ledgerservice.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> create(@RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/wallets/{groupId}/accounts")
    public ResponseEntity<List<AccountResponse>> listWalletAccounts(@PathVariable("groupId") UUID groupId) {
        return ResponseEntity.ok(accountService.listWalletAccounts(groupId));
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountResponse>> list(
            @RequestParam(value = "accountRef", required = false) String accountRef,
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(accountService.listAccounts(accountRef, status));
    }

    @GetMapping("/accounts/{accountRef}")
    public ResponseEntity<AccountResponse> get(@PathVariable("accountRef") String accountRef) {
        return ResponseEntity.ok(accountService.getAccount(accountRef));
    }
}
