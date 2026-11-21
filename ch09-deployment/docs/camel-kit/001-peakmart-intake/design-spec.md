---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T16:28:12.464946Z"
  by: "camel-brainstorm"
---
# Trailhead Peakmart Intake Prototype — Design Spec

**Date:** 2026-11-13
**Camel Version:** 4.22.2
**Runtime:** Main (Camel JBang, `camel run`, YAML DSL)
**Platform BOM Version:** 4.22.2 (catalog `org.apache.camel:camel-catalog:4.22.2`)
**Status:** Approved 2026-11-13 by the integration architect, with the changes of the design review
`docs/camel-kit/001-peakmart-intake/design-review.md` (items 1–6), which are applied in this revision

**Sources:** `docs/requirements.md`, `docs/use_cases/UC-002-take-in-peakmart-order.md`,
`docs/use_cases/UC-003-check-order.md`, `docs/messages/` (schemas, mappings, examples), `docs/entity_model.md`,
`docs/api/peakmart-orders.asyncapi.yaml`, `docs/api/external/product-catalog.openapi.yaml`,
`docs/api/external/order-desk-mail.md`, `docs/open_questions.md`.
The message schemas and mappings decide formats. The use-case business rules decide behavior. This spec only adds
prototype decisions and does not restate or change those rules.

## Not Doing (and Why)

- **UC-001 Submit Partner Order (partner HTTP endpoint, API keys, Order Answer, Rejection Email)**: out of scope for this
  prototype per the request. The UC-003 checks that apply only to partners (Order Number Range BR-003, Order Number
  Already Used BR-004) are left out too, because UC-003 A1 skips them for Peakmart Orders.
- **UC-004 Deliver Pick List (SFTP, CSV)**: out of scope per the request. Recorded orders stay in the Order State
  `Recorded` and have no `PICK_LIST` row.
- **UC-005 Look Up Order**: out of scope per the request.
- **UC-006 alert emails (set-aside event, Peakmart Order waiting 2 h, topic not read 15 min)**: these are alerts by email
  to the Integration Team mailbox, and that is a separate use case. The prototype logs one WARN line with the event id
  and Order Number (no personal data) where UC-002 A2 asks for an alert. The `ALERT` tables are not created.
- **UC-008 Resubmit Set-Aside Order Event**: a separate use case with its own API. Set-aside events are stored, so this
  can be added later without a data migration.
- **UC-007 Partner registration / `PARTNER` table**: there are no partner orders in scope.
- **Real SMTP delivery of the Order Desk Notification**: no mail server is available in development, and we may not
  start containers. The notification is persisted (outbox) and delivered as a plain-text `.eml`-style file to a local
  outbox folder. The Spring Boot step swaps the file endpoint for `smtp`.
- **90-day retention purge (C-022)**: this is a scheduled housekeeping job, not part of the intake flow. It is deferred
  to the Spring Boot project.
- **Spring Boot packaging, Flyway, TLS, metrics**: the prototype exists to prove the flow on Camel Main first, as the
  request says. C-002/C-024 apply to the production build.
- **Horizontal scaling / multiple instances**: the prototype runs as a single process. The waiting-order poller does no
  row locking (`FOR UPDATE SKIP LOCKED`), so we must add it before running more than one instance.

---

## 1. Executive Summary

### Business Purpose
Take in every `order.placed` event Peakmart publishes on `peakmart.orders.v2` without manual handling. Each Peakmart
Order is checked with the shared Order Checks (UC-003). It is then either recorded once in the Order Database or reported
to the Order Desk as rejected. A Peakmart Order is never lost or recorded twice. This prototype proves that flow against
real Kafka and PostgreSQL before it becomes a Spring Boot project.

### Business Value
- Marketplace orders reach the Order Database without retyping (FR-007, FR-021).
- Redeliveries never create a second order (FR-023, NFR-006).
- Rejections reach the Order Desk with every reason at once (FR-017, FR-031).
- Catalog outages delay orders but never reject or lose them (FR-029, BR-006).

### Stakeholders
Order Desk (notifications), Integration Team (operates the flow and set-aside events), Warehouse (later consumer of
recorded orders), Peakmart (source only, nothing is sent back, C-007).

---

## 2. Systems Landscape

| System | Type | Protocol | Role | Prototype instance |
|--------|------|----------|------|--------------------|
| Peakmart order topic | Kafka topic `peakmart.orders.v2` | Kafka | Source | `camel infra run kafka`, `localhost:9092` (already running) |
| Order Database | PostgreSQL 17.10 | JDBC | Target (and durable waiting store) | `camel infra run postgres`, `localhost:15432`, db `postgres`, user/password `test` (already running) |
| Product Catalog | HTTP JSON API `GET /products/{sku}` | HTTP | Lookup | Stub on `localhost:8091`, which serves `docs/api/external/product-catalog.openapi.yaml` and the product examples |
| Order Desk mailbox | Email | SMTP (stand-in: file) | Target | Files in `prototype/target/outbox/order-desk/` |

Ports 8080, 8081 and 5432 are taken on this machine and are not used. The integration itself opens no HTTP port.

---

## 3. Flow Designs

The prototype has three flows: **peakmart-order-intake**, **peakmart-order-check** and **order-desk-notification**.
It also has a one-shot schema-init step and a separately run **product-catalog-stub**. They all share one datasource,
`#orderDb`.

### Overall shape (Ingestion → Processing → Delivery)

```text
kafka peakmart.orders.v2
  └─ peakmart-order-intake ──(not JSON)──> peakmart-event-set-aside (key kafka-<p>-<o>)
        ├─(JSON, not order.placed)──> skip (A1)
        ├─(unreadable)──> peakmart-event-set-aside ──> SET_ASIDE_ORDER_EVENT + WARN log (A2)
        ├─ map to canonical Order ─> peakmart-order-receive ──(order_ref seen)──> skip as Duplicate (A3)
        │                                 └─> ORDER_RECEIPT(state Received, waiting_for=Check, waiting_order=Order JSON)
        │                                     + ORDER_STATE_CHANGE(Received)      [one SQL statement]
        └─ last step on every path: commit this record's offset (KafkaManualCommit)

sql poller: ORDER_RECEIPT where waiting_for IS NOT NULL
  └─ peakmart-order-check-poll ─> order-check
        ├─ structural checks (bean)              UC-003 steps 1,3–6
        ├─ split lines ─> product-lookup (throttle 20/s, 2 s timeout)   UC-003 steps 7–8
        └─ evaluate (bean) ─┬─ CATALOG_OUTAGE ─> leave waiting, WARN log (A5, BR-012)
                            ├─ REJECTED       ─> order-reject  ─> REJECTION_REASON + state Rejected + NOTIFICATION (A4)
                            └─ PASSED         ─> order-record  ─> CUSTOMER_ORDER + ORDER_LINE + state Recorded (steps 5–6)

sql poller: NOTIFICATION where sent_at IS NULL
  └─ order-desk-notification-send ─> plain-text file in outbox ─> sent_at, attempts (BR-004)
```

