# Warehouse pick list format

One file per order, UTF-8, comma-separated, header line first. File name `PL-<order number>.csv`, for example
`PL-TG-1060.csv`. Upload to `/inbound/picklists`. Write the file under a temporary name ending in `.tmp` and rename
it when complete; we ignore `.tmp` files.

| Column       | Content                                   |
|--------------|-------------------------------------------|
| order_no     | Order number                              |
| line_no      | 1, 2, 3 ...                               |
| sku          | Product SKU                               |
| description  | Product name                              |
| qty          | Quantity                                  |
| unit_weight_g| Weight of one item in grams               |
| ship_name    | Recipient name                            |
| ship_street  | Street line (address lines joined by ", ")|
| ship_city    | City                                      |
| ship_postcode| Postal code                               |
| ship_country | ISO 3166-1 alpha-2 country code           |
| total_weight_g | Total weight of the order in grams, the same on every line |
