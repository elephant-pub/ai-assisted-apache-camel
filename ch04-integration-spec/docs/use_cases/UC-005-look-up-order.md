# Use Case: Look Up Order

## Overview

**Use Case ID:** UC-005  
**Use Case Name:** Look Up Order  
**Primary Actor:** Order Desk  
**Goal:** The Order Desk finds an Order of the last 90 days by its Order Number from its Helpdesk Tool and sees what happened to it, so that it can answer "where is order X?" without asking the Integration Team  
**Trigger:** An Order Desk member looks up an Order in the Helpdesk Tool, for example because a Customer or a Partner asks about it  
**Status:** Approved  

**Requirements:** [FR-032, FR-033, FR-034, NFR-004, NFR-005, NFR-015, C-021, C-022](../requirements.md)  
**Layers:** API  
**API:** [lookUpOrder](../api/order-lookup.openapi.yaml#lookUpOrder)

## Preconditions

- The Helpdesk Tool is set up to call the order lookup of the integration.

## Main Success Scenario

1. Order Desk member looks up an Order Number from the Helpdesk Tool.
2. System verifies that the lookup comes from the Helpdesk Tool.
3. System finds the Order received with this Order Number in the last 90 days.
4. System answers with the Order, its Channel, its Partner for a Partner Order, and its current Order State.
5. System answers with the Order History of the Order: every Order State it has passed through, with date and time.

## Alternative Flows

### A1: Lookup Not From the Helpdesk Tool

**Trigger:** The lookup is not authenticated as the Helpdesk Tool (step 2)  
**Flow:**

1. System refuses the lookup without reading any Order.
2. Use case ends.

### A2: No Order Found

**Trigger:** No Order with this Order Number was received in the last 90 days (step 3)  
**Flow:**

1. System answers that no Order with this Order Number was received in the last 90 days.
2. Use case ends.

### A3: Order Number Received More Than Once

**Trigger:** Several Orders were received with this Order Number, such as a Rejected Order and its corrected Order (step 3)  
**Flow:**

1. System answers with every Order received with this Order Number, the most recent first, each with its own Order State and Order History.
2. Use case ends.

### A4: Rejected Order

**Trigger:** The Order is a Rejected Order (step 5)  
**Flow:**

1. System answers with every Rejection Reason of the Order, in the order the Partner or the Order Desk received them.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Order Desk member has seen in the Helpdesk Tool the current Order State and the Order History of the Order, and the Rejection Reasons of a Rejected Order.

### Failure Postconditions

_None — the use case changes no data._

## Business Rules

### BR-001: Last 90 Days

Every Order received in the last 90 days can be found, accepted or rejected, from either Channel; everything the integration keeps about an Order is deleted 90 days after it was received.

### BR-002: Order States

The Order States shown are received, rejected, recorded and sent to warehouse, each with the date and time the Order reached it.

### BR-003: Fresh Order State

A change of Order State is visible in the order lookup within 1 minute of happening.

### BR-004: No Personal Data Shown

The order lookup shows the Order Number, the Channel, the Partner, the Order States and the Rejection Reasons, and no Customer name, email address or Shipping Address; the Order Desk finds those in the Order Database or the ERP when it needs them. It does not show when a Rejection Email or an Order Desk Notification was sent, nor a refused or unreadable request, nor a Set-Aside Order Event.

### BR-005: Read-Only Lookup for the Helpdesk Tool

The order lookup is read-only and is offered to the Helpdesk Tool only, authenticated as the Helpdesk Tool; the Order Desk keeps working in the Helpdesk Tool and gets no screen of the integration. Partners cannot use it (FR-037 is deferred).
