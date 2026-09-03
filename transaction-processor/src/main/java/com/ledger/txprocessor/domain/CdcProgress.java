package com.ledger.txprocessor.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cdc_progress")
public class CdcProgress {

    @Id
    private short id = 1;

    @Column(name = "last_lsn")
    private String lastLsn;

    @Column(name = "last_event_captured_at")
    private Instant lastEventCapturedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CdcProgress() {
        // JPA
    }

    public CdcProgress(String lastLsn, Instant lastEventCapturedAt) {
        this.id = 1;
        this.lastLsn = lastLsn;
        this.lastEventCapturedAt = lastEventCapturedAt;
        this.lastHeartbeatAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public short getId() { return id; }
    public String getLastLsn() { return lastLsn; }
    public Instant getLastEventCapturedAt() { return lastEventCapturedAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
