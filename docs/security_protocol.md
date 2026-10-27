# Query Engine — Security Protocol

Status: revised 2026-10-27. The engine now uses **one datasource**, the service's own, and **one table**, `qe_stored_query`.
The earlier design (a separate engine login, a read-only login, a `query_engine` schema and a provisioning step) was removed on
purpose; the section "What changed from the three-login design" says what that trades away. The section "What was checked"
lists what was run and what was not.

## Why

Each domain service (`order-query`, `inventory-query`) embeds query-engine. A published query is SQL written by an author and
run by the engine. It must only ever read projection data: it must not write or drop projection tables, change stored queries
(for example give itself more `allowed_roles`), or write through a stored function that looks like a plain `SELECT`.

## The four rules

1. **One datasource.** The engine connects with the service's own `spring.r2dbc.*` login and has no database settings of its
   own. The login must be a plain one (not a superuser, not `BYPASSRLS`); the service refuses to start otherwise.
2. **Every author query runs inside `BEGIN READ ONLY`.** On a dedicated pool of the same datasource, used for nothing but
   author SQL (simulate, `/data-query`, and the `EXPLAIN` check at draft time). Postgres refuses every write in such a
   transaction, DML and DDL alike, including writes made inside stored functions and `SECURITY DEFINER` functions. The engine's
   own bookkeeping writes use the service's normal pool and never carry author SQL.
3. **A SQL lexer checks the query at draft, simulate, publish and call time.** It rejects anything that is not a single
   `SELECT`, and any use of `qe_stored_query`. This gives authors clear errors early. It is a guard rail: it cannot see inside
   functions, so rule 2 is what actually stops writes.
4. **The backend owns every state change of a stored query.** A query is one row that moves `drafted` -> `simulated` ->
   `published`. The service checks that a step is allowed (who holds the draft, whether it was simulated, whether the roles are
   set, whether the SQL still passes the lexer) and applies it as one conditional `UPDATE`, so a step based on a row that
   changed in the meantime changes nothing. See the PRD, "Query lifecycle".

## Who can do what

| Login | Can read | Can write | Used by |
| --- | --- | --- | --- |
| The service's own login (`order_query`, `inventory_query`) | its projections and `qe_stored_query` | its projections and `qe_stored_query` | the service's event consumers, the engine's bookkeeping (draft, simulate state, publish, list, lookup), and the author-SQL pool |
| Admin (`postgres` in development) | everything | everything | creating the login and seeding the projections (`docker/init`). Never the running service |

How the layers stack up for an author's SQL:

| Attack | Stopped by |
| --- | --- |
| `DELETE`, `UPDATE`, `DROP`, `TRUNCATE`, DDL in the SQL | Lexer, and the read-only transaction (`SQLSTATE 25006`) |
| `UPDATE qe_stored_query ...` (widen `allowed_roles`, publish something unsimulated) | Lexer, and the read-only transaction |
| Reading other queries' SQL or roles from `qe_stored_query` | Lexer only (the login may read its own table) |
| `SELECT my_function()` where the function writes, even `SECURITY DEFINER` owned by a superuser | The read-only transaction |
| `COMMIT; DELETE ...` to break out of the transaction | Lexer (one statement only). The driver also refuses several commands in one prepared statement |
| `set_config(...)` that leaks to the next caller on a pooled connection | Lexer function denylist. Without it the call works, but the explicit `BEGIN READ ONLY` still holds |
| `pg_terminate_backend(...)` on the service's own sessions | Lexer function denylist only; everything shares one login now |
| A stored query that was published before a rule was added | Checked again at call time (`SQL_REJECTED`) and logged at startup |

## What changed from the three-login design

