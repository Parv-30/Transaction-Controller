package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.TransactionDetailResponse;
import com.ledger.ledgerservice.api.dto.TransactionSummaryResponse;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class TransactionQueryController {

    private final TransactionService transactionService;

    public TransactionQueryController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<TransactionSummaryResponse>> list(
            @RequestParam(value = "accountRef", required = false) String accountRef,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "since", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(value = "until", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until) {
        return ResponseEntity.ok(transactionService.listTransactions(accountRef, status, since, until));
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<TransactionDetailResponse> get(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(transactionService.getTransaction(id));
    }

    @PostMapping("/transactions/{id}/reverse")
    public ResponseEntity<TransactionSummaryResponse> reverse(@PathVariable("id") UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.reverseTransaction(id));
    }
}
