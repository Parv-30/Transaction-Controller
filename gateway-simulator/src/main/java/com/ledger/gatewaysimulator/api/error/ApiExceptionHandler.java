package com.ledger.gatewaysimulator.api.error;

import com.ledger.gatewaysimulator.api.DepositNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DepositNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleDepositNotFound(DepositNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WithdrawalNotYetSubmittedException.class)
    public ResponseEntity<Map<String, String>> handleWithdrawalNotYetSubmitted(WithdrawalNotYetSubmittedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WithdrawalNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleWithdrawalNotFound(WithdrawalNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
