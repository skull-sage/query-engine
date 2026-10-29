# Query Engine — Progress / Resume Notes

Last updated: 2026-10-27. Snapshot of what's been built so we can pick up later. The `/data-query` backpressure work is
implemented; see "Backpressure for /data-query". The engine now uses one datasource and one table (`qe_stored_query`) with the
states drafted / simulated / published, and runs every author query in `BEGIN READ ONLY`; see "One datasource, one table".

## Architecture

The enterprise is event-driven microservices following DDD and CQRS. `query-engine` is the shared read-side
abstraction; each domain has its own query service that embeds it and owns its own projection store:

| Project | Kind | Port | Database (host port) |
| --- | --- | --- | --- |
| `query-engine` | Java library (no app, no image) | – | – |
| `order-query` | Spring Boot service, depends on `query-engine` | 8081 | `order_query` Postgres (5433) |
| `inventory-query` | Spring Boot service, depends on `query-engine` | 8082 | `inventory_query` Postgres (5434) |
| `query-builder-ui` | React UI, talks to the selected domain's service | 5173 | – |

- **Independent Gradle builds.** `query-engine`, `order-query` and `inventory-query` each have their own `settings.gradle`,
  `build.gradle` and wrapper; there is no root build. `query-engine` is published locally
  (`cd query-engine && ./gradlew publishToMavenLocal`) as `com.brac.query:query-engine:0.0.1-SNAPSHOT`, and each service
  resolves it from `mavenLocal()` (restricted to the `com.brac.query` group).
- **query-engine wires itself in** via `QueryEngineAutoConfiguration` (registered in
  `META-INF/spring/...AutoConfiguration.imports`): its controllers (`/engine/*`, `/data-query`, `/query-roles`), service,
  store and error handling. A service supplies `spring.r2dbc.*` and its projection tables; the engine uses that datasource for
  everything, plus a second pool of the same datasource for author SQL (`query-engine.projection.*` only sizes it).
- **query-engine owns one table, `qe_stored_query`.** `query-engine/src/main/resources/query-engine/schema.sql` is a single
  `CREATE TABLE IF NOT EXISTS`, run on every service start with the service's own login (disable with
  `query-engine.schema.initialize=false` when a DBA creates it). One row per query; its `state` is drafted, simulated or
  published (PRD, "Query lifecycle"). Each domain's projection tables stay in `public`, seeded by its own `docker/init`, which
  also creates the service's plain login. No SQL file contains an `ALTER`.
- **Separate docker compose per service** (`<service>/docker/docker-compose.yml`): its own Postgres + the service container,
  independent networks and lifecycles.
- Reactive end to end (WebFlux → reactive service → R2DBC), Java 25, no in-memory lifecycle state.

## Seed data (for WHERE-clause variance)

- `order_projection` (24 rows): `status` (7 values incl. QUEUED, IN_PROGRESS), `branchId` (6), `channel` (WEB, MOBILE, POS,
  CALL_CENTER), `customerTier` (GOLD, SILVER, BRONZE), `orderDate`, `total`.
- `inventory_projection` (24 rows, one per SKU per warehouse): `status` (IN_STOCK, LOW_STOCK, OUT_OF_STOCK, BACKORDERED,
  DISCONTINUED), `warehouse_id` (4), `category` (5), `supplier_id` (5), `quantity_on_hand` vs `reorder_level`, `unit_price`,
  `last_restocked`.

## Engine API notes

- `allowed_roles` are chosen at **publish** time (`POST /engine/publish {queryName, allowed_roles}`; none → 422
  `NO_ALLOWED_ROLES`). Drafts carry no roles.
- `DraftResponse` now also returns `entry` (`{sql, params}` as submitted, present even when validation failed) and
  `simulated`, so an editor can resume any draft and knows whether it can be published.

## UI

