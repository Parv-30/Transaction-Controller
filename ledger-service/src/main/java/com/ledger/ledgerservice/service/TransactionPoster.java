package com.ledger.ledgerservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.ledgerservice.api.dto.CreateTransactionRequest;
import com.ledger.ledgerservice.api.dto.TransactionResponse;
import com.ledger.ledgerservice.domain.*;
import com.ledger.ledgerservice.holds.HoldsServiceClient;
import com.ledger.ledgerservice.repository.AccountRepository;
import com.ledger.ledgerservice.repository.EntryRepository;
import com.ledger.ledgerservice.repository.OutboxRepository;
import com.ledger.ledgerservice.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the single @Transactional boundary for posting a transaction. Split out of
 * {@link TransactionService} into its own Spring-managed bean so that {@code @Transactional}
 * is honored via Spring's proxy: a same-class (self-invocation) call to an
 * {@code @Transactional} method silently bypasses the proxy and runs with no transaction at
 * all, which is exactly the bug this split avoids.
 */
@Service
public class TransactionPoster {

    /**
     * Account-ref prefix marking the platform's internal FX clearing accounts, which are allowed
     * to be debited below zero (see the insufficient-funds check below). Because that is a
     * genuine privilege, {@link AccountService} rejects client-supplied account refs carrying
     * this prefix -- the two must stay in lockstep, hence the shared constant.
     */
    public static final String FX_CLEARING_ACCOUNT_REF_PREFIX = "fx-clearing-";

    /**
     * Account-ref prefixes exempt from the synchronous Holds Service held-balance check (see
     * below): FX clearing accounts (see {@link #FX_CLEARING_ACCOUNT_REF_PREFIX}), plus
     * external-clearing accounts, which the Gateway Simulator posts deposit/withdrawal/reversal
     * entries against as an internal suspense account and which likewise should not require an
     * active Holds Service round-trip.
     */
    private static final List<String> CLEARING_ACCOUNT_REF_PREFIXES =
            List.of(FX_CLEARING_ACCOUNT_REF_PREFIX, "external-clearing-");

