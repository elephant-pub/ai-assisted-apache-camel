# Use Case: Submit Partner Order

## Overview

**Use Case ID:** UC-001  
**Use Case Name:** Submit Partner Order  
**Primary Actor:** Partner  
**Secondary Actors:** Product Catalog, Order Database  
**Goal:** The Partner learns at once whether Trailhead Gear took its Order, and an accepted Order is recorded once in the Order Database without anyone retyping it  
**Trigger:** A Partner sends an Order from its own system  
**Status:** Approved  

**Requirements:** [FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-017, FR-019, FR-021, FR-022, FR-030, FR-040, NFR-002, NFR-003, NFR-006, NFR-007, NFR-008, NFR-009, NFR-010, NFR-011, NFR-013, NFR-014, C-009, C-020, C-021, C-023, C-024](../requirements.md)  
**Layers:** API  
**API:** [submitPartnerOrder](../api/partner-orders.openapi.yaml#submitPartnerOrder)

## Preconditions

- The Partner is registered with its API Key, Order Number Range and Order Contact (UC-007 Register Partner).

## Main Success Scenario

1. Partner submits a Partner Order together with its API Key.
2. System identifies the Partner by the API Key.
3. System verifies that the Partner Order names the Partner the API Key belongs to.
4. System verifies that the Partner Order is not a Duplicate and records its receipt in the Order History with the Order State received.
5. System checks the Partner Order against every Order Check (UC-003 Check Order).
6. System gives every Order Line the Catalog Price and records the Accepted Order in the Order Database with its Customer, Shipping Address and Order Lines.
7. System records the Order State recorded in the Order History.
8. System answers the Partner that the Order is accepted.

## Alternative Flows

### A1: API Key Missing or Unknown

**Trigger:** The request carries no API Key, or one that belongs to no registered Partner (step 2)  
**Flow:**

1. System refuses the request without running any Order Check.
2. System logs the refused request with its correlation id, without personal data.
3. Use case ends.

### A2: Order Names Another Partner

**Trigger:** The Partner Order names a Partner other than the one the API Key belongs to (step 3)  
**Flow:**

1. System refuses the request as not permitted for this Partner, without running any Order Check.
2. System logs the refused request with its correlation id, the Partner of the API Key and the Order Number, without personal data.
3. Use case ends.

### A3: Request Holds No Readable Order

**Trigger:** The request cannot be read as a Partner Order, for example because it holds a field the Partner Order format does not name (step 3)  
**Flow:**

1. System answers the Partner that the Order is rejected, with a Rejection Reason that names what cannot be read.
2. System logs the request with its correlation id, the Partner and the Order Number when it can be read, without personal data.
3. Use case ends.

### A4: Partner Resends an Order

**Trigger:** The Partner Order is a Duplicate of an Order that was answered accepted or rejected, or that was recorded in the Order Database but never answered (step 4)  
**Flow:**

1. System answers the Partner as it did the first time: accepted, or rejected with the same Rejection Reasons; an Order that was recorded but never answered is answered accepted from its existing receipt.
2. Use case ends.

### A5: Order Fails Order Checks

**Trigger:** The Partner Order fails at least one Order Check (step 5)  
**Flow:**

1. System records the Order State rejected with every Rejection Reason in the Order History.
2. System answers the Partner that the Order is rejected and lists every Rejection Reason.
3. System sends a Rejection Email with the Order Number and every Rejection Reason to the Order Contact of the Partner and keeps when it was sent.
4. Use case ends.

### A6: Catalog Outage

**Trigger:** The Product Catalog does not answer, so the Order cannot be checked (step 5)  
**Flow:**

1. System answers the Partner that the Order is temporarily unavailable, so that the Partner resends it later.
2. System leaves the receipt of the Order in the Order State received.
3. Use case ends.

### A7: Order Database Unavailable

**Trigger:** The Order Database is unavailable, so the Accepted Order cannot be recorded (step 6)  
**Flow:**

1. System answers the Partner that the Order is temporarily unavailable, so that the Partner resends it later.
2. System leaves the receipt of the Order in the Order State received.
3. Use case ends.

### A8: Resend While the First Request Is Being Checked

**Trigger:** The Partner Order has the Order Number of an earlier request of the Partner that is still being checked, with the same or with different content (step 4)  
**Flow:**

1. System answers the Partner that the Order is temporarily unavailable, without checking the second request.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Accepted Order is recorded exactly once in the Order Database with its Customer, Shipping Address and Order Lines, each Order Line with its SKU, quantity, Catalog Price, product name and weight.
- The Order History of the Order shows the Order States received and recorded with date and time.
- The Partner has been answered that the Order is accepted.
- The Accepted Order waits for its Pick List (UC-004 Deliver Pick List).

### Failure Postconditions

- No Order is answered as accepted unless it is recorded in the Order Database.
- An Accepted Order is never overwritten and never recorded a second time.
- An Order that was refused, rejected or answered temporarily unavailable is not recorded in the Order Database and gets no Pick List.
- No Rejection Email is sent for an Order that was not answered as rejected.

## Business Rules

### BR-001: Authentication Before Any Order Check

Every request is authenticated by the API Key of a registered Partner before any Order Check runs. A refused request gets no Order Check, no entry in the Order History and no Rejection Email.

### BR-002: One Partner per API Key

A Partner Order must name the Partner its API Key belongs to; otherwise it is refused, so that one Partner cannot send Orders for another. A refused Order is not a Rejected Order: it gets no entry in the Order History and no Rejection Email.

### BR-003: Accepted Only After Recording

The Partner is answered "accepted" only after the Accepted Order is recorded in the Order Database.

### BR-004: Every Rejection Reason at Once

The answer to a Rejected Order and its Rejection Email list every Rejection Reason the Order Checks found, in the same order, so that the Partner can fix everything at once.

### BR-005: Rejection Email

The Rejection Email is plain text in English, the same for every Partner, with the subject "Order <Order Number> rejected", and goes to the Order Contact of the Partner within 5 minutes of the rejection. It contains the Order Number and the Rejection Reasons only, and no Customer name, email address or Shipping Address. A request that cannot be read as a Partner Order (A3) gets its Rejection Reason in the answer only, without a Rejection Email and without an entry in the Order History. When the mail server does not take the Rejection Email, it is tried again until it is sent, also after a restart of the integration; nobody is alerted when the 5 minutes pass, because the Partner has the Rejection Reasons in the Order Answer. The System keeps when the Rejection Email was sent, but the order lookup does not show it (UC-005 BR-004). A Rejection Email that cannot be delivered to the Order Contact bounces to the sender mailbox, and nobody acts on it.

### BR-006: Catalog Price

Every Order Line of a Partner Order carries the Catalog Price in US dollars; the price the Partner sent is not used.

### BR-007: Duplicate Partner Order

A Partner Order with the Order Number and the same content as an earlier Order of the Partner that was answered accepted or rejected is a Duplicate: it gets the same answer as the first time, without a second recording, a second Rejection Email or a second Pick List. An earlier Order that was recorded in the Order Database but never answered, for example because the integration stopped before the answer, is a Duplicate too: the resend is answered accepted from the existing receipt and is not recorded again. An earlier Order that was answered temporarily unavailable and not recorded does not make a resend a Duplicate; the resend is checked like a new Order. A resend that arrives while the earlier request is still being checked is answered temporarily unavailable without being checked (A8); when the Partner resends again after the first answer, it gets that answer (A4).

### BR-008: Corrected Order

A Partner Order with the Order Number of a Rejected Order but different content is checked anew like a new Order. A Partner Order with the Order Number of an Accepted Order but different content is rejected with the Rejection Reason "order number already used" (UC-003 BR-004). A Partner Order with the Order Number of an earlier request that is still being checked but with different content is answered temporarily unavailable without being checked, like a resend with the same content (A8); when the Partner resends it later, the rules above for a reused Order Number apply.

### BR-009: Temporarily Unavailable

When the Order cannot be checked during a Catalog Outage, or cannot be recorded while the Order Database is unavailable, the Partner is answered "temporarily unavailable". This is not a rejection: the Order gets no Rejection Reason and no Rejection Email, and the Partner resends it later. The receipt recorded at step 4 stays in the Order State received, and the Partner's resend is checked on the same receipt.

### BR-010: Partner Order Format

Trailhead Gear defines the Partner Order format, the same for every Partner. It allows no field the format does not name; a request with such a field cannot be read as a Partner Order (A3).

### BR-011: Order Answer

Every request that is not refused gets an Order Answer with the Order Number, when it can be read, and the result: accepted, rejected or temporarily unavailable. The Order Answer of a Rejected Order lists every Rejection Reason, each with the failed Order Check, the Order Line it refers to and a text. A refused request (A1, A2) gets no Order Answer. A Duplicate of an Order answered accepted or rejected gets the original Order Answer. If the Order was recorded before the first request could be answered, the Duplicate gets an accepted answer from the existing receipt (A4, BR-007).

### BR-012: Trace of Refused and Unreadable Requests

A refused request (A1, A2) and a request that cannot be read (A3) are logged with their correlation id, the Partner when it is known and the Order Number when it can be read, and with no personal data. They do not appear in the order lookup.
