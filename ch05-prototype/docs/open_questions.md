# Open Questions

Questions about the specification that the specification does not answer yet. A question is `Resolved` only when
the answer is in the specification itself; **Resolved in** names where. Resolved and withdrawn questions stay as
history.

## OQ-001: Who is told about a Peakmart Order that fails the Order Checks?

**Element:** FR-031
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The Order Desk gets an email with the Order Number and every Rejection Reason; nothing goes to Peakmart (C-007).

The kickoff notes list this as open after the meeting; today the Order Desk learns of it when the Customer calls.
Readings: (a) email to the Order Desk; (b) only visible in the Order History; (c) a ticket in the Order Desk's tool.

**Answer:** (a), as assumed. An email to the order desk mailbox.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** FR-031, C-023, Glossary: Order Desk Notification

## OQ-002: What happens to a Peakmart Order during a Catalog Outage?

**Element:** FR-029
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The Peakmart Order is checked again until the Product Catalog answers; it is neither rejected nor lost.

Open after the kickoff meeting. Readings: (a) retry until the Product Catalog answers; (b) reject with a Rejection
Reason and notify the Order Desk; (c) park it for the Order Desk to release by hand.

**Answer:** (a), retry until the catalog answers, but if a Peakmart order is still not checked two hours after it arrived, the integration team is alerted. It keeps waiting; nobody rejects it because of an outage.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** FR-029, FR-039

## OQ-003: What does a Partner get when its order cannot be checked because the Product Catalog or the Order Database is down?

**Element:** FR-030
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The request is answered as temporarily unavailable (no rejection, no Rejection Email); the Partner resends it, as Partners already do after a timeout.

Readings: (a) temporarily unavailable, Partner resends; (b) accept it and check it later, sending a Rejection Email
if it fails; (c) reject it.

**Answer:** (a), as assumed.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** FR-030

## OQ-004: Do Partners need their own Order State lookup in this release?

**Element:** FR-037
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Not in this release; FR-037 is Deferred until the Partner Manager has asked the Partners.

The kickoff notes leave it open; the Partner Manager will ask the Partners.

**Answer:** As assumed, deferred.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** FR-037

## OQ-005: Does the 15-minute delivery goal hold during the Maintenance Window and a Catalog Outage?

**Element:** NFR-001
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Time inside the Maintenance Window or a Catalog Outage is not counted; an Order held by one is delivered within 15 minutes after it ends.

The vision asks for every Accepted Order within 15 minutes, but the Warehouse SFTP Server is down two hours every
Sunday (C-015) and the Product Catalog a few minutes a week (C-016), so the goal as written cannot be met.

**Answer:** As assumed.
**Answered by:** Warehouse lead, 2026-11-10
**Resolved in:** NFR-001

## OQ-006: Which response time must the partner endpoint meet?

**Element:** NFR-002
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** 95% within 3 seconds and 100% within 10 seconds for orders of up to 30 Order Lines.

The sources say "right away". The Product Catalog answers in about 50 ms per SKU without a batch lookup, so 30 Order
Lines take about 1.5 seconds of lookups alone. The Partners' own timeouts are unknown.

**Answer:** As assumed.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** NFR-002

## OQ-007: Which order volume must the integration carry?

**Element:** NFR-003
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** 4,000 orders per day and 600 in any single hour, three times today's 1,200 per day.

Today about 300 Partner Orders and 900 Peakmart Orders a day, "more in November", and two more Partners from Q1; no
peak figure was given.

**Answer:** Not as assumed. Peakmart told us to expect up to 2,500 orders a day in November. Plan for 5,000 orders a day in total and 800 in any hour.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** NFR-003

## OQ-008: Can the Order Number of a Rejected Order be used again for a corrected order?

**Element:** FR-024
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Yes. Only the Order Number of an Accepted Order is taken; a corrected order with the number of a Rejected Order is checked anew. An Accepted Order Number sent with different content is rejected with "order number already used".

The kickoff notes cover a resend with the same number and content only.

**Answer:** As assumed.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** FR-024, FR-040

## OQ-009: Is a Peakmart redelivery recognised by Order Number or by event id?

**Element:** FR-023
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** By Order Number (`order_ref`), so that an Order is skipped even when Peakmart publishes it again under a new event id.

Peakmart only said that each event has an `event_id` and that events arrive more than once.

**Answer:** As assumed, by order number. A Peakmart order number is placed once.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** FR-023, Glossary: Duplicate

## OQ-010: Must a Partner Order Number lie in that Partner's Order Number Range?

**Element:** FR-010
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Yes; an Order Number outside the Partner's range is rejected with a Rejection Reason.

The kickoff notes say each Partner gets a range but not whether it is checked.

**Answer:** As assumed.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** FR-010

## OQ-011: What does the 90-day retention cover?

**Element:** C-022
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The Order History, including the personal data of Rejected Orders, is deleted after 90 days (GDPR Art. 5(1)(e)). The retention of Accepted Orders in the Order Database is not decided by this integration.

The Order Desk wants 90 days because older orders are in the ERP; the vision names GDPR but no article.

**Answer:** Not as assumed. Everything the integration keeps about an order (the order, its lines, its history, rejected orders) is deleted 90 days after the order was received. Accepted orders are also in the ERP; keeping them longer is the ERP's job, not ours.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** C-022, Glossary: Order Database, Glossary: ERP