- Header domain switcher; first visit asks for a domain, later visits reopen the last one. Switching remounts the workspace.
- Main view: query list with All / Draft / Published tabs and name search.
- Query wizard dialog (Draft → Simulate → Publish) for new queries and for a list item's Edit / Simulate / Publish.
  Recovers when the server's draft has moved on (gone, re-saved elsewhere, no longer simulated) by sending the user to the
  step that fixes it. All requests are cancelled when a dialog closes or the domain changes.
- Run dialog for published queries: pick the calling identity (allowed roles, or others to see the 403), fill params, run.

## How to run

```bash
./dry-run.sh                    # both domain stacks + UI, opens http://localhost:5173
./dry-run.sh --domain order     # one domain only (repeatable)
./dry-run.sh --no-frontend      # services only; use <service>/http/*.http
./stop.sh                       # stop everything and reset the databases
```

The UI dev server is managed by `scripts/ui-dev-server.sh` (sourced by both scripts): it runs `vite` in its own process
group and is stopped by killing the whole group (TERM, then KILL after 5s). `stop.sh` also stops any untracked dev
server of this UI (e.g. a manual `npm run dev`), recognized by its working directory being `query-builder-ui`; other
programs are never touched. `dry-run.sh` reclaims port 5173 from such leftovers, refuses if another program holds it,
and stops the dev server it started if it fails or is interrupted before the UI is ready.

Without the script:

```bash
(cd query-engine && ./gradlew publishToMavenLocal)
# per domain: build the jar, then the database and the service (the init scripts create the service's login)
(cd order-query && ./gradlew bootJar)
docker compose -f order-query/docker/docker-compose.yml up -d --build --wait
(cd inventory-query && ./gradlew bootJar)
docker compose -f inventory-query/docker/docker-compose.yml up -d --build --wait
(cd query-builder-ui && npm run dev)
```

## Verified (2026-10-17)

- Both services build independently against the locally published query-engine; each database gets the engine tables
  plus only its own projection.
- Order: draft → simulate (WEB/GOLD → 6 rows) → publish to `[data_analyst, finance]` → invoke as finance → 200; as
  warehouse_manager → 403.
- Inventory: JSON and YAML drafts, wrong param type → 400, simulate WH-DHK/IN_STOCK → 5 rows, restock-by-category → 4 rows,
  publish without roles → 422, publish → invoke WH-CTG/LOW_STOCK → 2 rows.
- Isolation: an Order query name on inventory-query → 404; each service lists only its own queries.
- Restarting a service container keeps its drafts and published queries.
- UI: production build passes; (originally via the dev-server proxy, now direct, see "Direct service calls") a stopped service surfaces as `SERVICE_UNREACHABLE`.
  The dialogs themselves have not been exercised in a browser yet.

## Backpressure for /data-query (implemented)
Full design, acceptance criteria and measured findings: `docs/reactive_R2DBC_Cursor-impl.md`.

- **Contract:** `Accept: application/x-ndjson` streams one row per line, reading Postgres through a cursor in `fetchSize`
  portions only as fast as the client reads. `application/json` stays the default but is capped (`413 RESULT_TOO_LARGE`
  above 1000 rows). A failure after the first byte aborts the connection (no marker line).
- **Per-query `fetchSize`:** optional in the draft (`fetchSize`, 1 to 10000), stored in the `fetch_size` column of `qe_stored_query`,
  `INVALID_FETCH_SIZE` on a bad value. The UI YAML editor passes it through.
- **Protection:** a separate projection pool (acquire timeout 3 s, `statement_timeout` 60 s) so slow streams cannot starve
  `/engine/*`; stall timeout 30 s and max duration 10 min fail the stream rather than ending it; pool exhaustion is
  `503 SERVICE_BUSY` with `Retry-After`. All values are configurable under `query-engine.*`.
- **Demo:** `order_history_projection` (50,000 rows, `order-query/docker/init/02_...sql`) and the "Downstream demo" nav
  item in `query-builder-ui`, which prepares `orders.historyByDate` itself and consumes it 100 rows per 100 ms.
  `order-query/docker/docker-compose.lowmem.yml` caps the service at a 192 MB heap to show memory staying flat.
