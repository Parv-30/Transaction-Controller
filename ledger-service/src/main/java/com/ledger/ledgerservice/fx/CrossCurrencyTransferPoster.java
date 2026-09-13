package com.ledger.ledgerservice.fx;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.service.TransactionService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Holds the individual steps of the cross-currency saga. Each public method here is called by
 * {@link CrossCurrencyTransferService} (a different bean) or directly by the recovery sweep --
 * never self-invoked -- per this codebase's established self-invocation-avoidance pattern.
 *
 * <p>Every step posts its ledger transaction under a deterministic idempotency key derived from
 * the pending-transfer id ({@code fx-leg1-<id>}, {@code fx-leg2-<id>}, {@code fx-compensate-<id>})
 * so that a retry of the same step -- whether from this saga or from the crash-recovery sweep --
 * replays the already-posted transaction instead of double-posting money.
 *
 * <p><strong>Why these methods are deliberately NOT {@code @Transactional}.</strong> They mirror
 * {@link TransactionService#postTransaction} exactly, and for the same reason. That method is
 * itself non-transactional on purpose: it calls
 * {@link com.ledger.ledgerservice.service.TransactionPoster#postInTransaction} (which owns the
 * one real transaction boundary) and, if two concurrent requests race on the same idempotency
 * key, catches the loser's exception and performs the replay lookup in a <em>brand new</em>
 * transaction, after Spring has finished rolling the failed one back.
 *
 * <p>If a step here held its own {@code @Transactional} boundary, the nested
 * {@code postInTransaction} call would join that outer transaction (default {@code REQUIRED})
 * rather than owning its own. The race exception would then mark the <em>shared</em> transaction
 * rollback-only, so {@code postTransaction}'s recovery lookup could not run in a fresh
 * transaction and the whole step would die with {@code UnexpectedRollbackException} instead of
 * correctly replaying the winner's already-committed result. That race is exactly what the
 * crash-recovery sweep can provoke, since it retries these same steps by id concurrently with
 * an in-flight saga.
 *
 * <p>Nothing is lost by dropping the annotation: the atomic money movement is entirely inside
 * {@code postInTransaction}'s own boundary, and each step's remaining work is a read plus a
 * single {@code save()} of the {@link PendingFxTransfer} row, which Spring Data JPA already
 * wraps in its own transaction per call. The status update is intentionally sequenced
 * <em>after</em> the posting returns, so a crash between the two leaves the row behind the
 * ledger rather than ahead of it -- the direction the sweep can safely repair, because
 * re-running the step replays the transaction under the same deterministic key.
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
