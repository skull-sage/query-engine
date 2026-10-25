-- Database logins and permissions every service that embeds query-engine needs. The design and
-- the reasons are in docs/security_protocol.md. This is a template: com.brac.query.engine.provision
-- fills in the placeholders from the service's own application.yml and runs it as an
-- administrator (./gradlew provision in a service). It is written to be run again and again: roles
-- and grants that already exist are brought back to what is below, nothing is dropped.
--
-- Placeholders are written in double braces as kind:name. Kinds: ident (a name, quoted), literal (a text
-- value, quoted), and idents / literals (the same for a list, comma separated). The names:
--
--   service_*   spring.r2dbc.username / password   the service's own login: owns and writes the projections
--   owner_*     query-engine.store.*               owns the engine's schema and tables, nothing else
--   reader_*    query-engine.projection.*          runs authors' queries: reads everything, writes nothing
--   database    the database in spring.r2dbc.url (or query-engine.provision.database)
--   engine_schema        query_engine
--   projection_schemas   query-engine.provision.projection-schemas (default: public)
--
-- Everything runs in one transaction: it all applies or none of it does.

-- 1. Logins. The service's login is only created if it is missing (its password and attributes belong to
--    whoever manages it); the two engine logins are always set to what the configuration says.
DO $provision$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = {{literal:service_login}}) THEN
    CREATE ROLE {{ident:service_login}} LOGIN PASSWORD {{literal:service_password}} NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = {{literal:owner_login}}) THEN
    CREATE ROLE {{ident:owner_login}} LOGIN;
  END IF;
  ALTER ROLE {{ident:owner_login}} WITH LOGIN PASSWORD {{literal:owner_password}} NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = {{literal:reader_login}}) THEN
    CREATE ROLE {{ident:reader_login}} LOGIN;
  END IF;
  ALTER ROLE {{ident:reader_login}} WITH LOGIN PASSWORD {{literal:reader_password}} NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
END
$provision$;

-- 2. The engine's schema, owned by the engine's login and closed to everyone else. A schema or table that
--    already exists (for example created by a development fallback) is handed over to that login.
CREATE SCHEMA IF NOT EXISTS {{ident:engine_schema}} AUTHORIZATION {{ident:owner_login}};
ALTER SCHEMA {{ident:engine_schema}} OWNER TO {{ident:owner_login}};
REVOKE ALL ON SCHEMA {{ident:engine_schema}} FROM PUBLIC;
DO $provision$
DECLARE t record;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables
            WHERE schemaname = {{literal:engine_schema}} AND tableowner <> {{literal:owner_login}} LOOP
    EXECUTE format('ALTER TABLE %I.%I OWNER TO %I', {{literal:engine_schema}}, t.tablename, {{literal:owner_login}});
  END LOOP;
END
$provision$;

-- 3. The reader: reads every table now and later, writes none, and finds unqualified projection names in the
--    projection schemas. default_transaction_read_only is only a safety net (a query could switch it off for
--    its own session); the real guard is the BEGIN READ ONLY the engine opens around each query.
GRANT pg_read_all_data TO {{ident:reader_login}};
ALTER ROLE {{ident:reader_login}} SET default_transaction_read_only = on;
ALTER ROLE {{ident:reader_login}} SET search_path = {{idents:projection_schemas}};

-- 4. The service's login can use and write the projection schemas, including tables an administrator created
--    (a development seed) and ones created later by the administrator. Tables the service creates itself it owns.
DO $provision$
DECLARE s text;
BEGIN
  FOREACH s IN ARRAY ARRAY[{{literals:projection_schemas}}]::text[] LOOP
    EXECUTE format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', s, {{literal:service_login}});
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', s, {{literal:service_login}});
    EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO %I', s, {{literal:service_login}});
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', s, {{literal:service_login}});
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', s, {{literal:service_login}});
  END LOOP;
END
$provision$;

-- 5. Close Postgres's open defaults: nobody but the service creates tables in public or temp tables, and only
--    the three logins (and administrators) may connect to this database.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE TEMPORARY ON DATABASE {{ident:database}} FROM PUBLIC;
REVOKE CONNECT ON DATABASE {{ident:database}} FROM PUBLIC;
GRANT CONNECT ON DATABASE {{ident:database}} TO {{ident:service_login}}, {{ident:owner_login}}, {{ident:reader_login}};
