-- Pending Pick Lists (the outbox), oldest first (UC-004). One row per Pick List; the route builds the file only when
-- the Warehouse SFTP Server does not already have it.
SELECT p.id                AS id,
       p.file_name         AS file_name,
       p.customer_order_id AS customer_order_id,
       c.order_receipt_id  AS order_receipt_id,
       c.order_number      AS order_number,
       r.correlation_id    AS correlation_id
FROM pick_list p
JOIN customer_order c ON c.id = p.customer_order_id
JOIN order_receipt r ON r.id = c.order_receipt_id
WHERE p.delivery_state = 'Pending'
ORDER BY p.id