The durable waiting store is the key decision (OQ-069, taken as assumed). The intake writes the receipt, with the
canonical Order in `waiting_order`, **before** the Kafka offset is committed. After that the order lives in PostgreSQL.
A catalog outage or a database outage on one order never blocks the topic (BR-006, OQ-049). The order is checked again
on every poll until it is rejected or recorded, and a restart loses nothing (NFR-007).

### Flow: peakmart-order-intake

| Field | Value |
|---|---|
| Target Module | `prototype` |

**Purpose:** Read Peakmart Order Events, skip other types, set aside unreadable ones, and durably receive each new Peakmart
Order Number exactly once.

**Source:**
- System: Peakmart order topic
- Component: `kafka` (MCP-verified), `org.apache.camel:camel-kafka:4.22.2`
- **Rationale:** The source is a Kafka topic that Peakmart owns (C-005). Consuming directly with `camel-kafka` needs no
  bridge and gives us offset control. Offset control is what makes "never lost" achievable.
- **Constraints:** read-only access and a consumer group that Peakmart grants (C-005). At-least-once delivery with
  redeliveries (C-008). No replies (C-007). Topic retention is 7 days.
- Trigger: each record on `{{peakmart.topic}}`
- Endpoint options: `brokers={{kafka.brokers}}`, `groupId={{peakmart.group-id}}`,
  `autoOffsetReset={{peakmart.auto-offset-reset}}` (`earliest`, so that a new group reads the events already on the
  topic), `breakOnFirstError=true`, `autoCommitEnable=false`, `allowManualCommit=true` (design review item 2).
- **Offset commit:** auto-commit is not used. Its guarantee depends on when the Kafka client commits on its own (on a
  timer during poll and again when the consumer closes), and nobody reading the route can see that. Instead, the last
  step of `peakmart-order-intake` commits the record explicitly with the `KafkaManualCommit` that the consumer puts in
  header `CamelKafkaManualCommit` (MCP-verified header). That step is reached only after the receipt insert, the
  set-aside insert or the skip has completed. A record whose processing fails after redeliveries never reaches the
  commit, and with `breakOnFirstError=true` it is consumed again. This is the "keeps waiting" behavior for a database
  outage at intake time (A6 analogue). The receipt and set-aside inserts are idempotent, so a redelivery after a crash
  between insert and commit is harmless.
  **Kill-and-restart test (kept; it is how we know):** kill the process between the receipt insert and the commit,
  restart, and check that the event is consumed again and skipped as a Duplicate. Its result is recorded in the
  execution report together with scenarios 1–10.

**Routes:**

1. `peakmart-order-intake` (from kafka)
   - EIP `setProperty` (MCP-verified): `correlation-id` = `${uuid}` (simple), `kafka-position` =
     `${header.CamelKafkaPartition}-${header.CamelKafkaOffset}` (MCP-verified consumer headers; used for logging and as
     the fallback set-aside key `kafka-<partition>-<offset>`).
   - EIP `doTry` (MCP-verified) around one step only: EIP `setProperty` `event-type`, language `jsonpath`
     (MCP-verified) `$.event_type` with `suppressExceptions=true` (a missing field gives no value). `doCatch`
     `java.lang.Exception` → `setProperty not-json=true`. The record is not JSON when parsing it fails. A record with an
     empty value counts as not JSON. **Implementation must prove** with scenario 6 that a non-JSON record lands in the
     catch and not in the skip branch; if `suppressExceptions` also hides parse errors, the readability test is a
     separate `jsonpath` `$` with `suppressExceptions=false` in the same `doTry`.
   - EIP `choice` (MCP-verified):
     - `when` `not-json` → `setProperty cannot-read-reason` = `not JSON` (fixed text) → `to direct:peakmart-event-set-aside`,
       which stores it under the key `kafka-<partition>-<offset>`. We cannot tell that such a record is not a broken
       `order.placed`, and UC-002 says an order that was read is never lost. The Integration Team decides what it was
       (design review item 3, replaces the former skip of A-6).
     - `when` `event-type == 'order.placed'` → `to direct:peakmart-event-read`.
     - `otherwise` → skipped with a DEBUG log of `kafka-position` only, with no error and no Order History (A1, BR-001).
   - EIP `script` (MCP-verified), language `simple`: `${header.CamelKafkaManualCommit.commit()}`. This is the **last
     step** of the route and commits this record's offset after the receipt insert, the set-aside insert or the skip
     (design review item 2). It runs only when no exception escaped the steps before it.
   - **Rationale (choice vs filter):** there are now three outcomes (set aside, read, skip), and the commit must run
     after all of them, so a `choice` followed by the commit step says exactly that.
2. `peakmart-event-read` (direct). It has two top-level steps, so each "set aside" path ends its branch, with no `stop`
   needed:
   - EIP `doTry` (MCP-verified):
     - `to json-validator:file:{{schemas.dir}}/peakmart-order-placed.schema.json` (MCP-verified component,
       `org.apache.camel:camel-json-validator:4.22.2`). The spec schema is used unchanged as the readability rule
       (BR-009).
     - `doCatch` `org.apache.camel.component.jsonvalidator.JsonValidationException` → EIP `bean` (MCP-verified)
       `cannotReadReason.of(exception)` → `setProperty cannot-read-reason` → `to direct:peakmart-event-set-aside`.
       The bean returns only the path and keyword of the **first** validation error, as `<path>: <keyword>` (for
       example `$.buyer.email: format`), and **never** the validator's message: we assume the message echoes values
       (design review item 4). "First" is the first error in the order the validator returns them. Implementation
       verifies the error accessors (path, keyword) against the resolved validator library version.
   - EIP `choice` (runs only when the validator did not fail; the catch branch sets property `readable=false` and the
     `choice` tests it):
     - `when` not readable → nothing (already set aside).
     - `when` `$.buyer.email` is present and does not match `{{peakmart.email-pattern}}` (`jsonpath` with
       `suppressExceptions=true` + simple `regex`) → `setProperty cannot-read-reason` = `$.buyer.email: format` (the same
       path-and-keyword form as the validator errors) → `to direct:peakmart-event-set-aside`. This explicit check is there because format assertions in the
       JSON Schema 2020-12 validator are not guaranteed. Example `pm-50119-buyer-email-not-an-email.json` must be set
       aside.
     - `otherwise` → EIP `setBody` (MCP-verified), language `groovy` (MCP-verified): the DataMapper
       `kaoto-datamapper-5c1e9a27` below, which gives the canonical Order JSON → `to direct:peakmart-order-receive`.
