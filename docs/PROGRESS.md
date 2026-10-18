# Query Engine — Progress / Resume Notes

Last updated: 2026-10-18. Snapshot of what's been built so we can pick up later. The `/data-query` backpressure work is
implemented; see "Backpressure for /data-query".

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
  stores and error handling. A service only supplies `spring.r2dbc.*` and its projection tables.
- **query-engine owns its tables.** `query-engine/src/main/resources/query-engine/schema.sql` (drafts, published queries,
  realm roles/groups catalog) runs on every service startup; all statements are idempotent. Disable with
  `query-engine.schema.initialize=false`. Each domain's projection tables are seeded by its own `docker/init`.
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
(cd order-query && ./gradlew bootJar) && docker compose -f order-query/docker/docker-compose.yml up -d --build --wait
(cd inventory-query && ./gradlew bootJar) && docker compose -f inventory-query/docker/docker-compose.yml up -d --build --wait
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
- **Per-query `fetchSize`:** optional in the draft (`fetchSize`, 1 to 10000), stored on `query_draft` and `published_query`,
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
1. **Reported halt at 77.8% (open, cause unknown).** A run over the 100,000-row dataset in the Downstream demo stopped at
   77.8% and the page received no more data. Not yet diagnosed; nothing was captured from the service at the time.
   It may be the same thing as the one unexplained stall seen during the spike (first full pull after a container
   recreate, cut at 14,192 rows by the 30 s stall guard). Whether it is related is not established.
   - What to capture the next time it happens, right after the halt:
     - `docker logs order-query-app 2>&1 | grep "data-query query" | tail -3`. The `outcome=` and `reason=` fields say who
       ended the stream: `reason=STREAM_STALLED` means the page stopped reading for 30 s and the server cut it off on purpose
       (a frozen, hidden or sleeping tab would cause that; not confirmed); `outcome=error` means a database or pool failure;
       `outcome=cancelled` means the browser or proxy dropped the connection; `outcome=completed` means the server finished and
       the page lost the tail.
     - The browser console and the Network tab entry for `/data-query` (state, size, error).
     - `docker stats order-query-app order-query-postgres` and the `pg_stat_activity` query shown on the demo page.
     - The Resource usage panel's "Copy CSV" for the run (CPU, memory, event-loop lag, rows per second, tab hidden marks).
   - Logging that would make this diagnosable without reproducing it (proposed, not built):
     - a progress line for long streams every 10 s (rows, elapsed, rows/s) so a stall shows as a flat counter;
     - log the last-row time and the demand state when the stall guard fires;
     - log the moment a stream is cancelled and which side closed it, if the server can tell;
     - suppress the noisy `UnsupportedOperationException` when an error occurs after the response is committed.
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

## Possible next steps

- Open the Downstream demo page in a browser and watch memory and the Postgres connection while it runs.
- Quiet the post-commit error logging and investigate the one unexplained stream stall.
- Auth is still a stand-in: `X-Author` and `X-Roles` headers. `/data-query` defaults to `senior_DBA` without `X-Roles`.
- The roles catalog is seeded locally per database; swap for the real IdP (e.g. Keycloak) later.
- Publish `query-engine` to a shared Maven repository instead of Maven Local, so services build on other machines and in CI.
- No automated tests yet.
