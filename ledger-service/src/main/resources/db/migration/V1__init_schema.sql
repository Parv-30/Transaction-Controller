CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_ref     VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(256),
    currency        CHAR(3) NOT NULL DEFAULT 'USD',
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key       VARCHAR(255) NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','FAILED','REVERSED')),
    transaction_type      VARCHAR(32) NOT NULL DEFAULT 'TRANSFER',
    description           TEXT,
    request_payload_hash  CHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transactions_idem_key UNIQUE (idempotency_key)
);

CREATE TABLE entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_transaction_id ON entries(transaction_id);
CREATE INDEX idx_entries_account_id ON entries(account_id);

CREATE TABLE outbox (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type    VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION',
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(64) NOT NULL DEFAULT 'TRANSACTION_POSTED',
    payload           JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox(aggregate_id);

CREATE TABLE reconciliation_runs (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at                TIMESTAMPTZ NOT NULL,
    finished_at               TIMESTAMPTZ,
    status                    VARCHAR(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    transactions_checked      INT NOT NULL DEFAULT 0,
    entries_imbalance_count   INT NOT NULL DEFAULT 0,
    outbox_missing_count      INT NOT NULL DEFAULT 0,
    outbox_stuck_count        INT NOT NULL DEFAULT 0,
    summary                   JSONB
);

CREATE TABLE reconciliation_findings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              UUID NOT NULL REFERENCES reconciliation_runs(id),
    finding_type        VARCHAR(32) NOT NULL CHECK (finding_type IN
                          ('ENTRIES_NOT_ZERO','OUTBOX_MISSING','OUTBOX_STUCK_UNPUBLISHED')),
    transaction_id      UUID,
    outbox_id           UUID,
    detail              JSONB NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