## OQ-012: May a Rejection Email contain the Customer's personal data?

**Element:** C-023
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No; it holds the Order Number and the Rejection Reasons only (GDPR Art. 5(1)(c)). The Partner finds the rest in its own system by Order Number.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** C-023

## OQ-013: Which availability must the partner endpoint reach?

**Element:** NFR-008
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** 99.5% of each calendar month.

The sources give no figure; Partners resend after a timeout, so a short outage loses no Order.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** NFR-008

## OQ-014: How fast must the order lookup answer, and how fresh must the Order State be?

**Element:** NFR-004
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** 95% of lookups within 2 seconds (NFR-004); a change of Order State visible within 1 minute (NFR-005).

The vision asks that the Order Desk sees an Order's state "in under a minute", which is the time of the person, not of
the system.

**Answer:** As assumed.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** NFR-004, NFR-005

## OQ-015: When must the Integration Team be alerted about an Order that did not reach the Warehouse?

**Element:** FR-035
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** When an Accepted Order has not been sent to the Warehouse 30 minutes after it was recorded, outside a Maintenance Window.

Not in the sources; added because the vision's 15-minute goal otherwise has no one watching it.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** FR-035

## OQ-016: How fast must a Rejection Email or an Order Desk notification go out?

**Element:** NFR-014
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Within 5 minutes of the rejection.

**Answer:** As assumed.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** NFR-014

## OQ-017: Is an Order accepted when it passed the Order Checks, or only once it is recorded?

**Element:** Glossary: Accepted Order
**Raised by:** /requirements, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Only once it is recorded in the Order Database; the Partner's answer "accepted" follows the recording, so an accepted answer is never given for an Order that was not recorded.

**Answer:** As assumed.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** FR-004, Glossary: Accepted Order

## OQ-018: In which time zone is the Maintenance Window?

**Element:** C-015
**Raised by:** review meeting, 2026-11-10
**Blocking:** Yes
**Status:** Resolved

C-015 and the glossary term Maintenance Window say Sundays 02:00 to 04:00 but name no time zone, so the window cannot
be scheduled or tested. Raised and answered in the review meeting of 2026-11-10.

**Answer:** The Sunday 02:00 to 04:00 maintenance window is in the warehouse's local time, US Mountain Time (America/Denver).
**Answered by:** Warehouse lead, 2026-11-10
**Resolved in:** C-015, Glossary: Maintenance Window

## OQ-019: Is a Partner Order that names another Partner refused, or rejected like a failed Order Check?

**Element:** UC-001 A2
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Refused like a request without a valid API Key: no Order Check, no entry in the Order History, no Rejection Email.

FR-003 says the order is "refused" but not whether it is a Rejected Order. Readings: (a) refused, nothing recorded,
no email; (b) a Rejected Order with a Rejection Reason, recorded in the Order History and reported by Rejection Email
to the Order Contact of the API Key's Partner.

**Answer:** As assumed.
**Answered by:** Partner manager and integration architect, 2026-11-10
**Resolved in:** UC-001 A2, UC-001 BR-002

## OQ-020: What does a Partner get for a request that cannot be read as an Order at all?

**Element:** UC-001 A3
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The answer is "rejected" with a Rejection Reason naming what cannot be read; nothing is recorded in the Order History and no Rejection Email is sent, because there may be no Order Number to report.

The requirements cover Orders that fail an Order Check, not requests that hold no readable Order.

**Answer:** As assumed.
**Answered by:** Partner manager and integration architect, 2026-11-10
**Resolved in:** UC-001 A3, UC-001 BR-005

## OQ-021: Is a resend of an Order that was answered "temporarily unavailable" a Duplicate?

**Element:** UC-001 BR-007
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No. Only an earlier Order answered accepted or rejected makes a resend a Duplicate; a resend after "temporarily unavailable" is checked like a new Order.

FR-022 asks for "the same answer as the first time", which would repeat "temporarily unavailable" forever if taken
literally.

**Answer:** As assumed.
**Answered by:** Partner manager and integration architect, 2026-11-10
**Resolved in:** UC-001 BR-007

## OQ-022: What happens to a Peakmart Order Event of type order.placed that cannot be read as an Order?

**Element:** UC-002 A2
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** It is set aside with its event id for the Integration Team to examine, and the next Order Event is processed; no Order Desk Notification, because it may carry no Order Number.

Readings: (a) set aside for the Integration Team; (b) a Rejected Order with an Order Desk Notification when an Order
Number can be read; (c) stop reading the topic until someone acts.

**Answer:** As assumed.
**Answered by:** Partner manager and integration architect, 2026-11-10
**Resolved in:** UC-002 A2, UC-002 BR-009

## OQ-023: Is an Order rejected when some Order Checks failed but the Product Catalog does not answer?

**Element:** UC-003 BR-012
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No. The Order cannot be checked and is treated as during any Catalog Outage (Partner: temporarily unavailable; Peakmart: checked again), so that the Rejection Reasons stay complete (FR-017).

Readings: (a) not checkable, as assumed; (b) rejected at once with the Rejection Reasons already found, without the
SKU check.

**Answer:** As assumed.
**Answered by:** Partner manager and integration architect, 2026-11-10
**Resolved in:** UC-003 BR-012

