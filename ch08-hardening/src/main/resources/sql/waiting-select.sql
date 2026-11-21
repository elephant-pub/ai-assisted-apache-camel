-- Peakmart Orders waiting to be checked (OQ-069), the one checked longest ago first (never checked first, then by
-- arrival), so an order that keeps failing does not hold back the ones behind it (design 005 H5).
-- waiting_for = 'Recording' is now set by order-park.sql and is never selected here.
SELECT id, order_number, correlation_id, waiting_order
FROM order_receipt
WHERE waiting_for = 'Check'
ORDER BY last_check_at NULLS FIRST, received_at, id