- **Verified:** unit tests for the stream guards; live runs against order-query (see findings in the impl doc). **Not
  verified:** the demo page in a real browser (checked with a Node consumer through the Vite proxy, and a production build).
- **Known issues:** see "Open issues" below.

### Spike findings (summary)
Measured 2026-10-18 on order-query in Docker, Postgres 14, with the first 100,000-row dataset (since reduced to 50,000;
the findings do not depend on the row count). Full detail is in `docs/reactive_R2DBC_Cursor-impl.md`, section 9.

- Cursor reads are demand-driven: Postgres logs one `execute fetch` per `fetchSize` rows, and fetching stops when the client
  stops. The read-ahead between consumer and database is a fixed bound of about 14.6K rows (about 8 MB), the same at 6 s and
  14 s of reading.
- A full pull took about 2 s. Memory stayed inside a 192 MB heap, also with 6 concurrent pulls.
- Errors before the first byte come back as JSON with the right status, also under `Accept: application/x-ndjson`. An error
  after the first byte aborts the transfer (curl exit 18).
- Disconnects release the connection (all backends idle after 25 clients were killed). Pool exhaustion gives
  `503 SERVICE_BUSY` with `Retry-After` while `/engine/list` stays fast (46 ms).
- Not verified: a statement hitting `statement_timeout`; a Postgres error before the first row under NDJSON; a real browser.

### Open issues
1. **Halt at 77.8%: cause found (2026-10-19), fix not yet made.** Reproduced with the 100,000-row dataset at the demo's pace
   (100 rows per 100 ms): the page stopped at 77,000 rows and stayed on "Streaming" with no error. Two separate causes:
   - **Root cause: `statement_timeout`.** The projection connections set `statement_timeout = 60s`
     (`query-engine.stream.statement-timeout`). Postgres counts it across the whole cursor read, not per fetch, so any stream
     that stays open for more than 60 s is cancelled (`canceling statement due to statement timeout` in the Postgres log;
     `outcome=timeout reason=QUERY_TIMEOUT durationMs=61834` in the service log). At 1,000 rows/s the consumer was at about
     62K rows when it fired, and it then drained the roughly 15K rows already buffered, which is why it stopped near 77%.
     The 50K runs finish in about 50 s, under the limit, which is why they passed. Proof: the same 100K run with the
     timeout raised to 5 minutes delivered all 100,000 rows in order (Finished at 1:41, `outcome=completed`).
   - **Second defect: the Vite proxy hides the abort.** When the service aborts a stream mid-way, curl straight to the service
     exits 18 (incomplete transfer), but the same call through the dev-server proxy (`/api/order/...`) keeps the connection open
     and delivers nothing more until the client's own timeout. The page therefore never sees an error. Reproduced with a 10 s
     timeout: direct curl exit 18 after 21.7 s, proxied curl exit 28 (client timeout) at 40 s, same row count both times.
   - Proposed fixes (not made): (a) stop using `statement_timeout` as the stream limit; it was meant to bound runaway queries
     but it also caps slow consumers, and `max-duration` (10 min) and the stall guard already bound a stream. Options: raise it
     above `max-duration`, apply it only to JSON/simulate, or time the first row only. (b) make the proxy close the browser
     connection when the upstream aborts (`proxyRes.on('aborted', () => res.destroy())` in `vite.config.js`). (c) a
     client-side watchdog in the page that fails the run when no bytes arrive for a while.
   - Why it was not seen earlier: the spike's longest stream was under 60 s.
2. Noisy ERROR log when an error happens after the response is committed (harmless, see above).
3. One unexplained stall in a first run after a container recreate (not reproduced in about 20 runs).
4. The Downstream demo page has been checked with a Node consumer and a production build, not by hand in a browser (see the
   resource monitor entry below for what was checked).

### Downstream demo page and resource monitor
- The page no longer streams on navigation. It has a start block (rows per batch, pause per batch, "Run query"); pressing
  Run query prepares `orders.historyByDate` if it is not published, then streams. Below that is a two-column layout: runtime
  stats, recent batches and service availability on the left; resource charts on the right.
