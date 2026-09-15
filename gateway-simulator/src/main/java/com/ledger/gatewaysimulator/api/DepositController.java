package com.ledger.gatewaysimulator.api;

import com.ledger.gatewaysimulator.api.dto.DepositResponse;
import com.ledger.gatewaysimulator.api.dto.DepositWebhookRequest;
import com.ledger.gatewaysimulator.api.dto.SimulateDepositRequest;
import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.service.DepositService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class DepositController {

    private final DepositService depositService;
    private final ExternalDepositRepository depositRepository;

    public DepositController(DepositService depositService, ExternalDepositRepository depositRepository) {
        this.depositService = depositService;
        this.depositRepository = depositRepository;
    }

    @PostMapping("/simulator/deposits")
    public ResponseEntity<DepositResponse> simulateDeposit(@RequestBody SimulateDepositRequest request) {
        String externalReference = "sim-deposit-" + UUID.randomUUID();
        DepositWebhookRequest webhookRequest = new DepositWebhookRequest(
                externalReference, request.accountRef(), request.amountMinor(), request.currency());
        DepositResponse response = depositService.handleWebhook(webhookRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/webhooks/deposits")
    public ResponseEntity<DepositResponse> receiveWebhook(@RequestBody DepositWebhookRequest request) {
        DepositResponse response = depositService.handleWebhook(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/external-deposits/{externalReference}")
    public ResponseEntity<DepositResponse> getDeposit(@PathVariable("externalReference") String externalReference) {
        ExternalDeposit deposit = depositRepository.findByExternalReference(externalReference)
                .orElseThrow(() -> new DepositNotFoundException(externalReference));
        return ResponseEntity.ok(new DepositResponse(deposit.getExternalReference(), deposit.getAccountRef(),
                deposit.getAmountMinor(), deposit.getCurrency(), deposit.getStatus().name(),
                deposit.getTransactionId()));
    }
}
