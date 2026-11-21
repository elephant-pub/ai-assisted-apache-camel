-- Design 003: also the Catalog Price for a Partner Order (set by OrderChecks.evaluate) and the Pick List outbox row.
-- Record one checked Order of either Channel (UC-003 steps 5-6) in one atomic statement: the Accepted Order, its Order Lines
-- with the Peakmart Price (UC-002 BR-003), state Recorded (only from Received) and the Order History.
-- The body is the completed canonical Order JSON (validated against order.schema.json before this statement).
WITH input AS (
  SELECT CAST(:#${exchangeProperty.receipt-id} AS bigint) AS receipt_id,
         CAST(:#${body} AS jsonb)                         AS doc
),
r AS (
  UPDATE order_receipt o
  SET order_state = 'Recorded', waiting_for = NULL, waiting_order = NULL, checking_since = NULL,
      waiting_reason = NULL, last_check_at = NULL
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
),
-- Design 003: the Pick List outbox row of the Accepted Order, in the same statement as its recording (UC-004 BR-001)
pick AS (
  INSERT INTO pick_list (customer_order_id, file_name, total_weight_g, delivery_state, created_at, attempts)
  SELECT c.id,
         'PL-' || (i.doc ->> 'orderNumber') || '.csv',
         (SELECT COALESCE(SUM(w.quantity * w."unitWeightGrams"), 0)
          FROM jsonb_to_recordset(i.doc -> 'lines') AS w(quantity integer, "unitWeightGrams" integer)),
         'Pending',
         now(),
         0
  FROM c, input i
)
INSERT INTO order_state_change (order_receipt_id, order_state, changed_at)
SELECT r.id, 'Recorded', now() FROM r