- Resource monitor: container CPU and memory come from `docker stats` through a dev-server-only Vite plugin
  (`query-builder-ui/vite-plugins/dockerStats.js`, `GET /__dev/stats`), plus browser measurements (event-loop lag, long
  tasks, JS heap on Chromium, tab visibility) and rows per second, one sample per second. Design and limits are in
  `docs/reactive_R2DBC_Cursor-impl.md`, section 11.
- Verified (2026-10-18) in real headless Chrome driven over the DevTools protocol against the live stack: nothing is requested on
  navigation; "Run query" prepares the query and streams; all 50,000 rows arrive (progress 5,000 per 5 s at the default pace);
  all seven charts, the availability card and "Copy CSV" render with data; Stop cancels the request; the service log shows one
  `outcome=completed rows=50000` per run. `/__dev/stats` returned live CPU and memory for both containers. During a run the
  service stayed `Up` with 6 to 11 ms responses. The server finished emitting about 31 s into a roughly 50 s run, which fits
  a read-ahead of roughly 19K rows between the service and the page.
- Not verified: a hidden or throttled tab, a Docker-less machine (the "No container figures" path), Firefox and Safari (JS heap
  and long tasks are Chromium-only), and the original 77.8% halt, which did not occur in these runs.
- Not built: Spring Boot Actuator metrics in the services (JVM heap, GC, process CPU). Left out because it opens an
  unauthenticated endpoint; it is listed in section 11 as an option.

## Drafts no longer expire (2026-10-19)
- Removed the 7-day draft TTL: no `expires_at`, no `expiresAt` in the draft, simulate and list responses, no expiry check
  on lookups or on the lock. A draft stays until it is published or discarded. Startup drops the old `expires_at` column.
- Consequence: another author's draft keeps its `409 DRAFT_LOCKED` until it is published or discarded. Any caller can
  discard today (no author check), so a forgotten draft is released by discarding it.
- Verified live on order-query: draft, lock (409 with the new message), simulate, list, a draft backdated 60 days still found
  and publishable, and the old column dropped on restart. UI list and wizard no longer show "expires".

## Author-SQL pool limited to 4 (2026-10-27)
- `query-engine.projection.max-size` is now **4** (default in `DBProjectionPoolProperties`, and written out in both `application.yml`
  files as `${QE_PROJECTION_MAX_SIZE:4}`, with `initial-size` 2). To be revisited. Measured with 6 slow readers on `order-query`: 4 streams
  ran, 2 answered `503 SERVICE_BUSY` after 6 s, the author-SQL pool held exactly 4 connections, and `/engine/list` stayed at 5 to 7 ms
  because storing and listing use the service's own pool.
- Consequence to remember: simulate and the draft-time `EXPLAIN` check use the same 4 connections, so while 4 streams are open a
  draft waits about 6 s and is saved without the database check (`SQL_NOT_CHECKED`), and simulate answers `503`. The earlier idea of
  keeping connections free for them (cap streams below the pool size) is still open.

## Security protocol (revised 2026-10-27)
Full design, rules, trade-offs and what was checked: `docs/security_protocol.md`. The three-login design built on 2026-10-25
(`query_engine_owner`, `query_engine_ro`, a `query_engine` schema, `./gradlew provision`) was replaced by "One datasource, one
table" below.

- **Read-only.** Every author query (simulate, `/data-query`, the draft-time `EXPLAIN`) runs in `BEGIN READ ONLY` on a dedicated
  pool. Postgres refuses writes there, also from a `SECURITY DEFINER` function, and a query that flipped
  `default_transaction_read_only` on its pooled session did not get past it. Held with the SQL guard switched off, too.
- **SQL lexer.** `SqlLexer` / `SqlGuard` (backend) and `src/sql/sqlGuard.js` (UI, live panel) reject anything but a single SELECT
  and any use of `qe_stored_query` (`SQL_FORBIDDEN_TABLE`), at draft, simulate, publish and call time, and audit stored queries at
  startup. Both run `query-engine/src/test/resources/sql-guard-cases.json`.
