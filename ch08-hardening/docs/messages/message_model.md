# Message Model

Every message that crosses a boundary of the order integration, and how one shape becomes the next. Terms follow
`docs/glossary.md`.

## Formats

| Message | Owner | Direction | Schema | Used by |
|---------|-------|-----------|--------|---------|
| Partner Order | Trailhead Gear (defined for every Partner) | Inbound, partner HTTP endpoint | [partner-order](partner-order.schema.json) | UC-001 |
| Peakmart Order Event `order.placed` | Peakmart | Inbound, topic `peakmart.orders.v2` | [peakmart-order-placed](peakmart-order-placed.schema.json) | UC-002 |
| Product | Product Catalog | Inbound, answer to `GET /products/{sku}` | [product](product.schema.json) | UC-003 |
| Order (canonical) | Trailhead Gear | Internal | [order](order.schema.json) | UC-001, UC-002, UC-003, UC-004 |
| Order Answer | Trailhead Gear | Outbound, response to the Partner | [order-answer](order-answer.schema.json) | UC-001 |
| Rejection Email | Trailhead Gear | Outbound, email to the Order Contact | [rejection-email](rejection-email.schema.json) | UC-001 |
| Order Desk Notification | Trailhead Gear | Outbound, email to the Order Desk mailbox | [order-desk-notification](order-desk-notification.schema.json) | UC-002 |
| Pick List | Warehouse | Outbound, file on the Warehouse SFTP Server | [pick-list](pick-list.schema.json) | UC-004 |
| Rejection Reason (shared part) | Trailhead Gear | Part of the Order Answer, both emails and the Order Lookup | [rejection-reason](rejection-reason.schema.json) | UC-001, UC-002, UC-003, UC-005 |
| Order Lookup | Trailhead Gear | Outbound, answer to the Helpdesk Tool | [order-lookup](order-lookup.schema.json) | UC-005 |
| Alert | Trailhead Gear | Outbound, email to the Integration Team mailbox | [alert](alert.schema.json) | UC-006 |
| Partner Configuration | Trailhead Gear | Inbound, read from the repository of the integration | [partner-configuration](partner-configuration.schema.json) | UC-007 |
| Order Event Resubmission | Trailhead Gear | Inbound, request of an Integration Team member | [order-event-resubmission](order-event-resubmission.schema.json) | UC-008 |
| Resubmission Result | Trailhead Gear | Outbound, answer to the Integration Team member | [resubmission-result](resubmission-result.schema.json) | UC-008 |

The Order Lookup and the Alert are built from the Order History and the Set-Aside Order Events, not converted from
another message, so they have no mapping. A resubmitted Order Event, unchanged or corrected, is mapped to the Order
like any other (`peakmart-order-placed-to-order.md`, UC-008 BR-001).

## Mappings

- [Partner Order to Order](mappings/partner-order-to-order.md)
- [Peakmart Order Event order.placed to Order](mappings/peakmart-order-placed-to-order.md)
- [Order to Pick List](mappings/order-to-pick-list.md)
- [Partner Order to Order Answer](mappings/partner-order-to-order-answer.md)
- [Partner Order to Rejection Email](mappings/partner-order-to-rejection-email.md)
- [Peakmart Order Event order.placed to Order Desk Notification](mappings/peakmart-order-placed-to-order-desk-notification.md)

## Decisions to review

**Inbound schemas say what can be read; the canonical Order holds the Order Checks.** The Partner Order and the
Peakmart Order Event schemas require only what is needed to read an Order (UC-001 A3, UC-002 A2): an Order Number,
the Partner, the time, an address object and lines with SKU and quantity. They deliberately leave out the Order
Number format, the quantity range, the line count, the address parts and the country, because those are Order Checks
that must each produce a Rejection Reason, all at once (UC-003 BR-010), not an unreadable request. The canonical
Order carries every Order Check about the Order alone, so a Rejected Order is never a valid Order; the checks that
need other data (Order Number Range, Order Number already used, Duplicate, Active Product) are named in the property
descriptions.

**Money is a string with two decimals next to a currency.** A JSON number loses cents in floating point. The Partner
Order and the Product Catalog send numbers (`unitPrice`, `listPrice`) and are described as they are; the mapping
writes the Catalog Price as a two-decimal string. Peakmart already sends strings (C-006). The currency is always
`USD` (C-020).

**Formats we don't control.** The Peakmart Order Event and the Product answer allow extra fields, so that a field
their owners add does not break the integration. The Partner Order is a format Trailhead Gear defines for every
Partner and allows no extra field (UC-001 BR-010). The Pick List format belongs to the Warehouse; its schema follows the
rule for formats others own and allows extra fields, but the mapping fixes exactly the twelve columns the Warehouse
agreed. A Pick List is CSV, not JSON: its schema describes the rows as objects keyed by column name, and a value
holding a comma or a double quote (such as a joined street line) is quoted as RFC 4180 says (C-012).

**Unusable Product answers.** A Product answer that fails its schema (no active flag, no weight, no price) counts as
the Product Catalog not answering (UC-003 BR-013); the Order is not rejected because of it.

**No secrets and no personal data in what we send the Integration Team.** The Partner Configuration names each API
Key in the Secret Store and allows no field for the key itself (UC-007 BR-003). The Alert, the Order Lookup and the
Resubmission Result allow no field for a Customer name, email address or Shipping Address (UC-005 BR-004, UC-006
BR-004, UC-008 BR-004). Only a corrected Order Event in a resubmission carries personal data, as Peakmart sent it.

**Personal data.** The Customer email, the Recipient name and the Shipping Address are personal data (C-021) and are
marked so in every schema. The Rejection Email and the Order Desk Notification allow no field but the Order Number
and the Rejection Reasons, so the schema itself stops personal data from getting into them (C-023). The Pick List
carries the Recipient and the Shipping Address because the Warehouse needs them to ship, and no Customer email.

**Catalog completion and price per Channel.** Product name and weight come from the Product Catalog for every
Channel. The price comes from the Product Catalog for a Partner Order (UC-001 BR-006) and from Peakmart for a
Peakmart Order (UC-002 BR-003); the price a Partner sends is dropped.

**Illustrative values in the examples.** The canonical Order and Pick List examples are the samples mapped. Only
`TENT-2P` has Product data in `docs/inputs/`; the name, weight and Catalog Price used for `LAMP-HD200` and
`STAKE-AL8` are illustrative, and so are the correlation ids, receipt times and Rejection Reason texts.
