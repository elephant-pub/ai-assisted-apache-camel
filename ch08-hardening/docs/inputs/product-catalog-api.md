# Product catalog API (internal)

`GET /products/{sku}`

200, the product:

```json
{
  "sku": "TENT-2P",
  "name": "Ridgeline 2-Person Tent",
  "listPrice": 249.00,
  "currency": "USD",
  "weightGrams": 2150,
  "active": true
}
```

404 when no product has this SKU.

No authentication inside the network. No batch lookup.
