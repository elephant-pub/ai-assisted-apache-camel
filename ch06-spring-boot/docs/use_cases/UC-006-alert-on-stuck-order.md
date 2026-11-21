# Use Case: Alert on Stuck Order

## Overview

**Use Case ID:** UC-006  
**Use Case Name:** Alert on Stuck Order  
**Primary Actor:** Integration Team  
**Goal:** The Integration Team learns of an Order that is stuck in the integration, or of Peakmart Orders that cannot reach it, in time to act before the Warehouse misses them  
**Trigger:** An Order passes its time limit (an Accepted Order is not sent to the warehouse 30 minutes after it was recorded, or a Peakmart Order is not checked or not recorded 2 hours after it arrived), a Peakmart Order Event is set aside, or the Peakmart order topic has not been read for 15 minutes  
**Status:** Approved  

**Requirements:** [FR-035, FR-039, FR-042, FR-043, NFR-001, NFR-011, C-005, C-015](../requirements.md)  
**Layers:** Service

## Preconditions

_None — the use case starts from the Order History, the Set-Aside Order Events and the reading of the Peakmart order topic._

## Main Success Scenario

1. System finds the Accepted Orders that have not reached the Order State sent to warehouse 30 minutes after they were recorded, not counting time inside the Maintenance Window.
2. System sends the Integration Team one Alert that lists, for each of these Orders, the Order Number, the Channel, the time the Order was recorded and how long it has been waiting.
3. System notes in the Order History of each of these Orders that the Integration Team was alerted about it.

## Alternative Flows

### A1: Peakmart Order Waiting

**Trigger:** The stuck Order is a Peakmart Order that has still not been checked, or has passed the Order Checks but is still not recorded, 2 hours after it arrived, because the Product Catalog does not answer or the Order Database is unavailable (step 1)  
**Flow:**

1. System sends the Integration Team one Alert that lists, for each such Peakmart Order, the Order Number, whether it waits to be checked or to be recorded, the time it arrived and how long it has been waiting.
2. System keeps the Peakmart Order waiting; it is not rejected because of the Alert.
3. Use case continues at step 3.

### A2: Already Alerted

**Trigger:** The Integration Team was already alerted about the Order for the same time limit (step 1)  
**Flow:**

1. System leaves the Order that was already alerted out of the Alert and sends no second Alert about it.
2. Use case continues at step 2 with the other Orders.

### A3: Order Event Set Aside

**Trigger:** A Peakmart Order Event was set aside because it cannot be read as an Order (step 1)  
**Flow:**

1. System sends the Integration Team an Alert with the event id of the Set-Aside Order Event, the time it arrived, and the Order Number when it can be read.
2. System notes on the Set-Aside Order Event that the Integration Team was alerted about it.
3. Use case ends.

### A4: Peakmart Order Topic Not Read

**Trigger:** The integration has not been able to read the Peakmart order topic for 15 minutes (step 1)  
**Flow:**

1. System sends the Integration Team an Alert with the time the topic was last read.
2. System sends no further Alert until the topic has been read again.
3. Use case ends.

### A5: Alert Cannot Be Sent

**Trigger:** The mail server does not take the Alert (step 2)  
**Flow:**

1. System tries the Alert again until it is sent, also after a restart of the integration.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- The Integration Team has been alerted about the stuck Order once for the time limit it passed.
- The Integration Team has been alerted about every Set-Aside Order Event, and about the Peakmart order topic not being read.
- The Order History shows that the Integration Team was alerted about the Order.

### Failure Postconditions

- The Alert never changes, rejects or delivers the Order.
- An Order or a Set-Aside Order Event is not noted as alerted unless the Alert was sent.

## Business Rules

### BR-001: Not Sent to Warehouse in 30 Minutes

An Accepted Order is stuck when it has not reached the Order State sent to warehouse 30 minutes after it was recorded. Time inside the Maintenance Window (Sundays 02:00 to 04:00 US Mountain Time, America/Denver) is not counted, so no Alert is raised for an Order the Warehouse SFTP Server cannot take during the window.

### BR-002: Peakmart Order Waiting for 2 Hours

A Peakmart Order is stuck when it has still not been checked, or has passed the Order Checks but is still not recorded, 2 hours after it arrived. It keeps waiting for the Product Catalog or the Order Database after the Alert, and nobody rejects it because of the outage (UC-002 BR-006).

### BR-003: One Alert per Time Limit

The Integration Team is alerted about an Order at most once for each time limit it passes, so a Peakmart Order can be alerted once as waiting (BR-002) and once more as not sent to the warehouse (BR-001). An Order that was already alerted for a time limit is left out of the next Alert for it, and the other Orders are still alerted (A2); when every Order found was already alerted, no Alert is sent.

### BR-004: No Personal Data in Alerts

An Alert identifies an Order by its Order Number and a Set-Aside Order Event by its event id only, and contains no Customer name, email address or Shipping Address.

### BR-005: One Alert for Orders at the Same Time

Orders that pass the same time limit at the same time are reported in one Alert that lists their Order Numbers, so that a long outage at peak does not send one Alert per Order.

### BR-006: Alert Channel

An Alert is a plain-text email to the Integration Team mailbox with the content of step 2 or of the alternative flow that sends it.

### BR-007: Peakmart Order Topic Read

The Integration Team is alerted when the Peakmart order topic has not been read for 15 minutes, the time NFR-001 leaves for a Peakmart Order, so that reading is restored long before Peakmart deletes the Order Events after 7 days (C-005).
