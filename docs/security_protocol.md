# Query Engine — Security Protocol

Status: built and checked against the dockerized stack on 2026-10-25 (provisioning moved into a shared template the same day).
The section "What was checked" lists what was checked and what was not.

## Why

Each domain service (`order-query`, `inventory-query`) embeds query-engine. The engine's own tables used to sit in `public`
next to the domain's projections (`query_draft`, `published_query`, `realm_role`, `realm_group`), and everything connected
with one login. In the dev containers that login was a superuser.

A published query is SQL written by an author. Run with that login, it could:

- change the engine's own tables, for example give itself more `allowed_roles`
- change or drop projection tables
- write through a stored function, even when the SQL itself looks like a plain `SELECT`

The goal: published SQL can only read projection data, and only the engine's own code can change the engine's tables.

## The four rules

1. **Two separate logins.** `query_engine_owner` owns the `query_engine` schema and is used only by the engine's own
   code. `query_engine_ro` runs every author query: it may read data and owns nothing. Neither is a superuser, and neither
   is the service's own login.
2. **Every author query runs inside `BEGIN READ ONLY`.** Postgres then refuses any write in that transaction, including
   writes made inside stored functions.
3. **A SQL lexer checks the query at draft, simulate, publish and call time.** It rejects anything that is not a single
   `SELECT`. This gives authors clear errors early. It is a guard rail: it can't see inside functions, so rules 1 and 2
   are what actually protect the data.
4. **The engine's own SQL always names the schema** (`query_engine.published_query`). It never depends on `search_path`,
   so a connection pooler such as PgBouncer can't send it to the wrong table.

## Who can do what

| Login | Owns | Can read | Can write | Used by |
| --- | --- | --- | --- | --- |
| Service login (`order_query`, `inventory_query`) | The tables it creates (dev seeds belong to the admin; provisioning grants the service login access) | Projections | Projections | The service's event consumers (`spring.r2dbc.*`) |
| `query_engine_owner` | `query_engine` schema and its tables | Engine tables | Engine tables | Engine stores and `schema.sql` (`query-engine.store.*`) |
| `query_engine_ro` | Nothing | Everything, through `pg_read_all_data` | Nothing | Simulate and `/data-query` (`query-engine.projection.*`) |
| Admin (`postgres`) | The database | Everything | Everything | `./gradlew provision` and, in dev, seeding the projections. Never the running app |

How the layers stack up for a published query:

| Attack | Stopped by |
| --- | --- |
| `DELETE`, `UPDATE`, `DROP` in the SQL | Lexer, the read-only transaction, and the login having no write rights |
| `SELECT my_function()` where the function writes | Read-only transaction (also when the function is `SECURITY DEFINER` owned by a superuser) |
| `COMMIT; DELETE ...` to break out of the transaction | Lexer (one statement only). The driver also refuses it, see "Checked" below |
| `UPDATE query_engine.published_query ...` | All three layers. The lexer also blocks reads of `query_engine.*` |
| `pg_terminate_backend(...)` on the engine's own connections | Separate logins (a login can only kill its own sessions), plus the lexer's function denylist |
| `set_config(...)` that leaks to the next caller | Lexer function denylist. Without it the call works and stays on the pooled connection, see "Checked" below |

### Known trade-off: `pg_read_all_data`

`pg_read_all_data` lets `query_engine_ro` read every table in every schema, including `query_engine`, and it can't be
narrowed to exclude one schema. It also covers every other database on the same server that this login may connect to.
That is the price of "we don't need to track which schemas exist". Two things limit it:

