# Use Case: Resubmit Set-Aside Order Event

## Overview

**Use Case ID:** UC-008  
**Use Case Name:** Resubmit Set-Aside Order Event  
**Primary Actor:** Integration Team  
**Goal:** A Customer's paid Peakmart Order that was set aside reaches the Warehouse after its cause is fixed, without ever being recorded twice  
**Trigger:** An Integration Team member has fixed the cause of a Set-Aside Order Event and resubmits it  
**Status:** Approved  

**Requirements:** [FR-041, FR-007, FR-023, NFR-006, NFR-007, NFR-011, NFR-013, C-007, C-021, C-022](../requirements.md)  
**Layers:** API  
**API:** [resubmitOrderEvent](../api/order-event-resubmission.openapi.yaml#resubmitOrderEvent)

## Preconditions

- The Integration Team member is authorised to resubmit Set-Aside Order Events.

## Main Success Scenario

1. Integration Team member resubmits a Set-Aside Order Event by its event id, as it was set aside or with the content that could not be read corrected.
2. System finds the Set-Aside Order Event with this event id.
3. System takes the Order Event in like a new Order Event and reads it as a Peakmart Order (UC-002 Take In Peakmart Order).
4. System verifies that the Order Number of the Peakmart Order was not received before and records its receipt in the Order History with the Order State received.
5. System removes the Order Event from the Set-Aside Order Events and keeps who resubmitted it and when.
6. System reports to the Integration Team member that the Order Event was taken in, with its Order Number.

## Alternative Flows

### A1: No Set-Aside Order Event Found

**Trigger:** No Set-Aside Order Event with this event id is kept, because it was already resubmitted or deleted after 90 days (step 2)  
**Flow:**

1. System reports that no Set-Aside Order Event with this event id is kept.
2. Use case ends.

### A2: Still Cannot Be Read

**Trigger:** The resubmitted Order Event still cannot be read as an Order (step 3)  
**Flow:**

1. System keeps the Order Event set aside and reports to the Integration Team member what cannot be read.
2. Use case ends.

### A3: Order Already Received

**Trigger:** The Order Number of the Peakmart Order was already received (step 4)  
**Flow:**

1. System skips the Peakmart Order as a Duplicate, without a second recording, a second Order Desk Notification or a second Pick List.
2. System removes the Order Event from the Set-Aside Order Events and keeps who resubmitted it and when.
3. System reports to the Integration Team member that the Order was already received.
4. Use case ends.

## Postconditions

### Success Postconditions

- The Peakmart Order of the Order Event is received, with its receipt in the Order History, and goes on through the Order Checks and the recording as every Peakmart Order (UC-002 Take In Peakmart Order).
- The Order Event is no longer among the Set-Aside Order Events, and who resubmitted it and when is kept.

### Failure Postconditions

- A resubmission never records a Peakmart Order a second time and never leads to a second Pick List.
- A Set-Aside Order Event is never lost by a resubmission: it ends taken in, skipped as a Duplicate, or still set aside.
- Nothing is sent to Peakmart.

## Business Rules

### BR-001: Processed Like a New Order Event

A resubmitted Order Event is processed like a new Order Event of type `order.placed` (UC-002 Take In Peakmart Order), with the same Order Checks, and the Duplicate rule by Order Number (UC-002 BR-002) prevents a second recording.

### BR-002: Corrected Content

The Integration Team member may resubmit the Order Event unchanged, after a fix in the integration, or with the content that could not be read corrected. The event id stays the same, and who resubmitted it and when is kept with the Order Event.

### BR-003: Only Kept Set-Aside Order Events

Only a Set-Aside Order Event that is still kept can be resubmitted; it is deleted 90 days after it arrived (UC-002 BR-010).

### BR-004: No Personal Data in the Report

The report to the Integration Team member and the log entries of a resubmission identify the Order Event by its event id and the Order by its Order Number only, and contain no Customer name, email address or Shipping Address.