## OQ-024: May the order lookup show the Customer's personal data?

**Element:** UC-005 BR-004
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No. It shows Order Number, Channel, Partner, Order States and Rejection Reasons only (C-021, data minimisation).

FR-032 to FR-034 name only the Order States and Rejection Reasons; the Order Desk may still want the Recipient or the
Shipping Address to answer a Customer.

**Answer:** As assumed.
**Answered by:** Partner manager and integration architect, 2026-11-10
**Resolved in:** UC-005 BR-004

## OQ-025: Who may use the order lookup?

**Element:** UC-005
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Only signed-in Order Desk members (precondition of UC-005); the lookup is offered through a screen (Layers: UI), not to Partners (FR-037 is deferred).

The requirements name no access rule for the lookup.

**Answer:** Not as assumed. The order desk keeps working in its helpdesk tool; it doesn't want another screen. The integration offers a read-only order lookup that the helpdesk tool calls (Layers: API), authenticated as the helpdesk tool. What it shows stays as in OQ-024.
**Answered by:** Order desk lead (product owner), 2026-11-10
**Resolved in:** UC-005 (Layers: API, A1, BR-005), FR-032, NFR-015, Glossary: Helpdesk Tool

## OQ-026: Does time inside the Maintenance Window count towards the 30 minutes of a stuck Order?

**Element:** UC-006 BR-001
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No; time inside the Maintenance Window is not counted, as in NFR-001.

FR-035 says "outside a maintenance window". Readings: (a) window time not counted; (b) no alert is raised during the
window, but the 30 minutes run on, so every Order held by the window is alerted when it ends.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** UC-006 BR-001

## OQ-027: How often is the Integration Team alerted about one stuck Order?

**Element:** UC-006 BR-003
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Once for each time limit it passes, so a Peakmart Order can be alerted once as not checked (FR-039) and once more as not sent to the warehouse (FR-035).

The entity model has one `alerted_at` per Order Receipt, which holds only one alert.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** UC-006 BR-003

## OQ-028: Who creates a Partner's API Key?

**Element:** UC-007 BR-003
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The System creates it at registration and displays it once to the Integration Team member, who hands it over to the Partner.

FR-036 says "register a partner with its API key". Readings: (a) the System creates it; (b) the Integration Team
enters a key agreed with the Partner.

**Answer:** Not as assumed. The integration team creates the API key in the secret store; the integration never shows a key.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** UC-007 step 1, UC-007 BR-003, FR-036, Glossary: API Key, Glossary: Secret Store

## OQ-029: How does the Integration Team register a Partner?

**Element:** UC-007
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Through a registration screen of the integration (Layers: UI) that checks the data before the Partner is registered.

NFR-012 says "through configuration only and without a code change". Readings: (a) a registration screen, as
assumed; (b) a configuration entry the integration reads on start, in which case the checks of UC-007 steps 2 to 4
run when the configuration is loaded.

**Answer:** Not as assumed. No screen. A partner is registered by adding it to the partner configuration in the integration's repository, reviewed and released like code (NFR-012 allows one working day).
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** UC-007 (Layers: Service, steps 2 to 9, BR-004), FR-036, NFR-012, Glossary: Partner Configuration

## OQ-030: Who owns the Partner Order format, and may a Partner send fields it does not name?

**Element:** UC-001 A3
**Raised by:** /message-model, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Trailhead Gear defines the format, the same for every Partner, and it allows no extra field (`docs/messages/partner-order.schema.json`); a request with an unknown field cannot be read and is answered rejected (UC-001 A3).

The kickoff notes give one sample for six Partners but do not say whether the Partners or Trailhead Gear set the
format. Readings: (a) ours and closed, as assumed; (b) ours but tolerant of extra fields, which are dropped; (c) one
format per Partner.

**Answer:** As assumed.
**Answered by:** Partner manager and warehouse lead, 2026-11-10
**Resolved in:** UC-001 A3, UC-001 BR-010

## OQ-031: Is `occurred_at` of a Peakmart `order.placed` event the time the Customer placed the Order?

**Element:** UC-002 step 2
**Raised by:** /message-model, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Yes; it becomes the Order's `orderedAt` (`docs/messages/mappings/peakmart-order-placed-to-order.md`).

The sample has no other timestamp, and Peakmart's AsyncAPI document has not been shared. The event time may instead
be when Peakmart published the event, which can be later after a redelivery.

**Answer:** As assumed.
**Answered by:** Partner manager and warehouse lead, 2026-11-10
**Resolved in:** UC-002 step 2, UC-002 BR-008

## OQ-032: How is a Pick List value that holds a comma written?

**Element:** C-012
**Raised by:** /message-model, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** As in RFC 4180: such a value is enclosed in double quotes, and a double quote inside it is doubled.

The Pick List is comma-separated and its street line joins address lines with ", " (`2240 Pearl Street, Apt 4`), so
the comma appears in values; product names may hold commas too. `docs/inputs/warehouse-picklist-format.md` says
nothing about quoting. To be confirmed by the Warehouse lead.

**Answer:** As assumed.
**Answered by:** Partner manager and warehouse lead, 2026-11-10
**Resolved in:** C-012

## OQ-033: What happens to a Peakmart Order Event whose buyer email is not an email address?