- The lexer rejects any reference to `query_engine.` in author SQL. (With the lexer switched off, a query can read the
  engine's tables. This was confirmed; writing them fails.)
- Provisioning revokes `CONNECT` on the domain database from `PUBLIC`, then grants it to the three logins.

If drafts and role mappings must be strictly hidden, replace `pg_read_all_data` with explicit `GRANT SELECT` per
projection schema. The section "Alternative: explicit grants" below shows the DDL.

## DDL changes

### 1. Provisioning: one shared template, filled in from `application.yml`

The statements that create the logins and set the permissions live once, in the library:
`query-engine/src/main/resources/query-engine/provision.sql`. They need administrator rights, so the running service never
runs them. A small program in the library, `com.brac.query.engine.provision.Provisioner`, loads the service's own
`application.yml` the way the service does (profiles, environment variables, command line), fills in the template and runs it
as an administrator, in one transaction. Each service has a Gradle task for it:

```bash
./gradlew provision                                              # the dev database, administrator postgres/postgres
QE_ADMIN_USERNAME=dba QE_ADMIN_PASSWORD=... R2DBC_URL=... ./gradlew provision      # any other database
./gradlew provision --args='--query-engine.provision.dry-run=true'                 # print the SQL (passwords hidden), connect to nothing
```

Nothing is written twice. Every value comes from a setting the service already has:

| Value | Setting in `application.yml` |
| --- | --- |
| database name | the database in `spring.r2dbc.url` (or `query-engine.provision.database`) |
| service login and its password | `spring.r2dbc.username`, `spring.r2dbc.password` |
| engine login (`query_engine_owner`) and its password | `query-engine.store.username`, `query-engine.store.password` |
| reader login (`query_engine_ro`) and its password | `query-engine.projection.username`, `query-engine.projection.password` |
| schemas that hold projection tables | `query-engine.provision.projection-schemas` (default `public`) |

The administrator login is the one thing that is not in the yml, on purpose: the running service must never have it. The task
passes it as the environment variables `QUERYENGINE_PROVISION_ADMIN_USERNAME` and `..._PASSWORD`, taken from
`QE_ADMIN_USERNAME` and `QE_ADMIN_PASSWORD` (default `postgres` / `postgres`, the dev database). The run stops with a message
naming the setting when one is missing, and when the three logins are not three different users.

The template is plain SQL with `kind:name` placeholders in double braces (`ident`, `literal`, `idents`, `literals`). The
program quotes every value, so a name or password cannot break out of its quotes (`ProvisionSql`, unit tested). It runs 13
statements and is safe to run again, which is also how you change a password: edit it in the yml, run `provision`.

| Step | What it does |
| --- | --- |
| Logins | Creates the service login only if it is missing (its password and attributes belong to whoever manages it). Creates the engine and reader logins if missing and always sets their password to the configured one, `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS` |
| Engine schema | `CREATE SCHEMA IF NOT EXISTS query_engine AUTHORIZATION <engine login>`, makes that login its owner and the owner of any table already in it, `REVOKE ALL ... FROM PUBLIC` |
| Reader | `GRANT pg_read_all_data` (Postgres 14+); role settings `default_transaction_read_only = on` (a safety net under rule 2) and `search_path` = the projection schemas |
| Service login | `USAGE, CREATE` on each projection schema, read/write on its existing tables and sequences, and default privileges for ones the administrator creates later. Tables the service creates itself it owns |
| Defaults | `REVOKE CREATE ON SCHEMA public FROM PUBLIC`; `REVOKE TEMPORARY` and `REVOKE CONNECT ON DATABASE` from `PUBLIC`; `GRANT CONNECT` to the three logins |

Notes:

- Role names are server-wide. The dev setup has one Postgres per domain, so the plain names are fine. If several domain
  databases share one Postgres server, give each domain its own pair in the yml (`order_engine_owner`, `order_engine_ro`) so
  one domain's reader can't connect to another domain's database.
- Revoking `CONNECT` from `PUBLIC` means any other login that uses this database needs its own `CONNECT` grant. Superusers are
  not affected.
- A domain that keeps projections outside `public` lists its schemas in `query-engine.provision.projection-schemas`.

### 2. Configuration (`application.yml`) and Docker

Both services now use `application.yml` (the `.properties` files are gone). The part that matters here, from
`order-query`; every value can be overridden by the environment variable shown:

```yaml
spring:
  r2dbc:
    url: "${R2DBC_URL:r2dbc:postgresql://localhost:5433/order_query}"   # the database name lives here
    username: "${DB_USERNAME:order_query}"                              # the service login
    password: "${DB_PASSWORD:order_query}"
query-engine:
  store:                                                                # the engine's tables
    username: "${QE_STORE_USERNAME:query_engine_owner}"
    password: "${QE_STORE_PASSWORD:qe_owner}"
  projection:                                                           # authors' queries
    username: "${QE_PROJECTION_USERNAME:query_engine_ro}"
    password: "${QE_PROJECTION_PASSWORD:qe_ro}"
  provision:
    projection-schemas: public
```

In `docker-compose.yml`, `POSTGRES_USER` is a separate admin (`postgres`). It seeds the projection tables from `docker/init`
(before any login exists, so those tables belong to the admin and provisioning grants the service login access to them) and
is what `./gradlew provision` connects as. The init folder no longer creates logins. The service container gets the same
`DB_*` and `QE_*` variables as before. Order of events, which `./dry-run.sh` follows (also with `--db-only`):
database up, `./gradlew provision`, service up. A service started before provisioning stops at startup, naming the problem.

### 3. `schema.sql` (changed)

The engine runs this as `query_engine_owner`, so that login owns the tables. Every name is qualified, and it only creates:
no `ALTER` is left. `fetch_size` (rows per round trip for a query read through a cursor) is a column of the two query tables
from the start.

```sql
CREATE TABLE IF NOT EXISTS query_engine.drafted_query (
    query_name VARCHAR(255) NOT NULL PRIMARY KEY, sql_text TEXT NOT NULL, ... updated_at TIMESTAMPTZ NOT NULL,
    fetch_size INT );
CREATE TABLE IF NOT EXISTS query_engine.published_query ( ... published_by VARCHAR(255) NOT NULL, fetch_size INT );
CREATE TABLE IF NOT EXISTS query_engine.realm_role  ( ... );
CREATE TABLE IF NOT EXISTS query_engine.realm_group ( ... );
INSERT INTO query_engine.realm_role  (name, description)       VALUES ... ON CONFLICT (name) DO NOTHING;
INSERT INTO query_engine.realm_group (name, path, description) VALUES ... ON CONFLICT (name) DO NOTHING;
```

`query_draft` is renamed `drafted_query`, to match `published_query`. The `DROP COLUMN expires_at` and the `fetch_size`
column for older tables moved to the migration script below. `schema.sql` does not create the schema: provisioning does, so
the engine login needs no right to create schemas. For a development database that was never provisioned, the engine creates
`query_engine` itself when it is missing, which only works for a login that may create schemas; otherwise startup stops with
a message pointing at `./gradlew provision`.

### 4. Moving an existing database (one-off, runs as admin)

Built as `query-engine/db/migrate_public_to_query_engine.sql`. For a database that already has the engine tables in
`public`. It keeps every draft and published query, only moves tables that aren't already in `query_engine`, renames
`query_draft` to `drafted_query` (and its primary-key index), makes `query_engine_owner` the owner, drops the old
`expires_at` column and adds the `fetch_size` column to tables from before it existed. Safe to run more than once.

Run `./gradlew provision` first (it creates the schema and logins), then this, and only then start the new engine version.
Otherwise `schema.sql` creates an empty `drafted_query` and the old drafts stay behind in `query_engine.query_draft`. At
startup the engine logs a warning when it finds `query_draft` or `published_query` still in `public`.

```sql
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['query_draft', 'published_query', 'realm_role', 'realm_group'] LOOP
    IF to_regclass('public.' || t) IS NOT NULL AND to_regclass('query_engine.' || t) IS NULL THEN
      EXECUTE format('ALTER TABLE public.%I SET SCHEMA query_engine', t);
    END IF;
  END LOOP;

  IF to_regclass('query_engine.query_draft') IS NOT NULL AND to_regclass('query_engine.drafted_query') IS NULL THEN
    ALTER TABLE query_engine.query_draft RENAME TO drafted_query;
    ALTER INDEX IF EXISTS query_engine.query_draft_pkey RENAME TO drafted_query_pkey;
  END IF;

  FOREACH t IN ARRAY ARRAY['drafted_query', 'published_query', 'realm_role', 'realm_group'] LOOP
    IF to_regclass('query_engine.' || t) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE query_engine.%I OWNER TO query_engine_owner', t);
    END IF;
  END LOOP;
END $$;
ALTER TABLE IF EXISTS query_engine.drafted_query DROP COLUMN IF EXISTS expires_at;
ALTER TABLE IF EXISTS query_engine.drafted_query   ADD COLUMN IF NOT EXISTS fetch_size INT;
ALTER TABLE IF EXISTS query_engine.published_query ADD COLUMN IF NOT EXISTS fetch_size INT;
```

### Alternative: explicit grants instead of `pg_read_all_data`

Use this when `query_engine_ro` must not see `query_engine` at all. Repeat it for each projection schema. Not built; the
startup check works the same way with it.

```sql
GRANT USAGE  ON SCHEMA public TO query_engine_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO query_engine_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE order_query IN SCHEMA public GRANT SELECT ON TABLES TO query_engine_ro;
```

## Code changes (query-engine library)

### Engine connection

- `store.EngineDatabase`, built like `ProjectionDatabase`: its own small pool (default max 5) and its own
  `DatabaseClient`, logging in with `query-engine.store.*`. Those settings fall back to `spring.r2dbc.*`, so a service
  still starts without them, but then the startup check (below) refuses it unless it is in `warn` mode.
- `DraftStore`, `PublishedQueryRepository` and `QueryRolesRepository` use it, and every statement names `query_engine.`.
- `schema.sql` runs on that pool, not on the service's. It only creates; see "3. schema.sql" above.
- Publish saves the published query and deletes the draft in one transaction: `EngineDatabase.inTransaction(...)`, a
  `TransactionalOperator` over the same pool. It is not exposed as a transaction-manager bean, because Spring Boot and the
  consuming service expect exactly one, on the service's own pool. The old `@Transactional` on `publish` is gone.
- `config.ConnectionSettings` resolves url, user and password for both pools (own setting, else `spring.r2dbc.*`).

### Provisioning

- `provision.ProvisionSql` fills in the template's placeholders with quoted values and splits the result into statements (using
  `SqlLexer`, so a `;` inside a `DO` body does not split it). Pure text work, unit tested.
