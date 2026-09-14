ALTER TABLE pending_fx_transfers ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT now();