3. `peakmart-event-set-aside` (direct)
   - `to sql:file:{{sql.dir}}/set-aside-insert.sql` (MCP-verified `sql`): `INSERT … ON CONFLICT (event_id) DO NOTHING`
     with `event_id` (or `kafka-<partition>-<offset>` when the event id itself cannot be read, assumption A-7, or when
     the record is not JSON, design review item 3), `order_number` when readable, `event_content` (raw record as text;
     empty string for an empty value, because the column is Not Null), `cannot_read_reason` (property
     `cannot-read-reason`: `<path>: <keyword>` or the fixed text `not JSON`, never the validator's message and never
     values from the event), `arrived_at`, state `Set Aside`. An event id is set aside only once (OQ-071 as assumed).
   - EIP `log` WARN: `Order event set aside eventId=… orderNumber=… reason=…`. This stands in for the UC-006 alert and
     contains no personal data.
4. `peakmart-order-receive` (direct)
   - `to sql:file:{{sql.dir}}/receipt-insert.sql`. This is one atomic statement:
     `WITH r AS (INSERT INTO order_receipt(…, order_state='Received', waiting_for='Check', waiting_order=:#order, content_hash=encode(sha256(convert_to(:#order,'UTF8')),'hex')) ON CONFLICT (order_number) WHERE channel='Peakmart' DO NOTHING RETURNING id, received_at) INSERT INTO order_state_change(order_receipt_id, order_state, changed_at) SELECT id,'Received',received_at FROM r`.
   - EIP `choice` (MCP-verified) on header `CamelSqlUpdateCount`:
     - `== 0` → log INFO `Duplicate Peakmart order skipped orderNumber=… eventId=… correlationId=…` (A3, BR-002). No
       history, no notification.
     - otherwise → log INFO `Peakmart order received orderNumber=… correlationId=…`.

**DataMapper:**

### DataMapper: kaoto-datamapper-5c1e9a27

**Mapping ID:** `kaoto-datamapper-5c1e9a27`
**Transformation Engine:** Groovy (inline)
**Source:** JSON_SCHEMA — `docs/messages/peakmart-order-placed.schema.json`
**Target:** JSON_SCHEMA — `docs/messages/order.schema.json` (completed later by `order-record`; product fields are absent at intake)
**Format Pair:** JSON → JSON

This table is a 1:1 transcription of `docs/messages/mappings/peakmart-order-placed-to-order.md`. Where the two differ,
that file wins.

#### Source Parameters

| Parameter | Type | Schema Path |
|-----------|------|-------------|
| correlation-id | Primitive | none |
| received-at (UTC now, ISO-8601) | Primitive | none |

#### Field Mappings

| Source Field | Src Type | Target Field | Tgt Type | Transformation | How |
|---|---|---|---|---|---|
| order_ref | string | orderNumber | string | Direct copy | Auto |
| — | — | channel | string | Constant `peakmart` | Manual |
| event_id | string | eventId | string | Direct copy | Auto |
| occurred_at | string | orderedAt | string | Direct copy, keep offset | Auto |
| (param) received-at | string | receivedAt | string | Parameter | Manual |
| (param) correlation-id | string | correlationId | string | Parameter | Manual |
| buyer.email | string | customerEmail | string | Copy when present | Manual |
| ship_to.full_name | string | shippingAddress.recipientName | string | Copy when present | Auto |
| ship_to.address1, ship_to.address2 | string | shippingAddress.streetLine | string | Join non-empty parts with ", " (BR-007) | Manual |
| ship_to.city | string | shippingAddress.city | string | Copy when present | Auto |
| ship_to.state | string | shippingAddress.stateRegion | string | Copy when present and not empty | Manual |
| ship_to.zip | string | shippingAddress.postalCode | string | Copy when present | Auto |
| ship_to.country_code | string | shippingAddress.country | string | Copy when present | Auto |
| items[] (index) | integer | lines[].lineNumber | integer | Position from 1 | Manual |
| items[].sku | string | lines[].sku | string | Direct copy | Auto |
| items[].qty | integer | lines[].quantity | integer | Direct copy | Auto |
| items[].price_usd | string | lines[].unitPrice.amount | string | Direct copy (Peakmart Price, BR-003) | Auto |
| — | — | lines[].unitPrice.currency | string | Constant `USD` | Manual |

#### Conditional Mappings

| Target Field | Condition | True Value | False Value | Notes |
|---|---|---|---|---|
| customerEmail | buyer.email present | buyer.email | omitted | — |
| shippingAddress.* | source part present and non-empty | copied value | omitted | a missing part becomes a Rejection Reason in `order-check`, not a mapping error |
| shippingAddress.stateRegion | ship_to.state present and non-empty | ship_to.state | omitted | — |

#### Collection Mappings

| Source Collection | Target Collection | Iteration |
|---|---|---|
| items[] | lines[] | for-each, in Peakmart order |

`lines[].productName` and `lines[].unitWeightGrams` are filled by `order-check` from the Product Catalog (UC-003 step 9).
The Catalog Price is not used for Peakmart Orders (BR-003).

**Error Handling:**
- Strategy: Retry, then do not commit (Kafka redelivers).
- Route `onException` (MCP-verified) for `java.sql.SQLException` and `org.springframework.dao.DataAccessException`:
  `maximumRedeliveries=3`, `redeliveryDelay=1000`, `useExponentialBackOff=true`, `backOffMultiplier=2`,
  `logExhaustedMessageBody=false`, `logExhaustedMessageHistory=false`, not handled. The manual commit step is
  therefore never reached, and with `breakOnFirstError=true` the event is consumed again on the next poll. The order "waits" on the topic while the database is down and is never lost.
- Dead Letter: none. The set-aside table plays this role for unreadable events. A database failure must not drop an
  event.
- Circuit breaker: no.

**Configuration Properties:**
```properties
kafka.brokers=localhost:9092
peakmart.topic=peakmart.orders.v2
peakmart.group-id=trailhead-order-integration
peakmart.auto-offset-reset=earliest
peakmart.email-pattern=^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$
schemas.dir=../docs/messages
sql.dir=sql
```

### Flow: peakmart-order-check

