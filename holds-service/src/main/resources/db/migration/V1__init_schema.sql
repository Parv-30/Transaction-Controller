CREATE TABLE holds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_ref             VARCHAR(128) NOT NULL,
    destination_account_ref VARCHAR(128) NOT NULL,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency                CHAR(3) NOT NULL,
    status                  VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
    idempotency_key         VARCHAR(255) NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    captured_amount_minor   BIGINT NOT NULL DEFAULT 0,
    created_transaction_id  UUID,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_holds_idem_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_holds_account_status ON holds(account_ref, status);

CREATE TABLE account_balance_cache (
    account_ref             VARCHAR(128) PRIMARY KEY,
    posted_balance_minor    BIGINT NOT NULL DEFAULT 0,
    held_balance_minor      BIGINT NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_holds_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
