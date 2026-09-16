package com.ledger.ledgerservice.api.error;

import com.ledger.ledgerservice.service.AccountNotActiveException;
import com.ledger.ledgerservice.service.AccountNotFoundException;
import com.ledger.ledgerservice.service.AccountRefAlreadyExistsException;
import com.ledger.ledgerservice.holds.HoldsServiceUnavailableException;
import com.ledger.ledgerservice.service.IdempotencyConflictException;
import com.ledger.ledgerservice.service.InsufficientFundsException;
import com.ledger.ledgerservice.service.CannotReverseAReversalException;
import com.ledger.ledgerservice.service.ReservedAccountRefException;
import com.ledger.ledgerservice.service.TransactionAlreadyReversedException;
import com.ledger.ledgerservice.service.TransactionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTransactionNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<Map<String, String>> handleAccountNotActive(AccountNotActiveException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccountRefAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleAccountRefAlreadyExists(AccountRefAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ReservedAccountRefException.class)
    public ResponseEntity<Map<String, String>> handleReservedAccountRef(ReservedAccountRefException e) {
        // 400, not 409: nothing already exists to conflict with -- the request itself is disallowed.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HoldsServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleHoldsServiceUnavailable(HoldsServiceUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TransactionAlreadyReversedException.class)
    public ResponseEntity<Map<String, String>> handleTransactionAlreadyReversed(TransactionAlreadyReversedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CannotReverseAReversalException.class)
    public ResponseEntity<Map<String, String>> handleCannotReverseAReversal(CannotReverseAReversalException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
