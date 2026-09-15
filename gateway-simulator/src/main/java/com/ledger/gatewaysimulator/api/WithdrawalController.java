package com.ledger.gatewaysimulator.api;

import com.ledger.gatewaysimulator.api.dto.ConfirmWithdrawalRequest;
import com.ledger.gatewaysimulator.api.dto.WithdrawalResponse;
import com.ledger.gatewaysimulator.api.error.WithdrawalNotFoundException;
import com.ledger.gatewaysimulator.domain.ExternalWithdrawal;
import com.ledger.gatewaysimulator.repository.ExternalWithdrawalRepository;
import com.ledger.gatewaysimulator.service.WithdrawalResolutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WithdrawalController {

    private final WithdrawalResolutionService resolutionService;
    private final ExternalWithdrawalRepository withdrawalRepository;

    public WithdrawalController(WithdrawalResolutionService resolutionService,
                                 ExternalWithdrawalRepository withdrawalRepository) {
        this.resolutionService = resolutionService;
        this.withdrawalRepository = withdrawalRepository;
    }

    @PostMapping("/simulator/withdrawals/{id}/confirm")
    public ResponseEntity<WithdrawalResponse> confirm(@PathVariable("id") UUID id,
                                                        @RequestBody ConfirmWithdrawalRequest request) {
        ExternalWithdrawal withdrawal = resolutionService.confirm(id, request.outcome());
        return ResponseEntity.ok(toResponse(withdrawal));
    }

    @GetMapping("/external-withdrawals/{id}")
    public ResponseEntity<WithdrawalResponse> get(@PathVariable("id") UUID id) {
        ExternalWithdrawal withdrawal = withdrawalRepository.findById(id)
                .orElseThrow(() -> new WithdrawalNotFoundException(id));
        return ResponseEntity.ok(toResponse(withdrawal));
    }

    private WithdrawalResponse toResponse(ExternalWithdrawal withdrawal) {
        return new WithdrawalResponse(withdrawal.getId(), withdrawal.getSourceTransactionId(),
                withdrawal.getAccountRef(), withdrawal.getAmountMinor(), withdrawal.getCurrency(),
                withdrawal.getStatus().name(), withdrawal.getReversalTransactionId());
    }
}
