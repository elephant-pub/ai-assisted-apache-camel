# Source to Order

**Source:** [source](../source.schema.json)
**Target:** [order](../order.schema.json)

| Source | Target | Rule |
|---|---|---|
| `ref` | `orderId` | copied |
| `to.full_name` | `shipTo.name` | copied |
| `items[].sku` | `lines[].sku` | copied |
| `items[].quantity` | `lines[].qty` | copied |
