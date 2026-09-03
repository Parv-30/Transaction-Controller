-- Creates a dedicated, least-privilege Postgres role for the Transaction Processor's
-- embedded Debezium CDC engine.
--
-- This script is mounted into ledger-postgres's /docker-entrypoint-initdb.d/ and runs
-- automatically once, on first container boot, immediately after Postgres finishes initdb --
-- which is BEFORE ledger-service has had any chance to connect and run its Flyway migrations
-- (V1__init_schema.sql, which creates the "outbox" table). initdb.d scripts that error abort
-- the entrypoint and crash the container, so this script deliberately only creates the role
-- here (no dependency on any application table). The GRANT SELECT on public.outbox and the
-- CREATE PUBLICATION Debezium needs (publication.autocreate.mode=disabled -- see
-- DebeziumEngineLifecycle) both depend on "outbox" existing and are run later, once
-- ledger-service is confirmed healthy (and therefore has migrated), by
-- scripts/provision.sh's "Granting CDC privileges..." step. A first attempt at doing all of
-- this in one initdb.d script was tried and reproducibly failed: Postgres init scripts run
-- so early that "outbox" reliably does not exist yet, and the resulting error
-- ("relation \"public.outbox\" does not exist") crashes the entire ledger-postgres container
-- (exit code 3), taking down the whole compose stack -- not a soft/recoverable race.
CREATE ROLE debezium_replicator WITH REPLICATION LOGIN PASSWORD 'debezium_replicator';
GRANT CONNECT ON DATABASE ledger_db TO debezium_replicator;
GRANT USAGE ON SCHEMA public TO debezium_replicator;