| Field | Value |
|---|---|
| Target Module | `prototype` |

**Purpose:** Run every Order Check on each waiting Peakmart Order and then record it, reject it, or leave it waiting
during a Catalog Outage.

**Source:**
- System: Order Database (waiting receipts)
- Component: `sql` consumer (MCP-verified), `org.apache.camel:camel-sql:4.22.2`
- **Rationale:** the waiting order lives in `ORDER_RECEIPT` (OQ-069). A polling `sql` consumer turns "check again until
  the catalog answers" into a plain query (`waiting_for IS NOT NULL`). It survives restarts and never blocks the topic.
  `sql` was chosen over `jdbc` because we need named parameters, `file:`-externalized queries and a scheduled consumer.
- **Constraints:** single instance (see Not Doing). Poll interval and batch size are externalized.
- Trigger: `sql:file:{{sql.dir}}/waiting-select.sql?dataSource=#orderDb&delay={{order-check.poll-delay-ms}}&maxMessagesPerPoll={{order-check.batch-size}}`
  (oldest first by `received_at`). The poller ignores `waiting_for='Recording'`, because the prototype never sets it
  (see Error Handling).

**Routes:**

1. `peakmart-order-check-poll` (from sql): EIP `setProperty` `correlation-id`/`receipt-id`/`order-number` from the row,
   `setBody` = `waiting_order`, `to direct:order-check`.
2. `order-check` (direct). This is the UC-003 sequence; reasons are kept in check order (BR-010, A3):
   - EIP `bean` (MCP-verified) `orderChecks.structuralReasons`. This is a Java bean that returns the ordered list of
     Rejection Reasons for: order-number-format (`^PM-\d+$`, BR-002); number-of-order-lines (1–30, BR-005); quantity per
     line (1–20, BR-006); shipping-address per missing part in the order recipient name, street line, city, postal code,
     country (BR-007); destination-country (US/CA, BR-008). The result is stored in property `structural-reasons`.
   - EIP `split` (MCP-verified) on `jsonpath` `$.lines` with `aggregationStrategy` =
     `org.apache.camel.processor.aggregate.GroupedBodyAggregationStrategy`. Each line goes to
     `direct:product-lookup`, and the aggregated body is the list of per-line catalog answers. The split is sequential
     because the throttle sets the pace.
   - EIP `bean` `orderChecks.evaluate(order, structural-reasons, catalog-answers)` returns `{outcome, reasons, order}`:
     - any answer `OUTAGE` → `CATALOG_OUTAGE`. This holds even when other checks failed (BR-012).
     - else adds a `sku` reason per line whose product is `UNKNOWN` or inactive (BR-009), after the structural reasons;
     - reasons not empty → `REJECTED`.
     - otherwise → `PASSED`, with each line completed with `productName` and `unitWeightGrams` (BR-011). The Peakmart
       price is kept.
   - EIP `choice`:
     - `CATALOG_OUTAGE` → log WARN `Peakmart order waits for Product Catalog orderNumber=… correlationId=…`. Nothing is
       written, so the next poll checks it again (A5).
     - `REJECTED` → `to direct:order-reject`.
     - `PASSED` → `to direct:order-record`.
   - **Rationale (Java bean vs. Groovy/YAML for the checks):** there are eight rules with per-line reasons and fixed
     texts. That is business logic, and the constitution puts it in beans (rule 3). A plain Java class runs under
     `camel run` today and moves unchanged into the Spring Boot project, where UC-001 reuses it for Partner Orders
     (FR-016).
3. `product-lookup` (direct), one call per Order Line:
   - EIP `throttle` (MCP-verified): `mode=TotalRequests`, expression `{{catalog.max-calls-per-second}}` (20),
     `timePeriodMillis=1000`. This throttle node is shared by every check and every re-check (BR-014, OQ-065 as
     assumed).
   - EIP `setHeader` `CamelHttpPath` = `/products/${sku}` (URL-encoded by the bean/simple).
   - EIP `doTry`:
     - `to http:{{catalog.base-url}}?throwExceptionOnFailure=false&connectTimeout={{catalog.timeout-ms}}&responseTimeout={{catalog.timeout-ms}}&connectionRequestTimeout={{catalog.timeout-ms}}`
       (MCP-verified `http`, `org.apache.camel:camel-http:4.22.2`). The 2 s limit comes from BR-013.
     - `choice` on `CamelHttpResponseCode`:
       - `200` → `json-validator:file:{{schemas.dir}}/product.schema.json`. If it passes, the answer is
         `{lineNumber, sku, status: FOUND, product}`. If the validator fails (`doCatch JsonValidationException`), the
         answer is `OUTAGE` (BR-013, OQ-053).
       - `404` → `UNKNOWN`.
       - otherwise → `OUTAGE` (OQ-039).
     - `doCatch` `java.io.IOException` (connect refused, timeout) → `OUTAGE`.
   - **Rationale:** `http` (Apache HttpClient 5) exposes all three timeouts as scalar options and
     `throwExceptionOnFailure=false` lets the route map 404 vs. other statuses explicitly. `rest`-producer was not
     needed for a single GET.
4. `order-reject` (direct): `to sql:file:{{sql.dir}}/order-reject.sql`. This is one atomic statement (CTE) that does all
   of the following:
   - updates the receipt to `Rejected`, guarded by `AND order_state='Received'`, and clears `waiting_for` and
     `waiting_order`;
   - inserts `ORDER_STATE_CHANGE(Rejected)`;
   - inserts every `REJECTION_REASON` with its `position`, from `jsonb_to_recordset(CAST(:#reasons AS jsonb))`;
   - inserts `NOTIFICATION(type 'Order Desk Notification', recipient {{orderdesk.mailbox}}, attempts 0)`.

   It then logs INFO `Peakmart order rejected orderNumber=… reasons=<count> correlationId=…` (A4 steps 1–2).
5. `order-record` (direct):
   - `to json-validator:file:{{schemas.dir}}/order.schema.json`. This guard proves that the completed Order matches the
     canonical model before anything is recorded. If it fails, that is a defect, not a rejection: log ERROR, write
     nothing, and the order stays waiting.
   - `to sql:file:{{sql.dir}}/order-record.sql`. This is one atomic CTE that inserts `CUSTOMER_ORDER`, inserts every
     `ORDER_LINE` (`jsonb_to_recordset` over `lines`, `unit_price` = Peakmart Price), updates the receipt to `Recorded`
     (guarded, waiting cleared) and inserts `ORDER_STATE_CHANGE(Recorded)`. The guard `order_state='Received'` and
     `customer_order.order_number UNIQUE` make the double recording of FR-021 impossible.
   - log INFO `Peakmart order recorded orderNumber=… lines=… correlationId=…`.