- **Startup check.** The read-only self-test (a write through the author-SQL path must be refused with `25006`) is fatal in every
  mode. A superuser or BYPASSRLS login is refused in `query-engine.security.mode=enforce`.
- **Config is YAML,** with production guidance as comments (secrets, plain login, pre-created table, TLS, CORS, identity headers).
- **Known trade-offs:** the login can read `qe_stored_query`, so only the lexer keeps author SQL from other queries' SQL and roles;
  a column literally named `insert` / `update` / `delete` / `merge` must be quoted; a write that reaches the database (only possible
  with the lexer off) comes back as `500 QUERY_EXECUTION_FAILED` (SQLSTATE 25006), not a dedicated code.

## One datasource, one table (2026-10-27)
- **Datasource.** The engine uses the service's own `spring.r2dbc.*` login for everything; author SQL gets a second pool of the same
  datasource. Removed: `query-engine.store.*`, the url / login / password settings of `query-engine.projection.*`, `EngineDatabase`,
  `DraftStore`, `PublishedQueryRepository`, `QueryRolesRepository`, the `provision` package and `provision.sql`, the `provision` Gradle
  task in both services, and `db/migrate_public_to_query_engine.sql`. There is no migration for older databases (reset a development
  volume with `./stop.sh`).
- **One table.** `qe_stored_query`, one row per query, `state` drafted | simulated | published, `revision` for conditional writes.
  Lifecycle rules are in `QueryLifecycle` (unit tested); the backend validates every transition and applies it with a single
  conditional `UPDATE`, so a stale step answers `409 DRAFT_CHANGED` instead of overwriting.
- **Editing a published query** (your decision): it moves back to `drafted` and is offline until it is simulated and published
  again. Drafting over a published query needs `"unpublish": true` (else `409 QUERY_PUBLISHED`). While offline `/data-query` answers
  `409 QUERY_NOT_PUBLISHED`. Discard of such a query deletes it. The UI asks for confirmation before the first save, shows
  "Offline · being edited", and starts the publish step from the previous roles.
- **Roles catalog** moved from two tables to configuration: `query-engine.roles`, with built-in demo defaults.
- **Dev database.** `docker/init/00_service_login.sql` creates the plain login; the projection scripts grant it read and write.
  The compose files no longer pass `QE_*` variables.
- **Verified** (details in `docs/security_protocol.md`): 38 live lifecycle checks on `order-query`; every write blocked with the SQL
  guard off; superuser refused / allowed with `warn`; the whole wizard flow in headless Chrome; 140 backend and 133 UI tests.
  **Not verified:** Postgres other than 14, a DBA-created table with `schema.initialize=false`, several instances at once, the 100K
  baseline.
- **Docs changed:** PRD ("Query lifecycle" is new; the as-built sections follow it), `security_protocol.md` (rewritten),
  `order-query/http/publish_query.http`. `docs/query-engine-schema-and-select-only.md` describes the superseded design.

## Direct service calls (2026-10-26)
- The Vite dev-server proxy (`/api/order`, `/api/inventory`) is removed. The browser calls `order-query` (8081) and
  `inventory-query` (8082) directly, so each request and response to a service origin can be inspected, blocked, overridden
  or given breakpoints in the browser's DevTools. The UI's domain descriptor (`src/domains.js`) now carries only `serviceUrl`
  (no `apiBase`); override with `VITE_ORDER_QUERY_URL` / `VITE_INVENTORY_QUERY_URL` when the dev server starts.
- Cross-origin now, so the services answer CORS: new `query-engine.cors.allowed-origins` (library: `CorsProperties`,
  `web/EngineCors`, a `CorsWebFilter` first in the chain, only present when the property is set; empty means no browser
  origin is allowed). Dev defaults in both `application.yml`: `http://localhost:5173` and `http://127.0.0.1:5173`
  (`QE_CORS_ALLOWED_ORIGINS` overrides). Methods GET, POST, OPTIONS; headers Content-Type, Accept, X-Author, X-Roles; no
  credentials; `Retry-After` exposed; preflight cached 30 min. `*` is accepted but logged as a warning. There is still no
  authentication, so the origin list is the only browser-side control.
