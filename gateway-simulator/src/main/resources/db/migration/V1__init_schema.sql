CREATE TABLE external_deposits (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_reference  VARCHAR(255) NOT NULL UNIQUE,
    account_ref         VARCHAR(128) NOT NULL,
    amount_minor        BIGINT NOT NULL CHECK (amount_minor > 0),
    currency            CHAR(3) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'RECEIVED'
                          CHECK (status IN ('RECEIVED','CREDITED','REJECTED')),
    transaction_id      UUID,
    raw_payload         JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE external_withdrawals (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_transaction_id   UUID NOT NULL UNIQUE,
    account_ref             VARCHAR(128) NOT NULL,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency                CHAR(3) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED'
                              CHECK (status IN ('SUBMITTED','CONFIRMED','FAILED','TIMED_OUT','REVERSED')),
    submitted_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,
    reversal_transaction_id UUID
);
CREATE INDEX idx_external_withdrawals_status_submitted_at
    ON external_withdrawals(status, submitted_at);

CREATE TABLE webhook_dedup (
    external_reference  VARCHAR(255) PRIMARY KEY,
    webhook_count       INT NOT NULL DEFAULT 1,
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_gateway_sim_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
