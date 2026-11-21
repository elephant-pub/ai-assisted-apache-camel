# Use Case: Check Order

## Overview

**Use Case ID:** UC-003  
**Use Case Name:** Check Order  
**Primary Actor:** Partner, Peakmart  
**Secondary Actors:** Product Catalog  
**Goal:** An Order is accepted or rejected for the same reasons whichever Channel it came from, every Rejection Reason is known at once, and an Order that passes carries the Product data the Warehouse needs  
**Trigger:** A received Partner Order or Peakmart Order is handed over for checking (UC-001 step 5, UC-002 step 4)  
**Status:** Approved  

**Requirements:** [FR-009, FR-010, FR-011, FR-012, FR-013, FR-014, FR-015, FR-016, FR-017, FR-018, FR-024, NFR-002, C-010, C-016, C-017, C-018](../requirements.md)  
**Layers:** Service

## Preconditions

- The receipt of the Order is recorded in the Order History with the Order State received.
- The Channel of the Order is known, and for a Partner Order the Partner that sent it is identified.

## Main Success Scenario

1. System checks the format of the Order Number for the Channel of the Order and notes a Rejection Reason when it fails.
2. System checks that the Order Number of the Partner Order lies in the Order Number Range of its Partner and does not belong to an Accepted Order with different content, and notes a Rejection Reason for each check that fails.
3. System checks that the Order has at least 1 and at most 30 Order Lines and notes a Rejection Reason when it fails.
4. System checks that the quantity of every Order Line is between 1 and 20 and notes a Rejection Reason for every Order Line that fails.
5. System checks that the Shipping Address has a Recipient name, a street line, a city, a postal code and a country, and notes a Rejection Reason for every part that is missing.
6. System checks that the country of the Shipping Address is the US or Canada and notes a Rejection Reason when it fails.
7. System asks the Product Catalog for the Product of the SKU of every Order Line.
8. System checks that the Product of every Order Line exists and is an Active Product, and notes a Rejection Reason for every Order Line that fails.
9. System completes every Order Line with the product name, the weight in grams and the Catalog Price from the Product Catalog.
10. System finds no Rejection Reason and reports that the Order passed every Order Check, with its completed Order Lines.

## Alternative Flows

### A1: Peakmart Order

**Trigger:** The Order is a Peakmart Order, which has no Order Number Range (step 2)  
**Flow:**

1. System skips the checks of the Order Number Range and of an Order Number already used.
2. Use case continues at step 3.

### A2: Catalog Outage

**Trigger:** The Product Catalog does not answer for at least one SKU within 2 seconds, gives an answer that names neither a Product nor an unknown SKU, or names a Product without the data an Order Line needs (step 7)  
**Flow:**

1. System stops checking and reports that the Order cannot be checked because of a Catalog Outage, without any Rejection Reason.
2. Use case ends.

### A3: Order Fails Order Checks

**Trigger:** At least one Rejection Reason was noted (step 10)  
**Flow:**

1. System reports that the Order failed the Order Checks, with every Rejection Reason in the order of the checks.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Order is reported as passed every Order Check.
- Every Order Line of the Order carries its product name, its weight in grams and the Catalog Price from the Product Catalog.

### Failure Postconditions

- An Order that failed an Order Check is never reported as passed.
- No Order is rejected because the Product Catalog did not answer.

## Business Rules

### BR-001: Same Order Checks for Every Channel

Partner Orders and Peakmart Orders pass the same Order Checks. Only the Order Number checks depend on the Channel: the format differs per Channel (BR-002), and the Order Number Range (BR-003) and an Order Number already used (BR-004) apply to Partner Orders only, because a Peakmart Order Number that was already received is skipped as a Duplicate before the checks (UC-002 BR-002).

### BR-002: Order Number Format

The Order Number of a Partner Order is `TG-` followed by four or more digits; the Order Number of a Peakmart Order is `PM-` followed by one or more digits.

### BR-003: Order Number Range

The digits after `TG-` of a Partner Order Number, read as a number, lie within the Order Number Range of the Partner that sent it, both limits included. An Order Number outside it is rejected, so that two Partners never use the same Order Number.

### BR-004: Order Number Already Used

A Partner Order whose Order Number belongs to an Accepted Order but whose content differs is rejected with the Rejection Reason "order number already used", so that an Accepted Order is never overwritten. The Order Number of a Rejected Order is not used up.

### BR-005: Number of Order Lines

An Order has at least 1 and at most 30 Order Lines, so that the Warehouse can handle it as one shipment.

### BR-006: Quantity per Order Line

The quantity of every Order Line is between 1 and 20. More than 20 of one Product in one Order Line is a Reseller, and Trailhead Gear does not sell to Resellers through these Channels.

### BR-007: Complete Shipping Address

The Shipping Address has a Recipient name, a street line, a city, a postal code and a country.

### BR-008: Destination Country

The country of the Shipping Address is the US or Canada.

### BR-009: Active Product

The SKU of every Order Line identifies a Product in the Product Catalog, and that Product is an Active Product.

### BR-010: All Rejection Reasons at Once

An Order is checked against every Order Check before it is rejected; one failed check does not stop the others. Each Rejection Reason names one failed Order Check, the Order Line it refers to when it refers to one, and what to change.

### BR-011: Product Data from the Catalog

Every Order Line of an Order that passed carries the product name, the weight in grams and the Catalog Price of its Product. Which price the recorded Order Line keeps is decided by the Channel (UC-001 BR-006, UC-002 BR-003).

### BR-012: Catalog Outage Is No Rejection

When the Product Catalog does not answer for an SKU, the Order cannot be checked and is reported as such, even when other Order Checks already failed; it is never rejected because of the outage. UC-001 answers it temporarily unavailable; UC-002 checks it again until the Product Catalog answers.

### BR-013: Product Catalog Not Answering

The Product Catalog counts as not answering for an SKU, for both Channels, when it has not answered within 2 seconds, when its answer is neither the Product nor that the SKU is unknown, or when it names a Product without the active flag, the weight or the Catalog Price. The Order then cannot be checked (BR-012); it is not rejected because of it. The 2 seconds let a Partner Order be answered within the 10 seconds of NFR-002.

### BR-014: Product Catalog Load

The integration asks the Product Catalog at most 20 times a second, the load the Product Catalog carries, also when waiting Peakmart Orders are checked again after a Catalog Outage.