- `/__dev/stats` stays on the dev server (it is not a service proxy).
- Verified: unit tests (`EngineCorsTest`, 5 cases; 128 backend tests and 121 UI tests pass); `curl` preflights (allowed origin
  gets the headers, another origin gets 403, an error response from the service still carries the header); real headless Chrome
  with the Network domain on: every service call goes to `localhost:8081` / `8082` with one preflight per endpoint, the
  Downstream demo delivered all 50,000 rows in order, the inventory list loads, and with order-query stopped the page shows "Not
  answering".
- Not verified: the UI's "Can't reach ... allows this page's origin" message against a real CORS refusal in a browser (only the
  server's 403 was checked), and an aborted stream seen from a browser through a direct connection. The earlier halt defect where the
  Vite proxy hid a service abort should be gone with the proxy, but that has not been re-tested in a browser.
- Trade-offs: a production deployment must list its UI origin(s); the UI shows one generic "Can't reach" message for a refused
  connection and for a CORS refusal because the browser does not tell scripts which it was.

## Invalid SQL is now reported at draft and simulate (2026-10-26)
- Problem: the lexer only checks that the text is a single read-only SELECT, so `SELECT * FORM t`, a missing column or a type
  mismatch was saved as `VALIDATED`, and only simulate failed, as a generic `500 QUERY_EXECUTION_FAILED`.
- Draft: after the lexer and param checks pass, `ProjectionStoreExecutor.check` runs `EXPLAIN <sql>` (planned, never executed)
  in the read-only transaction, binding each param with a neutral sample of its declared type. A Postgres rejection (SQLSTATE
  class 42, 22 or 0A) becomes error `SQL_INVALID` with Postgres's wording, so the draft is stored as `DRAFT` and Simulate and
  Publish stay locked. If the database is busy or unreachable the draft is saved unchecked (logged), never marked invalid.
- Simulate: a database rejection of the SQL (for example a division by zero that only happens on real rows, which `EXPLAIN` cannot
  see) is now `422 SQL_INVALID` with the database message, instead of `500`. `/data-query` still answers `500
  QUERY_EXECUTION_FAILED` without database wording.
- UI: the draft step shows "Saved, but the query definition is invalid" with the `SQL_INVALID` line; the simulate step shows
  "The SQL is invalid" with the message and a hint to go back to the Draft step.
- Verified: 14 SQL cases against order-query (10 invalid ones now `DRAFT` with `SQL_INVALID`; 4 valid ones, including a window
  function, a CTE and a trailing `;`, still `VALIDATED` and simulate fine); `ProjectionExceptionTest` (3 cases); in headless
  Chrome, a syntax error shows the invalid-draft panel with the Postgres message and locks Simulate, and a draft that only fails on
  data shows "The SQL is invalid" in the simulate step. Also seen in Chrome: with the CORS origin list empty the page shows "Can't
  reach ... allows this page's origin".
- Limits: `EXPLAIN` only plans, so errors that depend on row data surface at simulate; the planner folds constant expressions, so
  `SELECT 1/0` is caught at draft. Each draft now costs one extra planning round trip. A draft that is edited into a valid state
  is rechecked on every save.
