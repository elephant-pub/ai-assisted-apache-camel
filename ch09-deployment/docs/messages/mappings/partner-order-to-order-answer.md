# Partner Order to Order Answer

**Source:** [partner-order](../partner-order.schema.json)
**Target:** [order-answer](../order-answer.schema.json)

| Source | Target | Rule |
|--------|--------|------|
| `orderId` | `orderNumber` | Copied; left out when the request cannot be read as a Partner Order (UC-001 A3) |
| — | `result` | `accepted` once the Accepted Order is recorded (UC-001 step 8, BR-003); `rejected` when an Order Check fails (UC-001 A5) or the request cannot be read (UC-001 A3); `temporarily-unavailable` during a Catalog Outage or while the Order Database is unavailable (UC-001 A6, A7); for a Duplicate, the result of the first answer from the Order History (UC-001 A4) |
| — | `rejectionReasons` | Only when `result` is `rejected`: every Rejection Reason the Order Checks found, in the order of the checks (UC-003 A3, UC-001 BR-004); for a Duplicate, the Rejection Reasons of the first answer from the Order History; for an unreadable request, one reason that names what cannot be read, without `orderCheck` (UC-001 A3) |
| `partnerId`, `orderedAt`, `customerEmail` | — | Dropped: the answer names the Order by its Order Number only |
| `shipTo`, `lines` | — | Dropped: the answer names the Order by its Order Number only |
