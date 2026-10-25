-- Inventory domain read model: inventory_projection, as maintained by the InventoryQuery
-- service's event consumers in production (StockReceived, StockReserved, ProductDiscontinued,
-- ...). Seeded here so queries can be drafted and simulated against realistic, varied data.
--
-- One row per SKU per warehouse. Filterable attributes and their spread:
--   status            IN_STOCK, LOW_STOCK, OUT_OF_STOCK, BACKORDERED, DISCONTINUED
--   warehouse_id      WH-DHK, WH-CTG, WH-SYL, WH-KHL
--   category          ELECTRONICS, GROCERY, APPAREL, PHARMA, HOME
--   supplier_id       SUP-01 .. SUP-05
--   quantity_on_hand  0 .. 950 (compare against reorder_level for "needs restock" queries)
--   unit_price        0.85 .. 1450.00
--   last_restocked    2026-08-15 .. 2026-10-12
--
-- snake_case column names are used so they need no quoting in query templates.

-- Seeded as the administrator (docker-entrypoint-initdb.d), before the database logins exist; `./gradlew provision`
-- then lets the service login use and write these tables.

CREATE TABLE IF NOT EXISTS inventory_projection (
    sku               VARCHAR(32)    NOT NULL,
    warehouse_id      VARCHAR(16)    NOT NULL,
    product_name      VARCHAR(128)   NOT NULL,
    category          VARCHAR(32)    NOT NULL,
    supplier_id       VARCHAR(16)    NOT NULL,
    quantity_on_hand  INT            NOT NULL,
    reorder_level     INT            NOT NULL,
    unit_price        NUMERIC(12, 2) NOT NULL,
    status            VARCHAR(32)    NOT NULL,
    last_restocked    DATE           NOT NULL,
    PRIMARY KEY (sku, warehouse_id)
);

INSERT INTO inventory_projection
    (sku, warehouse_id, product_name, category, supplier_id, quantity_on_hand, reorder_level, unit_price, status, last_restocked)
VALUES
    ('SKU-1001', 'WH-DHK', 'Wireless Earbuds',          'ELECTRONICS', 'SUP-01', 340, 100,   49.99, 'IN_STOCK',     '2026-10-02'),
    ('SKU-1001', 'WH-CTG', 'Wireless Earbuds',          'ELECTRONICS', 'SUP-01',  42, 100,   49.99, 'LOW_STOCK',    '2026-09-11'),
    ('SKU-1002', 'WH-DHK', '27in 4K Monitor',           'ELECTRONICS', 'SUP-02',   0,  20, 1450.00, 'OUT_OF_STOCK', '2026-08-15'),
    ('SKU-1002', 'WH-SYL', '27in 4K Monitor',           'ELECTRONICS', 'SUP-02',  18,  20, 1450.00, 'LOW_STOCK',    '2026-09-20'),
    ('SKU-1003', 'WH-KHL', 'USB-C Charger 65W',         'ELECTRONICS', 'SUP-01',   0,  50,   29.50, 'BACKORDERED',  '2026-08-28'),
    ('SKU-2001', 'WH-DHK', 'Basmati Rice 5kg',          'GROCERY',     'SUP-03', 950, 300,   12.40, 'IN_STOCK',     '2026-10-12'),
    ('SKU-2001', 'WH-CTG', 'Basmati Rice 5kg',          'GROCERY',     'SUP-03', 610, 300,   12.40, 'IN_STOCK',     '2026-10-09'),
    ('SKU-2002', 'WH-SYL', 'Green Tea 100 bags',        'GROCERY',     'SUP-03',  75, 120,    6.25, 'LOW_STOCK',    '2026-09-30'),
    ('SKU-2003', 'WH-KHL', 'Mineral Water 500ml',       'GROCERY',     'SUP-04', 820, 400,    0.85, 'IN_STOCK',     '2026-10-11'),
    ('SKU-2004', 'WH-DHK', 'Instant Noodles (Legacy)',  'GROCERY',     'SUP-04',   0,   0,    0.95, 'DISCONTINUED', '2026-08-20'),
    ('SKU-3001', 'WH-DHK', 'Cotton T-Shirt M',          'APPAREL',     'SUP-05', 220,  80,    8.99, 'IN_STOCK',     '2026-09-25'),
    ('SKU-3001', 'WH-KHL', 'Cotton T-Shirt M',          'APPAREL',     'SUP-05',  30,  80,    8.99, 'LOW_STOCK',    '2026-09-05'),
    ('SKU-3002', 'WH-CTG', 'Rain Jacket L',             'APPAREL',     'SUP-05',   0,  25,   39.00, 'BACKORDERED',  '2026-08-30'),
    ('SKU-3003', 'WH-SYL', 'Denim Jeans 32',            'APPAREL',     'SUP-05', 145,  60,   24.75, 'IN_STOCK',     '2026-10-04'),
    ('SKU-4001', 'WH-DHK', 'Paracetamol 500mg x100',    'PHARMA',      'SUP-02', 500, 200,    3.10, 'IN_STOCK',     '2026-10-08'),
    ('SKU-4001', 'WH-SYL', 'Paracetamol 500mg x100',    'PHARMA',      'SUP-02', 160, 200,    3.10, 'LOW_STOCK',    '2026-09-27'),
    ('SKU-4002', 'WH-CTG', 'Digital Thermometer',       'PHARMA',      'SUP-01',   0,  40,   15.60, 'OUT_OF_STOCK', '2026-09-01'),
    ('SKU-4003', 'WH-KHL', 'Hand Sanitizer 250ml',      'PHARMA',      'SUP-04', 380, 150,    2.45, 'IN_STOCK',     '2026-10-10'),
    ('SKU-5001', 'WH-DHK', 'Non-stick Frying Pan',      'HOME',        'SUP-03',  64,  30,   22.00, 'IN_STOCK',     '2026-09-18'),
    ('SKU-5001', 'WH-CTG', 'Non-stick Frying Pan',      'HOME',        'SUP-03',  12,  30,   22.00, 'LOW_STOCK',    '2026-09-02'),
    ('SKU-5002', 'WH-SYL', 'LED Desk Lamp',             'HOME',        'SUP-02',   0,  15,   18.90, 'OUT_OF_STOCK', '2026-08-22'),
    ('SKU-5003', 'WH-KHL', 'Ceramic Dinner Set (Old)',  'HOME',        'SUP-03',   0,   0,   55.00, 'DISCONTINUED', '2026-08-18'),
    ('SKU-5004', 'WH-DHK', 'Bath Towel Set',            'HOME',        'SUP-05',   0,  40,   14.30, 'BACKORDERED',  '2026-09-08'),
    ('SKU-1004', 'WH-CTG', 'Bluetooth Speaker',         'ELECTRONICS', 'SUP-02', 210,  60,   35.00, 'IN_STOCK',     '2026-10-06')
ON CONFLICT (sku, warehouse_id) DO NOTHING;
