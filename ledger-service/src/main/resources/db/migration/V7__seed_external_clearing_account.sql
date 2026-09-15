-- Seeds the platform-owned external clearing account used as the suspense/netting account for
-- Gateway Simulator's inbound deposits and outbound withdrawal reversals (see
-- gateway-simulator's DepositService / WithdrawalResolutionService). This account cannot be
-- created through the ordinary POST /accounts endpoint: AccountService deliberately rejects any
-- client-supplied accountRef under the "external-clearing-" prefix (ReservedAccountRefException),
-- because TransactionPoster grants that prefix an unlimited-overdraft privilege that must not be
-- reachable by client-chosen data (see TransactionPoster.isClearingAccount and Task 2/Task 3 of
-- the V5 implementation plan). It is baseline platform data, not a user-created wallet, so it is
-- seeded here via migration rather than via any HTTP endpoint. account_group_id is left NULL:
-- it does not belong to any customer wallet/group. Only USD is seeded because this platform's
-- Gateway Simulator scope (V5) only ever references external-clearing-USD; extend this file with
-- additional currencies if a later feature needs them, following the same pattern as
-- V4__seed_fx_clearing_accounts.sql's multi-currency seeding.
INSERT INTO accounts (id, account_ref, display_name, currency, balance_minor, status, account_group_id)
VALUES
    (gen_random_uuid(), 'external-clearing-USD', 'External Clearing (USD)', 'USD', 0, 'ACTIVE', NULL)
ON CONFLICT (account_ref) DO NOTHING;