    public static boolean isClearingAccount(String accountRef) {
        return CLEARING_ACCOUNT_REF_PREFIXES.stream().anyMatch(accountRef::startsWith);
    }

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final EntryRepository entryRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final HoldsServiceClient holdsServiceClient;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public TransactionPoster(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              EntryRepository entryRepository,
                              OutboxRepository outboxRepository,
                              ObjectMapper objectMapper,
                              HoldsServiceClient holdsServiceClient,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.holdsServiceClient = holdsServiceClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * The atomic orchestration: idempotency pre-check, ordered account locking,
     * insufficient-funds check, transaction+entries insert, balance updates, and outbox row
     * insert all happen in this single @Transactional boundary -- they commit or roll back
     * together.
     *
     * <p>Two distinct races can make this method throw instead of returning normally, and
     * both MUST be caught by the caller ({@link TransactionService#postTransaction}) outside
     * this transaction, not with a try/catch in here:
     * <ul>
     *   <li>{@link org.springframework.dao.DataIntegrityViolationException} from the unique
     *       constraint on {@code transactions.idempotency_key} when two requests both pass
     *       the pre-check's {@code findByIdempotencyKey} miss and then both try to
     *       insert.</li>
     *   <li>{@link org.springframework.orm.ObjectOptimisticLockingFailureException} from
     *       {@code accountRepository.lockAccountsForUpdate}: Hibernate's query-level
     *       {@code @Lock(PESSIMISTIC_WRITE)} on the {@code @Version}-annotated
     *       {@code Account} entity performs a version consistency check when the
     *       {@code SELECT ... FOR UPDATE} unblocks after waiting behind another
     *       transaction's row lock. If that other transaction was the winner of this same
     *       idempotency race and bumped the account's version while this thread waited,
     *       Hibernate raises this exception here even though this thread never read a
     *       stale copy -- it simply waited behind the winner's lock.</li>
     * </ul>
     * Either exception marks the current Spring transaction rollback-only; any further use
     * of the same transaction (e.g. another repository call to look up the winner) throws
     * {@code UnexpectedRollbackException} on commit. That is why the replay/conflict lookup
     * for the loser must run in a new transaction after this one has finished rolling back,
     * which is why {@link TransactionService#postTransaction} -- not this method -- owns
     * that recovery logic.
     */
    @Transactional
    public TransactionResponse postInTransaction(CreateTransactionRequest request,
                                                   String idempotencyKey, String requestHash) {
        var sample = io.micrometer.core.instrument.Timer.start(meterRegistry);
        try {
            return doPostInTransaction(request, idempotencyKey, requestHash);
        } finally {
            sample.stop(meterRegistry.timer("ledger.transaction.latency"));
        }
    }

    private TransactionResponse doPostInTransaction(CreateTransactionRequest request,
                                                      String idempotencyKey, String requestHash) {
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash, request);
        }

        Account debitAccountRef = accountRepository.findByAccountRef(request.debitAccountRef())
                .orElseThrow(() -> new AccountNotFoundException(request.debitAccountRef()));
        Account creditAccountRef = accountRepository.findByAccountRef(request.creditAccountRef())
                .orElseThrow(() -> new AccountNotFoundException(request.creditAccountRef()));

        List<UUID> idsToLock = List.of(debitAccountRef.getId(), creditAccountRef.getId());
        List<Account> locked = accountRepository.lockAccountsForUpdate(idsToLock);
        Map<UUID, Account> byId = locked.stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, a -> a));
        Account debitAccount = byId.get(debitAccountRef.getId());
        Account creditAccount = byId.get(creditAccountRef.getId());

        if (debitAccount.getStatus() != AccountStatus.ACTIVE || creditAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException("One or both accounts are not ACTIVE");
        }
        // Clearing accounts are the platform's internal netting/suspense mechanism, not real
        // funded accounts: e.g. leg 2 of a cross-currency transfer debits the destination-
        // currency FX clearing account, and the Gateway Simulator debits/credits an
        // external-clearing suspense account when posting deposits/withdrawals/reversals. Both
        // are expected to run negative by convention. This bypass is narrowly scoped to the
        // known clearing-account ref prefixes; ordinary accounts keep the check unchanged.
        // AccountService refuses to create accounts under the fx-clearing- prefix, so it cannot
        // be claimed by a client via POST /accounts.
        boolean debitAccountAllowsNegativeBalance = isClearingAccount(debitAccount.getAccountRef());
        if (!debitAccountAllowsNegativeBalance) {
            long heldBalanceMinor = holdsServiceClient.getHeldBalance(debitAccount.getAccountRef());
            long availableBalanceMinor = debitAccount.getBalanceMinor() - heldBalanceMinor;
            if (request.amountMinor() > availableBalanceMinor) {
                throw new InsufficientFundsException(debitAccount.getAccountRef());
            }
        }

        UUID transactionId = UUID.randomUUID();
        Transaction transaction = new Transaction(transactionId, idempotencyKey, TransactionStatus.POSTED,
                request.transactionType(), request.description(), requestHash);

        transactionRepository.saveAndFlush(transaction);

        Entry debitEntry = new Entry(UUID.randomUUID(), transactionId, debitAccount.getId(),
                Direction.DEBIT, request.amountMinor(), request.currency());
        Entry creditEntry = new Entry(UUID.randomUUID(), transactionId, creditAccount.getId(),
                Direction.CREDIT, request.amountMinor(), request.currency());
        entryRepository.save(debitEntry);
        entryRepository.save(creditEntry);

        debitAccount.debit(request.amountMinor());
        creditAccount.credit(request.amountMinor());
        accountRepository.save(debitAccount);
        accountRepository.save(creditAccount);

        String payload = buildOutboxPayload(transaction, debitAccount, creditAccount, request);
        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), "TRANSACTION", transactionId,
                "TRANSACTION_POSTED", payload));

        return new TransactionResponse(transactionId, transaction.getStatus().name(),
                request.debitAccountRef(), request.creditAccountRef(),
                request.amountMinor(), request.currency(), false);
    }

    /**
     * Not transactional itself: called both from within {@link #postInTransaction}'s
     * transaction (fast-path replay) and from {@link TransactionService#postTransaction}
     * after a lost race, outside any transaction (a plain read via
     * {@code transactionRepository.findByIdempotencyKey}, already committed, needs none).
     */
    TransactionResponse replayOrConflict(Transaction existing, String requestHash,
                                          CreateTransactionRequest request) {
        if (!existing.getRequestPayloadHash().equals(requestHash)) {
            throw new IdempotencyConflictException(existing.getIdempotencyKey());
        }
        meterRegistry.counter("ledger.idempotency.replay").increment();
        return new TransactionResponse(existing.getId(), existing.getStatus().name(),
                request.debitAccountRef(), request.creditAccountRef(),
                request.amountMinor(), request.currency(), true);
    }

    private String buildOutboxPayload(Transaction transaction, Account debitAccount,
                                       Account creditAccount, CreateTransactionRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "transactionId", transaction.getId().toString(),
                    "idempotencyKey", transaction.getIdempotencyKey(),
                    "debitAccountRef", debitAccount.getAccountRef(),
                    "creditAccountRef", creditAccount.getAccountRef(),
                    "amountMinor", request.amountMinor(),
                    "currency", request.currency(),
                    "debitAccountBalanceAfter", debitAccount.getBalanceMinor(),
                    "creditAccountBalanceAfter", creditAccount.getBalanceMinor(),
                    "occurredAt", Instant.now().toString(),
                    "transactionType", transaction.getTransactionType()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
