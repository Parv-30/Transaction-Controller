package com.ledger.holdsservice.service;

import com.ledger.holdsservice.api.dto.CreateHoldRequest;
import com.ledger.holdsservice.api.dto.HoldResponse;
import com.ledger.holdsservice.domain.Hold;
import com.ledger.holdsservice.repository.HoldRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class HoldService {

    private final HoldPoster holdPoster;
    private final HoldRepository holdRepository;

    public HoldService(HoldPoster holdPoster, HoldRepository holdRepository) {
        this.holdPoster = holdPoster;
        this.holdRepository = holdRepository;
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
}
