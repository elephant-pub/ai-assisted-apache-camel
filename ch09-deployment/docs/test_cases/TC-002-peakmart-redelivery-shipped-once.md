# Test Case: Peakmart Redelivery Shipped Once

## Overview

**ID:** TC-002  
**Goal:** Peakmart publishes the same Order twice under two event ids, and the Order is recorded once, gets exactly one Pick List, and shows a single Order in the order lookup.  
**Priority:** Critical  
**Status:** Approved

## Roles

- Peakmart (publishes the Order Event, then publishes the same Order again)
- Product Catalog (answers the Products of the Order Lines)
- Warehouse Team (receives the Pick List on the Warehouse SFTP Server)
- Order Desk (looks up the Order from the Helpdesk Tool)

## Preconditions

- The integration reads the Peakmart order topic of the test environment with the granted consumer group.
- The Product Catalog of the test environment answers the Active Products [tent-2p.json](../messages/examples/product/tent-2p.json) and [stake-al8.json](../messages/examples/product/stake-al8.json).
- The Warehouse SFTP Server of the test environment is available, and `/inbound/picklists` and `/inbound/picklists/processed` hold no file `PL-PM-50117.csv`.
- No Order `PM-50117` has been received in the last 90 days (the Order Database of the test environment holds no Order `PM-50117`).
- The Helpdesk Tool is set up to call the order lookup with its own key from the test environment's Secret Store (OQ-066).

## Flow

| Step | Name                       | Description                                                                                                                                                                                           | Test Data                                                                                                                                         | Use Case                                                |
|------|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| 1    | Publish peakmart order     | Peakmart publishes the Order Event `order.placed` for `PM-50117` (TENT-2P × 1, STAKE-AL8 × 2, ship to Jordan Reyes, Boulder, US) under event id `8d2f6c1e-4b7a-4f0e-9a51-3c2e7b9d0f14`.                | [peakmart-order-placed-sample.json](../messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json)                                 | [UC-002](../use_cases/UC-002-take-in-peakmart-order.md) |
| 2    | Check order                | The System checks the Peakmart Order received in step 1 against every Order Check; both SKUs are Active Products, and the Order Lines keep the Peakmart Prices 229.00 and 12.50.                       | [tent-2p.json](../messages/examples/product/tent-2p.json), [stake-al8.json](../messages/examples/product/stake-al8.json)                          | [UC-003](../use_cases/UC-003-check-order.md)            |
| 3    | Deliver pick list          | The System delivers the Pick List of the Accepted Order `PM-50117` to the Warehouse SFTP Server.                                                                                                       | -                                                                                                                                                 | [UC-004](../use_cases/UC-004-deliver-pick-list.md)      |
| 4    | Verify pick list delivered | Within 15 minutes of step 1, `PL-PM-50117.csv` is on the Warehouse SFTP Server with two rows as in the example, street line `2240 Pearl Street, Apt 4`, Total Weight 2390 on both rows.                  | [pl-pm-50117.json](../messages/examples/pick-list/pl-pm-50117.json)                                                                               | -                                                       |
| 5    | Redeliver peakmart order   | Peakmart publishes the same Order `PM-50117`, with the same content, again under the new event id `c3a8f1d2-7e4b-4a96-9d05-6f1b2e8c4a77`.                                                                | [pm-50117-redelivered.json](../messages/examples/peakmart-order-placed/pm-50117-redelivered.json)                                                 | [UC-002](../use_cases/UC-002-take-in-peakmart-order.md) |
| 6    | Verify no second pick list | 15 minutes after step 5, the Warehouse SFTP Server still holds exactly one `PL-PM-50117.csv`, counting `/inbound/picklists` and `/inbound/picklists/processed` together, and no `PL-PM-50117.csv.tmp`. | -                                                                                                                                                 | -                                                       |
| 7    | Look up order              | An Order Desk member looks up the Order Number from step 1 in the Helpdesk Tool.                                                                                                                       | `PM-50117`                                                                                                                                        | [UC-005](../use_cases/UC-005-look-up-order.md)          |
| 8    | Verify single order        | The lookup answers exactly one Order, Channel peakmart, Order State sent to warehouse, with the Order History received, recorded, sent to warehouse; dates and times are those of the run.              | [pm-50117.json](../messages/examples/order-lookup/pm-50117.json)                                                                                  | -                                                       |

## Validation

1. **Shipped once**: Exactly one file `PL-PM-50117.csv` was delivered to the Warehouse SFTP Server during the whole journey.
2. **Recorded once**: The order lookup of `PM-50117` lists one Order, and each of received, recorded and sent to warehouse appears exactly once in its Order History.
3. **Redelivery silent**: The Order Desk mailbox received no Order Desk Notification for `PM-50117`, and the Integration Team mailbox received no Alert naming `PM-50117` or either event id.

## Postconditions

- One Accepted Order `PM-50117` of the Channel peakmart (TENT-2P × 1 at 229.00 USD, STAKE-AL8 × 2 at 12.50 USD, ship to Jordan Reyes, 2240 Pearl Street, Apt 4, Boulder CO 80302, US) is recorded in the Order Database.
- The Order History of `PM-50117` holds the Order States received, recorded and sent to warehouse.
- The file `PL-PM-50117.csv` lies on the Warehouse SFTP Server, in `/inbound/picklists` or `/inbound/picklists/processed`.
- Both Order Events stay on the Peakmart order topic of the test environment; the integration sends nothing to Peakmart.
- Cleanup order: the Order History of `PM-50117` is deleted together with or before its Order; the Pick List file is removed from the Warehouse SFTP Server. The Product Catalog data stay untouched.
