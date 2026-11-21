# Test Case: Set-Aside Order Event Reaches Warehouse

## Overview

**ID:** TC-004  
**Goal:** An unreadable Peakmart Order Event is set aside and alerted, an Integration Team member corrects and resubmits it, and the Order passes the Order Checks and reaches the Warehouse as exactly one Pick List.  
**Priority:** High  
**Status:** Approved

## Roles

- Peakmart (publishes the Order Event that cannot be read)
- Integration Team (receives the Alert, corrects and resubmits the Set-Aside Order Event)
- Product Catalog (answers the Product of the Order Line)
- Warehouse Team (receives the Pick List on the Warehouse SFTP Server)

## Preconditions

- The integration reads the Peakmart order topic of the test environment with the granted consumer group.
- The Product Catalog of the test environment answers the Active Product [tent-2p.json](../messages/examples/product/tent-2p.json).
- The Integration Team mailbox of the test environment is reachable and holds no Alert for event id `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f`.
- An Integration Team member is authorised to resubmit Set-Aside Order Events, with a bearer token issued by the test environment (OQ-067); the token is not part of this document.
- No Set-Aside Order Event with event id `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f` is kept, and no Order `PM-50119` has been received in the last 90 days.
- The Warehouse SFTP Server of the test environment is available and holds no file `PL-PM-50119.csv`.
- A corrected resubmission is allowed (assumption of the open question OQ-062).

## Flow

| Step | Name                         | Description                                                                                                                                                                                                                         | Test Data                                                                                                                         | Use Case                                                          |
|------|------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| 1    | Publish unreadable event     | Peakmart publishes the Order Event `order.placed` for `PM-50119` under event id `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f`, whose Customer email `sam.okafor-at-example.com` is not an email address; the System sets it aside.          | [pm-50119-buyer-email-not-an-email.json](../messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json) | [UC-002](../use_cases/UC-002-take-in-peakmart-order.md)           |
| 2    | Alert integration team       | The System alerts the Integration Team about the Set-Aside Order Event of step 1.                                                                                                                                                    | -                                                                                                                                 | [UC-006](../use_cases/UC-006-alert-on-stuck-order.md)             |
| 3    | Verify set-aside alert       | The Integration Team mailbox receives one Alert of kind order-event-set-aside with the event id of step 1, the Order Number `PM-50119` and the time it arrived, and no Customer name, email address or Shipping Address. No Pick List `PL-PM-50119.csv` exists. | [order-event-set-aside.json](../messages/examples/alert/order-event-set-aside.json)                                               | -                                                                 |
| 4    | Resubmit corrected event     | The Integration Team member resubmits the Set-Aside Order Event by the event id named in the Alert of step 3, with the Customer email corrected to `sam.okafor@example.com`.                                                          | [corrected-pm-50119.json](../messages/examples/order-event-resubmission/corrected-pm-50119.json)                                  | [UC-008](../use_cases/UC-008-resubmit-set-aside-order-event.md)   |
| 5    | Verify event taken in        | The Integration Team member is told that the Order Event was taken in, with the Order Number `PM-50119`.                                                                                                                            | [taken-in.json](../messages/examples/resubmission-result/taken-in.json)                                                           | -                                                                 |
| 6    | Check order                  | The System checks the Peakmart Order taken in at step 4 against every Order Check; `TENT-2P` is an Active Product, the country CA passes, and the Order Line keeps the Peakmart Price 229.00.                                        | [tent-2p.json](../messages/examples/product/tent-2p.json)                                                                         | [UC-003](../use_cases/UC-003-check-order.md)                      |
| 7    | Deliver pick list            | The System delivers the Pick List of the Accepted Order `PM-50119` to the Warehouse SFTP Server.                                                                                                                                     | -                                                                                                                                 | [UC-004](../use_cases/UC-004-deliver-pick-list.md)                |
| 8    | Verify pick list             | Within 15 minutes of step 4, `PL-PM-50119.csv` is on the Warehouse SFTP Server with one row as in the example: Sam Okafor, 88 Kensington Road NW, Calgary, `T2N 3P8`, CA, Total Weight 2150.                                          | [pl-pm-50119.json](../messages/examples/pick-list/pl-pm-50119.json)                                                               | -                                                                 |

## Validation

1. **One Pick List**: The Warehouse SFTP Server holds exactly one file `PL-PM-50119.csv`, counting `/inbound/picklists` and `/inbound/picklists/processed` together.
2. **No longer set aside**: Resubmitting event id `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f` once more is answered that no Set-Aside Order Event with this event id is kept ([not-found.json](../messages/examples/resubmission-result/not-found.json)), and no second Pick List follows.
3. **One Alert**: The Integration Team mailbox holds exactly one Alert for event id `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f`.
4. **Nothing to Peakmart**: The integration published nothing on the Peakmart order topic.

## Postconditions

- One Accepted Order `PM-50119` of the Channel peakmart (TENT-2P × 1 at 229.00 USD, ship to Sam Okafor, 88 Kensington Road NW, Calgary AB T2N 3P8, CA, Customer email `sam.okafor@example.com`) is recorded in the Order Database, with the Order States received, recorded and sent to warehouse in its Order History.
- The Order Event `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f` is no longer a Set-Aside Order Event; the integration keeps who resubmitted it and when (and, per OQ-072, the resubmission of Validation 2 is kept as well).
- One Alert of kind order-event-set-aside for that event id is kept and lies in the Integration Team mailbox of the test environment.
- The file `PL-PM-50119.csv` lies on the Warehouse SFTP Server, in `/inbound/picklists` or `/inbound/picklists/processed`.
- Cleanup order: the resubmission records and the Alert are deleted before the Set-Aside Order Event record they refer to; the Order History of `PM-50119` is deleted together with or before its Order; the Pick List file and the Alert email are removed. The Product Catalog data stay untouched.
