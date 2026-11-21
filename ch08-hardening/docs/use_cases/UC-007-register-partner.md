# Use Case: Register Partner

## Overview

**Use Case ID:** UC-007  
**Use Case Name:** Register Partner  
**Primary Actor:** Integration Team  
**Goal:** A new Partner is registered with its API Key, Order Number Range and Order Contact, so that it can send Orders without a code change  
**Trigger:** The Partner Manager asks the Integration Team to register a new Partner  
**Status:** Approved  

**Requirements:** [FR-036, NFR-012](../requirements.md)  
**Layers:** Service

## Preconditions

- The Integration Team has write access to the Secret Store and to the Partner Configuration in the repository of the integration.

## Main Success Scenario

1. Integration Team member creates the API Key of the Partner in the Secret Store.
2. Integration Team member adds the Partner to the Partner Configuration with its identifier, the name of its shop, the Order Number Range the Partner Manager assigned, the Order Contact and the name of its API Key in the Secret Store.
3. Integration Team member has the change of the Partner Configuration reviewed and released.
4. System reads the released Partner Configuration.
5. System verifies that every Partner identifier occurs only once.
6. System verifies that every Order Number Range is valid and does not overlap the Order Number Range of any other Partner.
7. System verifies that every Order Contact is a valid email address.
8. System verifies that the API Key of every Partner is in the Secret Store.
9. System registers the Partners of the Partner Configuration, so that the new Partner can send Orders with its API Key.

## Alternative Flows

### A1: Identifier Used Twice

**Trigger:** Two Partners of the Partner Configuration have the same identifier (step 5)  
**Flow:**

1. System reports to the Integration Team which identifier is used twice and keeps the Partners it registered before.
2. Use case ends.

### A2: Invalid or Overlapping Order Number Range

**Trigger:** The last number of an Order Number Range is smaller than its first number, or the range overlaps the Order Number Range of another Partner (step 6)  
**Flow:**

1. System reports to the Integration Team what is wrong with the Order Number Range, names the Partners whose ranges overlap, and keeps the Partners it registered before.
2. Use case ends.

### A3: Invalid Order Contact

**Trigger:** An Order Contact is not a valid email address (step 7)  
**Flow:**

1. System reports to the Integration Team which Partner's Order Contact is not a valid email address and keeps the Partners it registered before.
2. Use case ends.

### A4: API Key Not in the Secret Store

**Trigger:** The Secret Store holds no API Key under the name the Partner Configuration gives for a Partner (step 8)  
**Flow:**

1. System reports to the Integration Team which Partner has no API Key in the Secret Store and keeps the Partners it registered before.
2. Use case ends.

## Postconditions

### Success Postconditions

- The Partner is registered with its identifier, shop name, Order Number Range, Order Contact and API Key.
- Partner Orders sent with the new API Key are accepted for checking from now on (UC-001 Submit Partner Order).

### Failure Postconditions

- No Partner is registered with incomplete data, without an API Key, or with an Order Number Range that overlaps another Partner's.
- The Partners registered before stay registered as they were.
- No API Key is valid for a Partner that is not registered.

## Business Rules

### BR-001: Unique Partner Identifier

Every Partner has its own identifier, the one it names in every Partner Order.

### BR-002: Order Number Range

An Order Number Range is given as the first and the last number after `TG-`; the last number is not smaller than the first, and the Order Number Ranges of two Partners never overlap, so that two Partners never use the same Order Number.

### BR-003: API Key

The Integration Team creates the API Key in the Secret Store; every Partner has its own, and the Partner Configuration names it. The integration reads the API Key from the Secret Store and never shows it, neither in an answer nor in a log.

### BR-004: Registration Through the Partner Configuration

A Partner is registered by a reviewed and released change of the Partner Configuration, without a code change and without a screen of the integration, so that a new Partner sends its first Order within 1 working day.

### BR-005: Invalid Partner Configuration

A Partner Configuration that fails a check of steps 5 to 8 registers no Partner of it; the Partners registered before stay registered until a corrected Partner Configuration is released.
