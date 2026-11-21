# Test Case: Peakmart Order Rejected

## Overview

**ID:** TC-003  
**Goal:** A Peakmart Order with an inactive Product fails the Order Checks, the Order Desk is told by an Order Desk Notification, gets no Pick List, and the Order Desk sees the Rejection Reason in the order lookup.  
**Priority:** High  
**Status:** Approved

## Roles

- Peakmart (publishes the Order Event)
- Product Catalog (answers the Product of the Order Line as not active)
- Order Desk (receives the Order Desk Notification and looks up the Order from the Helpdesk Tool)

## Preconditions

- The integration reads the Peakmart order topic of the test environment with the granted consumer group.
- The Product Catalog of the test environment answers the Product [tent-1p-inactive.json](../messages/examples/product/tent-1p-inactive.json) (`TENT-1P`, not active).
- The Order Desk mailbox of the test environment is reachable and holds no Order Desk Notification for `PM-50118`.
- The Warehouse SFTP Server of the test environment is available and holds no file `PL-PM-50118.csv`.
- No Order `PM-50118` has been received in the last 90 days (the Order Database of the test environment holds no Order `PM-50118`).
- The Helpdesk Tool is set up to call the order lookup with its own key from the test environment's Secret Store (OQ-066).

## Flow

| Step | Name                             | Description                                                                                                                                                                                                                     | Test Data                                                                                                             | Use Case                                                |
|------|----------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| 1    | Publish peakmart order           | Peakmart publishes the Order Event `order.placed` for `PM-50118` with one Order Line `TENT-1P` × 1 under event id `5b7e2d94-8c1a-4e3f-b6d0-2a9c4f7e1b38`.                                                                        | [pm-50118-inactive-sku.json](../messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json)                   | [UC-002](../use_cases/UC-002-take-in-peakmart-order.md) |
| 2    | Check order                      | The System checks the Peakmart Order received in step 1; the Product Catalog names `TENT-1P` as not active, so the Order fails the Order Checks with one Rejection Reason for Order Line 1.                                       | [tent-1p-inactive.json](../messages/examples/product/tent-1p-inactive.json)                                           | [UC-003](../use_cases/UC-003-check-order.md)            |
| 3    | Verify order desk notification   | Within 5 minutes of step 1, the Order Desk mailbox receives one plain-text email with the subject `Peakmart order PM-50118 rejected`, holding the Order Number and the Rejection Reason of the example, and no Customer name, email address or Shipping Address. | [pm-50118.json](../messages/examples/order-desk-notification/pm-50118.json)                                           | -                                                       |
| 4    | Look up order                    | An Order Desk member looks up the Order Number from the notification of step 3 in the Helpdesk Tool.                                                                                                                             | `PM-50118`                                                                                                            | [UC-005](../use_cases/UC-005-look-up-order.md)          |
| 5    | Verify rejection reason shown    | The lookup answers one Order, Channel peakmart, Order State rejected, the Order History received, rejected, and the Rejection Reason of step 3 (`sku`, Order Line 1); dates and times are those of the run.                       | [pm-50118-rejected.json](../messages/examples/order-lookup/pm-50118-rejected.json)                                    | -                                                       |

## Validation

1. **No Pick List**: 15 minutes after step 1, the Warehouse SFTP Server holds no file `PL-PM-50118.csv` or `PL-PM-50118.csv.tmp`, in `/inbound/picklists` or `/inbound/picklists/processed`.
2. **Same reasons everywhere**: The Rejection Reasons of the Order Desk Notification and of the order lookup are the same, in the same order.
3. **One notification**: The Order Desk mailbox holds exactly one Order Desk Notification for `PM-50118`.
4. **Never recorded**: The Order History of `PM-50118` in the order lookup holds no Order State recorded or sent to warehouse.

## Postconditions

- One Rejected Order `PM-50118` of the Channel peakmart with one Rejection Reason (`sku`, Order Line 1) exists in the Order History with the Order States received and rejected; it is not recorded in the Order Database as an Accepted Order.
- One Order Desk Notification for `PM-50118` lies in the Order Desk mailbox of the test environment, and the integration keeps when it was sent.
- Cleanup order: the record of when the Order Desk Notification was sent and the Rejection Reason are deleted together with or before the Order History of `PM-50118`; the email is removed from the Order Desk mailbox. The Product Catalog data stay untouched.
