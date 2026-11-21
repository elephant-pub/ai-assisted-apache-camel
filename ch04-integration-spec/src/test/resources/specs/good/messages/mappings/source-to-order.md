# Source to Order

**Source:** [source](../source.schema.json)
**Target:** [order](../order.schema.json)

| Source | Target | Rule |
|---|---|---|
| `ref` | `orderId` | copied |
| `to.full_name` | `shipTo.name` | copied |
| `to.cc` | `shipTo.country` | copied |
| `items[].sku` | `lines[].sku` | copied |
| `items[].qty` | `lines[].quantity` | copied |