- `provision.Provisioner` is the entry point (`main`): it starts a Spring context that only loads the configuration, collects the
  values from `spring.r2dbc.*`, `query-engine.store.*` and `query-engine.projection.*`, connects as the administrator and runs the
  statements in one transaction. `provision.ProvisionProperties` binds `query-engine.provision.*` (admin login, database,
  projection schemas, dry run).
- Each service's `build.gradle` has a `provision` task (a `JavaExec` on the service's runtime classpath) that sets the
  administrator from `QE_ADMIN_USERNAME` / `QE_ADMIN_PASSWORD`.

### Projection connection

- `query-engine.projection.username` and `password` point at `query_engine_ro`.
- The connection options now also carry `default_transaction_read_only=on` and `standard_conforming_strings=on` (the lexer
  assumes backslashes are plain inside `'strings'`), next to the existing `statement_timeout`.
- `ProjectionStoreExecutor.stream` runs each query inside its own read-only transaction:
  `ProjectionDatabase.inReadOnlyTransaction`, a `TransactionalOperator` with a read-only definition (and
  `enforceReadOnly`, which also sends `SET TRANSACTION READ ONLY`). It opens when the stream starts and ends on finish,
  error or client disconnect; the cursor already held the connection for that long.

### Startup check

`security.SecurityStartupCheck` runs after the tables exist. It reads what the database says about both logins and, in
`enforce` mode (the default), refuses to start if any of these is true:

