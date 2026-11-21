# Order Desk Notification to the Order Desk

Contract for the Order Desk Notification the order integration sends for a Rejected Peakmart Order (UC-002 A4,
BR-004, FR-031). Trailhead Gear owns it; nothing goes to Peakmart (C-007).

## Recipient

The Order Desk mailbox. Its address and the sender address are configuration of the integration.

## Subject

`Peakmart order <Order Number> rejected`, for example `Peakmart order PM-50118 rejected` (FR-031).

## Body

Plain text, content described by
[`order-desk-notification.schema.json`](../../messages/order-desk-notification.schema.json) and mapped by
[Peakmart Order Event order.placed to Order Desk Notification](../../messages/mappings/peakmart-order-placed-to-order-desk-notification.md):

- the Order Number;
- every Rejection Reason, in the order of the Order Checks (UC-003 A3), each with its text and, when it refers to one,
  the Order Line.

Nothing else: no Customer name, email address or Shipping Address (C-023). The Order Desk finds those in the Order
Database or the ERP.

## Language

English (FR-031).

## When

Within 5 minutes of the rejection (NFR-014), once per Rejected Peakmart Order; a Duplicate gets no second Order Desk
Notification (UC-002 A3). When the mail server does not take it, it is tried again until it is sent, also after a
restart of the integration; nobody is alerted when the 5 minutes pass. The integration keeps when it was sent; the
order lookup does not show it (UC-002 BR-004).
