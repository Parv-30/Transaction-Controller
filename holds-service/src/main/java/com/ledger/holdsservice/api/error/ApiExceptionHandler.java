package com.ledger.holdsservice.api.error;

import com.ledger.holdsservice.service.CaptureExceedsRemainingAmountException;
import com.ledger.holdsservice.service.HoldIdempotencyConflictException;
import com.ledger.holdsservice.service.HoldNotFoundException;
import com.ledger.holdsservice.service.InsufficientAvailableBalanceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HoldNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(HoldNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InsufficientAvailableBalanceException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientBalance(InsufficientAvailableBalanceException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HoldIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(HoldIdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CaptureExceedsRemainingAmountException.class)
    public ResponseEntity<Map<String, String>> handleCaptureExceedsRemaining(CaptureExceedsRemainingAmountException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }
}