- the engine login and the projection login are the same user
- either login is a superuser, or the projection login has `BYPASSRLS`
- the projection login owns the `query_engine` schema
- the projection login can `INSERT`, `UPDATE`, `DELETE` or `TRUNCATE` on any table (the engine's or the domain's)

`query-engine.security.mode=warn` logs the same findings and starts anyway, for a dev machine that is not provisioned yet;
`off` skips the login checks. It then runs the lexer over every stored published query and logs a warning for each one
that fails; those are also refused when called. Finally it warns about engine tables still left in `public`.

## SQL lexer validation

### What it is

A small hand-written tokenizer, with no SQL parser library. It walks the SQL text once and splits it into pieces:

| Piece | Examples | Treated as |
| --- | --- | --- |
| Word | `SELECT`, `order_projection`, `pg_sleep` | Keyword or name, lower-cased the way Postgres folds names |
| Quoted name | `"orderId"` | Name, never a keyword. `U&"..."` names are flagged, not decoded |
| String | `'SHIPPED'`, `E'a\'b'`, `$tag$ ... $tag$` | Skipped. Its content is ignored |
| Comment | `-- ...`, `/* ... */` (may nest) | Skipped |
| Param | `:status` | A bound parameter |
| Cast | `::text` | Not a param (fixes the old false `UNDECLARED_PARAM`) |
| Number, symbol | `12`, `1.5e3`, `;`, `(`, `.` | Used for structure |

Because strings and comments are skipped, `WHERE note = 'please DELETE me'` is fine, and a keyword hidden in a comment
can't fool it.

### Rules and error codes

Errors use the same `{ code, param, message }` shape as the param checks. Messages say where: `(line 3, column 7)`.

