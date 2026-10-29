# Query Builder UI

React + Tailwind CSS v4 front end for the domain query services (`order-query`, `inventory-query`). Each service embeds
`query-engine`; this UI drafts a query as YAML, simulates it against that domain's real projection store, publishes it with
allowed roles, and test-runs published queries the way a client app would.

## Layout

- **Header** — domain switcher (OrderQuery / InventoryQuery). The first visit asks for a domain; later visits reopen the last one.
  Switching domains unmounts the whole workspace (cancelling requests, closing dialogs) and loads the new domain's queries.
- **Query list** (`/:domainKey`, the main view) — every query, each in exactly one state (drafted, simulated or published), filtered by
  the `All` / `Draft` / `Published` tabs (`?tab=draft|published`) and by name. Editing a published query takes it offline: Edit asks for
  confirmation first, and until the query is simulated and published again the list marks it "Offline · being edited" and callers get
  `409 QUERY_NOT_PUBLISHED`. Discarding such a query deletes it.
- **Query wizard** (dialog) — Draft → Simulate → Publish. "New query" opens it at Draft; a list item's Edit / Simulate /
  Publish opens the same dialog at that step for that query.
- **Run dialog** — calls a published query through `POST /data-query`, choosing the identity (sent as `X-Roles`) from the
  query's allowed roles, or any other role to check that it's refused with 403.

## How the UI reaches the services

Each domain is reached in one of two ways, chosen when the dev server starts.

| Mode | What the browser calls | The service needs | Use it for |
| --- | --- | --- | --- |
| direct (default) | the service itself, e.g. `http://localhost:8081` | CORS on for this page's origin | inspecting each service call in DevTools against the service's own origin |
| proxy | the dev server, e.g. `http://localhost:5173/api/order`, forwarded to the service | nothing | a service that has CORS off |

In direct mode the Network tab shows every request and response to `http://localhost:8081` (order-query) or `http://localhost:8082`
(inventory-query), so they can be inspected, blocked, overridden or given breakpoints per service origin.

| Domain | Service | Switch to proxy mode | Override the address |
| --- | --- | --- | --- |
| order | `http://localhost:8081` (order-query) | `VITE_ORDER_QUERY_MODE=proxy` | `VITE_ORDER_QUERY_URL` |
| inventory | `http://localhost:8082` (inventory-query) | `VITE_INVENTORY_QUERY_MODE=proxy` | `VITE_INVENTORY_QUERY_URL` |

The variables are read when the dev server starts, one domain at a time, so one domain can be proxied while the other is direct:
`VITE_ORDER_QUERY_MODE=proxy ./dry-run.sh` or `VITE_ORDER_QUERY_MODE=proxy npm run dev`. Domains are declared in `src/domains.js`;
the proxy is configured in `vite.config.js` (keep the two in sync).

**CORS on the service.** Direct mode needs the service to allow the page's origin. In the service's `application.yml`:
`query-engine.cors.enabled` is the switch and `query-engine.cors.allowed-origins` lists the origins (exact scheme, host and port;
the dev defaults are `http://localhost:5173` and `http://127.0.0.1:5173`). CORS is off when `enabled` is `false` or the list is empty;
the service then sends no CORS headers, and a page on another origin cannot read its responses. Same-origin pages, proxy mode and
non-browser callers are never affected. Environment overrides: `QE_CORS_ENABLED`, `QE_CORS_ALLOWED_ORIGINS`. `order-query` ships with
CORS off (`enabled: false`), `inventory-query` with it on.

**When a direct call fails** the page says which of two things happened. If the service answers a request the browser would not let
the page read, you get "Blocked by the browser (CORS)" with the two ways out: turn CORS on in the service, or start the UI in proxy
mode for that domain. If nothing answers at all, you get "Can't reach ...". The page tells them apart by retrying with a `no-cors`
request, which is allowed to complete without being readable. Every direct POST sends a preflight `OPTIONS` first (the JSON body and
the `X-Roles` / `X-Author` headers require it); when CORS is on, the service lets the browser cache that for 30 minutes.

The dev server also serves `GET /__dev/stats` (container CPU and memory for the Downstream demo), which only exists under `npm run dev`.

## Running

From the workspace root, `./dry-run.sh` starts both domain stacks and this UI on http://localhost:5173. To run the UI alone
against services that are already up:

```bash
npm ci
npm run dev
```

The dev server uses a fixed port (5173, `strictPort`) and fails if it's taken, instead of silently moving to another port.

## Source map

- `src/api/engineClient.js` — per-domain client; cancellable requests and normalized errors (`SERVICE_UNREACHABLE` when the
  service is down, otherwise the engine's own error code).
- `src/context/` — `DomainContext` (current domain + client), `LeaveGuard` (confirms before a domain switch would lose
  unsaved wizard edits).
- `src/features/query-wizard/` — `wizardState.js` (reducer and step gating), the three step components, and the dialog that
  runs the requests and recovers when the server's draft has moved on (discarded, re-saved elsewhere, no longer simulated).
- `src/features/run-query/RunQueryModal.jsx` — the run dialog.
- `src/features/streaming-demo/` — the "Streaming demo" page (nav item, shown for domains with a `streamDemo` in
  `src/domains.js`, currently OrderQuery). It publishes `orders.historyByDate` if missing, then streams it from
  `/data-query-streaming` as NDJSON, reading 100 rows per 100 ms (adjustable) and checking the count and row order at the end.
  It keeps counters only, never the rows. Leaving the page aborts the request.
- `src/features/plain-batch-demo/` — the "Plain batch demo" page (`PlainBatchDemoPage.jsx`, nav item). It visualizes batch
  data loading using the new `POST /data-query` endpoint with SQL `LIMIT` and `OFFSET`. The fetch size corresponds
  directly to the LIMIT size and vice versa.
- `src/pages/` — `DomainSelectPage` (first visit), `QueryListPage` (main view).