- Follow-up (2026-10-26, report "SELECT FROM projection WHERE is still accepted, no UI error"): on a current build both services reject
  it (checked on 8081 and 8082, through `/engine/draft` and `/engine/draft-yml`, and in Chrome in the wizard): status `DRAFT`, error
  `SQL_INVALID: syntax error at end of input`, Simulate locked. Two ways it could still look accepted, both now addressed:
  1. If the database cannot be asked (reader login cannot connect, pool busy), the draft used to be saved as `VALIDATED` with only a
     server log line, and the UI said "Draft saved and validated". The response now carries `warnings: [SQL_NOT_CHECKED]` and the
     draft step shows an amber "Draft saved, but the SQL was not checked against the database". Reproduced by setting the reader role
     `NOLOGIN`; the draft then saves `VALIDATED` with the warning, and simulate shows the real connection error.
  2. The live panel under the editor said "The definition looks valid" for any single SELECT, including broken SQL. It now says there
     are no structural problems and that the database checks the SQL when you save. The live panel still cannot find syntax errors;
     only the save does.
  3. A service started from an older build (a container not rebuilt, or `bootRun` against an old `mavenLocal` engine jar) has only the
     lexer and does accept such SQL. `./dry-run.sh` republishes the engine and rebuilds the images; for `bootRun`, run
     `cd query-engine && ./gradlew publishToMavenLocal` first.
- Note: `order-query/src/main/resources/application.yml` currently has `cors.allowed-origins: "${QE_CORS_ALLOWED_ORIGINS:}"`
  (empty default, edited outside this session). An empty list installs the CORS filter with no allowed origin, so the UI cannot
  reach the service until `QE_CORS_ALLOWED_ORIGINS=http://localhost:5173` is set.

## Cross-origin switch and proxy mode (2026-10-26)
- Backend: `query-engine.cors.enabled` (default true) is now an explicit switch next to `allowed-origins`. CORS is on only when
  it is true and at least one origin is listed; otherwise the filter is a pass-through and the service does no CORS handling
  (no headers, no 403), and startup logs one line saying which. `order-query` ships `enabled: "${QE_CORS_ENABLED:false}"`
  (off, as you had it, but now by the switch, with the origin list kept in the file); `inventory-query` ships it on.
  Overrides `QE_CORS_ENABLED`, `QE_CORS_ALLOWED_ORIGINS`.
- Frontend: a domain can be switched to proxy mode when the dev server starts: `VITE_ORDER_QUERY_MODE=proxy` or
  `VITE_INVENTORY_QUERY_MODE=proxy` (one domain at a time). The dev proxy exists only for those domains, with abort propagation
  so a service-side abort reaches the browser. Default stays direct. `src/domains.js` now carries `apiBase` (what the browser
  calls), `serviceUrl` (the real address), `connection` and `modeVar` again.
- Frontend error message: when a direct call fails, the client retries with `mode: 'no-cors'`. If that completes, the service is
  running but refuses this origin: code `CORS_BLOCKED`, title "Blocked by the browser (CORS)", with both fixes spelled out (turn
  CORS on in the service, or start the UI in proxy mode). If it fails too, `SERVICE_UNREACHABLE` as before. In proxy mode an empty 5xx
  from the proxy is "Can't reach ... through the dev server".
- Verified: backend unit tests (`EngineCorsTest` now 7 cases) and `engineClient.test.js` (7 cases); all 128 UI tests pass. Live, in
  headless Chrome: default run, order shows the CORS_BLOCKED message while inventory loads; order started in proxy mode loads through
  `/api/order` (no `:8081` call from the browser, availability Up) while inventory stays direct; with order-query stopped in proxy
  mode the page says "Can't reach ... through the dev server". Startup log shows "CORS is off ..." for order-query and "CORS is on for
  [...]" for inventory-query.
- Not verified: streaming through proxy mode, and the abort propagation hook (written, not exercised); `dry-run.sh` with the variable
  exported was not run end to end (the dev server was started by hand with the variable).

## Endpoint Separation & Concurrency Benchmark: Plain Batch vs Reactive Streaming (2026-10-29)