**Element:** UC-002 A2
**Raised by:** /message-model, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The Order Event cannot be read and is set aside for the Integration Team (UC-002 A2), because the Order Database keeps the Customer email with the format Email.

No Order Check covers the Customer email. Readings: (a) set aside, as assumed; (b) the Order is taken without the
Customer email; (c) a new Order Check with a Rejection Reason.

**Answer:** As assumed.
**Answered by:** Partner manager and warehouse lead, 2026-11-10
**Resolved in:** UC-002 A2, UC-002 BR-009

## OQ-034: What does the answer to a Partner look like?

**Element:** FR-004, FR-005
**Raised by:** /message-model, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** A JSON body with the Order Number, a result (`accepted`, `rejected`, `temporarily-unavailable`) and, for a rejected Order, the Rejection Reasons with the failed Order Check, the Order Line and a text (`docs/messages/order-answer.schema.json`). A refused request (UC-001 A1, A2) gets no Order Answer.

The sources say only that the Partner must learn at once whether the Order was taken and why not; no answer format
has been agreed with the Partners.

**Answer:** As assumed.
**Answered by:** Partner manager and warehouse lead, 2026-11-10
**Resolved in:** UC-001 BR-011, Glossary: Order Answer

## OQ-035: Does the Warehouse need the state or province on the Pick List?

**Element:** UC-004 BR-003
**Raised by:** /message-model, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No; the Pick List keeps the agreed columns and the state or province a Peakmart Order carries is dropped from it (it stays in the Order Database).

`docs/inputs/warehouse-picklist-format.md` has no column for it, while Peakmart sends one and US and Canadian
addresses usually need it for shipping.

**Answer:** As assumed.
**Answered by:** Partner manager and warehouse lead, 2026-11-10
**Resolved in:** UC-004 BR-003

## OQ-036: Under which path and with which header does a Partner send its Partner Order and API Key?

**Element:** UC-001
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** `POST /orders`, with the API Key in the header `X-API-Key`; the server address is given per environment (`docs/api/partner-orders.openapi.yaml`).

The inputs say only that a Partner sends one Order per HTTP request authenticated by an API Key (C-009). The
Partners cannot build against the endpoint until path, header and host are agreed with them.

**Answer:** As assumed. The partner manager sends `docs/api/partner-orders.openapi.yaml` to the partners for agreement; until they agree, the contract stays a draft.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** UC-001 (API: submitPartnerOrder)

## OQ-037: Which HTTP status codes and body does the Order Answer use?

**Element:** FR-004, FR-005
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** 200 with the Order Answer for accepted; RFC 9457 Problem Details (`application/problem+json`) carrying the Order Answer members for 400 (unreadable request, UC-001 A3), 422 (failed Order Checks, A5) and 503 (temporarily unavailable, A6, A7); plain Problem Details for 401 (no or unknown API Key, A1) and 403 (Order names another Partner, A2). A Duplicate gets the status of its first answer. No `Retry-After` header.

No status code is named in the inputs, and the Order Answer schema (`docs/messages/order-answer.schema.json`) allows
no Problem Details member, so a 4xx or 5xx body is not an Order Answer as that schema stands; `/message-model` may
need to allow them. Readings: (a) as assumed; (b) every answer with 200 and the Order Answer as body, the result
telling accepted, rejected and temporarily unavailable apart.

**Answer:** As assumed. The partner manager sends `docs/api/partner-orders.openapi.yaml` to the partners for agreement; until they agree, the contract stays a draft.
**Answered by:** Partner manager, 2026-11-10
**Resolved in:** UC-001 (API: submitPartnerOrder), UC-001 BR-011

## OQ-038: Will Peakmart share its AsyncAPI document, and which broker, consumer group id and message key does the topic use?

**Element:** C-005
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** `docs/api/peakmart-orders.asyncapi.yaml` is derived from the sample Order Event and is not agreed with Peakmart; it has no broker bindings.

Peakmart said it will "try to share" its AsyncAPI document. The inputs name the topic `peakmart.orders.v2` and a
consumer group Peakmart grants, but not the broker, the group id, the message key or the format of the other event
types on the topic. To be asked of Peakmart's integration contact.

## OQ-039: Where does the Product Catalog run, and which answers count as a Catalog Outage?

**Element:** C-010, UC-003 A2
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No answer, and any answer other than 200 or 404, counts as the Product Catalog not answering, so the Order cannot be checked (UC-003 BR-012).

`docs/inputs/product-catalog-api.md` documents 200 and 404 only and names no host; `docs/api/external/product-catalog.openapi.yaml`
is derived from it and not agreed with the Product Catalog team, who may have their own OpenAPI document.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** UC-003 A2, UC-003 BR-013

## OQ-040: How does the integration connect to the Warehouse SFTP Server, and what happens to a file after pickup?

**Element:** C-011, C-014
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The temporary name is `PL-<Order Number>.csv.tmp`; host, port and authentication are configuration.

`docs/inputs/warehouse-picklist-format.md` names the folder and the `.tmp` rule but not the host, the authentication,
the exact temporary name, or whether the Warehouse removes or moves a file after its pickup every 10 minutes. To be
confirmed by the Warehouse lead (`docs/api/external/warehouse-sftp.md`).

