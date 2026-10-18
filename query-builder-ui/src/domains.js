// The domain query services the Query Builder can work against. Each one is a separate
// deployable (order-query, inventory-query) embedding query-engine, with its own database and
// its own published queries.
//
// apiBase is the dev-server proxy prefix (see vite.config.js). serviceUrl is the service's real
// address, shown to users as the endpoint independent client apps call.

export const DOMAINS = [
  {
    key: 'order',
    label: 'OrderQuery',
    service: 'order-query',
    description: 'Read model of the Order domain: order status, branch, sales channel, customer tier and order date.',
    apiBase: '/api/order',
    serviceUrl: 'http://localhost:8081',
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
    apiBase: '/api/inventory',
    serviceUrl: 'http://localhost:8082',
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