| Code | Rule | Example it rejects |
| --- | --- | --- |
| `SQL_UNTERMINATED` | Every string, quoted name, `$tag$` and comment is closed | `WHERE a = 'x` |
| `SQL_NOT_SELECT` | The statement starts with `SELECT` or `WITH` (after any opening brackets). Reported alone: the other content rules are skipped | `UPDATE t SET ...` |
| `SQL_MULTIPLE_STATEMENTS` | Only one statement. One `;` at the very end is allowed | `SELECT 1; DELETE FROM t` |
| `SQL_DATA_MODIFYING` | No `INSERT`, `UPDATE`, `DELETE` or `MERGE` word anywhere, which also covers a DML main statement after `WITH`. Not after a `.` (`t.update`) and not inside quotes | `WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x` |
| `SQL_SELECT_INTO` | No `INTO` (it creates a table) | `SELECT * INTO copy FROM t` |
| `SQL_LOCKING_CLAUSE` | No `FOR UPDATE`, `FOR SHARE`, `FOR NO KEY UPDATE`, `FOR KEY SHARE` | `SELECT * FROM t FOR UPDATE` |
| `SQL_FORBIDDEN_SCHEMA` | No reference to `query_engine.` | `SELECT * FROM query_engine.published_query` |
| `SQL_FORBIDDEN_FUNCTION` | No call to a denylisted function | `SELECT pg_sleep(600)` |
| `SQL_UNSUPPORTED_SYNTAX` | No `U&"..."` names, whose escapes would hide the real name | `SELECT * FROM U&"pg_sl\0065ep"(1)` |

Function denylist (`query-engine.sql-guard.denied-functions`; setting it replaces the list; an entry ending in `*` matches
every name starting with it). A name matches when it is followed by `(`, with or without a schema prefix
(`pg_catalog.pg_sleep(`) or quotes (`"pg_sleep"(`):

- sessions and settings: `set_config`, `pg_terminate_backend`, `pg_cancel_backend`, `pg_reload_conf`
- waiting and locks: `pg_sleep`, `pg_sleep_for`, `pg_sleep_until`, `pg_advisory_*`, `pg_try_advisory_*`
- side effects: `nextval`, `setval`, `pg_notify`, `lo_*`, `dblink*`
- server files: `pg_read_file`, `pg_read_binary_file`, `pg_ls_dir`, `pg_stat_file`
- run other SQL text, or read a table named by a string: `query_to_xml*`, `cursor_to_xml*`, `table_to_xml*`,
  `schema_to_xml*`, `database_to_xml*` (each also with `_and_xmlschema` and `xmlschema` variants). The `table_to_xml`
  family is there because it would read `'query_engine.published_query'` without the name ever appearing as an identifier.

Rules are deliberately targeted rather than a blanket keyword ban. A column called `status`, `comment` or `end` keeps
working, `CASE ... END` is fine, and so is `updated_at`: words are compared whole. A column literally called `update`,
`delete`, `insert` or `merge` must be quoted (`"update"`).

`query-engine.sql-guard.enabled=false` switches the rules off (the old `:name` scan is used), for testing the database
layers on their own. Never use it in a shared environment.

### Backend

In `com.brac.query.engine.validation`:

- `SqlLexer`: turns text into the pieces above, and reports unterminated literals.
- `SqlGuard`: applies the rules to the pieces and returns the `ValidationError`s and the param names, replacing the old
  regex.
- `config.SqlGuardProperties`: the denylist and the on/off switch.

Where it runs:

| Point | What happens on failure |
| --- | --- |
| Draft (`/engine/draft`, `/engine/draft-yml`) | Errors are stored on the draft with `status = DRAFT`, like the param errors, and shown first. If the text can't be tokenized the param cross-check is skipped, so it doesn't bury the real error |
| Simulate | The draft's SQL is checked again: `422 SQL_REJECTED`. A draft already stored as `DRAFT` still gets `422 DRAFT_INVALID` |
| Publish | The stored draft's SQL is checked again: `422 SQL_REJECTED`, nothing is published |
| Call (`/data-query`) | The published SQL is checked again, after the role gate and before the params: `422 SQL_REJECTED`. The caller sees only the codes; the detail is logged. This covers queries published before the lexer existed |
| Startup | Every published query is checked and a warning is logged for each failure |

Tests: `SqlGuardTest` runs every case in `query-engine/src/test/resources/sql-guard-cases.json` (103 cases: every rule,
keywords hidden in strings, comments and quoted names, `::` casts, `$$` strings, a trailing `;`, mixed case), plus the
seeded sample queries.

