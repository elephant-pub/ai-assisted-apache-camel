-- The content hash of the Accepted Order with this Order Number, or NULL when there is none (UC-003 BR-004).
-- Always one row, so the route reads one value.
SELECT (SELECT r.content_hash
        FROM customer_order c
        JOIN order_receipt r ON r.id = c.order_receipt_id
        WHERE c.order_number = :#${exchangeProperty.order-number}) AS content_hash
