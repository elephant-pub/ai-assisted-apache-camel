-- Receive one Peakmart Order (UC-002 steps 3-4) in one atomic statement.
-- A Peakmart Order Number already received is a Duplicate: nothing is written and the update count is 0 (BR-002).
-- The body is the canonical Order JSON; it waits in waiting_order until it is rejected or recorded.
WITH r AS (
  INSERT INTO order_receipt (order_number, channel, event_id, content_hash, correlation_id, received_at,
                             order_state, waiting_for, waiting_order)
  VALUES (:#${exchangeProperty.order-number}, 'Peakmart', :#${exchangeProperty.event-id},
          encode(sha256(convert_to(:#${body}, 'UTF8')), 'hex'), :#${exchangeProperty.correlation-id},
          CAST(:#${exchangeProperty.received-at} AS timestamptz), 'Received', 'Check', :#${body})
  ON CONFLICT (order_number) WHERE channel = 'Peakmart' DO NOTHING
  RETURNING id, received_at
)
INSERT INTO order_state_change (order_receipt_id, order_state, changed_at)
SELECT id, 'Received', received_at FROM r
