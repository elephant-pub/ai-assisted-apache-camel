-- Design 005 H6: one failed delivery attempt of a Pending Pick List: counted once (capped inside the V2 CHECK 0-100000),
-- with the time and the reason. It runs in the route's error clause, where the body is no longer the consumer's row, so
-- the row is the exchange property pick-list-id (set at the start of the route, as for pick-list-delivered.sql). The
-- reason is a fixed text or a class name set by the route as the exchange property last-failure, never file or order
-- content.
UPDATE pick_list
SET attempts = LEAST(attempts + 1, 100000), last_attempt_at = now(), last_failure = CAST(:#${exchangeProperty.last-failure} AS varchar)
WHERE id = CAST(:#${exchangeProperty.pick-list-id} AS bigint) AND delivery_state = 'Pending'
