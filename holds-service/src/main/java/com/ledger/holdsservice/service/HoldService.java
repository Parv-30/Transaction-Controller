package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.AvailableBalanceResponse;
import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class HoldService {

    private final HoldPoster holdPoster;
    private final HoldRepository holdRepository;
    private final LedgerTransactionClient ledgerTransactionClient;
    private final AccountBalanceCacheRepository accountBalanceCacheRepository;

    public HoldService(HoldPoster holdPoster, HoldRepository holdRepository,
                        LedgerTransactionClient ledgerTransactionClient,
                        AccountBalanceCacheRepository accountBalanceCacheRepository) {
        this.holdPoster = holdPoster;
        this.holdRepository = holdRepository;
        this.ledgerTransactionClient = ledgerTransactionClient;
        this.accountBalanceCacheRepository = accountBalanceCacheRepository;
    }

    public HoldResponse createHold(CreateHoldRequest request, String idempotencyKey) {
        var existing = holdRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return holdPoster.toResponse(existing.get(), true);
        }

        try {
            return holdPoster.createInTransaction(request, idempotencyKey);
        } catch (DataIntegrityViolationException raceLost) {
            Hold winner = holdRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raceLost);
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
}