**Answer:** The warehouse moves every file it has picked up to `/inbound/picklists/processed`. Host, port and login are configuration, as assumed.
**Answered by:** Warehouse lead, 2026-11-10
**Resolved in:** C-011, C-014, Glossary: Warehouse SFTP Server

## OQ-041: Which subject, language, sender and Order Desk mailbox do the Rejection Email and the Order Desk Notification use?

**Element:** FR-006, FR-031
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Subjects `Order <Order Number> rejected` and `Peakmart order <Order Number> rejected`, in English; sender address and Order Desk mailbox address are configuration.

The inputs say plain text, the same for every Partner, with the Order Number and the Rejection Reasons only, but name
no subject, language, sender or Order Desk mailbox address (`docs/api/external/partner-mail.md`,
`docs/api/external/order-desk-mail.md`).

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** FR-006, FR-031

## OQ-042: What does a Partner get when it resends an Order while the first request is still being checked?

**Element:** UC-001 BR-007
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The resend is answered temporarily unavailable without being checked; the Partner resends again and then gets the answer of the first request (UC-001 A4).

BR-007 covers a resend of an Order that was answered accepted or rejected, and OQ-021 one that was answered
temporarily unavailable. A Partner that times out after 10 seconds (NFR-002) may resend while the first request has
no answer yet, so neither rule applies. Readings: (a) as assumed; (b) the resend waits for the first request and
gets its answer; (c) the resend is checked like a new Order.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-001 A8, UC-001 BR-007

## OQ-043: Can anyone see afterwards that a request was refused or could not be read?

**Element:** UC-001 A1, A2, A3
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Each refused or unreadable request is logged with its correlation id, the Partner when known and the Order Number when readable, and no personal data (NFR-011, NFR-013); it does not appear in the order lookup.

BR-001 and BR-005 say these requests get no entry in the Order History, so the Order Desk cannot answer a Partner who
says "we sent TG-1062 and got an error". Nothing says whether the Partner gets a reference it can quote.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-001 A1, UC-001 A2, UC-001 A3, UC-001 BR-012, UC-005 BR-004

## OQ-044: What does the Order History show for an Order that was answered temporarily unavailable?

**Element:** UC-001 A6, A7
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The receipt recorded at step 4 stays in the Order State received; the Partner's resend is checked on the same receipt.

Step 4 records the receipt with the Order State received before the Order is checked or recorded. After A6 or A7
nothing moves it on, so the order lookup shows an Order as received that Trailhead Gear did not take, and none of the
four Order States (FR-033) fits it. Readings: (a) as assumed; (b) a further Order State such as "temporarily
unavailable"; (c) the receipt is removed, so the attempt leaves no trace.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-001 A6, UC-001 A7, UC-001 BR-009

## OQ-045: How long is a Rejection Email or an Order Desk Notification tried again when the mail server is down, and who is told?

**Element:** UC-001 A5, UC-002 A4
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** It is tried again until it is sent, also after a restart of the integration; nobody is alerted when the 5 minutes of NFR-014 pass, because the Rejection Reasons are in the Order Answer and in the order lookup.

NFR-014 asks for 5 minutes, but nothing says what happens when the mail server does not take the email, for how long
it is tried again, or whether a pending email survives a restart. For a Peakmart Order the Order Desk Notification is
the only message the Order Desk gets.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-001 BR-005, UC-002 BR-004

## OQ-046: What happens when a Rejection Email cannot be delivered to the Order Contact?

**Element:** UC-001 A5
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The bounce goes to the sender mailbox and nobody acts on it; the Partner still has the Rejection Reasons in the Order Answer.

The Order Contact is checked as an email address at registration (UC-007 step 4), but the mailbox can stop existing
later, for example when the person leaves the Partner. Readings: (a) as assumed; (b) the Partner Manager is told so
that the Order Contact is updated.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-001 BR-005

## OQ-047: Can the Order Desk see whether and when a Rejection Email or Order Desk Notification was sent?

**Element:** UC-001 A5, UC-002 A4
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No; the integration keeps when it was sent, but the order lookup shows only the Order States and the Rejection Reasons (UC-005).

No postcondition or requirement records that the email went out, and none of the Order States (FR-033) stands for
it. A Partner who says "we never got the email" cannot be answered.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-001 A5, UC-002 A4, UC-001 BR-005, UC-002 BR-004, UC-005 BR-004

## OQ-048: How long does Peakmart keep Order Events on its topic, and who is told when the integration cannot read it?

**Element:** C-005
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Peakmart keeps Order Events longer than any outage of the integration; the Integration Team is alerted when the topic has not been read for 15 minutes (NFR-001).

NFR-007 protects an Order once it was read from the topic, not before. If the integration or its connection to the
topic is down for longer than Peakmart keeps events, Peakmart Orders are lost without anyone noticing. To be asked of
Peakmart's integration contact, together with OQ-038.

**Answer:** Peakmart keeps events for 7 days. The alert after 15 minutes without reading, as assumed.
**Answered by:** Integration architect, after asking Peakmart, 2026-11-10
**Resolved in:** C-005, FR-043, Glossary: Order Event

## OQ-049: Does a Peakmart Order that waits hold up the Peakmart Orders behind it?

**Element:** UC-002 A5, A6
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** No; the Peakmart Orders behind it are processed, and the waiting one is checked again until it can be checked or recorded.