**Why single CTE statements instead of `transacted`:** each outcome is one PostgreSQL statement, so it is atomic without
a `PlatformTransactionManager` bean. Forage, which would provide transaction policies, is not configured for this
project (see §4). The SQL lives in `prototype/sql/*.sql`, where reviewers can read it.

**Rejection Reason texts** (`text` field, the shape of `rejection-reason.schema.json`, no personal data, C-023):

| orderCheck | lineNumber | text |
|---|---|---|
| order-number-format | — | `The Order Number <n> is not PM- followed by digits. Send the Peakmart order reference.` |
| number-of-order-lines | — | `The order has <k> Order Lines; it must have 1 to 30. Split or complete the order.` |
| quantity | per line | `Order Line <i>: the quantity <q> is not between 1 and 20. Change the quantity.` |
| shipping-address | — (one per missing part) | `The Shipping Address has no <recipient name/street line/city/postal code/country>. Add it.` |
| destination-country | — | `The Shipping Address is in <cc>; Trailhead Gear ships to US and CA only.` |
| sku (unknown) | per line | `Order Line <i>: the SKU <sku> is not in the Product Catalog. Replace it with a catalog SKU.` |
| sku (inactive) | per line | `Order Line <i>: the SKU <sku> is not an Active Product in the Product Catalog. Replace it with an active SKU.` |

The last text matches the existing example `docs/messages/examples/order-desk-notification/pm-50118.json`. The other
texts are illustrative ("Rejection Reason texts are illustrative", message model) and are assumption A-5.

**Error Handling:**
- Strategy: Log and retry on the next poll. A database failure while rejecting or recording rolls back the single
  statement. The receipt keeps `waiting_for='Check'` and is checked and recorded again on the next poll (A6). The
  prototype does not set `waiting_for='Recording'`, because it cannot write while the database is down. This is a known
  difference from the entity model's intent and only matters for UC-006 (assumption A-9).
- Route `onException` for `java.sql.SQLException`, `org.springframework.dao.DataAccessException`:
  `maximumRedeliveries=2`, `redeliveryDelay=1000`, `useExponentialBackOff=true`, handled, log WARN with
  order number/correlation id only, `logExhaustedMessageBody=false`.
- Catalog outage is not an exception path. It is the `CATALOG_OUTAGE` outcome.
- Circuit breaker: no. The throttle and the poll interval already bound the load on the catalog during an outage.

**Configuration Properties:**
```properties
order-check.poll-delay-ms=5000
order-check.batch-size=50
catalog.base-url=http://localhost:8091
catalog.timeout-ms=2000
catalog.max-calls-per-second=20
orderdesk.mailbox=order-desk@trailhead.example
```

### Flow: order-desk-notification

| Field | Value |
|---|---|
| Target Module | `prototype` |

**Purpose:** Deliver every pending Order Desk Notification once, retrying until it succeeds and across restarts
(UC-002 BR-004, OQ-045).

**Source:**
- Component: `sql` consumer (MCP-verified):
  `sql:file:{{sql.dir}}/notification-select.sql?dataSource=#orderDb&delay={{notification.poll-delay-ms}}&onConsume=…&onConsumeFailed=…`.
  The select joins the receipt and aggregates the reasons with `string_agg(… ORDER BY position)`. It selects
  `sent_at IS NULL`, type `Order Desk Notification`.
- **Rationale:** the `NOTIFICATION` row is the outbox, written in the same statement as the rejection. Polling it gives
  retry-until-sent and survives restarts with no extra queue. `onConsume` sets `sent_at=now(), attempts=attempts+1`;
  `onConsumeFailed` sets `attempts=attempts+1`.

**Transformation:** EIP `setBody` (simple) renders plain text in English: `To: <mailbox>`,
`Subject: Peakmart order <n> rejected`, a blank line, then `Order number: <n>` and one line per reason
(`- [Order Line i] <text>`). Only Order Number and reasons are included (C-023, the
`order-desk-notification.schema.json` content).

**Sink:**
- Component: `file` (MCP-verified, `org.apache.camel:camel-file:4.22.2`):
  `file:{{orderdesk.outbox-dir}}?fileName=peakmart-order-${exchangeProperty.order-number}-rejected.txt&fileExist=Override`.
- **Rationale:** this stands in for SMTP, because there is no mail server and starting containers is not allowed. A file
  per notification makes the test assertion trivial. `Override` keeps a crash-after-write/before-`onConsume` retry
  idempotent. The Spring Boot step replaces only this `to` with `smtp`.
- **Constraints:** sent at most once per rejected order (NOTIFICATION.order_receipt_id is UNIQUE).

**Error Handling:** a file write failure → `onConsumeFailed` increments `attempts`, and the next poll retries. No alert
(BR-004).

**Configuration Properties:**
```properties
notification.poll-delay-ms=5000
orderdesk.outbox-dir=target/outbox/order-desk
```

### Supporting step: order-db-schema-init (one-shot)

- `prototype/db/order-db-schema-init.camel.yaml`, route `order-db-schema-init`:
  `from timer:order-db-schema-init?repeatCount=1` (MCP-verified `timer`, `org.apache.camel:camel-timer:4.22.2`) →
  `to sql:file:{{sql.dir}}/schema.sql?dataSource=#orderDb` → log. `run.sh` runs it as
  `camel run db/order-db-schema-init.camel.yaml application.properties --max-messages=1` **before** starting the
  integration. `schema.sql` is one PostgreSQL `DO $$ … $$` block of `CREATE TABLE IF NOT EXISTS …` statements, so it is
  a single statement and safe to repeat. It contains no `#` or `:name` tokens, which camel-sql would treat as parameters.
- Tables (names and columns from `docs/entity_model.md`): `order_receipt`, `order_state_change`, `rejection_reason`,
  `notification`, `customer_order`, `order_line`, `set_aside_order_event`. Constraints:
  - partial unique index `order_receipt(order_number) WHERE channel='Peakmart'` (BR-002);
  - unique `correlation_id`;
  - unique `(order_receipt_id, order_state)`;
  - unique `(order_receipt_id, position)`;
  - unique `notification.order_receipt_id`;
  - unique `customer_order.order_number` and `customer_order.order_receipt_id`;
  - unique `(customer_order_id, line_number)`;
  - unique `set_aside_order_event.event_id`;
  - the CHECKs from the entity model's validation rules.

  `partner_id`, `content_hash` and `checking_since` exist on `order_receipt` for UC-001 later. The `PARTNER`,
  `PICK_LIST`, `ALERT`, `ALERTED_ORDER` and `ORDER_EVENT_RESUBMISSION` tables are not created (Not Doing).
