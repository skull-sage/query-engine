# Query Engine — Progress / Resume Notes

Last updated: 2026-10-25. Snapshot of what's been built so we can pick up later. The `/data-query` backpressure work is
implemented; see "Backpressure for /data-query". The security protocol (separate schema and logins, read-only queries,
SQL lexer) is built; see "Security protocol".

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
  stores and error handling. A service supplies `spring.r2dbc.*` and its projection tables; the engine opens two more pools
  (`query-engine.store.*` for its tables, `query-engine.projection.*` for author queries), each with its own login.
- **query-engine owns its tables, in the `query_engine` schema.** `query-engine/src/main/resources/query-engine/schema.sql`
  (`drafted_query`, `published_query`, `realm_role`, `realm_group`) runs on every service startup as
  `query_engine_owner`; all statements are idempotent. Disable with `query-engine.schema.initialize=false`. Each domain's
  projection tables stay in `public`, seeded by its own `docker/init` (as the admin). The logins are created by `./gradlew provision`
  in the service (see "Security protocol").
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
# per domain: database first, then its logins (read from the service's application.yml), then the service
docker compose -f order-query/docker/docker-compose.yml up -d --wait postgres
(cd order-query && ./gradlew bootJar provision)
docker compose -f order-query/docker/docker-compose.yml up -d --build --wait
docker compose -f inventory-query/docker/docker-compose.yml up -d --wait postgres
(cd inventory-query && ./gradlew bootJar provision)
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
- UI: production build passes; dev-server proxy reaches both services; a stopped service surfaces as `SERVICE_UNREACHABLE`.
  The dialogs themselves have not been exercised in a browser yet.

## Backpressure for /data-query (implemented)
Full design, acceptance criteria and measured findings: `docs/reactive_R2DBC_Cursor-impl.md`.

- **Contract:** `Accept: application/x-ndjson` streams one row per line, reading Postgres through a cursor in `fetchSize`
  portions only as fast as the client reads. `application/json` stays the default but is capped (`413 RESULT_TOO_LARGE`
  above 1000 rows). A failure after the first byte aborts the connection (no marker line).
- **Per-query `fetchSize`:** optional in the draft (`fetchSize`, 1 to 10000), stored on `drafted_query` and `published_query` (`query_draft` before the schema move),
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

## Security protocol (built 2026-10-25)
Full design, DDL, rules and what was checked: `docs/security_protocol.md`. It replaces the SELECT-only plan in section 12 of
`docs/reactive_R2DBC_Cursor-impl.md`.

- **Schema and logins.** Engine tables moved to the `query_engine` schema (`query_draft` renamed `drafted_query`), owned by
  `query_engine_owner`. Author queries run as `query_engine_ro` (`pg_read_all_data`, owns nothing). The service keeps its
  own login for the projections. One shared template in the library (`query-engine/provision.sql`) creates them:
  `./gradlew provision` in a service reads that service's `application.yml` (service login = `spring.r2dbc.*`, engine login =
  `query-engine.store.*`, reader = `query-engine.projection.*`, database from the URL) and runs it as the admin login
  (`postgres`, from the environment, never in the yml). Safe to run again, e.g. after changing a password. Existing databases:
  run `query-engine/db/migrate_public_to_query_engine.sql` first.
- **Read-only.** Every projection query runs in `BEGIN READ ONLY` on its own pool. Postgres refuses writes there, also from a
  `SECURITY DEFINER` function, and a query that flipped `default_transaction_read_only` on its pooled session did not get past it.
- **SQL lexer.** `SqlLexer` / `SqlGuard` (backend) and `src/sql/sqlGuard.js` (UI, live "SQL checks" panel) reject anything but
  a single SELECT, at draft, simulate, publish and call time, and audit stored queries at startup. Both run
  `query-engine/src/test/resources/sql-guard-cases.json`. `vitest` is new in the UI (`npm test`). The draft step flags any invalid
  definition (SQL, YAML, queryName, params, fetchSize) as you type: red panel, red editor outline, header badge, footer note;
  after a save the server's list replaces it. Checked in headless Chrome.
- **Startup check.** The service refuses to start unless the engine and projection logins differ, neither is a superuser, and the
  projection login can write no table. `query-engine.security.mode=warn` starts anyway for an unprovisioned dev machine.
- **Config is YAML.** Both services use `application.yml` (the `.properties` files are gone). `schema.sql` no longer uses `ALTER`:
  `fetch_size` is part of the `CREATE TABLE`s (older tables get it from the migration script).
- **Verified** live in both domains (details in the doc). **Not verified:** Postgres other than 14,
  PgBouncer, a production provisioning run, the 100K / 6-pull memory baseline.
- **Known trade-offs:** `pg_read_all_data` lets the reader see `query_engine` when the lexer is off (the lexer rejects
  `query_engine.`); a column literally named `insert` / `update` / `delete` / `merge` must be quoted; a write that reaches
  the database comes back as `500 QUERY_EXECUTION_FAILED` (SQLSTATE 25006), not a dedicated code.

## Possible next steps

- Fix the 77.8% halt (open issue 1: `statement_timeout` on streams, the dev proxy hiding aborts, a page-side watchdog).
- Map SQLSTATE 25006 / 42501 from the projection pool to a dedicated error code (e.g. `QUERY_NOT_READ_ONLY`).
- Open the Downstream demo page in a browser and watch memory and the Postgres connection while it runs.
- Quiet the post-commit error logging and investigate the one unexplained stream stall.
- Auth is still a stand-in: `X-Author` and `X-Roles` headers. `/data-query` defaults to `senior_DBA` without `X-Roles`.
- The roles catalog is seeded locally per database; swap for the real IdP (e.g. Keycloak) later.
- Publish `query-engine` to a shared Maven repository instead of Maven Local, so services build on other machines and in CI.
- Automated tests are thin: `StreamGuardsTest` and `SqlGuardTest` in the library, `vitest` for the SQL lexer in the UI. Nothing covers the stores or the lifecycle yet.
