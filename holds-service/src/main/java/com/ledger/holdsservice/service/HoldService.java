package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.AvailableBalanceResponse;
import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HeldBalanceResponse;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.domain.HoldStatus;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.security.CallerContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class HoldService {

    private final HoldPoster holdPoster;
    private final HoldRepository holdRepository;
    private final LedgerTransactionClient ledgerTransactionClient;
    private final AccountBalanceCacheRepository accountBalanceCacheRepository;
    private final MeterRegistry meterRegistry;

    public HoldService(HoldPoster holdPoster, HoldRepository holdRepository,
                        LedgerTransactionClient ledgerTransactionClient,
                        AccountBalanceCacheRepository accountBalanceCacheRepository,
                        MeterRegistry meterRegistry) {
        this.holdPoster = holdPoster;
        this.holdRepository = holdRepository;
        this.ledgerTransactionClient = ledgerTransactionClient;
        this.accountBalanceCacheRepository = accountBalanceCacheRepository;
        this.meterRegistry = meterRegistry;
    }

    public HoldResponse createHold(CreateHoldRequest request, String idempotencyKey) {
        var existing = holdRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            meterRegistry.counter("holds.idempotency.replay").increment();
            return holdPoster.toResponse(existing.get(), true);
        }

        try {
            return holdPoster.createInTransaction(request, idempotencyKey);
        } catch (DataIntegrityViolationException raceLost) {
            Hold winner = holdRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            meterRegistry.counter("holds.idempotency.replay").increment();
            return holdPoster.toResponse(winner, true);
        }
    }

    public HoldResponse release(UUID holdId) {
        return holdPoster.releaseInTransaction(holdId);
    }

    public HoldResponse getHold(UUID holdId) {
        Hold hold = holdRepository.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return holdPoster.toResponse(hold, false);
    }

    /**
     * Backs {@code GET /holds?accountRef=&status=}. An {@code admin}-role caller may omit
     * {@code accountRef} for the full list; a non-admin caller must supply a non-blank
     * {@code accountRef} and is scoped to holds matching it (see the design spec, Section 4) --
     * a non-admin caller may never receive the unscoped "all holds" list.
     */
    @Transactional(readOnly = true)
    public List<HoldResponse> listHolds(String accountRefFilter, String statusFilter, CallerContext caller) {
        if (!caller.isAdmin() && (accountRefFilter == null || accountRefFilter.isBlank())) {
            throw new AccountRefRequiredForNonAdminException();
        }
        HoldStatus status = statusFilter != null ? HoldStatus.valueOf(statusFilter) : null;
        List<Hold> holds = holdRepository.search(accountRefFilter, status);
        return holds.stream().map(hold -> holdPoster.toResponse(hold, false)).toList();
    }

    public HoldResponse capture(UUID holdId, long amountMinor) {
        var hold = holdPoster.validateCaptureRequest(holdId, amountMinor);

        var result = ledgerTransactionClient.postTransaction(
                hold.getAccountRef(), hold.getDestinationAccountRef(), amountMinor,
                hold.getCurrency(), "hold capture " + holdId,
                "hold-capture-" + holdId);

        return holdPoster.completeCaptureInTransaction(holdId, amountMinor, result.transactionId());
    }

    /**
     * Read-only lookup for {@code GET /accounts/{accountRef}/available-balance}. An account with
     * no cache row yet (never held funds, never had a ledger.transaction.posted event consumed
     * for it) is treated as a zero-balance account rather than "not found" — the same convention
     * used by {@link HoldPoster#createInTransaction} and
     * {@code LedgerTransactionPostedApplier#upsertPostedBalance}, both of which synthesize a
     * fresh {@code AccountBalanceCache(accountRef, 0, 0)} instead of raising an error when no row
     * exists yet. No row is persisted here since this is a plain read.
     */
    @Transactional(readOnly = true)
    public AvailableBalanceResponse getAvailableBalance(String accountRef) {
        AccountBalanceCache cache = accountBalanceCacheRepository.findById(accountRef)
                .orElseGet(() -> new AccountBalanceCache(accountRef, 0L, 0L));
        return new AvailableBalanceResponse(accountRef, cache.getPostedBalanceMinor(),
                cache.getHeldBalanceMinor(), cache.availableBalanceMinor());
    }

    /**
     * Read-only lookup for {@code GET /accounts/{accountRef}/held-balance}. An account with
     * no cache row yet (never held funds, never had a ledger.transaction.posted event consumed
     * for it) is treated as a zero-balance account rather than "not found" — the same convention
     * used by {@link HoldPoster#createInTransaction} and
     * {@code LedgerTransactionPostedApplier#upsertPostedBalance}, both of which synthesize a
     * fresh {@code AccountBalanceCache(accountRef, 0, 0)} instead of raising an error when no row
     * exists yet. No row is persisted here since this is a plain read.
     */
    @Transactional(readOnly = true)
    public HeldBalanceResponse getHeldBalance(String accountRef) {
        long heldBalanceMinor = accountBalanceCacheRepository.findById(accountRef)
                .map(AccountBalanceCache::getHeldBalanceMinor)
                .orElse(0L);
        return new HeldBalanceResponse(accountRef, heldBalanceMinor);
    }
}
