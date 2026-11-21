-- Record one checked Peakmart Order (UC-003 steps 5-6) in one atomic statement: the Accepted Order, its Order Lines
-- with the Peakmart Price (UC-002 BR-003), state Recorded (only from Received) and the Order History.
-- The body is the completed canonical Order JSON (validated against order.schema.json before this statement).
WITH input AS (
  SELECT CAST(:#${exchangeProperty.receipt-id} AS bigint) AS receipt_id,
         CAST(:#${body} AS jsonb)                         AS doc
),
r AS (
  UPDATE order_receipt o
  SET order_state = 'Recorded', waiting_for = NULL, waiting_order = NULL
  FROM input i
  WHERE o.id = i.receipt_id AND o.order_state = 'Received'
  RETURNING o.id
),
c AS (
  INSERT INTO customer_order (order_receipt_id, order_number, ordered_at, customer_email, recipient_name, street_line,
                              city, state_region, postal_code, country, recorded_at)
  SELECT r.id,
         i.doc ->> 'orderNumber',
         CAST(i.doc ->> 'orderedAt' AS timestamptz),
         i.doc ->> 'customerEmail',
         i.doc -> 'shippingAddress' ->> 'recipientName',
         i.doc -> 'shippingAddress' ->> 'streetLine',
         i.doc -> 'shippingAddress' ->> 'city',
         i.doc -> 'shippingAddress' ->> 'stateRegion',
         i.doc -> 'shippingAddress' ->> 'postalCode',
         i.doc -> 'shippingAddress' ->> 'country',
         now()
  FROM r, input i
  RETURNING id
),
lines AS (
  INSERT INTO order_line (customer_order_id, line_number, sku, product_name, quantity, unit_price, unit_weight_grams)
  SELECT c.id, l."lineNumber", l.sku, l."productName", l.quantity, CAST(l."unitPrice" ->> 'amount' AS numeric(10,2)),
         l."unitWeightGrams"
  FROM c, input i,
       jsonb_to_recordset(i.doc -> 'lines')
         AS l("lineNumber" integer, sku text, "productName" text, quantity integer, "unitPrice" jsonb,
              "unitWeightGrams" integer)
)
INSERT INTO order_state_change (order_receipt_id, order_state, changed_at)
SELECT r.id, 'Recorded', now() FROM r
