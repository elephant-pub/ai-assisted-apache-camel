-- Reject one waiting Peakmart Order (UC-002 A4 steps 1-2) in one atomic statement: state Rejected (only from
-- Received), Order History, every Rejection Reason in check order, and the Order Desk Notification outbox row.
-- The body is the result of OrderChecks.evaluate; reasons is its JSON array of Rejection Reasons.
WITH input AS (
  SELECT CAST(:#${exchangeProperty.receipt-id} AS bigint) AS receipt_id,
         CAST(:#reasons AS jsonb)                          AS reasons,
         CAST(:#${exchangeProperty.mailbox} AS varchar)    AS mailbox
),
r AS (
  UPDATE order_receipt o
  SET order_state = 'Rejected', waiting_for = NULL, waiting_order = NULL
  FROM input i
  WHERE o.id = i.receipt_id AND o.order_state = 'Received'
  RETURNING o.id
),
history AS (
  INSERT INTO order_state_change (order_receipt_id, order_state, changed_at)
  SELECT r.id, 'Rejected', now() FROM r
),
reasons AS (
  INSERT INTO rejection_reason (order_receipt_id, order_check, line_number, reason_text, position)
  SELECT r.id,
         CASE x.order_check
           WHEN 'order-number-format'       THEN 'Order Number Format'
           WHEN 'order-number-range'        THEN 'Order Number Range'
           WHEN 'order-number-already-used' THEN 'Order Number Already Used'
           WHEN 'sku'                       THEN 'SKU'
           WHEN 'quantity'                  THEN 'Quantity'
           WHEN 'number-of-order-lines'     THEN 'Number of Order Lines'
           WHEN 'shipping-address'          THEN 'Shipping Address'
           WHEN 'destination-country'       THEN 'Destination Country'
         END,
         x.line_number,
         x.reason_text,
         x.position
  FROM r, input i,
       ROWS FROM (jsonb_to_recordset(i.reasons) AS ("orderCheck" text, "lineNumber" integer, "text" text))
         WITH ORDINALITY AS x(order_check, line_number, reason_text, position)
)
INSERT INTO notification (order_receipt_id, notification_type, recipient_email, created_at, attempts)
SELECT r.id, 'Order Desk Notification', i.mailbox, now(), 0
FROM r, input i