The Product Catalog may not answer for one SKU only, so one Peakmart Order can wait while others could go through.
If it holds up the rest, every later Peakmart Order misses NFR-001. Peakmart Orders stand alone; nothing says that
the order in which they are processed matters.

**Answer:** As assumed.
**Answered by:** Integration architect, 2026-11-10
**Resolved in:** UC-002 A5, UC-002 A6, UC-002 BR-006

## OQ-050: Can a Peakmart Order Event that was set aside be put through again after a fix?

**Element:** UC-002 A2
**Raised by:** /integration-review, 2026-11-10
**Blocking:** Yes
**Status:** Resolved

A set-aside Order Event is a Customer's paid Peakmart Order that never reaches the Warehouse. UC-002 A2 ends with the
Integration Team examining it but says nothing about what happens then. Peakmart takes nothing back (C-007) and the
integration only reads the topic (C-005). Readings: (a) the Integration Team releases the fixed Order Event, which is
then taken in like a new one, the Duplicate check by Order Number preventing a double effect (a new use case);
(b) the Order Desk asks Peakmart to publish the Order again; (c) the Order is handled outside the integration, for
example entered in the ERP by hand. To be answered by the product owner with the integration architect.

**Answer:** Yes. A member of the integration team can resubmit a set-aside Order Event after the cause is fixed. It is then processed like a new Order Event, so the duplicate rule still prevents a second recording. This is a use case of its own for the integration team.
**Answered by:** Order desk lead (product owner) and integration architect, 2026-11-10
**Resolved in:** UC-008, UC-002 A2, FR-041, Glossary: Set-Aside Order Event, Glossary: Resubmission

## OQ-051: Who learns that a Peakmart Order Event was set aside, and how long is it kept?

**Element:** UC-002 A2
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The Integration Team is alerted with the event id, and the Order Number when it can be read, as for a stuck Order (UC-006); the set-aside event, which holds personal data, is deleted 90 days after it arrived (C-022).

A2 sets the event aside "so that the Integration Team can examine it", but nobody is told, it gets no Order History
entry, and the Order Desk cannot find it in the order lookup. It holds the buyer email and the Shipping Address
(C-021), and C-022 names orders, not set-aside events.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-002 A2, UC-002 BR-010, UC-006 A3, FR-042, C-022

## OQ-052: Is the Integration Team alerted about a Peakmart Order that waits for the Order Database?

**Element:** UC-002 A6
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Yes, as for an unchecked Peakmart Order: when it is still not recorded 2 hours after it arrived (FR-039).

UC-006 alerts about an Accepted Order not sent to the warehouse and about a Peakmart Order not checked because of a
Catalog Outage. A Peakmart Order that passed the Order Checks but waits for the Order Database fits neither time
limit, so it can wait without limit and without an alert.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-002 BR-006, UC-006 A1, UC-006 BR-002, FR-039

## OQ-053: What happens when the Product Catalog answers for an SKU but the answer cannot be used?

**Element:** UC-003 step 7
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** It counts as the Product Catalog not answering (UC-003 BR-012), so the Order is not rejected because of it.

The contract covers 200 and 404, and OQ-039 the other status codes. A 200 answer that breaks
`docs/messages/product.schema.json` (no active flag, no weight, no price) leaves the Order Line without the data it
needs. Readings: (a) as assumed, so a Partner Order is answered temporarily unavailable and a Peakmart Order waits
until the Product Catalog team fixes the Product; (b) the Order Line is rejected with a Rejection Reason.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-003 A2, UC-003 BR-013

## OQ-054: How long does the integration wait for the Product Catalog before it counts as not answering?

**Element:** UC-003 A2, NFR-002
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** A call that has no answer after 2 seconds counts as not answering, for both Channels, so that a Partner Order is answered within the 10 seconds of NFR-002.

A2 starts when the Product Catalog "does not answer", but a slow Product Catalog does answer, late. For a Partner
Order the whole request must be answered within 10 seconds (NFR-002); for a Peakmart Order nothing limits the wait.

**Answer:** As assumed.
**Answered by:** Review meeting (order desk lead as product owner, partner manager, warehouse lead, integration architect), 2026-11-10
**Resolved in:** UC-003 A2, UC-003 BR-013

## OQ-055: Can the Product Catalog carry the calls of the integration at peak?

**Element:** C-010
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Yes; to be confirmed by the Product Catalog team.

One call per SKU without a batch lookup (C-010) and 800 Orders an hour (NFR-003) of up to 30 Order Lines make up to
24,000 calls an hour, more when waiting Peakmart Orders are checked again after a Catalog Outage. Nothing says that
the Product Catalog team agreed to that load or that it limits the rate.

**Answer:** Confirmed, the catalog carries 20 calls a second.
**Answered by:** Integration architect, after asking the catalog team, 2026-11-10
**Resolved in:** C-010

## OQ-056: What happens when the Warehouse cannot read or loses a delivered Pick List?

**Element:** UC-004 step 4
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** The Warehouse tells the Order Desk, and the Order is handled outside the integration from the Order Database or the ERP; the integration never delivers the Pick List again (UC-004 BR-005).

