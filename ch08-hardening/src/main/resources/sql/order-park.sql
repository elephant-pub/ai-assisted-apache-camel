-- Design 005 H5: a waiting Peakmart Order that is checked but cannot be recorded now is parked: waiting_for becomes
-- 'Recording' so the check poll (waiting-select.sql, 'Check' only) no longer selects it. Only for a receipt still
-- Received and waiting for the check. The reason is a fixed text built by the route; the parameters are exchange properties.
UPDATE order_receipt
SET waiting_for = 'Recording', waiting_reason = CAST(:#${exchangeProperty.waiting-reason} AS varchar), last_check_at = now()
WHERE id = CAST(:#${exchangeProperty.receipt-id} AS bigint) AND order_state = 'Received' AND waiting_for = 'Check'