- Rationale: this is deterministic ordering with no race between schema creation and consumers starting. In the Spring
  Boot project it becomes a Flyway migration (V1) with the same content.

### Supporting app: product-catalog-stub (separate `camel run`, port 8091)

| Field | Value |
|---|---|
| Target Module | `prototype/stubs/product-catalog` |

- **Contract-first REST** (`rest` with `openApi`, MCP-verified EIP):
  `specification: ../../../docs/api/external/product-catalog.openapi.yaml`. The spec is used as is, never copied. The
  `getProduct` operation is implemented by `direct:getProduct`, served by `platform-http` (MCP-verified,
  `org.apache.camel:camel-platform-http:4.22.2`) with `camel.server.port=8091` (8081 is in use on this machine,
  design review item 1).
- Why not `missingOperation: mock`: the catalog verification shows mock mode answers **204** for a GET without a mock
  file. The contract requires **404** for an unknown SKU, and 204 would count as a Catalog Outage (BR-013). So the
  operation is implemented explicitly.
- Route `product-catalog-get-product` (direct `getProduct`): a Groovy expression reads
  `{{stub.examples-dir}}` (`../../../docs/messages/examples/product`) and returns the example whose `sku` equals the path
  parameter, with 200 and `application/json`. If no example matches, it returns 404 with no body. The examples
  (`tent-2p`, `stake-al8`, `lamp-hd200`, `tent-1p-inactive`) are served unchanged (assumption A-3).
- Fault modes for the outage scenarios, chosen by `stub.mode` at start:
  - `normal`
  - `invalid`: serves `invalid/active-missing.json` for every SKU, for BR-013.
  - `slow`: delays 3 s before answering, for the 2 s timeout.
  - A stopped stub stands in for "not answering".
- Risk: if the OpenAPI loader cannot resolve the relative external `$ref`/`externalValue` in the spec, the fallback is a
  plain `rest: get /products/{sku}` with the same route. The test acceptance criteria are unchanged.

---

## 4. Cross-Cutting Concerns

### Performance
- NFR-003 asks for 800 orders/hour, roughly 0.22/s, with up to 30 lines each. The catalog limit is 20 calls/s, which is
  enforced by the throttle.
- The poll interval is 5 s, so time from event to recording is about 5–6 s plus catalog time, well inside NFR-001's
  15 min.
- The prototype is not load-tested. That is a Spring Boot concern.

### Security
- Credentials come from properties or environment (`DB_USERNAME`, `DB_PASSWORD` override). Nothing is hardcoded in YAML
  (rule 6).
- NFR-011 says logs must hold no personal data. The design follows these rules:
  - Log lines carry only `orderNumber`, `eventId`, `correlationId`, `kafka-position` and counts.
  - No route logs a body.
  - Error handlers set `logExhaustedMessageBody=false` and `logExhaustedMessageHistory=false`.
  - The `cannot_read_reason` holds only the path and keyword of the first validation error (for example
    `$.buyer.email: format`) or the fixed text `not JSON`. The validator's message is never stored or logged, because
    we assume it echoes values (design review item 4).
- C-021: personal data lives only in `order_receipt.waiting_order` (cleared on outcome), `customer_order` and
  `set_aside_order_event.event_content`, as the entity model intends.
- TLS (NFR-010/C-024) does not apply: the local dev infrastructure is plain-text.

### Monitoring
- Every route has a `routeId` and `description`.
- Every decision point logs at INFO/WARN with the order number and correlation id (NFR-013, rule 5): skip, set-aside,
  duplicate, received, waiting, rejected, recorded, notified.
- No metrics stack.

### Constraints
- YAML DSL routes. Java appears in two classes loaded by `camel run`: `OrderChecks.java` (checks, evaluation, reason
  texts) and `CannotReadReason.java` (the set-aside reason from a validation error). The datasource is declared in properties, not in Java.
- Forage is unavailable: `.camel-kit/config.properties` has no `forage.version`, and Camel 4.22.2 has no entry in the
  shipped Forage mapping. So the datasource is a rung-3 bean, with a reason comment:
  `camel.beans.orderDb=#class:org.postgresql.ds.PGSimpleDataSource` with `url`, `user`, `password` from
  `{{db.*}}`. Implementation must verify the `PGSimpleDataSource` setters against the resolved pgjdbc version.
- Dependencies are resolved by `camel run`. `org.postgresql:postgresql` uses the version managed by the Camel 4.22.2
  build; implementation pins whatever that resolves to and does not guess it. `camel-groovy`, `camel-jsonpath` and
  `camel-json-validator` are auto-detected from the routes.
- Do not start or stop containers. Do not use ports 8080, 8081 or 5432.

```properties
db.url=jdbc:postgresql://localhost:15432/postgres
db.username=test
db.password=test
```

### Acceptance criteria and scenario approach
There are **no Citrus tests in this pipeline** (design review item 5). They come with the test cases TC-002 and TC-003
in the next step of the project. Instead:
- `prototype/scenarios.sh` publishes the example events of scenarios 1–6 to `peakmart.orders.v2` on the **already
  running** Kafka (9092) and then prints what the Order Database (15432) holds for those scenarios. It does not start or
  stop containers and does not use ports 8080, 8081 or 5432. How it publishes and queries (for example one-shot
  `camel` JBang runs, or the client tools inside the running `camel infra` containers) is an implementation choice
  (assumption A-15).
- Scenarios 7–10 need the stub stopped or the integration restarted and are run by hand.
- The execution report records the result of scenarios 1–10 and of the kill-and-restart test of the intake.