- **Backend Endpoint Separation (`query-engine`)**:
  - The reactive streaming endpoint was renamed from `/data-query` to `@PostMapping("/data-query-streaming")`, preserving reactive cursor streaming (`application/x-ndjson`) with R2DBC statement `fetchSize`.
  - Implemented `@PostMapping(path = "/data-query", produces = MediaType.APPLICATION_JSON_VALUE)` executing non-streaming reactive queries using Spring Data R2DBC (`executeDirect`) returning standard JSON arrays (`List<Map<String, Object>>`), excluding database cursors and chunked framing.
  - Added `executeDirect` in `DataQueryService` and `ProjectionStoreExecutor`.
  - Updated `GlobalExceptionHandler` to handle Spring WebFlux `WebExchangeBindException` alongside `MethodArgumentNotValidException`.
  - Added unit test suite in `DataQueryControllerTest.java` (all 145 backend tests pass).
  - Published updated library to Maven Local (`cd query-engine && ./gradlew publishToMavenLocal`).

- **Frontend Visualization (`query-builder-ui`)**:
  - Renamed `features/downstream-demo` to `features/streaming-demo` (`StreamingDemoPage.jsx`, `useStreamingDemo.js`) and updated UI routing to target `/data-query-streaming`.
  - Created `features/plain-batch-demo/PlainBatchDemoPage.jsx` and `usePlainBatchDemo.js` to visualize paginated batch loading against `/data-query`, featuring bidirectional synchronization between UI fetch size and SQL `LIMIT` / `OFFSET`.
  - Added unit tests in `usePlainBatchDemo.test.js` (all 134 frontend tests pass, production Vite build succeeds).

- **Performance & Concurrency Audit (`scripts/benchmark-cpu-mem.mjs`)**:
  - Built an automated, non-invasive benchmarking tool using an out-of-band Docker daemon stats stream (`docker stats --format '{{json .}}'`) to monitor `order-query-app` and `order-query-postgres` with zero in-container agent overhead.
  - Implemented strict sequential isolation with 4-second cooldown intervals between tests to eliminate GC and connection pool interference.
  - Evaluated concurrency scaling across 10, 40, 50, and 100 concurrent clients fetching 3,000 rows with a fixed batch size of 100 rows.
  - **Key Empirical Findings**:
    - **PostgreSQL CPU**: Streaming saves up to 68% PostgreSQL CPU at 100 concurrency (24.8% vs 78.7%) by eliminating the $O(N)$ row-scanning penalty of repeated SQL `OFFSET` traversals.
    - **Application CPU**: Streaming reduces application CPU by ~37% under burst load by replacing 3,000 separate HTTP request-response cycles with 100 persistent chunked NDJSON streams.
    - **Memory Stability**: Both approaches exhibit bounded, stable JVM heap utilization (~855–865 MiB).
  - Detailed methodology, power tables, and architectural analysis are documented in [docs/benchmark_batch_vs_streaming_concurrency.md](file:///Volumes/work-space/brac-v3/generic-query/docs/benchmark_batch_vs_streaming_concurrency.md).

## Possible next steps

- Fix the 77.8% halt (open issue 1: `statement_timeout` on streams, the dev proxy hiding aborts, a page-side watchdog).
- Map SQLSTATE 25006 / 42501 from the author-SQL pool to a dedicated error code (e.g. `QUERY_NOT_READ_ONLY`).
- The login can read `qe_stored_query`; decide whether that is acceptable or the table needs a schema author SQL cannot read.
- Open the Downstream demo page in a browser and watch memory and the Postgres connection while it runs.
- Quiet the post-commit error logging and investigate the one unexplained stream stall.
- Auth is still a stand-in: `X-Author` and `X-Roles` headers. `/data-query` defaults to `senior_DBA` without `X-Roles`.
- The roles catalog comes from `query-engine.roles` (or built-in demo defaults); point it at the real IdP (e.g. Keycloak) later.
- Publish `query-engine` to a shared Maven repository instead of Maven Local, so services build on other machines and in CI.
- Automated tests: the library has `QueryLifecycleTest`, `SqlGuardTest`, `StreamGuardsTest`, `EngineCorsTest` and `ProjectionExceptionTest`; the UI has `vitest` for the SQL lexer, definition checks and the client. Nothing covers `StoredQueryRepository` or the service end to end against a database except the manual runs recorded here; a Testcontainers test would.
