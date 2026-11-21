# Order integration kickoff, notes

Attendees: integration team (2), order desk lead, warehouse lead, partner manager. Peakmart's integration contact
joined for the second half.

## Partners

- Six partner shops today, about 300 orders a day between them; two more partners sign in Q1.
- Each partner has an API key. Partners send one order per request and wait for the answer.
- The partner manager: "They need to know right away if we took the order. If we didn't, an email with the reasons to
  the partner's order contact, the same email for every partner, plain text is fine."
- Partner order numbers start with `TG-` and four or more digits (we give each partner a range). A partner sometimes
  resends an order after a timeout. Same number, same content.
- Sample: `partner-order-sample.json`.

## Peakmart

- About 900 orders a day, more in November.
- Peakmart publishes `order.placed` events on its topic `peakmart.orders.v2`. We get a consumer group and read-only
  access. They don't take replies or errors from us.
- Their contact: "Delivery is at least once. You will see the same event more than once, especially after we
  rebalance." Each event has an `event_id`; the order reference `order_ref` starts with `PM-`.
- Prices are strings in US dollars, two decimals.
- Sample: `peakmart-order-placed-sample.json`. They have an AsyncAPI document internally and will "try to share it".
- Who hears about a Peakmart order we can't accept? Not decided. Today the order desk finds out when the customer
  calls.

## Checks (order desk lead)

- Every SKU must exist in the product catalog and be active.
- Quantity 1 to 20 per line. More than 20 is a reseller, and we don't sell to resellers through these channels.
- At most 30 lines per order.
- The shipping address needs a name, a street line, a city, a postal code and a country. We ship to the US and
  Canada only.
- Partner prices: we take the catalog price, not the partner's. Peakmart prices: Peakmart's price stands, it's what the
  customer paid.

## Product catalog

- REST, `GET /products/{sku}`, internal. Answers in about 50 ms. Returns name, list price, weight and an active flag.
  Description in `product-catalog-api.md`.
- It's down for deployments a few minutes a week, usually Tuesday mornings.

## Warehouse (warehouse lead)

- One pick list file per order, CSV, on the warehouse SFTP server, folder `/inbound/picklists`. Format in
  `warehouse-picklist-format.md`.
- They pick files up every 10 minutes. Maintenance window Sundays 02:00 to 04:00, server down.
- "Never send us the same order twice. If you do, we ship it twice."
- Total weight per order is on the pick list; they choose the carrier from it.

## Order desk

- They want to look up an order by its number and see: received, rejected (and why), recorded, sent to the warehouse.
- Keep 90 days. Older orders are in the ERP anyway.

## Open after the meeting

- What to do with a Peakmart order that fails the checks.
- What happens to orders while the catalog or the SFTP server is down.
- Whether partners need an order status lookup too (partner manager will ask them).
