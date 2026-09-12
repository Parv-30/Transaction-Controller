package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Holds the individual @Transactional steps of the cross-currency saga. Each public method here
 * is called by {@link CrossCurrencyTransferService} (a different bean) or directly by the
 * recovery sweep -- never self-invoked -- so Spring's transactional proxy applies correctly to
 * each step, per this codebase's established self-invocation-avoidance pattern.
 *
 * <p>Every step posts its ledger transaction under a deterministic idempotency key derived from
 * the pending-transfer id ({@code fx-leg1-<id>}, {@code fx-leg2-<id>}, {@code fx-compensate-<id>})
 * so that a retry of the same step -- whether from this saga or from the crash-recovery sweep --
 * replays the already-posted transaction instead of double-posting money.
 */
@Service
public class CrossCurrencyTransferPoster {

    private final TransactionService transactionService;
    private final AccountRepository accountRepository;
    private final PendingFxTransferRepository pendingFxTransferRepository;
    private final FxClearingAccountsProperties clearingAccounts;

    public CrossCurrencyTransferPoster(TransactionService transactionService,
                                       AccountRepository accountRepository,
                                       PendingFxTransferRepository pendingFxTransferRepository,
                                       FxClearingAccountsProperties clearingAccounts) {
        this.transactionService = transactionService;
        this.accountRepository = accountRepository;
        this.pendingFxTransferRepository = pendingFxTransferRepository;
        this.clearingAccounts = clearingAccounts;
    }

    @Transactional
    public void postLeg1(UUID pendingTransferId) {
        PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
        String sourceCurrency = accountRepository.findByAccountRef(transfer.getSourceAccountRef())
                .orElseThrow().getCurrency();
        String clearingAccountRef = clearingAccounts.get(sourceCurrency);

        TransactionResponse leg1 = transactionService.postTransaction(
                new CreateTransactionRequest(transfer.getSourceAccountRef(), clearingAccountRef,
                        transfer.getSourceAmountMinor(), sourceCurrency, "fx-transfer-leg1"),
                "fx-leg1-" + pendingTransferId);

        transfer.markLeg1Posted(leg1.transactionId());
        pendingFxTransferRepository.save(transfer);
    }

    @Transactional
    public void postLeg2(UUID pendingTransferId) {
        PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
        String destCurrency = accountRepository.findByAccountRef(transfer.getDestAccountRef())
                .orElseThrow().getCurrency();
        String clearingAccountRef = clearingAccounts.get(destCurrency);

        TransactionResponse leg2 = transactionService.postTransaction(
                new CreateTransactionRequest(clearingAccountRef, transfer.getDestAccountRef(),
                        transfer.getDestAmountMinor(), destCurrency, "fx-transfer-leg2"),
                "fx-leg2-" + pendingTransferId);

        transfer.markLeg2Posted(leg2.transactionId());
        pendingFxTransferRepository.save(transfer);
    }

    @Transactional
    public void compensate(UUID pendingTransferId) {
        PendingFxTransfer transfer = pendingFxTransferRepository.findById(pendingTransferId).orElseThrow();
        if (transfer.getStatus() != PendingFxTransferStatus.COMPENSATING) {
            transfer.markCompensating();
            pendingFxTransferRepository.save(transfer);
        }

        String sourceCurrency = accountRepository.findByAccountRef(transfer.getSourceAccountRef())
                .orElseThrow().getCurrency();
        String clearingAccountRef = clearingAccounts.get(sourceCurrency);

        TransactionResponse reversal = transactionService.postTransaction(
                new CreateTransactionRequest(clearingAccountRef, transfer.getSourceAccountRef(),
                        transfer.getSourceAmountMinor(), sourceCurrency, "fx-transfer-compensation"),
                "fx-compensate-" + pendingTransferId);

        transfer.markCompensated(reversal.transactionId());
        pendingFxTransferRepository.save(transfer);
    }
}
