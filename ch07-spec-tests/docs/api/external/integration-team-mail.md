# Alert to the Integration Team

Contract for the Alert the order integration sends to the Integration Team (UC-006 Alert on Stuck Order, BR-006,
FR-035, FR-039, FR-042, FR-043). Trailhead Gear owns it.

## Recipient

The Integration Team mailbox. Its address and the sender address are configuration of the integration.

## Subject

Not agreed; the subject names the kind of the Alert and, for one Order or one Set-Aside Order Event, its Order Number
or event id. It holds no personal data.

## Body

Plain text, content described by [`alert.schema.json`](../../messages/alert.schema.json), one of four kinds:

- **Not sent to warehouse:** every Accepted Order that passed the 30 minutes at the same time, each with its Order
  Number, Channel, the time it was recorded and how long it has been waiting (UC-006 steps 1 and 2, BR-001, BR-005).
- **Peakmart Order waiting:** every Peakmart Order that passed the 2 hours at the same time, each with its Order
  Number, whether it waits to be checked or to be recorded, the time it arrived and how long it has been waiting
  (UC-006 A1, BR-002).
- **Order Event set aside:** the event id, the time it arrived, and the Order Number when it can be read (UC-006 A3);
  the Integration Team can resubmit it (`../order-event-resubmission.openapi.yaml`).
- **Peakmart order topic not read:** the time the topic was last read, 15 minutes or more ago (UC-006 A4, BR-007).

Nothing else: no Customer name, email address or Shipping Address (UC-006 BR-004, NFR-011).

## Language

English, as the Rejection Email and the Order Desk Notification (assumed).

## When

- At most once per Order for each time limit it passes (UC-006 BR-003); Orders that pass the same time limit at the
  same time share one Alert (UC-006 BR-005).
- Once per Set-Aside Order Event.
- Once when the Peakmart order topic has not been read for 15 minutes, and not again until it has been read again
  (assumed, OQ-063).
- When the mail server does not take the Alert, it is tried again until it is sent, also after a restart of the
  integration (assumed, OQ-064).
