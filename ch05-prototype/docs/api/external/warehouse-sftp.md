# Pick List file drop: Warehouse SFTP Server

Contract for the Pick Lists the order integration delivers to the Warehouse (UC-004 Deliver Pick List). Based on the
Warehouse's own description, `docs/inputs/warehouse-picklist-format.md`, the kickoff notes and the answers of the
Warehouse lead.

## Location

- **Host:** the Warehouse SFTP Server. Host name, port and login are configuration of the integration, never part
  of this contract.
- **Folder:** `/inbound/picklists` (C-011). The Warehouse moves every file it has picked up to
  `/inbound/picklists/processed`.
- The connection is encrypted, as SFTP always is (C-024).

## File name

`PL-<Order Number>.csv`, for example `PL-TG-1060.csv` or `PL-PM-50117.csv` (C-013). One file per Accepted Order.

## Format

- **Record:** one row per Order Line, in line order; one row is described by
  [`pick-list.schema.json#/items`](../../messages/pick-list.schema.json), the mapping from the Order by
  [Order to Pick List](../../messages/mappings/order-to-pick-list.md).
- **Encoding:** UTF-8.
- **Separator:** comma. A value that holds a comma, such as a joined street line, is enclosed in double quotes and a
  double quote inside it is doubled, as in RFC 4180 (C-012).
- The state or province of a Peakmart Order is not on the Pick List (UC-004 BR-003).
- **Header:** one header line first, with the twelve columns in this order: `order_no`, `line_no`, `sku`,
  `description`, `qty`, `unit_weight_g`, `ship_name`, `ship_street`, `ship_city`, `ship_postcode`, `ship_country`,
  `total_weight_g` (C-012).
- Address lines are joined by ", " into `ship_street`; `ship_country` is the ISO 3166-1 alpha-2 code;
  `total_weight_g` is the same on every row (UC-004 BR-002, BR-003).
- The file holds the Recipient name and the Shipping Address, which are personal data (C-021), and no Customer email
  and no prices.

## Writing

1. The integration writes the file under the temporary name `PL-<Order Number>.csv.tmp`; the Warehouse ignores files
   ending in `.tmp` (C-014).
2. When the file is complete, the integration renames it to `PL-<Order Number>.csv`.
3. The Pick List counts as delivered once it is complete under its final name (UC-004 BR-005). A delivery that breaks
   off leaves only a `.tmp` file, which the integration replaces on the next attempt (UC-004 A2).

## Pickup

- The Warehouse picks up the files every 10 minutes (kickoff notes).
- Maintenance Window: the Warehouse SFTP Server is down every Sunday from 02:00 to 04:00 US Mountain Time
  (America/Denver) (C-015). The integration holds Pick Lists while the server is unavailable and delivers them as
  soon as it is available again (UC-004 A1).
- After pickup the Warehouse moves the file to `/inbound/picklists/processed` (C-011).
- A Pick List the Warehouse cannot read or loses is reported by the Warehouse to the Order Desk and handled outside
  the integration; the integration never delivers it again (UC-004 BR-007).

## Duplicates

The Warehouse ships every Pick List it receives: the same file arriving twice ships the Order twice. The integration
therefore delivers each Pick List exactly once, also after a Peakmart redelivery, a Partner resend or a restart
(UC-004 BR-005, NFR-006), and treats a Pick List already present under its final name, in `/inbound/picklists` or
in `/inbound/picklists/processed`, as delivered and never writes it again.
