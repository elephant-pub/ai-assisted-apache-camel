# Rejection Email to the Partner

Contract for the Rejection Email the order integration sends to a Partner for a Rejected Partner Order (UC-001 A5,
BR-005, FR-006). Trailhead Gear owns it; it is the same for every Partner.

## Recipient

The Order Contact of the Partner whose API Key sent the Order, as registered in the Partner Configuration (UC-007).
Never an address taken from the Partner Order. The sender address is configuration of the integration. A Rejection
Email that cannot be delivered bounces to the sender mailbox, and nobody acts on it; the Partner has the Rejection
Reasons in the Order Answer (UC-001 BR-005).

## Subject

`Order <Order Number> rejected`, for example `Order TG-1061 rejected` (FR-006).

## Body

Plain text, content described by [`rejection-email.schema.json`](../../messages/rejection-email.schema.json) and
mapped by [Partner Order to Rejection Email](../../messages/mappings/partner-order-to-rejection-email.md):

- the Order Number;
- every Rejection Reason, in the same order as in the Order Answer (UC-001 BR-004), each with its text and, when it
  refers to one, the Order Line.

Nothing else: no Customer name, email address or Shipping Address (C-023).

## Language

English, the language of the Rejection Reasons in the Order Answer (FR-006).

## When

Within 5 minutes of the rejection (NFR-014). When the mail server does not take it, it is tried again until it is
sent, also after a restart of the integration; nobody is alerted when the 5 minutes pass. The integration keeps when
it was sent; the order lookup does not show it (UC-001 BR-005). Only for an Order answered rejected; a Duplicate, an unreadable request,
a refused request and an Order answered temporarily unavailable get no Rejection Email (UC-001 BR-001, BR-005, BR-007,
BR-009).
