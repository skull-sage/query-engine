-- The one table query-engine needs, created by every service that embeds it the first time it starts
-- (QueryEngineAutoConfiguration runs this file on boot, in the service's own database and default schema,
-- with the service's own login). Each domain service has its own database, so each has its own copy.
--
-- A stored query is one row. Its state says where it is in its life (see the PRD, "Query lifecycle"):
--   drafted    saved, not yet simulated successfully, the definition may carry errors
--   simulated  the current definition ran successfully against real data, the only state that can be published
--   published  live: callers with an allowed role can run it through /data-query
-- The engine's code decides every change of state, the CHECK below only keeps the column to those three values.
--
-- This file only creates. It never alters a table that exists, so starting a newer version never changes the
-- shape of data in place, a change of shape is a deliberate migration. It is safe to run on every start.
--
-- Production: a DBA may create this table ahead of time with this exact statement and start the service with
-- query-engine.schema.initialize=false, so the service login needs no right to create tables in its schema.
CREATE TABLE IF NOT EXISTS qe_stored_query (
    query_name       VARCHAR(255)  NOT NULL PRIMARY KEY,            -- {module}.{name}
    state            VARCHAR(16)   NOT NULL CHECK (state IN ('drafted', 'simulated', 'published')),
    sql_text         TEXT          NOT NULL,                        -- the SQL template exactly as submitted
    params           JSONB         NOT NULL,                        -- declared param types as written, e.g. {"status":"String"}
    fetch_size       INT,                                           -- optional rows per round trip for streaming, null = service default
    validated_query  JSONB,                                         -- what the executor runs, null while the definition has errors
    errors           JSONB         NOT NULL,                        -- validation errors, [] when valid
    allowed_roles    JSONB         NOT NULL,                        -- chosen at publish and kept for the next publish, [] before that
    author           VARCHAR(255)  NOT NULL,                        -- the holder while drafted / simulated (the lock)
    revision         BIGINT        NOT NULL,                        -- increases on every write, simulate and publish apply only to the revision they read
    updated_at       TIMESTAMPTZ   NOT NULL,
    published_at     TIMESTAMPTZ,                                   -- set when published and never cleared: marks a query that was live
    published_by     VARCHAR(255)
);
