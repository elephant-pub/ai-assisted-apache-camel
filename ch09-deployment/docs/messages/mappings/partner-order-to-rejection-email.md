# Partner Order to Rejection Email

**Source:** [partner-order](../partner-order.schema.json)
**Target:** [rejection-email](../rejection-email.schema.json)

Only for a Rejected Partner Order (UC-001 A5); a Duplicate, an unreadable request and an Order answered temporarily unavailable get no Rejection Email (UC-001 BR-005, BR-007, BR-009).

| Source | Target | Rule |
|--------|--------|------|
| `orderId` | `orderNumber` | Copied |
| — | `rejectionReasons` | The Rejection Reasons of the Order Answer, in the same order (UC-001 BR-004) |
| `partnerId` | — | Not part of the content: names the Partner whose Order Contact the email goes to, from the Partner Configuration (UC-007) |
| `customerEmail`, `shipTo` | — | Dropped: no Customer name, email address or Shipping Address in a Rejection Email (C-023) |
| `orderedAt`, `lines` | — | Dropped: the email names the Order by its Order Number only (UC-001 BR-005) |
