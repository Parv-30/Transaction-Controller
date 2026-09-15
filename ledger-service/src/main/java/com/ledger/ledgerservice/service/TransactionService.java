package com.ledger.ledgerservice.service;

import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.EntryResponse;
import com.ledger.ledgerservice.api.dto.TransactionDetailResponse;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.api.dto.TransactionSummaryResponse;
import com.ledger.ledgerservice.domain.Direction;
import com.ledger.ledgerservice.domain.Entry;
import com.ledger.ledgerservice.domain.Transaction;
import com.ledger.ledgerservice.domain.TransactionStatus;
import com.ledger.ledgerservice.holds.HoldsServiceUnavailableException;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final IdempotencyHasher idempotencyHasher;
    private final TransactionPoster transactionPoster;
    private final MeterRegistry meterRegistry;
    private final EntryRepository entryRepository;
    private final AccountRepository accountRepository;

    public TransactionService(TransactionRepository transactionRepository,
                               IdempotencyHasher idempotencyHasher,
                               TransactionPoster transactionPoster,
                               MeterRegistry meterRegistry,
                               EntryRepository entryRepository,
                               AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.idempotencyHasher = idempotencyHasher;
        this.transactionPoster = transactionPoster;
        this.meterRegistry = meterRegistry;
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
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
        } catch (AccountNotFoundException | InsufficientFundsException | AccountNotActiveException
                 | IdempotencyConflictException | HoldsServiceUnavailableException genuineFailure) {
            // Real, user-visible transaction failures -- as opposed to the idempotency-race
            // replay handled above, which is expected/handled behavior and must never count
            // as a failure. This single point sees every genuine failure on every call path
            // (direct Java call or via TransactionController), so it is also the sole place
            // this counter is incremented -- do not duplicate it in ApiExceptionHandler.
            meterRegistry.counter("ledger.transaction.failed",
                    "exception", genuineFailure.getClass().getSimpleName()).increment();
            throw genuineFailure;
        }
    }

    /**
     * Deliberately NOT @Transactional, for the same reason as {@link #postTransaction}: it
     * calls {@code postTransaction}, which owns its own transaction (and race-recovery logic
     * that depends on running in its own transaction -- see that method's Javadoc). If this
     * method were @Transactional, that call would join this method's transaction instead
     * (Spring's default REQUIRED propagation), silently defeating postTransaction's isolation
     * and risking an UnexpectedRollbackException on commit even when postTransaction's own
     * race handling has already resolved things correctly. So this method does its own
     * pre-checks as plain reads, delegates the actual compensating posting to postTransaction
     * (which is safe to call here since neither method is @Transactional, so there's no
     * self-invocation bypass), and then hands the remaining bookkeeping -- linking the
     * reversal back to the original and marking the original REVERSED -- to
     * {@link TransactionPoster#finalizeReversal}, its own small transactional boundary on the
     * bean that legitimately owns @Transactional methods in this codebase.
     *
     * <p>Order of checks matters for idempotent-retry semantics: checking already-reversed
     * before is-a-reversal (though a transaction can never be both) mirrors the concurrent-
     * retry scenario this task's tests cover -- once an original has committed to REVERSED, a
     * second top-level call must reject via {@link TransactionAlreadyReversedException} rather
     * than silently returning the existing reversal, since the deterministic idempotency key on
     * the underlying postTransaction call already protects against genuinely concurrent
     * in-flight requests.
     */
    public TransactionSummaryResponse reverseTransaction(UUID transactionId) {
        Transaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        if (original.getReversalOfTransactionId() != null) {
            throw new CannotReverseAReversalException(transactionId);
        }
        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new TransactionAlreadyReversedException(transactionId);
        }

        List<Entry> entries = entryRepository.findByTransactionId(transactionId);
        Entry originalDebitEntry = entries.stream().filter(e -> e.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
        Entry originalCreditEntry = entries.stream().filter(e -> e.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
        String originalDebitAccountRef = accountRepository.findById(originalDebitEntry.getAccountId()).orElseThrow().getAccountRef();
        String originalCreditAccountRef = accountRepository.findById(originalCreditEntry.getAccountId()).orElseThrow().getAccountRef();

        CreateTransactionRequest reversalRequest = new CreateTransactionRequest(
                originalCreditAccountRef, originalDebitAccountRef, originalDebitEntry.getAmountMinor(),
                originalDebitEntry.getCurrency(), "Reversal of transaction " + transactionId, "REVERSAL");
        String reversalIdempotencyKey = "admin-reversal-" + transactionId;

        TransactionResponse reversalResponse = postTransaction(reversalRequest, reversalIdempotencyKey);

        transactionPoster.finalizeReversal(reversalResponse.transactionId(), transactionId);

        Transaction reversalTransaction = transactionRepository.findById(reversalResponse.transactionId()).orElseThrow();
        return toSummary(reversalTransaction);
    }

    public List<TransactionSummaryResponse> listTransactions(String accountRefFilter, String statusFilter,
                                                                Instant since, Instant until) {
        List<Transaction> transactions;
        if (accountRefFilter != null) {
            transactions = transactionRepository.findByAccountRef(accountRefFilter);
        } else {
            transactions = transactionRepository.findAll();
        }
        return transactions.stream()
                .filter(t -> statusFilter == null || t.getStatus().name().equals(statusFilter))
                .filter(t -> since == null || !t.getCreatedAt().isBefore(since))
                .filter(t -> until == null || !t.getCreatedAt().isAfter(until))
                .map(this::toSummary)
                .toList();
    }

    public TransactionDetailResponse getTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        List<Entry> entries = entryRepository.findByTransactionId(transactionId);
        List<EntryResponse> entryResponses = entries.stream().map(this::toEntryResponse).toList();
        return new TransactionDetailResponse(transaction.getId(), transaction.getStatus().name(),
                transaction.getTransactionType(), transaction.getDescription(), transaction.getCreatedAt(),
                transaction.getReversalOfTransactionId(), entryResponses);
    }

    private TransactionSummaryResponse toSummary(Transaction transaction) {
        List<Entry> entries = entryRepository.findByTransactionId(transaction.getId());
        Entry debitEntry = entries.stream().filter(e -> e.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
        Entry creditEntry = entries.stream().filter(e -> e.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
        String debitAccountRef = accountRepository.findById(debitEntry.getAccountId()).orElseThrow().getAccountRef();
        String creditAccountRef = accountRepository.findById(creditEntry.getAccountId()).orElseThrow().getAccountRef();
        return new TransactionSummaryResponse(transaction.getId(), transaction.getStatus().name(),
                transaction.getTransactionType(), debitAccountRef, creditAccountRef,
                debitEntry.getAmountMinor(), debitEntry.getCurrency(), transaction.getDescription(),
                transaction.getCreatedAt(), transaction.getReversalOfTransactionId());
    }

    private EntryResponse toEntryResponse(Entry entry) {
        String accountRef = accountRepository.findById(entry.getAccountId()).orElseThrow().getAccountRef();
        return new EntryResponse(entry.getAccountId(), accountRef, entry.getDirection().name(),
                entry.getAmountMinor(), entry.getCurrency());
    }
}
