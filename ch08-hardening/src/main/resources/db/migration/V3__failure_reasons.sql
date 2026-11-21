-- Design 005 (hardening): the reason a failure is kept in the Order Database, so a person can see why an order waits
-- or an outbox row is not delivered. Additive only: every new column is nullable; V1 and V2 are unchanged.
-- Reasons are fixed texts or exception class names, at most 500 characters, never request or order content.

-- H5: why a receipt still waits, and when it was last checked (a waiting order is polled oldest-check first)
ALTER TABLE order_receipt
  ADD COLUMN waiting_reason varchar(500),
  ADD COLUMN last_check_at  timestamptz,
  ADD CONSTRAINT order_receipt_waiting_reason_ck CHECK (waiting_reason IS NULL OR waiting_for IS NOT NULL);

-- H6: why the last delivery attempt failed (pick_list already has last_attempt_at and attempts since V2)
ALTER TABLE pick_list
  ADD COLUMN last_failure varchar(500);

-- H6: why the last send attempt failed, and when it was made (notification had attempts only)
ALTER TABLE notification
  ADD COLUMN last_failure    varchar(500),
  ADD COLUMN last_attempt_at timestamptz;
