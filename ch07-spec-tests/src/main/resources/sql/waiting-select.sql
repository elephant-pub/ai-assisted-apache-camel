-- Peakmart Orders waiting to be checked (OQ-069), oldest first. waiting_for = 'Recording' is never set by the
-- prototype (assumption A-9) and is ignored.
SELECT id, order_number, correlation_id, waiting_order
FROM order_receipt
WHERE waiting_for = 'Check'
ORDER BY received_at, id
