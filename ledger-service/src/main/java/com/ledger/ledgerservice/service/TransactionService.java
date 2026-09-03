package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.domain.Transaction;
import com.ledger.ledgerservice.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final IdempotencyHasher idempotencyHasher;
    private final TransactionPoster transactionPoster;

    public TransactionService(TransactionRepository transactionRepository,
                               IdempotencyHasher idempotencyHasher,
                               TransactionPoster transactionPoster) {
        this.transactionRepository = transactionRepository;
        this.idempotencyHasher = idempotencyHasher;
        this.transactionPoster = transactionPoster;
    }

    /**
     * Public entry point. Deliberately NOT @Transactional itself: it delegates the atomic
     * DB work to {@link TransactionPoster#postInTransaction}, a separate Spring bean, so
     * that call goes through Spring's real transactional proxy (a same-class/self-invocation
     * call would silently skip @Transactional entirely). Staying outside that transaction
     * boundary here also lets this method catch a lost-race exception AFTER Spring has
     * finished rolling the failed transaction back, then perform the idempotency-key replay
     * lookup in a brand new transaction. See {@link TransactionPoster#postInTransaction} for
     * why this split is required.
     */
    public TransactionResponse postTransaction(CreateTransactionRequest request, String idempotencyKey) {
        String requestHash = idempotencyHasher.hash(
                request.debitAccountRef(), request.creditAccountRef(),
                request.amountMinor(), request.currency());
        try {
            return transactionPoster.postInTransaction(request, idempotencyKey, requestHash);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException raceLost) {
            // Lost a race to another concurrent request with the same Idempotency-Key.
            // The winner has, by definition, already committed by the time either of these
            // exceptions surfaces here (both only happen after Postgres's row/unique-key
            // lock releases, which only happens on commit or rollback of the winning
            // transaction) -- so a fresh, separate transaction now sees its committed row.
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            return transactionPoster.replayOrConflict(winner, requestHash, request);
        }
    }
}
