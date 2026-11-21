-- Set aside one Order Event that cannot be read (UC-002 A2). An event id is set aside only once.
-- The key is the event id, or kafka-<partition>-<offset> when the event id cannot be read or the record is not JSON.
INSERT INTO set_aside_order_event (event_id, order_number, event_content, cannot_read_reason, arrived_at,
                                   set_aside_state)
VALUES (:#${exchangeProperty.set-aside-key}, :#${exchangeProperty.set-aside-order-number},
        :#${exchangeProperty.event-content}, :#${exchangeProperty.cannot-read-reason},
        CAST(:#${exchangeProperty.received-at} AS timestamptz), 'Set Aside')
ON CONFLICT (event_id) DO NOTHING
