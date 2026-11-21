-- Receive one Partner Order request (UC-001 step 4, A4, A8) in one atomic statement. Returns one row:
-- outcome, receipt_id, order_state.
--   IN_PROGRESS  a receipt of this Partner and Order Number, any content, is being checked (checking_since newer than
--                the expiry, OQ-074 reading b); also the loser of two identical concurrent requests (C-8)
--   DUPLICATE    the receipt with the same Partner, Order Number and content is Rejected, Recorded or Sent to Warehouse
--   CLAIMED      that receipt is Received with no live checking_since: it is marked and checked again (BR-009)
--   NEW          no such receipt: one is inserted as Received, with its history entry, and marked as being checked
-- Two identical concurrent requests: the unique index makes one insert win and the row lock makes one claim win;
-- the other request sees neither its insert nor its claim and gets IN_PROGRESS.
WITH input AS (
  SELECT CAST(:#${exchangeProperty.partner-db-id} AS bigint)                   AS partner_id,
         CAST(:#${exchangeProperty.order-number} AS varchar)                   AS order_number,
         CAST(:#${exchangeProperty.content-hash} AS varchar)                   AS content_hash,
         CAST(:#${exchangeProperty.correlation-id} AS varchar)                 AS correlation_id,
         CAST(:#${exchangeProperty.received-at} AS timestamptz)                AS received_at,
         CAST(:#${exchangeProperty.checking-expiry-seconds} AS integer) * interval '1 second' AS expiry
),
busy AS (
  SELECT o.id
  FROM order_receipt o, input i
  WHERE o.channel = 'Partner' AND o.partner_id = i.partner_id AND o.order_number = i.order_number
    AND o.checking_since > now() - i.expiry
  LIMIT 1
),
same AS (
  SELECT o.id, o.order_state
  FROM order_receipt o, input i
  WHERE o.channel = 'Partner' AND o.partner_id = i.partner_id AND o.order_number = i.order_number
    AND o.content_hash = i.content_hash
),
claimed AS (
  UPDATE order_receipt o
  SET checking_since = now()
  FROM input i
  WHERE NOT EXISTS (SELECT 1 FROM busy)
    AND o.channel = 'Partner' AND o.partner_id = i.partner_id AND o.order_number = i.order_number
    AND o.content_hash = i.content_hash AND o.order_state = 'Received'
    AND (o.checking_since IS NULL OR o.checking_since < now() - i.expiry)
  RETURNING o.id
),
inserted AS (
  INSERT INTO order_receipt (order_number, channel, partner_id, content_hash, correlation_id, received_at,
                             order_state, checking_since)
  SELECT i.order_number, 'Partner', i.partner_id, i.content_hash, i.correlation_id, i.received_at, 'Received', now()
  FROM input i
  WHERE NOT EXISTS (SELECT 1 FROM busy) AND NOT EXISTS (SELECT 1 FROM same)
  ON CONFLICT (partner_id, order_number, content_hash) WHERE channel = 'Partner' DO NOTHING
  RETURNING id, received_at
),
history AS (
  INSERT INTO order_state_change (order_receipt_id, order_state, changed_at)
  SELECT id, 'Received', received_at FROM inserted
)
SELECT CASE
         WHEN EXISTS (SELECT 1 FROM inserted) THEN 'NEW'
         WHEN EXISTS (SELECT 1 FROM claimed) THEN 'CLAIMED'
         WHEN EXISTS (SELECT 1 FROM busy) THEN 'IN_PROGRESS'
         WHEN (SELECT order_state FROM same) IN ('Rejected', 'Recorded', 'Sent to Warehouse') THEN 'DUPLICATE'
         ELSE 'IN_PROGRESS'
       END AS outcome,
       -- No receipt id for IN_PROGRESS: the request must never release another request's mark
       COALESCE((SELECT id FROM inserted), (SELECT id FROM claimed),
                (SELECT id FROM same
                 WHERE order_state IN ('Rejected', 'Recorded', 'Sent to Warehouse')
                   AND NOT EXISTS (SELECT 1 FROM busy))) AS receipt_id,
       (SELECT order_state FROM same) AS order_state
