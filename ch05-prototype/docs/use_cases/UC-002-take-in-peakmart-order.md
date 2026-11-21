# Use Case: Take In Peakmart Order

## Overview

**Use Case ID:** UC-002  
**Use Case Name:** Take In Peakmart Order  
**Primary Actor:** Peakmart  
**Secondary Actors:** Product Catalog, Order Database, Order Desk  
**Goal:** Every Order Peakmart publishes is recorded once in the Order Database without manual handling, or reported to the Order Desk when it cannot be accepted  
**Trigger:** Peakmart publishes an Order Event on its order topic  
**Status:** Approved  

**Requirements:** [FR-007, FR-008, FR-020, FR-021, FR-023, FR-029, FR-031, FR-039, FR-041, FR-042, NFR-001, NFR-003, NFR-006, NFR-007, NFR-011, NFR-013, NFR-014, C-005, C-006, C-007, C-008, C-020, C-021, C-022, C-023](../requirements.md)  
**Layers:** Service  
**API:** [receivePeakmartOrderEvent](../api/peakmart-orders.asyncapi.yaml#receivePeakmartOrderEvent)

## Preconditions

- The integration has read access to the Peakmart order topic with the consumer group Peakmart granted.

## Main Success Scenario

1. Peakmart publishes an Order Event on its order topic.
2. System reads the Order Event and takes it as a Peakmart Order, because its type is `order.placed`, with the time of the Order Event as the time the Customer placed the Order.
3. System verifies that the Order Number of the Peakmart Order was not received before and records its receipt in the Order History with the Order State received.
4. System checks the Peakmart Order against every Order Check (UC-003 Check Order).
5. System keeps the Peakmart Price on every Order Line and records the Accepted Order in the Order Database with its Customer, Shipping Address and Order Lines.
6. System records the Order State recorded in the Order History.

## Alternative Flows

### A1: Other Event Type

**Trigger:** The type of the Order Event is not `order.placed` (step 2)  
**Flow:**

1. System skips the Order Event without an error and without an entry in the Order History.
2. Use case ends.

### A2: Order Event Cannot Be Read

**Trigger:** An Order Event of type `order.placed` cannot be read as an Order, for example because its Customer email is not an email address (step 2)  
**Flow:**

1. System sets the Order Event aside with its event id as a Set-Aside Order Event, so that the Integration Team can examine it and resubmit it once the cause is fixed (UC-008 Resubmit Set-Aside Order Event).
2. System alerts the Integration Team with the event id, and the Order Number when it can be read (UC-006 Alert on Stuck Order).
3. System goes on with the next Order Event.
4. Use case ends.

### A3: Peakmart Redelivers an Order

**Trigger:** The Order Number was already received, under the same or another event id (step 3)  
**Flow:**

1. System skips the Peakmart Order as a Duplicate, without a second recording, a second Order Desk Notification or a second Pick List.
2. Use case ends.

### A4: Order Fails Order Checks

**Trigger:** The Peakmart Order fails at least one Order Check (step 4)  
**Flow:**

1. System records the Order State rejected with every Rejection Reason in the Order History.
2. System sends an Order Desk Notification with the Order Number and every Rejection Reason to the Order Desk mailbox and keeps when it was sent.
3. Use case ends.

### A5: Catalog Outage

**Trigger:** The Product Catalog does not answer, so the Peakmart Order cannot be checked (step 4)  
**Flow:**

1. System keeps the Peakmart Order waiting without rejecting it.
2. System goes on with the Peakmart Orders behind it.
3. System checks the waiting Peakmart Order again until the Product Catalog answers.
4. Use case continues at step 4.

### A6: Order Database Unavailable

**Trigger:** The Order Database is unavailable, so the Accepted Order cannot be recorded (step 5)  
**Flow:**

1. System keeps the Peakmart Order waiting until the Order Database is available again.
2. System goes on with the Peakmart Orders behind it.
3. Use case continues at step 5.

## Postconditions

### Success Postconditions

- The Accepted Order is recorded exactly once in the Order Database with its Customer, Shipping Address and Order Lines, each Order Line with its SKU, quantity, Peakmart Price, product name and weight.
- The Order History of the Order shows the Order States received and recorded with date and time.
- The Accepted Order waits for its Pick List (UC-004 Deliver Pick List).

### Failure Postconditions

- A Peakmart Order is never recorded twice and never gets a second Pick List, whichever event id it arrives under.
- An Order Event of type `order.placed` that was read is never lost: it ends recorded, rejected, skipped as a Duplicate, set aside, or still waiting.
- A Rejected Order is not recorded in the Order Database and gets no Pick List.
- Nothing is sent to Peakmart.

## Business Rules

### BR-001: Only Placed Orders

Only Order Events of type `order.placed` are Peakmart Orders. Every other type on the order topic is skipped without an error, so that unknown types do not stop the processing of Orders.

### BR-002: Duplicate by Order Number

A Peakmart Order is recognised as a Duplicate by its Order Number, not by its event id: Peakmart delivers each Order Event at least once and may publish the same Order again under a new event id. A Peakmart Order Number is placed once, so every later Order with an Order Number already received is skipped, whatever the outcome of the first one.

### BR-003: Peakmart Price

Every Order Line of a Peakmart Order keeps the Peakmart Price, the price in US dollars with two decimals that the Customer paid; the Catalog Price is not used.

### BR-004: Order Desk Notification

For every Rejected Peakmart Order an Order Desk Notification goes to the Order Desk mailbox within 5 minutes of the rejection. It is plain text in English with the subject "Peakmart order <Order Number> rejected", the Order Number and every Rejection Reason, and contains no Customer name, email address or Shipping Address. When the mail server does not take it, it is tried again until it is sent, also after a restart of the integration; nobody is alerted when the 5 minutes pass, because the Rejection Reasons are in the order lookup. The System keeps when the Order Desk Notification was sent, but the order lookup does not show it (UC-005 BR-004).

### BR-005: Nothing Back to Peakmart

Peakmart takes no replies or errors: neither an acceptance nor a rejection is published to Peakmart.

### BR-006: No Peakmart Order Lost

A Peakmart Order that cannot be checked during a Catalog Outage, or cannot be recorded while the Order Database is unavailable, waits and is processed as soon as the outage ends; it is never rejected because of the outage, and it is not lost after a restart of the integration. A waiting Peakmart Order does not hold up the Peakmart Orders behind it; they are processed, and the waiting one is checked or recorded as soon as it can be. A Peakmart Order still not checked, or still not recorded, 2 hours after it arrived is reported to the Integration Team (UC-006 Alert on Stuck Order) and keeps waiting.

### BR-007: Shipping Address of a Peakmart Order

The address lines of a Peakmart Shipping Address are joined by ", " into its street line; the state is kept when Peakmart sends one.

### BR-008: Time the Order Was Placed

The time of an `order.placed` Order Event is the time the Customer placed the Peakmart Order.

### BR-009: Readable Order Event

An Order Event of type `order.placed` can be read as an Order only when it matches the Peakmart order format and its Customer email is an email address; otherwise it is set aside (A2). A Set-Aside Order Event gets no Order History entry and no Order Desk Notification, because it may carry no Order Number.

### BR-010: Set-Aside Order Event

A Set-Aside Order Event is kept with its event id until the Integration Team resubmits it (UC-008 Resubmit Set-Aside Order Event) and is deleted 90 days after it arrived, because it holds personal data. The Integration Team is alerted about every Set-Aside Order Event with its event id, and the Order Number when it can be read, and no personal data.
