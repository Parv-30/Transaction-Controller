CREATE TABLE fx_rates (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate           NUMERIC(18,8) NOT NULL,
    source         VARCHAR(32) NOT NULL DEFAULT 'frankfurter',
    fetched_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (base_currency, quote_currency, fetched_at)
);
CREATE INDEX idx_fx_rates_pair_fetched ON fx_rates(base_currency, quote_currency, fetched_at DESC);

CREATE TABLE fx_quotes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate_used      NUMERIC(18,8) NOT NULL,
    locked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ
);