The earlier design used `query_engine_owner` (owns the engine's tables) and `query_engine_ro` (author SQL, reads only), kept apart
by a provisioning step and a startup check. This design drops them. What that gives up:

| Gone | Effect |
| --- | --- |
| A login that could not write anything | If author SQL ever ran outside the read-only wrapper, the login would no longer stop a write. Mitigated by making the wrapper the only way to reach that pool (`ProjectionDatabaseClient.client()` is package-private) and by a startup self-test that sends a write through it and requires Postgres to refuse it |
| `pg_read_all_data` and the engine's tables closed to author SQL | The login can read `qe_stored_query`. Only the lexer keeps author SQL away from it |
| Separate logins in the database log | `pg_stat_activity` shows one login; the pools differ by `application_name` (`query-engine-projection` for author SQL) |
| A login that could not kill the engine's sessions | Only the lexer's function denylist stops `pg_terminate_backend` |

What it keeps: Postgres itself refuses every write from author SQL (rule 2), and that held in every test, including with the
lexer switched off. The lexer is the only thing standing between author SQL and reading the stored queries.

## Startup checks (`SecurityStartupCheck`)

1. **Read-only self-test, always.** Through the very path author SQL takes, the engine requires `transaction_read_only = on` and
   sends a harmless write (`CREATE TEMPORARY TABLE`) that must be refused with `SQLSTATE 25006`. If not, the service does not
   start, in every mode.
2. **The login.** In `query-engine.security.mode=enforce` (the default) the service refuses to start if the login is a superuser
   or bypasses row-level security. `warn` logs and starts, for a development machine whose database uses the image's default
   superuser. `off` skips this check.
3. **Stored queries.** Every published query is run through the lexer; failures are logged and are refused when called.

## The table

`query-engine/src/main/resources/query-engine/schema.sql` is a single `CREATE TABLE IF NOT EXISTS qe_stored_query`. It runs on
boot with the service's login, in the service's default schema. No SQL file in the repository contains an `ALTER`: a file that
changes tables in place makes a startup do things nobody planned. A change to the table's shape is a deliberate migration (a new
table name, or a script a person runs), not a side effect of starting a newer version. Columns and meaning: PRD, "Query
lifecycle".

For production the table can be created ahead of time by a DBA with that same statement (the service login then needs `SELECT`,
`INSERT`, `UPDATE` and `DELETE` on it and no right to create tables), with `query-engine.schema.initialize=false`.

## Production guidance

The same points are written as comments in each service's `application.yml`.

- **Secrets.** No real password in `application.yml`, the image or git. Supply `R2DBC_URL`, `DB_USERNAME` and `DB_PASSWORD` from the
  environment or a secret manager.
- **Login.** A plain login (`NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`) that can read and write this service's projection
  tables and nothing else. Keep it as narrow as the service allows, even though author SQL cannot write.
- **Transport.** TLS to the database (for example `?sslMode=require`), and the database off public networks.
- **Identity.** The engine trusts the `X-Roles` and `X-Author` headers; there is no authentication yet. Put it behind a gateway
  that authenticates callers and sets them, and never expose a service port directly. CORS is not authentication.
- **CORS.** Only your UI's real `https` origins in `query-engine.cors.allowed-origins`, never `*`.
- **SQL guard.** Leave `query-engine.sql-guard.enabled` on. Turning it off exists only to test the database protection by itself.
- **Roles catalog.** `query-engine.roles` should list the names that exist in your identity provider. It only fills pick-lists.
- **Limits.** `query-engine.stream.*` bounds streams (`max-duration`, `stall-timeout`, pool `acquire-timeout`). Keep
  `statement-timeout` above `max-duration`, because Postgres applies it to the whole read of a stream.

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
| `SQL_FORBIDDEN_TABLE` | No use of the engine's own table, `qe_stored_query`, under any schema prefix, quoting or case | `SELECT sql_text FROM qe_stored_query` |
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
  family is there because it would read `'qe_stored_query'` without the name ever appearing as an identifier.

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
  the service login, and a plain login cannot see another login's queries in `pg_stat_activity`.

Tests: `vitest` 5.0.5 (pinned dev dependency, `npm test`). `src/sql/sqlGuard.test.js` runs the same case file as the
backend, read by relative path, so the two lexers can't quietly drift apart. `checkDefinition.test.js` covers each definition
problem and checks that the sample drafts and the demo query the UI ships pass.

