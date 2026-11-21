# Use Case: Deliver Pick List

## Overview

**Use Case ID:** UC-004  
**Use Case Name:** Deliver Pick List  
**Primary Actor:** Warehouse Team  
**Secondary Actors:** Warehouse SFTP Server  
**Goal:** The Warehouse Team gets one complete Pick List per Accepted Order, exactly once, so that it can pick and ship the Order without asking questions  
**Trigger:** An Order is recorded as an Accepted Order (UC-001 step 6, UC-002 step 5)  
**Status:** Approved  

**Requirements:** [FR-025, FR-026, FR-027, FR-028, NFR-001, NFR-006, NFR-007, C-011, C-012, C-013, C-014, C-015, C-024](../requirements.md)  
**Layers:** Service

## Preconditions

- The Accepted Order is recorded in the Order Database with its Shipping Address and its Order Lines completed from the Product Catalog.

## Main Success Scenario

1. System creates the Pick List of the Accepted Order with one row per Order Line, each row with the Order Number, the line number, the SKU, the product name, the quantity, the weight of one item and the Shipping Address.
2. System calculates the Total Weight of the Order and puts it on every row of the Pick List.
3. System delivers the Pick List to the Warehouse SFTP Server.
4. Warehouse SFTP Server holds the complete Pick List under its final name.
5. System records the Order State sent to warehouse in the Order History.

## Alternative Flows

### A1: Warehouse SFTP Server Unavailable

**Trigger:** The Warehouse SFTP Server is unavailable, during the Maintenance Window or at any other time (step 3)  
**Flow:**

1. System holds the Pick List.
2. System delivers the Pick List as soon as the Warehouse SFTP Server is available again.
3. Use case continues at step 3.

### A2: Delivery Interrupted

**Trigger:** The delivery breaks off, for example because the connection to the Warehouse SFTP Server breaks, and the Pick List is not under its final name in `/inbound/picklists` or `/inbound/picklists/processed` (step 4)  
**Flow:**

1. System holds the Pick List as not delivered; the incomplete file stays invisible to the Warehouse Team.
2. System looks for the Pick List under its final name in `/inbound/picklists` and in `/inbound/picklists/processed` before delivering it again, and finds it in neither folder.
3. Use case continues at step 3.

### A3: Pick List Already Delivered When the Delivery Broke Off

**Trigger:** The delivery breaks off, for example because the connection to the Warehouse SFTP Server breaks, but the Pick List is already under its final name in `/inbound/picklists` or `/inbound/picklists/processed` (step 4)  
**Flow:**

1. System looks for the Pick List under its final name in `/inbound/picklists` and in `/inbound/picklists/processed` before delivering it again, and finds it.
2. System counts the Pick List as delivered and does not deliver it again.
3. Use case continues at step 5.

### A4: Order State Sent to Warehouse Not Recorded

**Trigger:** Recording the Order State sent to warehouse fails after the Pick List was delivered (step 5)  
**Flow:**

1. System records the Order State sent to warehouse again until it succeeds.
2. System never delivers the Pick List again.
3. Use case ends.

## Postconditions

### Success Postconditions

- The Pick List of the Accepted Order is on the Warehouse SFTP Server exactly once, complete, under the name `PL-<Order Number>.csv`.
- The Order History of the Order shows the Order State sent to warehouse with date and time.

### Failure Postconditions

- No Accepted Order gets a second Pick List.
- No incomplete Pick List is visible to the Warehouse Team.
- A Pick List that could not be delivered is held and not lost, also after a restart of the integration.
- The Order State sent to warehouse is recorded only for a Pick List that is complete on the Warehouse SFTP Server.

## Business Rules

### BR-001: One Pick List per Accepted Order

Every Accepted Order gets exactly one Pick List, with one row per Order Line. A Rejected Order gets no Pick List.

### BR-002: Total Weight

The Total Weight is the sum of quantity times Product weight in grams over all Order Lines of the Order and stands on every row of the Pick List, so that the Warehouse Team can choose the carrier.

### BR-003: Pick List Format

A Pick List is a UTF-8, comma-separated file with a header line and the columns agreed with the Warehouse (`docs/inputs/warehouse-picklist-format.md`), named `PL-<Order Number>.csv` and delivered into the folder `/inbound/picklists`. The address lines of the Shipping Address are joined by ", "; the country is the ISO 3166-1 alpha-2 code. A value that holds a comma or a double quote is enclosed in double quotes, and a double quote inside it is doubled (RFC 4180). The state or province a Peakmart Order carries is not on the Pick List; it stays in the Order Database.

### BR-004: Visible Only When Complete

A Pick List is written under the temporary name `PL-<Order Number>.csv.tmp`, which the Warehouse ignores, and gets its final name only when it is complete.

### BR-005: Delivered Exactly Once

A Pick List counts as delivered once it is complete under its final name on the Warehouse SFTP Server. A delivered Pick List is never delivered again — not after a Peakmart redelivery, a Partner resend, or a restart of the integration at any point of processing — because the Warehouse ships every Pick List it receives. The Warehouse moves every file it has picked up to `/inbound/picklists/processed`, so a Pick List that is no longer in `/inbound/picklists` may already be delivered. Before the System delivers a Pick List again after a broken delivery, it therefore looks for the file under its final name in `/inbound/picklists` and in `/inbound/picklists/processed`; when it is in either folder, the Pick List counts as delivered (A3). When recording the Order State sent to warehouse fails after the delivery, the System records it again until it succeeds and never delivers the Pick List again (A4).

### BR-006: Delivery Time

Every Accepted Order is delivered as a Pick List within 15 minutes of arriving. Time inside the Maintenance Window (Sundays 02:00 to 04:00 US Mountain Time, America/Denver) or a Catalog Outage is not counted; a Pick List held by one is delivered within 15 minutes after it ends.

### BR-007: Unreadable or Lost Pick List

When the Warehouse cannot read a delivered Pick List or loses it, the Warehouse tells the Order Desk, and the Order is handled outside the integration from the Order Database or the ERP. The integration never delivers the Pick List again (BR-005).
