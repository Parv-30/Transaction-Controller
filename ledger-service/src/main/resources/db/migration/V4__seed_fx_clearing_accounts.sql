-- Seeds the platform-owned FX clearing accounts used as the intermediary leg of every
-- cross-currency transfer (see CrossCurrencyTransferService / TransactionPoster). These
-- accounts cannot be created through the ordinary POST /accounts endpoint: AccountService
-- deliberately rejects any client-supplied accountRef under the "fx-clearing-" prefix
-- (ReservedAccountRefException), because TransactionPoster grants that prefix an
-- unlimited-overdraft privilege that must not be reachable by client-chosen data. They are
-- baseline platform data, not user-created wallets, so they are seeded here via migration
-- rather than via any HTTP endpoint. account_group_id is left NULL: these do not belong to
-- any customer wallet/group.
INSERT INTO accounts (id, account_ref, display_name, currency, balance_minor, status, account_group_id)
VALUES
    (gen_random_uuid(), 'fx-clearing-USD', 'FX Clearing (USD)', 'USD', 0, 'ACTIVE', NULL),
    (gen_random_uuid(), 'fx-clearing-EUR', 'FX Clearing (EUR)', 'EUR', 0, 'ACTIVE', NULL),
    (gen_random_uuid(), 'fx-clearing-GBP', 'FX Clearing (GBP)', 'GBP', 0, 'ACTIVE', NULL)
ON CONFLICT (account_ref) DO NOTHING;
