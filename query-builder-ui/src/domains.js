// The domain query services the Query Builder can work against. Each one is a separate
// deployable (order-query, inventory-query) embedding query-engine, with its own database and
// its own published queries.
//
// Each domain can be reached in one of two ways, chosen when the dev server starts:
//
//   direct (default)  The browser calls the service itself, at serviceUrl. Every request shows up in
//                     DevTools against the service's own origin. The page and the service are different
//                     origins, so the service must allow this page's origin: query-engine.cors.enabled
//                     and query-engine.cors.allowed-origins in its application.yml.
//   proxy             The browser calls the dev server under a path prefix (apiBase) and the dev server
//                     forwards it to the service (see vite.config.js). Same origin as the page, so the
//                     service needs no CORS setup. Use it for a service that has CORS off. Select it with
//                     VITE_<DOMAIN>_QUERY_MODE=proxy, for example VITE_ORDER_QUERY_MODE=proxy.
//
// serviceUrl is always the service's real address (shown to users as the endpoint independent client
// apps call, and the target of the proxy). VITE_<DOMAIN>_QUERY_URL overrides it. Keep the prefixes in
// sync with vite.config.js.
const trimSlash = (url) => url.replace(/\/+$/, '')

/** serviceUrl, apiBase (what the browser calls), how it is reached and which variable switches the mode. */
function connection({ url, mode, fallbackUrl, prefix, modeVar }) {
  const serviceUrl = trimSlash(url || fallbackUrl)
  const proxied = mode === 'proxy'
  return { serviceUrl, apiBase: proxied ? prefix : serviceUrl, connection: proxied ? 'proxy' : 'direct', modeVar }
}

export const DOMAINS = [
  {
    key: 'order',
    label: 'OrderQuery',
    service: 'order-query',
    description: 'Read model of the Order domain: order status, branch, sales channel, customer tier and order date.',
    ...connection({
      url: import.meta.env.VITE_ORDER_QUERY_URL,
      mode: import.meta.env.VITE_ORDER_QUERY_MODE,
      fallbackUrl: 'http://localhost:8081',
      prefix: '/api/order',
      modeVar: 'VITE_ORDER_QUERY_MODE',
    }),
    queryModule: 'orders',
    // The Downstream demo page (nav item) streams this query from /data-query to show a slow
    // consumer being served without the service buffering the result. The page creates and
    // publishes the query itself if it isn't there yet. Needs order_history_projection
    // (order-query/docker/init/02_order_history_projection.sql, 50,000 rows).
    streamDemo: {
      queryName: 'orders.historyByDate',
      sql: `SELECT order_id, customer_id, branch_id, status, channel, customer_tier, order_date, total, notes
FROM order_history_projection
WHERE order_date >= :since
ORDER BY order_id`,
      params: { since: 'date' },
      fetchSize: 100,
      args: { since: '2026-01-01' },
      role: 'data-platform',
      expectedRows: 50000,
      idField: 'order_id',
      idPrefix: 'H-',
    },
    sampleDraft: `queryName: orders.ordersByStatusAndBranch
sql: |-
  SELECT orderId, status, total, branchId, channel, customerTier, orderDate
  FROM order_projection
  WHERE status = :status AND branchId = :branchId
  ORDER BY orderDate DESC
params:
  status: String
  branchId: int
`,
  },
  {
    key: 'inventory',
    label: 'InventoryQuery',
    service: 'inventory-query',
    description: 'Read model of the Inventory domain: stock per SKU and warehouse, category, supplier, reorder levels and stock status.',
    ...connection({
      url: import.meta.env.VITE_INVENTORY_QUERY_URL,
      mode: import.meta.env.VITE_INVENTORY_QUERY_MODE,
      fallbackUrl: 'http://localhost:8082',
      prefix: '/api/inventory',
      modeVar: 'VITE_INVENTORY_QUERY_MODE',
    }),
    queryModule: 'inventory',
    sampleDraft: `queryName: inventory.stockByWarehouseAndStatus
sql: |-
  SELECT sku, product_name, category, quantity_on_hand, reorder_level, status
  FROM inventory_projection
  WHERE warehouse_id = :warehouseId AND status = :status
  ORDER BY sku
params:
  warehouseId: String
  status: String
`,
  },
]

export function findDomain(key) {
  return DOMAINS.find((d) => d.key === key) || null
}