## What was checked (2026-10-27)

Against the dockerized stack (Postgres 14, both domains), from a fresh volume, after the move to one datasource.

**Passed**

- Fresh `./dry-run.sh` for both domains: no provisioning step. The init script creates the service login as a plain login; the
  service starts, creates `qe_stored_query` (owned by that login), and the startup log shows "Security check passed: author SQL runs
  read-only as login 'order_query'" (and `'inventory_query'`).
- The lifecycle, in 38 live checks on `order-query`: draft; another author locked out; publish refused before simulate; simulate;
  an edit clears "simulated"; publish needs roles; publish; `/data-query` as an allowed role, a refused role and a bare name; draft
  over a published query refused without `unpublish`; simulate and discard of a published query refused; unpublish by another author
  keeps the previous roles; `/data-query` while being edited answers `409 QUERY_NOT_PUBLISHED`; republish with new roles takes effect
  (old role refused); discard of an offline query deletes it (`404` afterwards); invalid SQL stays a draft with `SQL_INVALID`; reading
  `qe_stored_query` is rejected; and a re-draft that overlaps a running simulate makes the simulate answer `409 DRAFT_CHANGED` and
  leaves the new draft un-simulated.
- With the SQL guard switched off (`QUERYENGINE_SQLGUARD_ENABLED=false`), so only the database protection is left, every one of these
  failed with `SQLSTATE 25006` and changed nothing (24 projection rows intact, none `HACKED`, no new tables, no stored queries
  touched): `UPDATE` and `DELETE` on a projection, `CREATE TABLE AS`, `SELECT INTO`, `UPDATE qe_stored_query`, a data-modifying CTE,
  and a `SECURITY DEFINER` function owned by the superuser that updates a table. After `set_config('default_transaction_read_only',
  'off', false)` was run six times to leak onto pooled connections, four further `UPDATE`s and the definer function still failed.
  `DROP TABLE`, `TRUNCATE` and `COMMIT; UPDATE ...` were refused at draft time as invalid SQL; a two-statement query was refused by
  the driver. Reading `qe_stored_query` worked with the guard off, as the trade-off above says.
- A superuser login: refused at startup in `enforce` mode with the reason and the way out; with `QE_SECURITY_MODE=warn` it starts and
  logs the same text.
- UI, in real headless Chrome: draft, simulate and publish through the wizard; Edit on a published query shows the "take it offline"
  confirmation and sends no `unpublish` until the first save; the list shows "Offline · being edited" with no Run button; the discard
  confirmation says it deletes the query completely; republishing starts with the previous roles ticked.
- Tests: 140 backend (including 14 for the lifecycle rules and 113 SQL guard cases) and 133 UI.

**Not checked**

- Postgres versions other than 14, PgBouncer, a production setup with a DBA-created login and table
  (`query-engine.schema.initialize=false`), and a login without the right to create tables.
- A database that still holds the previous design's schema and tables: they are not migrated or read (there is deliberately no
  migration script). A development volume is reset by `./stop.sh`.
- The 100K-row, 6-concurrent-pull memory baseline from `reactive_R2DBC_Cursor-impl.md`.
- Several instances of one service running against one database at the same time (the conditional writes are designed for it but it
  was not run).

## Open points

- The login can read `qe_stored_query` (SQL text and allowed roles of every query). Only the lexer keeps author SQL from it. If that
  is not acceptable, the table would have to move to a schema or login the author-SQL pool cannot read, which is the design this one
  replaced.
- A write attempt that reaches the database (only possible with the lexer off) comes back as `500 QUERY_EXECUTION_FAILED` with
  `SQLSTATE 25006` rather than a dedicated code.
- Session settings: with `set_config` denylisted, author SQL cannot change session settings. If a gap turns up, add a connection
  reset when a connection returns to the projection pool.
- The 77.8% stream halt (`statement_timeout` across a whole cursor read) is unchanged by this work and stays on the pending list
  (`docs/PROGRESS.md`).
