# Query Builder UI

React + Tailwind CSS v4 front end for the domain query services (`order-query`, `inventory-query`). Each service embeds
`query-engine`; this UI drafts a query as YAML, simulates it against that domain's real projection store, publishes it with
allowed roles, and test-runs published queries the way a client app would.

## Layout

- **Header** — domain switcher (OrderQuery / InventoryQuery). The first visit asks for a domain; later visits reopen the last one.
  Switching domains unmounts the whole workspace (cancelling requests, closing dialogs) and loads the new domain's queries.
- **Query list** (`/:domainKey`, the main view) — every query with an open draft and/or a published version, filtered by the
  `All` / `Draft` / `Published` tabs (`?tab=draft|published`) and by name.
- **Query wizard** (dialog) — Draft → Simulate → Publish. "New query" opens it at Draft; a list item's Edit / Simulate /
  Publish opens the same dialog at that step for that query.
- **Run dialog** — calls a published query through `POST /data-query`, choosing the identity (sent as `X-Roles`) from the
  query's allowed roles, or any other role to check that it's refused with 403.

## How the UI reaches the services

The dev server proxies one path prefix per domain to that domain's service, so the browser never calls a service cross-origin:

| Prefix | Service | Override target with |
| --- | --- | --- |
| `/api/order` | `http://localhost:8081` (order-query) | `ORDER_QUERY_URL` |
| `/api/inventory` | `http://localhost:8082` (inventory-query) | `INVENTORY_QUERY_URL` |

Domains are declared in `src/domains.js` (label, proxy prefix, service URL, sample draft); keep it in sync with `vite.config.js`.

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
- `src/features/downstream-demo/` — the "Downstream demo" page (nav item, shown for domains with a `streamDemo` in
  `src/domains.js`, currently OrderQuery). It publishes `orders.historyByDate` if missing, then streams it from
  `/data-query` as NDJSON, reading 100 rows per 100 ms (adjustable) and checking the count and row order at the end.
  It keeps counters only, never the rows. Leaving the page aborts the request.
- `src/pages/` — `DomainSelectPage` (first visit), `QueryListPage` (main view).