| # | Scenario (input example from `docs/messages/examples/`) | Expected |
|---|---|---|
| 1 | `peakmart-order-placed-sample.json` (PM-50117) | `customer_order` PM-50117 with street line `2240 Pearl Street, Apt 4`, 2 lines, prices `229.00`/`12.50`, names/weights from the catalog. History is Received, Recorded. The recorded data equals `order/from-peakmart-order.json` except for receivedAt/correlationId. |
| 2 | then `pm-50117-redelivered.json` (new event id) | no new rows, INFO duplicate log (A3) |
| 3 | `pm-50118-inactive-sku.json` | receipt Rejected, 1 `sku` reason on line 1 with the text of `order-desk-notification/pm-50118.json`, and an outbox file `peakmart-order-PM-50118-rejected.txt` with subject `Peakmart order PM-50118 rejected` and no name/email/address. |
| 4 | `invalid/other-event-type.json` | skipped: no rows in any table |
| 5 | `invalid/pm-50119-buyer-email-not-an-email.json` | one `set_aside_order_event` row with reason `$.buyer.email: format`, no receipt, WARN log without the email |
| 6 | `invalid/price-as-number.json`, `invalid/event-id-missing.json`, and a record that is not JSON (published by `scenarios.sh` as literal text) | all set aside; reasons are `<path>: <keyword>` or `not JSON`, never the validator's message; the missing event id and the non-JSON record are keyed `kafka-<p>-<o>` |
| 7 | Catalog outage: stub stopped, publish a new valid order, then start the stub | the receipt stays Received/waiting with no rejection while the stub is down; it is recorded after the stub starts |
| 8 | Stub `invalid` and `slow` modes | same as 7 (BR-013) |
| 9 | Order with a structural failure (quantity 21, country DE) **and** stub stopped | not rejected while the stub is down (BR-012); after restart it is rejected with reasons in check order |
| 10 | Restart of the integration between scenarios 7 and the stub restart | the waiting order is still processed (NFR-007) |

---

## 5. Constitution Compliance

All flows in this spec are designed to comply with the 8 constitution rules:
- [x] Route Structure: every route has a source and a sink. `direct:` sub-routes are exempt. Pollers end in `sql`/`file`.
- [x] Single Responsibility: one purpose per route (intake, read, set-aside, receive, poll, check, lookup, reject,
  record, notify). `order-check` has 4 top-level steps.
- [x] Separation of Concerns: Ingestion (intake/receive) → Processing (check) → Delivery (record/reject/notify). Rules are
  in the `OrderChecks` bean and SQL is in files.
- [x] Naming Conventions: route IDs are `<domain>-<action>[-qualifier]`. Internal endpoints are `direct:<route-id>`.
  Custom properties are kebab-case (`correlation-id`, `order-number`, `receipt-id`).
- [x] Observability: routeId and description on every route; correlation id on every order log line.
- [x] External Configuration: every host, port, credential, path, interval and limit is a `{{property}}`.
- [x] Component Support: all components, EIPs and languages are MCP-verified (below).
- [x] Infrastructure via Forage: Forage is unavailable (no `forage.version`, and Camel 4.22.2 is not in the shipped
  Forage mapping). The datasource and beans are rung-3 hand-rolled, each with a one-line reason comment.

### Catalog Verification Evidence

Catalog binding: camel_catalog_components(limit=0); runtime=main; platformBom=org.apache.camel:camel-catalog:4.22.2; returned camelVersion=4.22.2; binding=MATCHED

| Artifact identity | Result | Verification provenance |
|---|---|---|
| component:kafka | VERIFIED | camel_catalog_component_doc (incl. headers CamelKafkaManualCommit, CamelKafkaPartition, CamelKafkaOffset; re-checked 2026-11-13 for the review amendments) + camel_catalog_component_maven (org.apache.camel:camel-kafka:4.22.2); requested component:kafka; options groupId, autoOffsetReset, breakOnFirstError, autoCommitEnable, allowManualCommit; catalog binding=MATCHED |
| component:sql | VERIFIED | camel_catalog_component_doc (incl. headers CamelSqlUpdateCount) + camel_catalog_component_maven (org.apache.camel:camel-sql:4.22.2); requested component:sql; options query (file: prefix), dataSource, delay, maxMessagesPerPoll, onConsume, onConsumeFailed; catalog binding=MATCHED |
| component:http | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-http:4.22.2); requested component:http; options connectTimeout, responseTimeout, connectionRequestTimeout, throwExceptionOnFailure; headers CamelHttpResponseCode, CamelHttpPath; catalog binding=MATCHED |
| component:json-validator | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-json-validator:4.22.2); requested component:json-validator; resourceUri file: prefix; catalog binding=MATCHED |
| component:platform-http | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-platform-http:4.22.2); requested component:platform-http; catalog binding=MATCHED |
| component:file | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-file:4.22.2); requested component:file; option fileExist; catalog binding=MATCHED |
| component:bean | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-bean:4.22.2); requested component:bean; catalog binding=MATCHED |
| component:timer | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-timer:4.22.2); requested component:timer; option repeatCount; catalog binding=MATCHED |
| component:direct | VERIFIED | camel_catalog_component_doc + camel_catalog_component_maven (org.apache.camel:camel-direct:4.22.2); requested component:direct; catalog binding=MATCHED |
| eip:choice | VERIFIED | camel_catalog_eip_doc; requested eip:choice; catalog binding=MATCHED |
| eip:split | VERIFIED | camel_catalog_eip_doc; requested eip:split; option aggregationStrategy; catalog binding=MATCHED |
| eip:throttle | VERIFIED | camel_catalog_eip_doc; requested eip:throttle; options mode (TotalRequests), timePeriodMillis; catalog binding=MATCHED |
| eip:doTry | VERIFIED | camel_catalog_eip_doc; requested eip:doTry; catalog binding=MATCHED |
| eip:onException | VERIFIED | camel_catalog_eip_doc; requested eip:onException; options redeliveryPolicy, handled; catalog binding=MATCHED |
| eip:rest | VERIFIED | camel_catalog_eip_doc; requested eip:rest; option openApi; catalog binding=MATCHED |
| eip:log | VERIFIED | camel_catalog_eip_doc; requested eip:log; catalog binding=MATCHED |
| eip:setProperty | VERIFIED | camel_catalog_eip_doc; requested eip:setProperty; catalog binding=MATCHED |
| eip:setHeader | VERIFIED | camel_catalog_eip_doc; requested eip:setHeader; catalog binding=MATCHED |
| eip:setBody | VERIFIED | camel_catalog_eip_doc; requested eip:setBody; catalog binding=MATCHED |
| eip:bean | VERIFIED | camel_catalog_eip_doc; requested eip:bean; catalog binding=MATCHED |
| eip:script | VERIFIED | camel_catalog_eip_doc; requested eip:script; option expression; catalog binding=MATCHED |
| eip:to | VERIFIED | camel_catalog_eip_doc; requested eip:to; catalog binding=MATCHED |
| language:simple | VERIFIED | camel_catalog_language_doc (org.apache.camel:camel-core-languages:4.22.2; function uuid); requested language:simple; catalog binding=MATCHED |
| language:jsonpath | VERIFIED | camel_catalog_language_doc (org.apache.camel:camel-jsonpath:4.22.2; option suppressExceptions); requested language:jsonpath; catalog binding=MATCHED |
| language:groovy | VERIFIED | camel_catalog_language_doc (org.apache.camel:camel-groovy:4.22.2); requested language:groovy; catalog binding=MATCHED |

