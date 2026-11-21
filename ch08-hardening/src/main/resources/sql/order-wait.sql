-- Design 005 H5: a waiting Peakmart Order that could not be checked now (Product Catalog unreachable, or a failure on the
-- way) stays waiting; record why and when. Only for a receipt still Received and waiting for the check.
-- The reason is a fixed text built by the route (no order content); the parameters are exchange properties.
UPDATE order_receipt
SET last_check_at = now(), waiting_reason = CAST(:#${exchangeProperty.waiting-reason} AS varchar)
WHERE id = CAST(:#${exchangeProperty.receipt-id} AS bigint) AND order_state = 'Received' AND waiting_for = 'Check'
