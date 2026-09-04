package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.AccountBalanceCache;
import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.domain.HoldStatus;
import com.ledger.holdsservice.repository.AccountBalanceCacheRepository;
import com.ledger.holdsservice.repository.HoldRepository;
import com.ledger.holdsservice.repository.OutboxRepository;
import com.ledger.holdsservice.domain.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class HoldPoster {

    private final HoldRepository holdRepository;
    private final AccountBalanceCacheRepository accountBalanceCacheRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public HoldPoster(HoldRepository holdRepository,
                       AccountBalanceCacheRepository accountBalanceCacheRepository,
                       OutboxRepository outboxRepository,
                       ObjectMapper objectMapper) {
        this.holdRepository = holdRepository;
        this.accountBalanceCacheRepository = accountBalanceCacheRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HoldResponse createInTransaction(CreateHoldRequest request, String idempotencyKey) {
        AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(request.accountRef())
                .orElseGet(() -> {
                    AccountBalanceCache fresh = new AccountBalanceCache(request.accountRef(), 0L, 0L);
                    accountBalanceCacheRepository.save(fresh);
                    return accountBalanceCacheRepository.lockByAccountRef(request.accountRef()).orElseThrow();
                });

        if (cache.availableBalanceMinor() < request.amountMinor()) {
            throw new InsufficientAvailableBalanceException(request.accountRef());
        }

        UUID holdId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(request.expiresInSeconds());
        Hold hold = new Hold(holdId, request.accountRef(), request.destinationAccountRef(),
                request.amountMinor(), request.currency(), HoldStatus.ACTIVE, idempotencyKey, expiresAt);

        holdRepository.saveAndFlush(hold);

        cache.hold(request.amountMinor());
        accountBalanceCacheRepository.save(cache);

        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.created",
                writePayload(hold)));

        return toResponse(hold, false);
    }

    @Transactional
    public HoldResponse releaseInTransaction(UUID holdId) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            return toResponse(hold, false);
        }

        AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(hold.getAccountRef())
                .orElseThrow(() -> new IllegalStateException("Missing balance cache for " + hold.getAccountRef()));
        cache.releaseHeld(hold.remainingAmountMinor());
        accountBalanceCacheRepository.save(cache);

        hold.markReleased();
        holdRepository.save(hold);

        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.released",
                writePayload(hold)));

        return toResponse(hold, false);
    }

    /**
     * Step 1 of capture: validates the hold is ACTIVE and the requested amount doesn't exceed
     * what remains, but does NOT call Transaction Processor and does NOT mutate anything yet —
     * kept in its own short transaction, separate from the blocking HTTP call in
     * HoldService.capture, per the lesson documented above this task.
     */
    @Transactional(readOnly = true)
    public Hold validateCaptureRequest(UUID holdId, long amountMinor) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Hold " + holdId + " is not ACTIVE (status: " + hold.getStatus() + ")");
        }
        if (amountMinor > hold.remainingAmountMinor()) {
            throw new CaptureExceedsRemainingAmountException(holdId, amountMinor, hold.remainingAmountMinor());
        }
        return hold;
    }

    /**
     * Step 2 of capture: called AFTER Transaction Processor has already durably posted the
     * transaction (HoldService.capture calls this only once postTransaction succeeds). Marks the
     * hold CAPTURED, releases any uncaptured remainder from the balance cache's held_balance
     * (the funds are either now posted via the real ledger transaction, or freed back up — either
     * way they must leave held_balance), and records the outbox event, all in one fresh
     * transaction.
     */
    @Transactional
    public HoldResponse completeCaptureInTransaction(UUID holdId, long capturedAmountMinor, UUID transactionId) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));

        long uncapturedRemainder = hold.remainingAmountMinor() - capturedAmountMinor;
        hold.markCaptured(capturedAmountMinor, transactionId);
        holdRepository.save(hold);

        AccountBalanceCache cache = accountBalanceCacheRepository.lockByAccountRef(hold.getAccountRef())
                .orElseThrow(() -> new IllegalStateException("Missing balance cache for " + hold.getAccountRef()));
        cache.releaseHeld(capturedAmountMinor + uncapturedRemainder);
        accountBalanceCacheRepository.save(cache);

        outboxRepository.save(new OutboxEvent(UUID.randomUUID(), holdId, "hold.captured", writePayload(hold)));

        return toResponse(hold, false);
    }

    HoldResponse toResponse(Hold hold, boolean replay) {
        return new HoldResponse(hold.getId(), hold.getStatus().name(), hold.getAccountRef(),
                hold.getDestinationAccountRef(), hold.getAmountMinor(), hold.getCapturedAmountMinor(),
                hold.getCurrency(), hold.getExpiresAt(), replay);
    }

    private String writePayload(Hold hold) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "holdId", hold.getId().toString(),
                    "accountRef", hold.getAccountRef(),
                    "destinationAccountRef", hold.getDestinationAccountRef(),
                    "amountMinor", hold.getAmountMinor(),
                    "status", hold.getStatus().name(),
                    "occurredAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
