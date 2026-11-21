# Peakmart Order Event order.placed to Order Desk Notification

**Source:** [peakmart-order-placed](../peakmart-order-placed.schema.json)
**Target:** [order-desk-notification](../order-desk-notification.schema.json)

Only for a Rejected Peakmart Order (UC-002 A4); a Duplicate and an Order Event that cannot be read get none (UC-002 A2, A3).

| Source | Target | Rule |
|--------|--------|------|
| `order_ref` | `orderNumber` | Copied |
| — | `rejectionReasons` | Every Rejection Reason the Order Checks found, in the order of the checks (UC-003 A3) |
| `buyer`, `ship_to` | — | Dropped: no Customer name, email address or Shipping Address in an Order Desk Notification (C-023) |
| `event_type`, `event_id`, `occurred_at`, `items` | — | Dropped: the notification names the Order by its Order Number only (UC-002 BR-004) |
