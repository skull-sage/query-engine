-- Order history read model: 50,000 rows used to demonstrate and verify backpressure on /data-query
-- (see docs/reactive_R2DBC_Cursor-impl.md). A downstream service pulls this list over HTTP and
-- processes it a batch at a time; the service and database must stay paced by that consumer.
--
-- Separate from order_projection on purpose, so the hand-written sample rows and the documented
-- results of the existing sample queries stay untouched.
--
-- Deterministic: every value derives from md5(g), never random(), so each load is identical and
-- results can be compared between runs. Different slices of the hash feed different columns so
-- the attributes are not correlated with each other.
--
-- Wide on purpose: `notes` makes a row about 700 bytes as JSON, so the whole result is roughly
-- 35 MB. Socket and client buffers hold a few MB, so a slow consumer stalls the stream long
-- before all the data is in flight, which makes the pacing visible.
--
--   order_id       H-000001 .. H-050000 (strictly sequential)
--   customer_id    about 5,000 distinct
--   branch_id      3, 5, 7, 9, 12, 21
--   status         SHIPPED (30%), PENDING (20%), QUEUED, IN_PROGRESS, PROCESSING, CANCELLED, RETURNED (10% each)
--   channel        WEB, MOBILE, POS, CALL_CENTER
--   customer_tier  GOLD, SILVER, BRONZE
--   order_date     2026-01-01 .. 2026-10-12
--   total          5.00 .. 499.99
--
-- Column names are snake_case so they need no quoting in query templates.

CREATE TABLE IF NOT EXISTS order_history_projection (
    order_id       VARCHAR(16)    NOT NULL PRIMARY KEY,
    customer_id    VARCHAR(16)    NOT NULL,
    branch_id      INT            NOT NULL,
    status         VARCHAR(32)    NOT NULL,
    channel        VARCHAR(16)    NOT NULL,
    customer_tier  VARCHAR(16)    NOT NULL,
    order_date     DATE           NOT NULL,
    total          NUMERIC(12, 2) NOT NULL,
    notes          TEXT           NOT NULL
);

INSERT INTO order_history_projection
    (order_id, customer_id, branch_id, status, channel, customer_tier, order_date, total, notes)
SELECT
    'H-' || lpad(g::text, 6, '0'),
    'C-' || lpad((('x' || substr(h, 17, 4))::bit(16)::int % 5000 + 1)::text, 5, '0'),
    (ARRAY[3, 5, 7, 9, 12, 21])[1 + ('x' || substr(h, 1, 4))::bit(16)::int % 6],
    (ARRAY['SHIPPED', 'SHIPPED', 'SHIPPED', 'PENDING', 'PENDING',
           'QUEUED', 'IN_PROGRESS', 'PROCESSING', 'CANCELLED', 'RETURNED'])[1 + ('x' || substr(h, 5, 4))::bit(16)::int % 10],
    (ARRAY['WEB', 'MOBILE', 'POS', 'CALL_CENTER'])[1 + ('x' || substr(h, 9, 4))::bit(16)::int % 4],
    (ARRAY['GOLD', 'SILVER', 'BRONZE'])[1 + ('x' || substr(h, 13, 4))::bit(16)::int % 3],
    DATE '2026-01-01' + (('x' || substr(h, 21, 4))::bit(16)::int % 285),
    5 + (('x' || substr(h, 25, 6))::bit(24)::int % 49500) / 100.0,
    repeat(h, 16)
FROM (
    SELECT g, md5(g::text) AS h
    FROM generate_series(1, 50000) AS g
) AS src
ON CONFLICT (order_id) DO NOTHING;

ANALYZE order_history_projection;