There is no way back from the Warehouse to the integration. A Pick List the Warehouse cannot read (see OQ-032 on
commas) or loses counts as delivered for the integration, and BR-005 forbids a second delivery. Readings: (a) as
assumed; (b) the Integration Team can deliver a Pick List again on the Warehouse lead's request.

**Answer:** As assumed.
**Answered by:** Warehouse lead and integration architect, 2026-11-10
**Resolved in:** UC-004 BR-007

## OQ-057: How is the Integration Team alerted when many Orders pass a time limit at once?

**Element:** UC-006 BR-003
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** Orders that pass a time limit at the same time are reported in one alert that lists their Order Numbers.

During a long Catalog Outage at peak, up to 800 Peakmart Orders an hour (NFR-003) pass the 2-hour limit, and BR-003
gives each of them its own alert.

**Answer:** As assumed.
**Answered by:** Warehouse lead and integration architect, 2026-11-10
**Resolved in:** UC-006 step 2, UC-006 BR-005

## OQ-058: Through which channel and in which format is the Integration Team alerted?

**Element:** UC-006 step 2
**Raised by:** /integration-review, 2026-11-10
**Blocking:** No
**Status:** Resolved
**Assumed:** An email to the Integration Team mailbox, whose address is configuration, with the content of UC-006 step 2 and no personal data (BR-004).

`docs/messages/message_model.md` says the alert has no agreed format, and no contract in `docs/api/` names its
channel (email, chat, monitoring system).

**Answer:** As assumed.
**Answered by:** Warehouse lead and integration architect, 2026-11-10
**Resolved in:** UC-006 BR-006, Glossary: Alert

## OQ-059: What happens when a released Partner Configuration fails a check?

**Element:** UC-007
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** No Partner of the failed configuration is registered; the Integration Team is told what is wrong, and the Partners registered before stay registered until a corrected configuration is released (UC-007 A1 to A4, BR-005).

OQ-029 moved registration into the Partner Configuration, so the checks of UC-007 run when the released configuration
is read, not while a person enters data. Readings: (a) as assumed; (b) only the faulty Partner is left out and the
others are registered; (c) the integration does not start.

## OQ-060: How does the Partner Configuration name a Partner's API Key in the Secret Store?

**Element:** UC-007 BR-003
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** The Partner Configuration gives, for each Partner, the name under which its API Key is kept in the Secret Store; a Partner whose API Key is not there is not registered (UC-007 A4).

OQ-028 says the Integration Team creates the API Key in the Secret Store, but not how the integration finds the key
of a Partner. Readings: (a) as assumed; (b) the Secret Store holds the key under the Partner identifier by convention.

## OQ-061: Through which entry point does the Integration Team resubmit a Set-Aside Order Event?

**Element:** UC-008
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** Through an operations API of the integration (Layers: API) that an authorised Integration Team member calls with the event id; no screen, as for UC-005 and UC-007.

OQ-050 asks for a use case of its own but names no entry point. Readings: (a) as assumed; (b) a screen (Layers: UI);
(c) a change in the repository, released like the Partner Configuration.

## OQ-062: May the Integration Team change the content of a Set-Aside Order Event before resubmitting it?

**Element:** UC-008 BR-002
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** Yes; it is resubmitted unchanged after a fix in the integration, or with the content that could not be read corrected; the event id stays, and who resubmitted it and when is kept.

OQ-050 says the event is resubmitted "after the cause is fixed". When the cause is in the event itself, such as a
Customer email that is not an email address (OQ-033), it can only go through with its content changed, which is a
change of personal data by the Integration Team. Readings: (a) as assumed; (b) only unchanged; such an Order is handled
outside the integration.

## OQ-063: How often is the Integration Team alerted while the Peakmart order topic cannot be read?

**Element:** UC-006 A4
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** Once when the topic has not been read for 15 minutes, and not again until it has been read again.

OQ-048 fixes the 15 minutes but not whether the Alert repeats during a long outage.

## OQ-064: What happens to an Alert the mail server does not take?

**Element:** UC-006 A5
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** It is tried again until it is sent, also after a restart of the integration, as for a Rejection Email (OQ-045); the Order is noted as alerted only once it is sent.

OQ-058 makes the Alert an email; when the mail server is down, the Integration Team learns of nothing.

## OQ-065: Must the integration limit its calls to the Product Catalog to 20 a second?

**Element:** UC-003 BR-014
**Raised by:** /use-case-spec, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** Yes; the integration asks the Product Catalog at most 20 times a second, also when waiting Peakmart Orders are checked again after a Catalog Outage.

OQ-055 confirms that the Product Catalog carries 20 calls a second. At peak the integration needs about 7 a second
(800 Orders an hour of up to 30 Order Lines), but checking many waiting Peakmart Orders again after an outage can
exceed 20 unless the integration limits itself.

## OQ-066: How does the Helpdesk Tool authenticate to the order lookup?

**Element:** UC-005 BR-005
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** With a key of its own in the header `X-API-Key`, kept in the Secret Store as the Partners' API Keys are (`docs/api/order-lookup.openapi.yaml`).

OQ-025 says the lookup is "authenticated as the helpdesk tool" but names no mechanism, and the Helpdesk Tool's own
capabilities are not described. Readings: (a) as assumed; (b) OAuth 2.0 client credentials; (c) mutual TLS. To be
agreed with the owner of the Helpdesk Tool.