### Frontend

The aim is that an invalid query definition is impossible to miss, and that the author hears about it before saving. The server
stays the authority: the UI never blocks a save on its own checks.

- `src/sql/sqlLexer.js` and `src/sql/sqlGuard.js`: a JavaScript port of the same lexer and rules, with the same codes.
- `src/sql/checkDefinition.js` checks the whole definition in the editor, not just the SQL: YAML that does not parse,
  a missing or malformed `queryName`, a missing `sql`, the `SQL_*` rules, params used but not declared, declared but not used,
  of an unknown type, and a bad `fetchSize`. The codes are the server's, so the live list and the saved list read the same.
  `useDefinitionChecks` recomputes it about 300 ms after typing stops.
- While a definition has problems the draft step shows:
  - a red **"Invalid query definition: N problems"** panel under the editor (announced to screen readers as an alert), one line
    per problem with its code, the param or field it concerns, and where in the SQL
  - the editor outlined in red
  - a red **"Invalid · N problems"** badge next to the save status
  - a red note in the dialog footer next to the Save button
- Saving is still allowed, so work is not lost. The server stores the draft as `DRAFT`, and the panel is replaced by the
  server's own list under **"Saved, but the query definition is invalid"**, which scrolls into view. The footer then says the
  saved draft is invalid, and "Continue to simulate" is not offered until it is fixed and saved again.
- `SQL_REJECTED` (simulate, publish or run) shows under the plain title "Not a read-only SELECT" with the server's message.
- The denylist is a copy in the UI. If a deployment changes it, the server's answer wins.
- The Downstream demo's printed `psql` hint now connects as `postgres`, since the stream's sessions belong to
  `query_engine_ro` and other logins can't see their queries in `pg_stat_activity`.

Tests: `vitest` 5.0.5 (pinned dev dependency, `npm test`). `src/sql/sqlGuard.test.js` runs the same case file as the
backend, read by relative path, so the two lexers can't quietly drift apart. `checkDefinition.test.js` covers each definition
problem and checks that the sample drafts and the demo query the UI ships pass.
## What was checked (2026-10-25)

Against the dockerized stack (Postgres 14, both domains), from a fresh volume, unless noted. The work was done in two rounds:
first with the logins created by SQL scripts in each service's `docker/init`, then again after provisioning moved into the
shared template described above. The grants are the same in both.

**Passed after the move to the shared template (re-run)**

- Provisioning from a fresh database in both domains: 13 statements applied. Roles: the service login, `query_engine_owner`
  and `query_engine_ro` are not superusers and cannot create databases or roles; `query_engine_ro` is a member of
  `pg_read_all_data` with `default_transaction_read_only=on` and `search_path=public`; `query_engine` is owned by
  `query_engine_owner` and closed to `PUBLIC`; the database grants `CONNECT` to the three logins only.
- Layout: the four engine tables exist only in `query_engine`, owned by `query_engine_owner`, and `fetch_size` is a column of
  both query tables although `schema.sql` has no `ALTER`. The service login can insert into a projection table, the reader
  cannot but can select. The engine pool connects as `query_engine_owner` and the projection pool as `query_engine_ro`.
- Running it again changes nothing and succeeds. Changing `query-engine.store.password` through the environment and running it
  again made the old password stop working and the new one work; running it with the default restored the original.
  `--query-engine.provision.dry-run=true` printed the SQL with every password shown as `'********'` and connected to nothing.
- Failures are one line and exit 1: a wrong administrator password (`password authentication failed for user "postgres"`),
  the service login set to the same user as the reader (`must be three different users`), and a blank
  `query-engine.store.password` (names the setting).
- `./dry-run.sh --db-only` publishes the library and provisions both databases, so `bootRun` works against just the database
  containers; the full run provisions between building a service and starting its container.
- The existing flows in both domains, with a `fetchSize` of 25 stored on the published query: draft, simulate, publish,
  invoke as JSON and NDJSON, a `403` for another role, and the role catalog. Drafts that are not a read-only SELECT are
  rejected with `SQL_NOT_SELECT`, `SQL_MULTIPLE_STATEMENTS`, `SQL_FORBIDDEN_SCHEMA` and `SQL_FORBIDDEN_FUNCTION`.
