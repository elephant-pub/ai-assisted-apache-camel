# Test Case: Pick List Retried After Moved to Processed

## Overview

**ID:** TC-006  
**Goal:** The delivery of a Pick List breaks off after the file is already under its final name, the Warehouse Team moves it to `processed/` before the delivery is tried again, and the retry finds it there: the Warehouse Team gets exactly one Pick List and the Order Desk sees the Order as sent to warehouse.  
**Priority:** High  
**Status:** Proposed

## Roles

- Partner Summit Outfitters (submits the Partner Order from its own system)
- Product Catalog (answers the Products of the Order Lines)
- Warehouse Team (receives the Pick List on the Warehouse SFTP Server and moves it to `/inbound/picklists/processed`)
- Order Desk (looks up the Order from the Helpdesk Tool)

## Preconditions

- Partner `summit-outfitters` is registered with Order Number Range 1000 to 1999, from the Partner Configuration [summit-outfitters.json](../messages/examples/partner-configuration/summit-outfitters.json) released into the test environment (UC-007). Its API Key is kept in the test environment's Secret Store under the name `partner-api-key-summit-outfitters`; the key itself is not part of this document.
- The Product Catalog of the test environment answers the Active Products [tent-2p.json](../messages/examples/product/tent-2p.json) and [lamp-hd200.json](../messages/examples/product/lamp-hd200.json).
- The Warehouse SFTP Server of the test environment is available, and `/inbound/picklists` and `/inbound/picklists/processed` hold no file `PL-TG-1062.csv`.
- The test environment can break off the delivery of the Pick List of `TG-1062` after the file is under its final name in `/inbound/picklists` and before the System counts it as delivered (UC-004 A3), for example by stopping the integration at that point.
- No Order `TG-1062` has been received in the last 90 days (the Order Database of the test environment holds no Order `TG-1062`).
- The Helpdesk Tool is set up to call the order lookup with its own key from the test environment's Secret Store (OQ-066).

## Flow

| Step | Name                          | Description                                                                                                                                                                                                  | Test Data                                                                                                                              | Use Case                                               |
|------|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| 1    | Submit partner order          | Summit Outfitters submits its Partner Order `TG-1062` (TENT-2P × 1, LAMP-HD200 × 2, ship to Lee Martin, Moab, US) with its API Key.                                                                           | [partner-order-sample.json](../messages/examples/partner-order/partner-order-sample.json) with Order Number `TG-1062`, API Key `partner-api-key-summit-outfitters` | [UC-001](../use_cases/UC-001-submit-partner-order.md)  |
| 2    | Check order                   | The System checks the Order received in step 1 against every Order Check; both SKUs are Active Products, and the Order Lines are completed with name, weight and Catalog Price.                              | [tent-2p.json](../messages/examples/product/tent-2p.json), [lamp-hd200.json](../messages/examples/product/lamp-hd200.json)             | [UC-003](../use_cases/UC-003-check-order.md)           |
| 3    | Verify order accepted         | Summit Outfitters gets the Order Answer `TG-1062` accepted.                                                                                                                                                   | [accepted.json](../messages/examples/order-answer/accepted.json) with Order Number `TG-1062`                                           | -                                                      |
| 4    | Deliver pick list             | The System delivers the Pick List of the Accepted Order `TG-1062`; the delivery breaks off after `PL-TG-1062.csv` is under its final name in `/inbound/picklists` and before the System counts it as delivered. | -                                                                                                                                      | [UC-004](../use_cases/UC-004-deliver-pick-list.md)     |
| 5    | Move pick list to processed   | Before the delivery is tried again, the Warehouse Team moves `PL-TG-1062.csv` from `/inbound/picklists` to `/inbound/picklists/processed`.                                                                    | `PL-TG-1062.csv`                                                                                                                       | -                                                      |
| 6    | Retry delivery                | The System tries the delivery of the Pick List of `TG-1062` again; it looks for `PL-TG-1062.csv` in `/inbound/picklists` and in `/inbound/picklists/processed`, finds it in `processed`, counts the Pick List as delivered and does not deliver it again. | -                                                                                                                                      | [UC-004](../use_cases/UC-004-deliver-pick-list.md)     |
| 7    | Look up order                 | An Order Desk member looks up the Order Number from step 1 in the Helpdesk Tool.                                                                                                                              | `TG-1062`                                                                                                                              | [UC-005](../use_cases/UC-005-look-up-order.md)         |
| 8    | Verify order history          | The lookup answers one Order, Channel partner, Partner `summit-outfitters`, Order State sent to warehouse, and the Order History received, recorded, sent to warehouse; dates and times are those of the run. | `TG-1062`                                                                                                                              | -                                                      |

## Validation

1. **No second Pick List**: 15 minutes after step 6, the Warehouse SFTP Server holds exactly one file `PL-TG-1062.csv`, the one in `/inbound/picklists/processed`, and none in `/inbound/picklists`; no `PL-TG-1062.csv.tmp` is left.
2. **Complete Pick List**: The file `PL-TG-1062.csv` in `/inbound/picklists/processed` has two rows as in the example, with Order Number `TG-1062` and Total Weight 2340 on both rows.
3. **Sent to warehouse once**: The order lookup of `TG-1062` lists one Order, and each of received, recorded and sent to warehouse appears exactly once in its Order History.

## Postconditions

- One Accepted Order `TG-1062` of Partner `summit-outfitters` (TENT-2P × 1 at 249.00 USD, LAMP-HD200 × 2 at 39.95 USD, ship to Lee Martin, 18 Canyon Road, Moab 84532, US) is recorded in the Order Database.
- The Order History of `TG-1062` holds the Order States received, recorded and sent to warehouse.
- The file `PL-TG-1062.csv` lies on the Warehouse SFTP Server in `/inbound/picklists/processed` only.
- Cleanup order: the Order History of `TG-1062` is deleted together with or before its Order; the Pick List file is removed from `/inbound/picklists/processed`. The Partner Configuration and the Product Catalog data stay untouched.