Also consulted: `camel_openapi_mock_guidance` (mode mock). It showed that a GET without a mock file returns 204, which
is why the stub implements `getProduct` explicitly.

Not catalog artifacts, verified at implementation: `org.postgresql.ds.PGSimpleDataSource` setters,
`org.apache.camel.processor.aggregate.GroupedBodyAggregationStrategy` (camel-core),
`org.apache.camel.component.kafka.consumer.KafkaManualCommit#commit()` (the type the catalog gives for header
`CamelKafkaManualCommit`), and the error accessors of `JsonValidationException` used by `CannotReadReason`. The manual
commit after processing is proven by acceptance scenario 10 and the kill-and-restart test described under the intake
source.

---

## 6. Project Structure

```
trailhead-orders/
├── .camel-kit/
│   ├── config.properties
│   └── pipeline.json
├── docs/                                   (spec — read-only for this pipeline; schemas referenced in place)
│   └── camel-kit/001-peakmart-intake/
│       ├── design-spec.md                  ← this file
│       ├── implementation-plan.md          [created by camel-plan]
│       ├── execution-report.md             [created by camel-execute]
│       └── validation-report.md            [created by camel-validate]
└── prototype/                              [Target Module]
    ├── peakmart-order-intake.camel.yaml    (intake, read, set-aside, receive)
    ├── peakmart-order-check.camel.yaml     (poll, check, product-lookup, reject, record)
    ├── order-desk-notification.camel.yaml
    ├── OrderChecks.java                     (structural checks, evaluate, reason texts)
    ├── CannotReadReason.java                (`<path>: <keyword>` of the first validation error, never the message)
    ├── application.properties
    ├── scenarios.sh                         (publishes scenarios 1–6 to Kafka, prints the database afterwards)
    ├── sql/
    │   ├── schema.sql
    │   ├── receipt-insert.sql
    │   ├── set-aside-insert.sql
    │   ├── waiting-select.sql
    │   ├── order-reject.sql
    │   ├── order-record.sql
    │   └── notification-select.sql
    ├── db/order-db-schema-init.camel.yaml
    ├── run.sh                               (schema init once, then `camel run` the integration)
    └── stubs/product-catalog/
        ├── product-catalog-stub.camel.yaml
        ├── application.properties           (camel.server.port=8091, stub.mode, stub.examples-dir)
        └── run.sh
```

There is no `src/test/resources`: Citrus tests come with TC-002 and TC-003 in the next step (design review item 5).

Schemas are referenced in place under `docs/messages/` (`schemas.dir=../docs/messages`) and not copied, so the spec stays
the single source of truth.

---

## 7. Assumptions Taken (non-interactive run — recommended option chosen)

| ID | Open prototype decision | Taken |
|---|---|---|
| A-1 | Where a waiting Peakmart Order waits (OQ-069, open) | As assumed in the spec: `order_receipt.waiting_order`, written before the offset commit and cleared on the outcome |
| A-2 | Catalog call limit (OQ-065, open) | As assumed: at most 20/s, one shared throttle |
| A-3 | Which products the stub serves | All four `docs/messages/examples/product/*.json`, not only `tent-2p`, which is the only one the OpenAPI file links. Without `STAKE-AL8` the sample order would be rejected |
| A-4 | Order Desk Notification delivery without a mail server | Outbox row + plain-text file per notification; SMTP comes in the Spring Boot step |
| A-5 | Rejection Reason texts other than the inactive-SKU one | The table in §3; illustrative per the message model |
| A-6 | Non-JSON record on the topic | **Changed by design review item 3:** set aside under `kafka-<partition>-<offset>` with reason `not JSON`, because it may be a broken `order.placed`; the Integration Team decides what it was |
| A-7 | `order.placed` event without an `event_id` (example `event-id-missing.json`) | Set aside under the key `kafka-<partition>-<offset>` |
| A-8 | Test infrastructure | **Changed by design review item 5:** no Citrus tests in this pipeline (they come with TC-002/TC-003); `prototype/scenarios.sh` runs scenarios 1–6 against the running Kafka/PostgreSQL, scenarios 7–10 are run by hand, and the execution report records all ten |
| A-9 | `waiting_for='Recording'` | Not used in the prototype; a database failure leaves `Check`, so the next poll re-checks and records |
| A-10 | UC-006 alerts | A WARN log line stands in; no email, no `ALERT` table |
| A-11 | Consumer group id (OQ-038, open) | `trailhead-order-integration`, `autoOffsetReset=earliest` |
| A-12 | Schema creation | One-shot `order-db-schema-init` run by `run.sh` before the integration starts; Flyway later |
| A-13 | Location of the prototype | `prototype/` module, so the repository root stays free for the Spring Boot project |
| A-14 | Check logic location | A Java bean `OrderChecks` (reusable by UC-001 and Spring Boot); the Peakmart→Order mapping uses inline Groovy per the DataMapper engine rule (18 leaf fields < 20) |
| A-15 | How `scenarios.sh` publishes to Kafka and reads the database | Implementation choice, limited to tools that need no new container and no port 8080/8081/5432 (added in the review amendment; the review does not say) |
| A-16 | Which validation error is "first" for the set-aside reason | The first error in the order the validator returns them (added in the review amendment) |

### Design review decisions applied (2026-11-13, `design-review.md`)

| Item | Decision | Where applied |
|---|---|---|
| 1 | Product Catalog stub on port 8091 | §2, `catalog.base-url`, stub section, §4 Constraints, §6 |
| 2 | `autoCommitEnable=false`, `allowManualCommit=true`, commit with `KafkaManualCommit` as the last step of `peakmart-order-intake`; keep `breakOnFirstError=true` and the kill-and-restart test | Overall shape, intake source, route 1, intake error handling, §5 evidence |
| 3 | Non-JSON records set aside under `kafka-<partition>-<offset>` | Overall shape, routes 1 and 3, scenario 6, A-6 |
| 4 | Set-aside reason is only `<path>: <keyword>` of the first validation error, never the message | Routes 2 and 3, §4 Security, scenario 5, `CannotReadReason.java`, A-16 |
| 5 | No Citrus tests; `prototype/scenarios.sh` for scenarios 1–6, scenarios 1–10 in the execution report; no `src/test/resources` | §4 Acceptance criteria, §6, A-8, A-15 |
| 6 | PostgreSQL from `camel infra` is 17.10 | §2 |
