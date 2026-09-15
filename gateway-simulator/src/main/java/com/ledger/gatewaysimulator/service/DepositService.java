package com.ledger.gatewaysimulator.service;

import com.ledger.gatewaysimulator.api.dto.DepositResponse;
import com.ledger.gatewaysimulator.api.dto.DepositWebhookRequest;
import com.ledger.gatewaysimulator.domain.DepositStatus;
import com.ledger.gatewaysimulator.domain.ExternalDeposit;
import com.ledger.gatewaysimulator.domain.WebhookDedup;
import com.ledger.gatewaysimulator.ledger.LedgerTransactionClient;
import com.ledger.gatewaysimulator.repository.ExternalDepositRepository;
import com.ledger.gatewaysimulator.repository.WebhookDedupRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles inbound deposit webhook delivery: dedups redelivered webhooks by
 * {@code externalReference}, persists the {@code external_deposits} row, and credits the target
 * account via {@link LedgerTransactionClient}.
 *
 * <p>Note: {@link #handleWebhook} is {@code @Transactional} and wraps the outbound, blocking
 * {@code ledgerTransactionClient.postTransaction(...)} HTTP call inside the DB transaction that
 * also does the dedup-check-and-insert. This mirrors the same lock-duration tradeoff already
 * accepted and documented for {@code TransactionPoster}'s blocking Holds Service call in the
 * hardening plan -- an established pattern in this codebase, not a new risk. Splitting the
 * transaction boundary here would complicate the redelivery-safety guarantee this method
 * provides, so it is deliberately left as-is; see Task 14's README update for the writeup.
 */
@Service
public class DepositService {

    private static final String EXTERNAL_CLEARING_PREFIX = "external-clearing-";

    private final ExternalDepositRepository depositRepository;
    private final WebhookDedupRepository webhookDedupRepository;
    private final LedgerTransactionClient ledgerTransactionClient;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public DepositService(ExternalDepositRepository depositRepository,
                           WebhookDedupRepository webhookDedupRepository,
                           LedgerTransactionClient ledgerTransactionClient,
                           MeterRegistry meterRegistry,
                           ObjectMapper objectMapper) {
        this.depositRepository = depositRepository;
        this.webhookDedupRepository = webhookDedupRepository;
        this.ledgerTransactionClient = ledgerTransactionClient;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DepositResponse handleWebhook(DepositWebhookRequest request) {
        WebhookDedup dedup = webhookDedupRepository.findById(request.externalReference())
                .orElse(null);
        if (dedup != null) {
            dedup.recordRedelivery();
            webhookDedupRepository.save(dedup);
            meterRegistry.counter("gateway_sim.webhook.duplicate").increment();
            ExternalDeposit existing = depositRepository.findByExternalReference(request.externalReference())
                    .orElseThrow();
            return toResponse(existing);
        }
        webhookDedupRepository.save(new WebhookDedup(request.externalReference()));

        String rawPayload = toJson(request);
        ExternalDeposit deposit = new ExternalDeposit(UUID.randomUUID(), request.externalReference(),
                request.accountRef(), request.amountMinor(), request.currency(),
                DepositStatus.RECEIVED, rawPayload);
        depositRepository.save(deposit);

        try {
            UUID transactionId = ledgerTransactionClient.postTransaction(
                    EXTERNAL_CLEARING_PREFIX + request.currency(), request.accountRef(),
                    request.amountMinor(), request.currency(), "simulated external deposit",
                    "external-deposit-" + request.externalReference());
            deposit.markCredited(transactionId);
        } catch (Exception rejected) {
            deposit.markRejected();
            meterRegistry.counter("gateway_sim.deposit.rejected").increment();
        }
        depositRepository.save(deposit);
        return toResponse(deposit);
    }

    private String toJson(DepositWebhookRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize webhook payload", e);
        }
    }

    private DepositResponse toResponse(ExternalDeposit deposit) {
        return new DepositResponse(deposit.getExternalReference(), deposit.getAccountRef(),
                deposit.getAmountMinor(), deposit.getCurrency(), deposit.getStatus().name(),
                deposit.getTransactionId());
    }
}
