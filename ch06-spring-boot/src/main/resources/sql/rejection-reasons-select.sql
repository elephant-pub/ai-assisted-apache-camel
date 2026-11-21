-- Every Rejection Reason of one receipt, in check order, as the JSON array of the Order Answer
-- (docs/messages/rejection-reason.schema.json): the stored Order Check mapped back to the contract enum, the Order
-- Line when the reason has one, and the text. Used to answer a Duplicate of a Rejected Order (UC-001 A4).
SELECT CAST(COALESCE(json_agg(json_strip_nulls(json_build_object(
         'orderCheck', CASE rr.order_check
                         WHEN 'Order Number Format'       THEN 'order-number-format'
                         WHEN 'Order Number Range'        THEN 'order-number-range'
                         WHEN 'Order Number Already Used' THEN 'order-number-already-used'
                         WHEN 'SKU'                       THEN 'sku'
                         WHEN 'Quantity'                  THEN 'quantity'
                         WHEN 'Number of Order Lines'     THEN 'number-of-order-lines'
                         WHEN 'Shipping Address'          THEN 'shipping-address'
                         WHEN 'Destination Country'       THEN 'destination-country'
                       END,
         'lineNumber', rr.line_number,
         'text', rr.reason_text)) ORDER BY rr.position), '[]') AS text) AS reasons
FROM rejection_reason rr
WHERE rr.order_receipt_id = CAST(:#${exchangeProperty.receipt-id} AS bigint)
