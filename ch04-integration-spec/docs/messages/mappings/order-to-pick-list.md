# Order to Pick List

**Source:** [order](../order.schema.json)
**Target:** [pick-list](../pick-list.schema.json)

One row per Order Line, in line order (UC-004 BR-001); the file is named `PL-<Order Number>.csv` (UC-004 BR-003).

| Source | Target | Rule |
|--------|--------|------|
| `orderNumber` | `[].order_no` | Copied on every row |
| `lines[].lineNumber` | `[].line_no` | Copied |
| `lines[].sku` | `[].sku` | Copied |
| `lines[].productName` | `[].description` | Copied |
| `lines[].quantity` | `[].qty` | Copied |
| `lines[].unitWeightGrams` | `[].unit_weight_g` | Copied |
| `shippingAddress.recipientName` | `[].ship_name` | Copied on every row |
| `shippingAddress.streetLine` | `[].ship_street` | Copied on every row; its address lines are already joined by ", " |
| `shippingAddress.city` | `[].ship_city` | Copied on every row |
| `shippingAddress.postalCode` | `[].ship_postcode` | Copied on every row |
| `shippingAddress.country` | `[].ship_country` | Copied on every row; already ISO 3166-1 alpha-2 |
| `lines[].quantity`, `lines[].unitWeightGrams` | `[].total_weight_g` | Calculated: the sum of quantity times unit weight over all Order Lines, the same on every row (UC-004 BR-002) |
| `shippingAddress.stateRegion` | — | Dropped: the Pick List has no column for it; it stays in the Order Database (UC-004 BR-003) |
| `lines[].unitPrice` | — | Dropped: the Pick List carries no prices |
| `customerEmail` | — | Dropped: the Warehouse does not need it to ship (C-021) |
| `channel`, `partnerId`, `eventId` | — | Dropped: the Warehouse picks every Order the same way |
| `orderedAt`, `receivedAt`, `correlationId` | — | Dropped: not a Pick List column |
