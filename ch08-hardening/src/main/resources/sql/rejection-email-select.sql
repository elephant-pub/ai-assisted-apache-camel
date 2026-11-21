-- Pending Rejection Emails (the outbox), oldest first, with every Rejection Reason in check order.
-- One reason per line, as "- <text>"; a reason about an Order Line names it in its text ("Order Line 1: ...").
-- The same as notification-select.sql except the notification type (design 003, Flow partner-rejection-email).
SELECT n.id              AS notification_id,
       n.recipient_email AS recipient_email,
       r.order_number    AS order_number,
       r.correlation_id  AS correlation_id,
       string_agg('- ' || rr.reason_text, E'\n' ORDER BY rr.position) AS reason_lines
FROM notification n
JOIN order_receipt r ON r.id = n.order_receipt_id
LEFT JOIN rejection_reason rr ON rr.order_receipt_id = r.id
WHERE n.sent_at IS NULL
  AND n.notification_type = 'Rejection Email'
GROUP BY n.id, n.recipient_email, r.order_number, r.correlation_id
ORDER BY n.id
