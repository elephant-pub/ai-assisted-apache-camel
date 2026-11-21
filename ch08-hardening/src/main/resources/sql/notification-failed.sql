-- Design 005 H6: one failed send attempt of an unsent notification (Order Desk Notification or Rejection Email): counted
-- once (capped inside the V1 CHECK 0-1000), with the time and the reason. It runs in the route's error clause, where the
-- headers are removed and the body is the mail text, so the row is the exchange property notification-id (set by the
-- route from the consumer's notification_id). The reason is a fixed text or a class name set by the route as the
-- exchange property last-failure, never message content.
UPDATE notification
SET attempts = LEAST(attempts + 1, 1000), last_attempt_at = now(), last_failure = CAST(:#${exchangeProperty.last-failure} AS varchar)
WHERE id = CAST(:#${exchangeProperty.notification-id} AS bigint) AND sent_at IS NULL
