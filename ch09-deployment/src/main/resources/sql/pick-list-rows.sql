-- The rows of one Pick List file (docs/messages/mappings/order-to-pick-list.md): one row per Order Line in line order,
-- the twelve warehouse columns in file order. The street line is stored already joined by ", ". Only the columns the
-- Warehouse needs are selected (UC-004 BR-003, C-021); the total weight is the one computed when the order was recorded.
SELECT c.order_number      AS order_no,
       l.line_number       AS line_no,
       l.sku               AS sku,
       l.product_name      AS description,
       l.quantity          AS qty,
       l.unit_weight_grams AS unit_weight_g,
       c.recipient_name    AS ship_name,
       c.street_line       AS ship_street,
       c.city              AS ship_city,
       c.postal_code       AS ship_postcode,
       c.country           AS ship_country,
       p.total_weight_g    AS total_weight_g
FROM customer_order c
JOIN order_line l ON l.customer_order_id = c.id
JOIN pick_list p ON p.customer_order_id = c.id
WHERE c.id = CAST(:#${exchangeProperty.customer-order-id} AS bigint)
ORDER BY l.line_number
