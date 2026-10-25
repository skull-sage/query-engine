-- Storage owned by query-engine, created on startup of every service that embeds it (see
-- QueryEngineAutoConfiguration). Each domain query service points query-engine at its own
-- database, so every domain gets its own drafts, published queries and identity catalog,
-- separate from the other domains.
--
-- Everything lives in the query_engine schema, apart from the domain's projection tables, and runs
-- as the query_engine_owner login (query-engine.store.*). The schema itself is created by the
-- provisioning step (docs/security_protocol.md, ./gradlew provision) so that login needs no right to
-- create schemas. Every name below spells out the schema, so nothing depends on
-- search_path.
--
-- query-engine keeps no lifecycle state in memory: every draft, published query and list read
-- goes through these tables. All statements are idempotent, so running this on every startup is
-- safe.
--
-- This file only creates: it never alters a table that exists. A database from before the query_engine
-- schema existed keeps its tables in public (and calls the draft table query_draft, with no fetch_size
-- column): run query-engine/db/migrate_public_to_query_engine.sql as an administrator before starting
-- this version, or those drafts and published queries stay behind.

CREATE TABLE IF NOT EXISTS query_engine.drafted_query (
    query_name       VARCHAR(255)  NOT NULL PRIMARY KEY,
    sql_text         TEXT          NOT NULL,
    params           JSONB         NOT NULL,
    allowed_roles    JSONB         NOT NULL,
    validated_query  JSONB,
    status           VARCHAR(32)   NOT NULL,
    errors           JSONB         NOT NULL,
    simulated        BOOLEAN       NOT NULL DEFAULT FALSE,
    author           VARCHAR(255)  NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    -- Optional rows-per-round-trip for reading the query through a database cursor (null = the service default).
    fetch_size       INT
);

CREATE TABLE IF NOT EXISTS query_engine.published_query (
    query_name       VARCHAR(255)  NOT NULL PRIMARY KEY,
    sql_text         TEXT          NOT NULL,
    params           JSONB         NOT NULL,
    allowed_roles    JSONB         NOT NULL,
    validated_query  JSONB         NOT NULL,
    published_at     TIMESTAMPTZ   NOT NULL,
    published_by     VARCHAR(255)  NOT NULL,
    -- Optional rows-per-round-trip for reading the query through a database cursor (null = the service default).
    fetch_size       INT
);

-- Identity catalog consulted by the UI when publishing a query (to choose which realm roles and
-- groups may invoke it) and when running a published query (to pick an identity to call as). In a
-- real deployment this mirrors the enterprise identity provider (e.g. Keycloak realm roles and
-- groups), which is shared across domains; here it is a self-contained seeded catalog exposed
-- via GET /query-roles.

CREATE TABLE IF NOT EXISTS query_engine.realm_role (
    name         VARCHAR(255) NOT NULL PRIMARY KEY,
    description  TEXT
);

CREATE TABLE IF NOT EXISTS query_engine.realm_group (
    name         VARCHAR(255) NOT NULL PRIMARY KEY,
    path         VARCHAR(255) NOT NULL,
    description  TEXT
);

INSERT INTO query_engine.realm_role (name, description) VALUES
    ('senior_DBA',        'Senior database administrator with broad read access to projections'),
    ('chief_DBA',         'Lead DBA, may invoke any published query'),
    ('data_analyst',      'Business/data analyst running reporting queries'),
    ('branch_manager',    'Branch manager, scoped to their own branch data'),
    ('warehouse_manager', 'Warehouse manager, scoped to their own warehouse stock'),
    ('auditor',           'Read-only auditor for compliance reviews'),
    ('ops_engineer',      'Operations/SRE engineer monitoring live data'),
    ('read_only',         'Generic least-privilege read-only role')
ON CONFLICT (name) DO NOTHING;

INSERT INTO query_engine.realm_group (name, path, description) VALUES
    ('data-platform',   '/data-platform',   'Data platform engineering team'),
    ('finance',         '/finance',         'Finance department'),
    ('operations',      '/operations',      'Operations and support'),
    ('supply-chain',    '/supply-chain',    'Procurement and supply chain planning'),
    ('audit',           '/audit',           'Internal audit and compliance'),
    ('branch-managers', '/branch-managers', 'All branch managers'),
    ('executives',      '/executives',      'Executive leadership')
ON CONFLICT (name) DO NOTHING;
