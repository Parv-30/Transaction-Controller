package com.ledger.holdsservice.service;

import com.ledger.holdsservice.domain.HoldStatus;
import com.ledger.holdsservice.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class HoldExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweep.class);

    private final HoldRepository holdRepository;
    private final HoldPoster holdPoster;

    public HoldExpirySweep(HoldRepository holdRepository, HoldPoster holdPoster) {
        this.holdRepository = holdRepository;
        this.holdPoster = holdPoster;
    }

    @Scheduled(fixedDelayString = "${holds.expiry-sweep.interval-ms:60000}")
    public void run() {
        var expiredHolds = holdRepository.findByStatusAndExpiresAtBefore(HoldStatus.ACTIVE, Instant.now());
        for (var hold : expiredHolds) {
            try {
                holdPoster.expireHoldInTransaction(hold.getId());
            } catch (Exception e) {
                log.error("Failed to expire hold {}: {}", hold.getId(), e.getMessage(), e);
                // continue sweeping the rest; a failed hold is picked up again next run
            }
        }
    }
}