- The service starts from `application.yml` alone and its startup check passes.
- The invalid-definition notifications, in real headless Chrome against the running UI and service (20 checks): a valid sample shows no
  alert; typing `DELETE FROM ...` shows the red panel, the red outline, the header badge and the footer note without saving; saving it
  shows the server's list once (not twice), keeps the outline, and offers no `Continue to simulate`; fixing it clears everything and
  saving validates; undeclared, unused and unknown-type params, and broken YAML, are each flagged.
- Migration: a database in the old layout (tables in `public`, one draft and one published query, and no `fetch_size`
  column at all) was moved, renamed, re-owned and given the column with the data intact; a second run changed nothing; the
  new `schema.sql` then ran cleanly as `query_engine_owner`.
- Tests: `gradlew test` in `query-engine` (123 tests: 108 for the SQL guard, 12 for the provisioning text, 3 for stream limits)
  and `npm test` in `query-builder-ui` (110 tests); the UI builds.

**Passed earlier, on the same grants (not repeated after the move)**

- 50,000 rows streamed as NDJSON in about 2.5 s inside the read-only transaction (`outcome=completed rows=50000`). Ten
  clients cut off mid-stream were all logged as cancelled and left every connection `idle`, none `idle in transaction`.
- Rejected at draft time, with the right code: a data-modifying CTE, `SELECT INTO`, `FOR UPDATE`, an unterminated string.
  Simulate on such a draft is `422 DRAFT_INVALID`. `x::text` is no longer a false `UNDECLARED_PARAM`.
- A stored query that breaks the rules is `422 SQL_REJECTED` when called, and the startup audit counts it.
- With the lexer switched off (the dev switch), so the database layers stand alone:
  - `UPDATE` on a projection, `CREATE TABLE AS`, `UPDATE query_engine.published_query` and a data-modifying CTE all fail
    with SQLSTATE `25006` (read-only transaction)
  - a `SECURITY DEFINER` function owned by a superuser that updates a table fails the same way
  - after `set_config('default_transaction_read_only', 'off', false)` leaked onto pooled connections (a probe saw
    `default_transaction_read_only = off`), `transaction_read_only` was still `on`, and ten `UPDATE`s on a table the reader
    had full rights to all failed. So the explicit `BEGIN READ ONLY` is what holds, not the session default
- Several statements in one call (`SELECT 1; COMMIT; UPDATE ...`, with and without a `fetchSize`) are refused by Postgres:
  "cannot insert multiple commands into a prepared statement". The driver uses the extended protocol because the service
  always sets a `fetchSize` above 0. The lexer is still the rule that guarantees it, since this depends on the driver.
- Publish is all-or-nothing: with a trigger that made the draft delete fail, the publish returned `500` and left no
  published row and the draft in place; after removing the trigger it published and removed the draft.
- Startup check: it refuses to start (exit 1, with the reasons) when the projection login is the engine login, a superuser,
  or the service login (which can write the projections); with `mode=warn` it logs the same and starts. It warns when
  `public.query_draft` is found.

**Not checked**

- Postgres versions other than 14, a real PgBouncer, and a production provisioning run with real secrets and a DBA-owned
  administrator login (only the dev `postgres` login was used).
- Provisioning a database whose service login already exists with its own password or tables (the template leaves an existing
  service login alone, but this path was not run), and a projection schema other than `public`.
- The 100K-row, 6-concurrent-pull memory and saturation baseline from `reactive_R2DBC_Cursor-impl.md`. The 50K pull above
  matched earlier timings, but this is not a like-for-like comparison.
- The explicit-grants alternative.
## Open points

- PgBouncer: not used today. Rule 4 covers the engine's own SQL. Author queries rely on the reader login's
  `search_path`, which is set on the role and so works behind a pooler. PgBouncer older than 1.21 doesn't support the
  prepared statements the driver uses in transaction mode. Check the version if one is added.
- Session settings: with `set_config` denylisted, author SQL can't change session settings. If a gap turns up, add a
  connection reset when a connection returns to the projection pool.
- A write attempt that reaches the database (only possible with the lexer off) comes back as `500 QUERY_EXECUTION_FAILED`
  with SQLSTATE `25006` rather than a dedicated code.
- `docs/reactive_R2DBC_Cursor-impl.md` section 12 is an earlier plan for the same problem; this document replaces it.
- The 77.8% stream halt (`statement_timeout` across a whole cursor read) is unchanged by this work and stays on the
  pending list.
