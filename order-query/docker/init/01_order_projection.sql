-- Order domain read model: order_projection, as maintained by the OrderQuery service's event
-- consumers in production (OrderPlaced, OrderShipped, ...). Seeded here so queries can be
-- drafted and simulated against realistic, varied data.
--
-- Filterable attributes and their spread:
--   status        QUEUED, PENDING, PROCESSING, IN_PROGRESS, SHIPPED, RETURNED, CANCELLED
--   branchId      3, 5, 7, 9, 12, 21
--   channel       WEB, MOBILE, POS, CALL_CENTER
--   customerTier  GOLD, SILVER, BRONZE
--   orderDate     2026-09-01 .. 2026-10-12
--   total         15.00 .. 500.00
--
-- Column names are intentionally unquoted so Postgres folds them to lowercase (orderid,
-- branchid, ...), matching how it folds the unquoted identifiers in a query template.

-- Seeded as the administrator (docker-entrypoint-initdb.d), after 00_service_login.sql, and then granted to the
-- service login at the end of this file: the service's event consumers write these tables.

CREATE TABLE IF NOT EXISTS order_projection (
    orderId      VARCHAR(32)    NOT NULL PRIMARY KEY,
    status       VARCHAR(32)    NOT NULL,
    total        NUMERIC(12, 2) NOT NULL,
    branchId     INT            NOT NULL,
    channel      VARCHAR(16)    NOT NULL,
    customerTier VARCHAR(16)    NOT NULL,
    orderDate    DATE           NOT NULL
);

INSERT INTO order_projection (orderId, status, total, branchId, channel, customerTier, orderDate) VALUES
    ('O-1001', 'SHIPPED',     128.50, 12, 'WEB',         'GOLD',   '2026-09-02'),
    ('O-1002', 'SHIPPED',      64.00, 12, 'MOBILE',      'SILVER', '2026-09-03'),
    ('O-1003', 'PENDING',      42.75, 12, 'POS',         'BRONZE', '2026-10-10'),
    ('O-1004', 'SHIPPED',     201.20,  7, 'WEB',         'GOLD',   '2026-09-05'),
    ('O-1005', 'CANCELLED',    15.00, 12, 'CALL_CENTER', 'BRONZE', '2026-09-06'),
    ('O-1006', 'SHIPPED',      99.99,  3, 'MOBILE',      'SILVER', '2026-09-08'),
    ('O-1007', 'PENDING',     310.00,  7, 'WEB',         'GOLD',   '2026-10-11'),
    ('O-1008', 'SHIPPED',      55.40, 12, 'POS',         'BRONZE', '2026-09-12'),
    ('O-1009', 'RETURNED',     88.25,  5, 'WEB',         'SILVER', '2026-09-14'),
    ('O-1010', 'PROCESSING',  143.10,  9, 'MOBILE',      'GOLD',   '2026-10-09'),
    ('O-1011', 'SHIPPED',      27.60, 21, 'POS',         'BRONZE', '2026-09-18'),
    ('O-1012', 'PENDING',     176.40,  5, 'CALL_CENTER', 'SILVER', '2026-10-12'),
    ('O-1013', 'CANCELLED',   500.00,  9, 'WEB',         'GOLD',   '2026-09-21'),
    ('O-1014', 'SHIPPED',      73.95, 21, 'MOBILE',      'SILVER', '2026-09-23'),
    ('O-1015', 'QUEUED',       34.20,  3, 'WEB',         'BRONZE', '2026-10-12'),
    ('O-1016', 'IN_PROGRESS', 215.75,  7, 'POS',         'GOLD',   '2026-10-08'),
    ('O-1017', 'QUEUED',      120.00, 12, 'MOBILE',      'SILVER', '2026-10-12'),
    ('O-1018', 'IN_PROGRESS',  18.90, 21, 'CALL_CENTER', 'BRONZE', '2026-10-07'),
    ('O-1019', 'QUEUED',      365.40,  9, 'WEB',         'GOLD',   '2026-10-11'),
    ('O-1020', 'IN_PROGRESS',  48.60,  5, 'MOBILE',      'SILVER', '2026-10-06'),
    ('O-1021', 'SHIPPED',     412.00,  3, 'WEB',         'GOLD',   '2026-09-25'),
    ('O-1022', 'RETURNED',     22.10, 21, 'POS',         'BRONZE', '2026-09-28'),
    ('O-1023', 'PROCESSING',   67.35, 12, 'CALL_CENTER', 'SILVER', '2026-10-10'),
    ('O-1024', 'SHIPPED',     158.80,  9, 'MOBILE',      'GOLD',   '2026-10-01')
ON CONFLICT (orderId) DO NOTHING;

-- The service's login reads and writes this table (its event consumers maintain it).
GRANT SELECT, INSERT, UPDATE, DELETE ON order_projection TO order_query;
