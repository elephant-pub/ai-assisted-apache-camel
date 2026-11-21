# Test Case: Partner Order Rejected With Two Rejection Reasons in Check Order

## Overview

**ID:** TC-007  
**Goal:** A Partner Order fails two Order Checks; the Partner gets both Rejection Reasons in the order of the checks, in the Order Answer and in the Rejection Email to its Order Contact, no Pick List is delivered, and the Order Desk sees the same reasons in the same order in the order lookup.  
**Priority:** High  
**Status:** Proposed

## Roles

- Partner Summit Outfitters (submits the Partner Order from its own system and receives the Rejection Email at its Order Contact)
- Product Catalog (answers the Products of the Order Lines)
- Order Desk (looks up the Order from the Helpdesk Tool)

## Preconditions

- Partner `summit-outfitters` is registered with Order Number Range 1000 to 1999 and Order Contact `orders@summit-outfitters.example.com`, from the Partner Configuration [summit-outfitters.json](../messages/examples/partner-configuration/summit-outfitters.json) released into the test environment (UC-007). Its API Key is kept in the test environment's Secret Store under the name `partner-api-key-summit-outfitters`; the key itself is not part of this document.
- The Product Catalog of the test environment answers the Active Products [tent-2p.json](../messages/examples/product/tent-2p.json) and [lamp-hd200.json](../messages/examples/product/lamp-hd200.json).
- The Order Contact mailbox `orders@summit-outfitters.example.com` of the test environment is reachable and holds no Rejection Email for `TG-1061`.
- The Warehouse SFTP Server of the test environment is available and holds no file `PL-TG-1061.csv`.
- No Order `TG-1061` has been received in the last 90 days (the Order Database of the test environment holds no Order `TG-1061`).
- The Helpdesk Tool is set up to call the order lookup with its own key from the test environment's Secret Store (OQ-066).

## Flow

| Step | Name                          | Description                                                                                                                                                                                                  | Test Data                                                                                                                              | Use Case                                               |
|------|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| 1    | Submit partner order          | Summit Outfitters submits its Partner Order `TG-1061` with its API Key: two Order Lines, Order Line 2 with quantity 25, and the Shipping Address in the country DE.                                         | [partner-order-sample.json](../messages/examples/partner-order/partner-order-sample.json) with Order Number `TG-1061`, quantity 25 on Order Line 2 and country `DE`, API Key `partner-api-key-summit-outfitters` | [UC-001](../use_cases/UC-001-submit-partner-order.md)  |
| 2    | Check order                   | The System checks the Order received in step 1 against every Order Check; it fails the quantity check for Order Line 2 and the destination country check, and notes both Rejection Reasons in the order of the checks: quantity first, destination country second. | [tent-2p.json](../messages/examples/product/tent-2p.json), [lamp-hd200.json](../messages/examples/product/lamp-hd200.json)             | [UC-003](../use_cases/UC-003-check-order.md)           |
| 3    | Verify order rejected         | Summit Outfitters gets the Order Answer `TG-1061` rejected with the two Rejection Reasons of the example, quantity (Order Line 2) first, destination country second.                                         | [rejected.json](../messages/examples/order-answer/rejected.json)                                                                       | -                                                      |
| 4    | Verify rejection email        | Within 5 minutes of step 1, the Order Contact mailbox receives one plain-text email with the subject `Order TG-1061 rejected`, holding the Order Number and the two Rejection Reasons in the same order as the Order Answer, and no Customer name, email address or Shipping Address. | [tg-1061.json](../messages/examples/rejection-email/tg-1061.json)                                                                      | -                                                      |
| 5    | Look up order                 | An Order Desk member looks up the Order Number from step 1 in the Helpdesk Tool.                                                                                                                              | `TG-1061`                                                                                                                              | [UC-005](../use_cases/UC-005-look-up-order.md)         |
| 6    | Verify rejection reasons shown | The lookup answers one Order, Channel partner, Partner `summit-outfitters`, Order State rejected, the Order History received, rejected, and the two Rejection Reasons of step 3 in the same order; dates and times are those of the run. | `TG-1061`                                                                                                                              | -                                                      |

## Validation

1. **Check order everywhere**: The Order Answer, the Rejection Email and the order lookup list the same two Rejection Reasons in the same order: quantity (Order Line 2) first, destination country second.
2. **One Rejection Email**: The Order Contact mailbox holds exactly one Rejection Email for `TG-1061`.
3. **No Pick List**: 15 minutes after step 1, the Warehouse SFTP Server holds no file `PL-TG-1061.csv` or `PL-TG-1061.csv.tmp`, in `/inbound/picklists` or `/inbound/picklists/processed`.
4. **Never recorded**: The Order History of `TG-1061` in the order lookup holds no Order State recorded or sent to warehouse.

## Postconditions

- One Rejected Order `TG-1061` of Partner `summit-outfitters` with two Rejection Reasons (quantity for Order Line 2, then destination country) exists in the Order History with the Order States received and rejected; it is not recorded in the Order Database as an Accepted Order.
- One Rejection Email for `TG-1061` lies in the Order Contact mailbox of the test environment, and the integration keeps when it was sent.
- Cleanup order: the record of when the Rejection Email was sent and the Rejection Reasons are deleted together with or before the Order History of `TG-1061`; the email is removed from the Order Contact mailbox. The Partner Configuration and the Product Catalog data stay untouched.
