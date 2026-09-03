CREATE TABLE processed_events (
    outbox_event_id   UUID PRIMARY KEY,
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(64) NOT NULL,
    captured_at       TIMESTAMPTZ NOT NULL,
    published_at      TIMESTAMPTZ,
    consumed_at       TIMESTAMPTZ,
    status            VARCHAR(24) NOT NULL DEFAULT 'CAPTURED'
                        CHECK (status IN ('CAPTURED','PUBLISHED','CONSUMED','DUPLICATE_IGNORED','PUBLISH_FAILED')),
    delivery_count    INT NOT NULL DEFAULT 1,
    payload           JSONB,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_processed_events_aggregate ON processed_events(aggregate_id);
CREATE INDEX idx_processed_events_status ON processed_events(status);

CREATE TABLE cdc_progress (
    id                      SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_lsn                VARCHAR(64),
    last_event_captured_at  TIMESTAMPTZ,
    last_heartbeat_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
