-- Release the "being checked" mark of a Partner receipt that was answered temporarily unavailable (UC-001 A6, A7):
-- the receipt stays Received and the Partner's resend is checked on it (BR-009).
UPDATE order_receipt
SET checking_since = NULL
WHERE id = CAST(:#${exchangeProperty.receipt-id} AS bigint) AND order_state = 'Received'