## OQ-067: How does an Integration Team member authenticate to resubmit a Set-Aside Order Event?

**Element:** UC-008
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** With a bearer token that identifies the member, so that who resubmitted is kept (UC-008 BR-002; `docs/api/order-event-resubmission.openapi.yaml`).

The requirements name no sign-in for the Integration Team, and a shared key would not tell who resubmitted.

## OQ-068: Which subject and language does the Alert use?

**Element:** UC-006 BR-006
**Raised by:** /integration-contracts, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** English, as the Rejection Email and the Order Desk Notification; the subject names the kind of the Alert and, for one Order or one Set-Aside Order Event, its Order Number or event id, with no personal data (`docs/api/external/integration-team-mail.md`).

OQ-058 makes the Alert an email to the Integration Team mailbox but names no subject or language.

## OQ-069: Where does a waiting Peakmart Order wait, with its personal data?

**Element:** Entity: ORDER_RECEIPT.waiting_order
**Raised by:** /entity-model, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** The Peakmart Order is kept on its receipt (`waiting_order`, with `waiting_for` Check or Recording) while it waits for the Product Catalog or the Order Database, and cleared once it is rejected or recorded.

UC-002 BR-006 keeps a waiting Peakmart Order through a restart and lets the Orders behind it go on, so it cannot
stay unread on the topic. The receipt otherwise holds no personal data. Readings: (a) as assumed; (b) a separate
store of waiting Orders outside the Order Database; (c) the Order Event is read again from the topic, which Peakmart
keeps for 7 days (C-005).

## OQ-070: Does a corrected resubmission replace the Order Event as it arrived?

**Element:** Entity: SET_ASIDE_ORDER_EVENT.event_content
**Raised by:** /entity-model, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** Yes; `event_content` holds the Order Event as last corrected, and the resubmission notes only that the content was corrected (`content_corrected`).

UC-008 BR-002 lets the Integration Team correct the content and keeps who resubmitted and when, but not whether the
content as Peakmart sent it stays. Readings: (a) as assumed; (b) the original and every corrected content are kept.

## OQ-071: Is a redelivered Order Event that cannot be read set aside a second time?

**Element:** Entity: SET_ASIDE_ORDER_EVENT.event_id
**Raised by:** /entity-model, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** No; an event id is set aside at most once, so a Peakmart redelivery (C-008) of the same unreadable Order Event raises no second Set-Aside Order Event and no second Alert.

UC-002 A2 sets aside every unreadable Order Event, and Peakmart delivers each one at least once. Readings: (a) as
assumed; (b) every delivery is set aside and alerted.

## OQ-072: Is a resubmission that still cannot be read kept?

**Element:** Entity: ORDER_EVENT_RESUBMISSION
**Raised by:** /entity-model, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** Yes; every resubmission is kept with who resubmitted it, when, whether the content was corrected and how it ended (Taken In, Already Received, Still Unreadable), until the Set-Aside Order Event is deleted 90 days after it arrived.

UC-008 step 5 and A3 keep who resubmitted and when; A2 says nothing about a resubmission that fails. Readings:
(a) as assumed; (b) only the resubmission that took the Order Event out is kept.

## OQ-073: How long is an Alert kept?

**Element:** Entity: ALERT
**Raised by:** /entity-model, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** 90 days after it was raised, as everything the integration keeps about an Order (C-022); the Orders it lists are deleted with their receipts.

An Alert holds no personal data (UC-006 BR-004), so C-022 does not bind it, but it must be kept until it is sent
(UC-006 A5) and as the note that an Order was alerted (UC-006 step 3). Readings: (a) as assumed; (b) kept only until
sent; (c) kept without limit.

## OQ-074: How does the integration tell that a Partner Order's first request is still being checked?

**Element:** Entity: ORDER_RECEIPT.checking_since
**Raised by:** /entity-model, 2026-11-10
**Blocking:** No
**Status:** Open
**Assumed:** The receipt notes since when a request is being checked (`checking_since`) and clears it with the answer; a resend with the same content in the meantime is answered temporarily unavailable (UC-001 A8).

UC-001 A8 needs to tell a request in progress from one answered temporarily unavailable, both in the Order State
received (UC-001 BR-009). Nothing says what happens when the integration restarts while `checking_since` is set.
Readings: (a) as assumed, the mark is cleared on restart; (b) the mark expires after the 10 seconds of NFR-002.

## OQ-075: What happens to an Order that passes every Order Check but does not fit the Order Database?

**Element:** UC-003, Entity: CUSTOMER_ORDER, ORDER_LINE
**Raised by:** prototype 001-peakmart-intake (execution report, ACR F2), 2026-11-13
**Blocking:** No
**Status:** Open

The prototype found values that pass every Order Check and fail only when the Accepted Order is recorded: a recipient
name over 100 characters, a price of 100000000.00 or more, a product name over 200 characters. Such an Order waits
and is checked again on every poll, so it waits forever and the Order Desk is never told; once 50 of them wait, newer
Orders are no longer checked. Readings: (a) new Order Checks with Rejection Reasons for each data limit, so the Order
is rejected and the Order Desk is told; (b) the Order waits as "waiting to be recorded", is alerted after 2 hours
(UC-002 BR-006) and does not hold up newer Orders.
