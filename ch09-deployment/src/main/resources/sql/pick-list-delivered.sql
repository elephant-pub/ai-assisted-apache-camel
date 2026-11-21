-- Record one delivered Pick List in one statement (UC-004 steps 5-6): the Pick List Delivered (only from Pending),
-- the receipt Sent to Warehouse (only from Recorded) and its history entry (once). Safe to run again: a second run
-- changes nothing, so a file found already on the server only records the state.
WITH delivered AS (
  UPDATE pick_list
  SET delivery_state = 'Delivered', delivered_at = now(), last_attempt_at = now(), last_failure = NULL,
      attempts = LEAST(attempts + 1, 100000)
  WHERE id = CAST(:#${exchangeProperty.pick-list-id} AS bigint) AND delivery_state = 'Pending'
  RETURNING id
),
sent AS (
  UPDATE order_receipt
  SET order_state = 'Sent to Warehouse'
  WHERE id = CAST(:#${exchangeProperty.receipt-id} AS bigint) AND order_state = 'Recorded'
  RETURNING id
)
INSERT INTO order_state_change (order_receipt_id, order_state, changed_at)
SELECT id, 'Sent to Warehouse', now() FROM sent
ON CONFLICT (order_receipt_id, order_state) DO NOTHING
