ALTER TABLE accounts ADD COLUMN account_group_id UUID;
CREATE INDEX idx_accounts_group_id ON accounts(account_group_id);

CREATE TABLE pending_fx_transfers (
    id                           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key              VARCHAR(255) NOT NULL UNIQUE,
    quote_id                     UUID NOT NULL,
    source_account_ref           VARCHAR(128) NOT NULL,
    dest_account_ref             VARCHAR(128) NOT NULL,
    source_amount_minor          BIGINT NOT NULL,
    rate_used                    NUMERIC(18,8) NOT NULL,
    dest_amount_minor            BIGINT NOT NULL,
    status                       VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING','LEG1_POSTED','LEG2_POSTED',
                                                      'COMPLETED','COMPENSATING','COMPENSATED','FAILED')),
    leg1_transaction_id          UUID,
    leg2_transaction_id          UUID,
    compensation_transaction_id  UUID,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_fx_transfers_status ON pending_fx_transfers(status);
