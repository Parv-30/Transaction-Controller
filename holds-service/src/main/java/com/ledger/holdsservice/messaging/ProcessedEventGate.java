package com.ledger.holdsservice.messaging;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Dedup gate for RabbitMQ's at-least-once delivery, backed by processed_events' primary-key
 * uniqueness (a DB constraint, not a Java-side read-then-write) — the same discipline V1
 * established for Transaction Processor's own dedup gate.
 */
@Component
public class ProcessedEventGate {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean markProcessedIfNew(UUID eventId) {
        try {
            jdbcTemplate.update("INSERT INTO processed_events (event_id) VALUES (?)", eventId);
            return true;
        } catch (DataIntegrityViolationException duplicateKey) {
            return false;
        }
    }
}
