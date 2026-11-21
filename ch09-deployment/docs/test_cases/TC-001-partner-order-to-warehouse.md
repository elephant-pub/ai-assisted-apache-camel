# Test Case: Partner Order to Warehouse

## Overview

**ID:** TC-001  
**Goal:** A Partner submits an Order that passes every Order Check, the Warehouse Team gets its Pick List once, and the Order Desk sees the Order as sent to warehouse in the order lookup.  
**Priority:** Critical  
**Status:** Approved

## Roles

- Partner Summit Outfitters (submits the Partner Order from its own system)
- Product Catalog (answers the Products of the Order Lines)
- Warehouse Team (receives the Pick List on the Warehouse SFTP Server)
- Order Desk (looks up the Order from the Helpdesk Tool)

## Preconditions

- Partner `summit-outfitters` is registered with Order Number Range 1000 to 1999 and Order Contact `orders@summit-outfitters.example.com`, from the Partner Configuration [summit-outfitters.json](../messages/examples/partner-configuration/summit-outfitters.json) released into the test environment (UC-007). Its API Key is kept in the test environment's Secret Store under the name `partner-api-key-summit-outfitters`; the key itself is not part of this document.
- The Product Catalog of the test environment answers the Active Products [tent-2p.json](../messages/examples/product/tent-2p.json) and [lamp-hd200.json](../messages/examples/product/lamp-hd200.json).
- The Warehouse SFTP Server of the test environment is available, and `/inbound/picklists` and `/inbound/picklists/processed` hold no file `PL-TG-1060.csv`.
- No Order `TG-1060` has been received in the last 90 days (the Order Database of the test environment holds no Order `TG-1060`).
- The Helpdesk Tool is set up to call the order lookup with its own key from the test environment's Secret Store (OQ-066).

## Flow

| Step | Name                     | Description                                                                                                                                                                                                 | Test Data                                                                                                                                                                  | Use Case                                               |
|------|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| 1    | Submit partner order     | Summit Outfitters submits its Partner Order `TG-1060` (TENT-2P × 1, LAMP-HD200 × 2, ship to Lee Martin, Moab, US) with its API Key.                                                                          | [partner-order-sample.json](../messages/examples/partner-order/partner-order-sample.json), API Key `partner-api-key-summit-outfitters`                                     | [UC-001](../use_cases/UC-001-submit-partner-order.md)  |
| 2    | Check order              | The System checks the Order received in step 1 against every Order Check; the Product Catalog names both SKUs as Active Products, and the Order Lines are completed with name, weight and Catalog Price.    | [tent-2p.json](../messages/examples/product/tent-2p.json), [lamp-hd200.json](../messages/examples/product/lamp-hd200.json)                                                 | [UC-003](../use_cases/UC-003-check-order.md)           |
| 3    | Verify order accepted    | Summit Outfitters gets the Order Answer `TG-1060` accepted, without Rejection Reasons.                                                                                                                       | [accepted.json](../messages/examples/order-answer/accepted.json)                                                                                                           | -                                                      |
| 4    | Deliver pick list        | The System delivers the Pick List of the Accepted Order `TG-1060` to the Warehouse SFTP Server.                                                                                                              | -                                                                                                                                                                          | [UC-004](../use_cases/UC-004-deliver-pick-list.md)     |
| 5    | Verify pick list         | Within 15 minutes of step 1, `PL-TG-1060.csv` is in `/inbound/picklists` (or `/inbound/picklists/processed`) with two rows as in the example, Total Weight 2340 on both rows, and no `.tmp` file is left.     | [pl-tg-1060.json](../messages/examples/pick-list/pl-tg-1060.json)                                                                                                          | -                                                      |
| 6    | Look up order            | An Order Desk member looks up the Order Number from step 1 in the Helpdesk Tool.                                                                                                                             | `TG-1060`                                                                                                                                                                  | [UC-005](../use_cases/UC-005-look-up-order.md)         |
| 7    | Verify order history     | The lookup answers one Order, Channel partner, Partner `summit-outfitters`, Order State sent to warehouse, and the Order History received, recorded, sent to warehouse in this order; dates and times are those of the run. | [tg-1060.json](../messages/examples/order-lookup/tg-1060.json)                                                                                                             | -                                                      |

## Validation

1. **One Pick List**: The Warehouse SFTP Server holds exactly one file `PL-TG-1060.csv`, counting `/inbound/picklists` and `/inbound/picklists/processed` together.
2. **Final Order State**: The order lookup of `TG-1060` shows the Order State sent to warehouse, and each of received, recorded and sent to warehouse appears exactly once in its Order History.
3. **No rejection**: No Rejection Email for `TG-1060` reached the Order Contact `orders@summit-outfitters.example.com`.
4. **No Alert**: The Integration Team mailbox received no Alert naming `TG-1060`.

## Postconditions

- One Accepted Order `TG-1060` of Partner `summit-outfitters` (TENT-2P × 1 at 249.00 USD, LAMP-HD200 × 2 at 39.95 USD, ship to Lee Martin, 18 Canyon Road, Moab 84532, US) is recorded in the Order Database.
- The Order History of `TG-1060` holds the Order States received, recorded and sent to warehouse.
- The file `PL-TG-1060.csv` lies on the Warehouse SFTP Server, in `/inbound/picklists` or `/inbound/picklists/processed`.
- Cleanup order: the Order History of `TG-1060` is deleted together with or before its Order; the Pick List file is removed from the Warehouse SFTP Server. The Partner Configuration and the Product Catalog data stay untouched.
