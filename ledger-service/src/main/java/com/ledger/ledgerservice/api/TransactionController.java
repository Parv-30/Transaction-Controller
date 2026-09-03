package com.ledger.ledgerservice.api;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @RequestBody CreateTransactionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        TransactionResponse response = transactionService.postTransaction(request, idempotencyKey);

        HttpStatus status = response.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (response.replay()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(response);
    }
}
