---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T20:06:49.458622Z"
  by: "camel-brainstorm"
---
# Trailhead Order Integration — Partner Orders and Pick Lists — Design Spec

**Date:** 2026-11-13
**Camel Version:** 4.22.2
**Runtime:** Spring Boot 4.1.1 (Maven layout, YAML DSL routes under `src/main/resources/camel/`), JDK 25
**Platform BOM Version:** 4.22.2 (`org.apache.camel.springboot:camel-spring-boot-bom:4.22.2`; catalog
`org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2`)
**Status:** Approved 2026-11-13 by the integration architect, with the five changes of
`docs/camel-kit/003-partner-orders-pick-lists/design-review.md` (R1–R5), which are written into this spec (see §8). The
open decisions of the first draft took the recommended option and are listed in §7.

**Starting point:** the Spring Boot project of pipeline 002 (`docs/camel-kit/002-spring-boot-move/`), accepted with
items 3 and 7 of `validation-decisions.md` carried here. Everything in 002 that this spec does not mention stays as it
is, including its review decisions R1–R4 and assumptions B-1 to B-12.

**Sources:** `docs/requirements.md`, UC-001, UC-003, UC-004, UC-007, `docs/api/partner-orders.openapi.yaml`,
`docs/api/external/partner-mail.md`, `docs/api/external/warehouse-sftp.md`, `docs/messages/*.schema.json` and
`docs/messages/mappings/*`, `docs/entity_model.md`, `docs/open_questions.md` (OQ-028–OQ-030, OQ-036, OQ-037, OQ-040–OQ-047,
OQ-059, OQ-060, OQ-074, OQ-075), TC-001, TC-005, the current code under `src/`, and the design review
`docs/camel-kit/003-partner-orders-pick-lists/design-review.md`.

## Not Doing (and Why)

- **UC-005 order lookup, UC-006 alerts, UC-008 resubmission:** the request lists them as "not yet". The tables this
  pipeline adds keep everything the lookup needs: Order History, Rejection Reasons and the Pick List state. Stuck pick
  lists and failed partner configurations are logged at WARN/ERROR in place of the UC-006 alert, as 002 does for its
  other alerts.
- **Citrus tests:** the test cases come next. This pipeline proves behaviour with scripted and manual scenarios (§4),
  in the same way as pipelines 001 and 002. TC-001 and TC-005 are run by hand except for the lookup steps (UC-005).
- **A real Secret Store client (Vault, AWS, Kubernetes API):** none runs in development, and the product is not named.
  The stand-in (C-1) is a directory of one file per API Key. That is the shape Vault Agent, the Secrets Store CSI
  driver and Kubernetes/Docker secrets all produce, so production can mount the real store there without a code change.
- **Reloading the Partner Configuration while the integration runs:** a release is a deployment, so the configuration
  and the API Keys are read at startup (C-3). Rotating a key also needs a restart.
- **A production Partner Configuration file:** no real partner has signed yet ("the two partners signing in Q1"). The
  repository gets the development configuration only. The production file is added through the UC-007 process when the
  first partner signs.
- **OQ-075 (an order that passes every check but does not fit the Order Database):** it is still an open business
  question. A Partner Order that fails only at recording is answered `temporarily-unavailable` (503), which matches how
  002 leaves the Peakmart order waiting (C-24).
- **90-day retention purge (C-022):** as in 002. `pick_list` and `partner` need no change for it later; their rows hang
  off `customer_order` and stay as long as the order does.
- **Several instances at once:** as in 002, the application runs as one instance. The pick-list poller takes no row
  lock. `checking_since` and the conditional state updates still keep two concurrent partner requests safe (§3).
- **Actuator over HTTP:** the web server this pipeline adds serves only the partner endpoint, and actuator HTTP is
  switched off (`management.server.port=-1`, C-22).
- **TLS to Kafka and HTTPS to the Product Catalog:** unchanged, as accepted in 002 (decisions 5 and 6).
- **Priority for partner lookups over Peakmart re-checks:** both channels share one catalog throttle of 20 calls/s
  (UC-003 BR-014). The risk to NFR-002 exists only while a Peakmart backlog is being re-checked after a Catalog Outage,
  and it is recorded in §4 Performance.

---

## 1. Executive Summary

### Business Purpose
Partners send their orders over HTTP and learn at once whether Trailhead Gear took them (UC-001). They are checked
exactly like Peakmart orders (UC-003). Partners are registered through the reviewed Partner Configuration and the Secret
Store, without a code change (UC-007). Every Accepted Order of either channel reaches the Warehouse SFTP Server as one
pick list, exactly once (UC-004). The two items carried from 002 are also done: the startup check names every missing
variable, and the development settings are no longer packaged in the jar.

### Business Value
- Partners stop emailing orders that someone retypes (FR-001). They get every rejection reason at once, in the answer
  and in an email (FR-005, FR-006).
- The warehouse receives every accepted order without manual handling, never twice, and also after a restart, a resend
  or SFTP downtime (FR-025–FR-028, NFR-006, NFR-007).
- Onboarding a partner takes one reviewed configuration change (NFR-012).
- Operations sees every missing setting in one startup failure.

### Stakeholders
Partners (send orders, receive answers and rejection emails), Partner Manager (assigns order number ranges), Warehouse
Team (receives pick lists), Order Desk (Peakmart notifications, unchanged), Integration Team (registers partners,
operates the application).

---

## 2. Systems Landscape

| System | Type | Protocol | Role | Development (`dev` profile, `config/application-dev.properties`) | Without a profile |
|---|---|---|---|---|---|
| Partner systems | HTTP clients | HTTPS (TLS ≥ 1.2) | Source (UC-001) | plain HTTP on `localhost:8082` (C-14) | `PARTNER_HTTP_PORT`, `PARTNER_TLS_CERTIFICATE`, `PARTNER_TLS_PRIVATE_KEY` |
| Partner Configuration | JSON file, released from the repository | file | Source (UC-007), read at startup | `config/dev/partner-configuration.json` | `PARTNER_CONFIGURATION` (file path) |
| Secret Store | one file per API Key, named by `apiKeyName` | file | Source of API Keys (UC-007) | `config/dev/secrets/` (C-1) | `SECRET_STORE_DIR` |
| Peakmart order topic | Kafka | Kafka | Source (unchanged) | `localhost:9092` | `KAFKA_BROKERS` |
| Order Database | PostgreSQL | JDBC | Target and outboxes | `localhost:15432`, `postgres`, `test`/`test` (V1 + test orders) | `DB_*` (unchanged) |
| Product Catalog | HTTP JSON | HTTP | Lookup (both channels) | stub `127.0.0.1:8091` | `CATALOG_HOST`, `CATALOG_PORT` |
| Mail server | SMTP | SMTP | Target: Order Desk Notification (unchanged), Rejection Email (new) | Mailpit `localhost:1025`, API `http://localhost:8025/api/v1` | `SMTP_*` (unchanged), `PARTNER_MAIL_SENDER` |
| Warehouse SFTP Server | SFTP | SFTP (SSH) | Target (UC-004) | `camel infra run sftp`, `localhost:2222`, `admin`/`admin`, host key from `camel infra get sftp` | `WAREHOUSE_SFTP_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_KNOWN_HOSTS` |

No container or `camel infra` service is started or stopped. Ports 8080, 8081 and 5432 are not used. Port 8082 is the
`dev` value of `server.port`. The review confirmed that 8082 is free on the development machine, so it is used as is
(R5, C-14).

---

## 3. Flow Designs

### Overview of route files

| File | Routes | Change |
|---|---|---|
| `camel/partner-order-api.camel.yaml` | `rest` (contract-first), `partner-order-submit`, `partner-order-receive`, `partner-order-check`, `partner-order-answer` | new |
| `camel/partner-rejection-email.camel.yaml` | `partner-rejection-email-send` | new |
| `camel/pick-list-delivery.camel.yaml` | `pick-list-delivery` | new |
| `camel/peakmart-order-check.camel.yaml` | `order-check`, `product-lookup` become channel-neutral; the outcome `choice` moves to `peakmart-order-check-poll` | changed |
| `camel/peakmart-order-intake.camel.yaml`, `order-desk-notification.camel.yaml` | — | unchanged |

### Flow: shared order checks (UC-003; changed)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Purpose:** one implementation of the Order Checks for both channels (UC-003 BR-001). The route returns the outcome to
its caller, and the caller decides what happens next.

**Changes to `order-check` (direct):**
1. The `choice-check-outcome` node (with its `when-catalog-outage`, `when-rejected`, `when-passed` branches and node ids)
   moves unchanged to the end of `peakmart-order-check-poll`. `order-check` ends with `bean-evaluate`, and its body is
   the evaluation result (`outcome`, `reasons`, `order`, `reasonCount`, `lineCount`). Peakmart behaviour does not
   change.
2. `set-structural-reasons` calls `orderChecks.structuralReasons(${exchangeProperty.order}, ${exchangeProperty.order-number-reasons})`.
   The second argument is the list of Partner-only reasons (range, already used) prepared by the partner route. It is
   absent (null) for Peakmart (UC-003 A1). The bean puts these reasons right after the format reason, which keeps the
   reasons in check order (UC-003 steps 1–6, BR-010).
3. `split-order-lines` gets `parallelProcessing: true` and `executorService: "#catalogLookupPool"`, a pool of
   `catalog.lookup-threads=10` threads declared in `IntegrationBeans` (C-13). All lookups still pass through the one
   `throttle-product-catalog` node, so BR-014 (≤ 20 calls/s, both channels together) holds. Parallel lookups keep a
   30-line partner order within NFR-002's 10 s even when the catalog answers slowly: three waves of ≤ 2 s instead of
   30 sequential calls. `GroupedBodyAggregationStrategy` stays. `evaluate` already finds the answers by `lineNumber`,
   so arrival order does not matter.

**Changes to `OrderChecks` (Java, both channels):**
- **Order number format** by `channel` of the canonical order: `partner` → `^TG-\d{4,}$`, `peakmart` → `^PM-\d+$`
  (unchanged text for Peakmart). Partner text: `The Order Number <n> is not TG- followed by four or more digits. Use an
  order number from your Order Number Range.`
- New method `partnerNumberReasons(order, partnerRangeFirst, partnerRangeLast, acceptedContentHash, contentHash)`.
  The partner route calls it and returns, in this order:
  - `order-number-range`, when the order number is `TG-` followed by digits and the number is outside
    `[first, last]`, both included. The number is compared as `BigInteger`, so a long digit string cannot overflow. An
    order number with no digit string after `TG-` gets only the format reason (C-11). Text: `The Order Number <n> is
    outside your Order Number Range <first> to <last>. Use a number from your range.`
  - `order-number-already-used`, when a `customer_order` with this order number exists and its receipt's
    `content_hash` differs from this request's (UC-003 BR-004). Text: `The Order Number <n> is already used by an
    accepted order with different content. Use a new order number.`
- **Catalog price:** in `evaluate`, when the outcome is PASSED and `channel` is `partner`, every line's `unitPrice`
  becomes `{"amount": listPrice with two decimals (BigDecimal, HALF_UP), "currency": "USD"}` (UC-001 BR-006,
  FR-019). Peakmart lines keep their price (FR-020). `product.schema.json` already requires `listPrice`, so a product
  without one counts as not answering (BR-013).
- All other reason texts stay as they are in 002. One text per check is shared by both channels (C-12).

**Endpoint and EIP changes:** none beyond the above. `product-lookup` is unchanged.

### Flow: partner-order-api (UC-001; new)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Purpose:** take one Partner Order per HTTP request, authenticate it, check it with the shared Order Checks, record or
reject it, and answer synchronously with the Order Answer (UC-001, FR-001–FR-006, FR-017, FR-019, FR-021, FR-022, FR-024,
FR-030, FR-040).

**Source:**
- System: partner systems.
- Component: Rest DSL `rest` with `openApi` (EIP `rest`, `openApi`, MCP-verified) on `platform-http` (MCP-verified,
  `org.apache.camel.springboot:camel-platform-http-starter:4.22.2`). `camel-rest-openapi-starter:4.22.2`
  (`rest-openapi`, MCP-verified) supplies the contract-first loading.
  ```yaml
  - rest:
      id: partner-orders-rest
      openApi:
        specification: classpath:contract/api/partner-orders.openapi.yaml
        missingOperation: fail
  ```
  Camel creates one route per operation and sends `submitPartnerOrder` to `direct:submitPartnerOrder`. With
  `missingOperation: fail`, the application does not start if the contract gains an operation that has no route.
- **Rationale:** the request asks for the contract first, and `openApi` makes `docs/api/partner-orders.openapi.yaml`
  the single source of the path and method. A hand-written `rest: post: /orders` could drift from the contract
  unnoticed. `platform-http` runs on Spring Boot's own embedded server, so there is one HTTP stack and Spring Boot's
  `server.*` settings govern the port and TLS.
- **Constraints:**
  - `clientRequestValidation` stays off (default). The contract demands RFC 9457 bodies that carry the Order Answer, and
    a reason text that names what cannot be read (UC-001 A3). The generic validator's 400 would not follow that, and it
    would run before authentication, which UC-001 BR-001 forbids. The route validates with `json-validator` after the
    API Key check instead.
  - The contract has external `$ref`s (`../messages/*.schema.json`). At build time `maven-resources-plugin` copies
    `docs/api/partner-orders.openapi.yaml` to `target/classes/contract/api/` and `docs/messages/{partner-order,
    order-answer, rejection-reason}.schema.json` to `target/classes/contract/messages/`. The relative layout is kept,
    and `docs/` stays the single source (the same rule as 002 E6). Implementation proves at startup that the spec loads
    and that `POST /orders` is served. If the parser cannot resolve the classpath `$ref`s, it records the error and
    stops: the fallback (a bundled single-file copy) needs a design amendment, because it would duplicate the contract.
  - Spring Boot needs a servlet web server for `platform-http`. Implementation takes the exact starter from the Boot
    4.1.1 BOM (`spring-boot-starter-web` or what `camel-platform-http-starter` already brings) and records it.
- Trigger: `POST /orders`, `Content-Type: application/json`, header `X-API-Key`.

**Routes:**

1. **`partner-order-submit`** (`from: direct:submitPartnerOrder`, `routeConfigurationId: partner-order-error-handling`)
   1. `setProperty` `correlation-id` = `${uuid}`, `received-at` = UTC now (ISO-8601). Every log line after this point
      carries `correlationId` (NFR-013).
   2. `setProperty` `partner` = `method(partnerRegistry, authenticate(${header.X-API-Key}))`. The bean hashes the given
      key with SHA-256 and compares it in constant time (`MessageDigest.isEqual`) with the registered keys' hashes. It
      returns the partner (`id`, `partnerId`, `rangeFirst`, `rangeLast`, `orderContact`) or null. The key is never
      logged, stored or echoed (UC-007 BR-003).
   3. `removeHeaders` pattern `*`. **Security-critical:** HTTP request headers arrive as Camel headers, and the `sql`
      catalog lists `CamelSqlQuery` as overriding the endpoint query. A partner header must never reach the `sql`,
      `http` or `smtp` steps. This also drops `X-API-Key`. The strip stays whatever `platform-http` does (R4). The
      execution report says whether Camel 4.22.2's `platform-http` already filters inbound `Camel*` headers: if it does,
      this step is a second guard; if it does not, the report says so plainly and this step is the only guard.
   4. `choice`:
      - partner is null → log WARN `Partner request refused: API key missing or unknown correlationId=…` → answer 401
        (UC-001 A1, BR-001, BR-012). No Order Check, no history, no email.
      - otherwise continue.
   5. **Readable** (UC-001 A3, BR-010). `doTry`, with any failure caught and turned into 400:
      - `convertBodyTo` `java.lang.String` (UTF-8).
      - `filter` on body length > `{{partner.max-request-chars}}` (1048576) → reason `the request is larger than … characters`.
      - `to json-validator:classpath:contract/messages/partner-order.schema.json`. `doCatch`
        `JsonValidationException` → reason from `partnerOrders.cannotReadReason(exception)`. Like 002's
        `CannotReadReason`, it names the JSON path and the keyword (for example `The request cannot be read as a Partner
        Order: the field orderId is missing.`, `… the field giftWrap is not part of the Partner Order.`) and never
        echoes values. A body that is not JSON → `… the request is not JSON.`
      - `partnerOrders.readableReason(body)` adds the guards that the JSON Schema cannot enforce, because `format`
        is not asserted (C-10): `orderedAt` is an ISO-8601 date-time with offset; `customerEmail`, when present, matches
        `{{peakmart.email-pattern}}` (the same address rule as Peakmart and the DB CHECK); `orderId` is at most 30
        characters and has no control characters (the column is `varchar(30)`, and the order number later goes into a
        mail subject and a file name).
      - On failure: log INFO `Partner request cannot be read partnerId=<of the key> orderNumber=<when readable>
        correlationId=…` → answer 400 with `result: rejected`, `orderNumber` when readable, and one reason without
        `orderCheck`. No history, no email (BR-005, BR-012).
   6. `choice` on `${body}` `partnerId` (jsonpath) ≠ `${exchangeProperty.partner.partnerId}` → log WARN `Partner request
      refused: order names another partner keyPartnerId=… orderNumber=… correlationId=…` → answer 403 (UC-001 A2,
      BR-002).
   7. **DataMapper `kaoto-datamapper-7d3b2e91`** (Groovy, below): the canonical Order. Then `setProperty`
      `content-hash` = `partnerOrders.contentHash(original request body)`: SHA-256 of the canonical JSON value (keys
      sorted, no whitespace, numbers by value), so a resend that only reformats the JSON is the same content (C-6).
      `order-number` is set from the order.
   8. `to direct:partner-order-receive`.

2. **`partner-order-receive`** (direct): UC-001 step 4, A4, A8.
   - `to sql:classpath:sql/partner-receipt-claim.sql` (one statement, `outputType: SelectOne`). It returns `outcome`,
     `receipt_id`, `order_state`:
     - **IN_PROGRESS:** a receipt of this partner and order number, with any content, has `checking_since` newer than
       `{{partner.checking-expiry-seconds}}` (30 s; OQ-074 answered with reading (b), R2, C-7). → 503 `temporarily-unavailable`, without checking (A8,
       BR-007, BR-008).
     - **DUPLICATE:** a receipt with the same `(partner_id, order_number, content_hash)` is `Rejected`, `Recorded` or
       `Sent to Warehouse`. → answered as the first time (A4): `Recorded`/`Sent to Warehouse` → 200 `accepted` (this
       also covers "recorded but never answered"); `Rejected` → `sql:classpath:sql/rejection-reasons-select.sql`
       (reasons by `position`, order check mapped back to the contract enum) → 422 with the same reasons. Nothing is
       written and no email is sent.
     - **CLAIMED:** that receipt is `Received` with no live `checking_since` (it was answered temporarily unavailable,
       or the integration stopped during the check). `checking_since` is set to now, and the order is checked on the
       same receipt (BR-009, OQ-044).
     - **NEW:** no such receipt. A receipt `Received` with `channel=Partner`, `partner_id`, `content_hash`,
       `correlation_id`, `received_at` and `checking_since=now()` is inserted, together with the history entry
       `Received` (step 4).
   - Concurrency: the insert is `ON CONFLICT (partner_id, order_number, content_hash) WHERE channel = 'Partner' DO
     NOTHING`. The claim is an `UPDATE … WHERE checking_since IS NULL OR checking_since < now() - expiry`. When two
     identical requests race, exactly one gets NEW or CLAIMED, and the other gets IN_PROGRESS (C-8). Implementation
     proves this with two parallel `curl`s (§4 P-9).
   - NEW or CLAIMED → `to direct:partner-order-check`.

3. **`partner-order-check`** (direct): UC-001 steps 5–8, A5–A7.
   1. `to sql:classpath:sql/accepted-content-select.sql` → property `accepted-content-hash` (the `content_hash` of the
      receipt of a `customer_order` with this order number, or null).
   2. `setProperty` `order-number-reasons` = `orderChecks.partnerNumberReasons(...)`.
   3. `to direct:order-check` (shared).
   4. `choice` on `${body[outcome]}`:
      - **CATALOG_OUTAGE** → `sql:classpath:sql/partner-receipt-release.sql` (`checking_since = NULL`, the receipt
        stays `Received`) → log WARN `Partner order temporarily unavailable: Product Catalog not answering orderNumber=…
        correlationId=…` → 503 (A6, BR-009). No reason, no email.
      - **REJECTED** → `setProperty` `notification-type` = `Rejection Email`, `recipient` = the partner's
        `orderContact` → `sql:classpath:sql/order-reject.sql` (shared, below) → log INFO `Partner order rejected
        orderNumber=… reasons=… correlationId=…` → 422 with every reason in check order (A5, BR-004). The email goes
        out through the outbox (Flow partner-rejection-email).
      - **PASSED** → `to json-validator:classpath:schemas/order.schema.json` (guard, as for Peakmart) →
        `sql:classpath:sql/order-record.sql` (shared, below) → log INFO `Partner order recorded orderNumber=… lines=…
        correlationId=…` → 200 `accepted` (steps 6–8, BR-003). The pick list is queued in the same statement.
   5. If `order-reject.sql` or `order-record.sql` updates 0 rows, a concurrent request has already moved the receipt
      past `Received` (possible only after the 30 s expiry). The request then answers 503 and writes nothing. The
      partner's resend gets the first answer (C-8).

4. **`partner-order-answer`** (direct, used by every branch): builds the response.
   - `removeHeaders` `*` (nothing from the exchange may become a response header), then `setHeader`
     `CamelHttpResponseCode` and `Content-Type`.
   - Body from `orderAnswers.body(status, orderNumber, reasonsJson)`, a Java bean that writes JSON with Jackson, so no
     value is concatenated into JSON by hand:
     - 200 `application/json`: `{"orderNumber","result":"accepted"}` (order-answer.schema.json).
     - 400/422/503 `application/problem+json`: `type: about:blank`, `title` (`Bad Request`, `Unprocessable Content`,
       `Service Unavailable`), `status`, `detail` (fixed text without personal data), plus `orderNumber` (when
       known), `result`, and `rejectionReasons` (only for `rejected`) — the contract's `OrderProblem`.
     - 401/403 `application/problem+json`: plain Problem (`Unauthorized` / `Forbidden`), with no Order Answer members.
   - No `Retry-After` header (contract).

**routeConfiguration `partner-order-error-handling`** (EIP `routeConfiguration`, `onException`, MCP-verified):
- `onException` `java.lang.Exception`, `handled: true`, `maximumRedeliveries: 0`, `logExhaustedMessageBody: false`,
  `logExhaustedMessageHistory: false`.
- It logs WARN `Partner order temporarily unavailable after a failure orderNumber=… correlationId=… cause=<exception
  class>`. Only the class is logged, never the message, because a SQL message may quote values.
- It releases `checking_since` on a best-effort basis: `partner-receipt-release.sql` inside a `doTry` whose failure is
  ignored, because when the database is down the release fails too, and the 30 s expiry covers that case.
- Then it answers 503 `temporarily-unavailable` (A7, BR-009). No redelivery inside a synchronous request: the partner
  resends, and NFR-002 has a 10 s budget.

**Error Handling summary:** a database failure at any step → 503, and the receipt stays `Received` (A7). A catalog
outage → 503. Unexpected exception → 503. The contract has no 500. No dead letter: the partner holds the order and
resends it.

**Configuration properties** (`application.properties`, shared):
```properties
partner.max-request-chars=1048576
partner.checking-expiry-seconds=30
catalog.lookup-threads=10
management.server.port=-1
server.ssl.enabled-protocols=TLSv1.3,TLSv1.2
```
Without a profile (`application-default.properties`):
```properties
server.port=${PARTNER_HTTP_PORT}
server.ssl.enabled=true
server.ssl.bundle=partner-endpoint
spring.ssl.bundle.pem.partner-endpoint.keystore.certificate=${PARTNER_TLS_CERTIFICATE}
spring.ssl.bundle.pem.partner-endpoint.keystore.private-key=${PARTNER_TLS_PRIVATE_KEY}
```
`dev` (`config/application-dev.properties`): `server.port=8082`, `server.ssl.enabled=false`.

### DataMapper: kaoto-datamapper-7d3b2e91

**Mapping ID:** `kaoto-datamapper-7d3b2e91`
**Transformation Engine:** Groovy (inline)
**Source:** JSON_SCHEMA — `docs/messages/partner-order.schema.json`
**Target:** JSON_SCHEMA — `docs/messages/order.schema.json` (product fields and `unitPrice` are completed later by
`OrderChecks.evaluate`; they are absent after this mapping)
**Format Pair:** JSON → JSON

This table transcribes `docs/messages/mappings/partner-order-to-order.md` 1:1. Where the two differ, that file wins.
13 leaf fields, so the engine is Groovy (< 20), like the Peakmart mapping `kaoto-datamapper-5c1e9a27`.

#### Source Parameters

| Parameter | Type | Schema Path |
|-----------|------|-------------|
| correlation-id | Primitive | none |
| received-at (UTC now, ISO-8601) | Primitive | none |

#### Field Mappings

| Source Field | Src Type | Target Field | Tgt Type | Transformation | How |
|---|---|---|---|---|---|
| orderId | string | orderNumber | string | Direct copy | Auto |
| — | — | channel | string | Constant `partner` | Manual |
| partnerId | string | partnerId | string | Direct copy (already matched to the API Key) | Auto |
| orderedAt | string | orderedAt | string | Direct copy, keep offset | Auto |
| (param) received-at | string | receivedAt | string | Parameter | Manual |
| (param) correlation-id | string | correlationId | string | Parameter | Manual |
| customerEmail | string | customerEmail | string | Direct copy when present, omitted otherwise | Auto |
| shipTo.name | string | shippingAddress.recipientName | string | Direct copy; omitted when absent (a missing part is a Rejection Reason) | Auto |
| shipTo.street | string | shippingAddress.streetLine | string | Direct copy; omitted when absent | Auto |
| shipTo.city | string | shippingAddress.city | string | Direct copy; omitted when absent | Auto |
| shipTo.postalCode | string | shippingAddress.postalCode | string | Direct copy; omitted when absent | Auto |
| shipTo.country | string | shippingAddress.country | string | Direct copy; omitted when absent | Auto |
| lines[] | array | lines[] | array | for-each, in the order sent | Auto |
| (position in lines, from 1) | integer | lines[].lineNumber | integer | Calculated | Manual |
| lines[].sku | string | lines[].sku | string | Direct copy | Auto |
| lines[].quantity | integer | lines[].quantity | integer | Direct copy | Auto |
| lines[].unitPrice | number | — | — | Dropped (Catalog Price applies, UC-001 BR-006) | Manual |

`shippingAddress.stateRegion` is not set (the Partner Order has no state). The Groovy script never logs a value.

#### Collection Mappings

| Source Collection | Target Collection | Iteration |
|---|---|---|
| lines[] | lines[] | for-each |

### Flow: shared reject and record statements (changed for both channels)

- **`order-reject.sql`:** the notification type and the recipient become parameters
  (`:#${exchangeProperty.notification-type}`, `:#${exchangeProperty.recipient}`). Peakmart's `order-reject` route sets
  `Order Desk Notification` and `{{orderdesk.mailbox}}`, so it behaves as before. The `UPDATE` also sets
  `checking_since = NULL`. Everything else (the CTE, the reasons with `WITH ORDINALITY`, the guard "only from Received")
  is unchanged.
- **`order-record.sql`:** the `UPDATE` also sets `checking_since = NULL`. A new CTE inserts the outbox row
  `pick_list(customer_order_id, file_name='PL-'||orderNumber||'.csv', total_weight_g = Σ quantity × unitWeightGrams
  from the doc, delivery_state='Pending', created_at=now(), attempts=0)` in the same statement. So every Accepted Order
  of either channel gets exactly one pick list, atomically with its recording (UC-004 BR-001, `pick_list.customer_order_id
  UNIQUE`).

### Flow: partner-rejection-email (FR-006, UC-001 BR-005; new)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Purpose:** send each pending Rejection Email to the partner's Order Contact, retrying until the mail server takes it,
also after a restart. It is a copy of the 002 `order-desk-notification-send` pattern, kept as its own route because the
subject, the sender and the selection differ (single responsibility).

**Source:** `sql` consumer (MCP-verified) `sql:classpath:sql/rejection-email-select.sql`. This is the 002 select with
`notification_type = 'Rejection Email'`: one `- <text>` line per reason, in `position` order. Options:
`dataSource: "#dataSource"`, `delay: "{{notification.poll-delay-ms}}"` (5 s, NFR-014), and `onConsume` /
`onConsumeFailed` exactly as 002 with the D4 cap `LEAST(attempts + 1, 1000)` (`RAW(...)`).

**Steps:** `setProperty` order number and correlation id → `removeHeaders "*"` → `setHeader To` = `recipient_email` →
`setHeader Subject` = `Order ${exchangeProperty.order-number} rejected` → `setBody`:
```text
Order number: ${exchangeProperty.order-number}
${body[reason_lines]}
```
→ `to smtp://{{smtp.host}}:{{smtp.port}}` (MCP-verified) with `from: "{{partner.mail-sender}}"`, `useHeaderRecipients`,
`useHeaderSubject`, `contentType: "text/plain; charset=UTF-8"`, and the 002 timeout and STARTTLS options →
log INFO `Rejection email sent orderNumber=… correlationId=…`. The partner's Order Contact is never logged.

**Error handling:** `routeConfiguration` `partner-rejection-email-error-handling`, the same as 002's (not handled,
0 redeliveries, no body or history in the log). Delivery is at least once, as 002 B-6.

**Sink rationale:** `smtp` with the existing mail settings. The Order Contact comes from the Partner Configuration at
rejection time, never from the order (`partner-mail.md`), and it is stored in `notification.recipient_email`. A
Duplicate never creates a second row (`notification.order_receipt_id UNIQUE`, and the reject statement runs only from
`Received`).

**Configuration:** shared `notification.poll-delay-ms` (existing). `partner.mail-sender`: without a profile
`${PARTNER_MAIL_SENDER}`, dev `orders@trailhead.example` (C-15).

### Flow: pick-list-delivery (UC-004; new)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Purpose:** deliver each pending pick list, for an Accepted Order of either channel, to the Warehouse SFTP Server
exactly once, and then record `Sent to Warehouse` (FR-025–FR-028, NFR-001, NFR-006, NFR-007).

**Source:** `sql` consumer (MCP-verified) `sql:classpath:sql/pick-list-select.sql`, which selects pending pick lists
oldest first (`id`, `file_name`, `customer_order_id`, `order_receipt_id`, `order_number`, `correlation_id`), with:
- `delay: "{{pick-list.poll-delay-ms}}"` (30000 ms, well inside NFR-001's 15 min) and
  `maxMessagesPerPoll: "{{pick-list.batch-size}}"` (50);
- `onConsumeFailed: "RAW(UPDATE pick_list SET attempts = LEAST(attempts + 1, 100000), last_attempt_at = now() WHERE id = :#id)"`.

Success is recorded by an explicit step, not by `onConsume` (see step 4).

**Steps:**
1. `setProperty` `order-number`, `correlation-id`, `file-name`, `pick-list-id`.
2. **Already delivered?** `setProperty` `delivered-folder` = `method(warehouseSftp, deliveredFolder(${exchangeProperty.file-name}))`.
   - The bean (Java, JSch from `camel-ftp`, C-18) runs SFTP `stat` on `<inbound>/PL-x.csv` **first** and then on
     `<processed>/PL-x.csv`. It returns the folder where the file is, or null.
   - The order of the two checks matters. The warehouse *moves* a file from inbound to processed, and a rename on the
     server is atomic. If the file is not in inbound at the first check, then either it was never delivered or it had
     already been moved before that check, and the second check then finds it in processed. Checking in the other order
     could miss a file that moves between the two checks, and the pick list would then be delivered twice.
   - The check runs before **every** attempt, the first one included. That covers a crash between the rename and the DB
     update (A3), and a failed state update after delivery (A4).
   - A `stat` on one path avoids listing the processed folder, which grows with every pick list ever delivered.
3. `choice`:
   - delivered-folder not null → log INFO `Pick list already on the warehouse server, counted as delivered
     orderNumber=… folder=… correlationId=…` → step 4.
   - otherwise:
     - `to sql:classpath:sql/pick-list-rows.sql` (`outputType: SelectList`): one row per order line in `line_number`
       order, with the twelve columns as aliases (`order_no`, …, `total_weight_g`). The order-to-pick-list mapping is in
       this SELECT: `street_line` is already joined by ", ", and `state_region`, prices and email are not selected
       (UC-004 BR-003, C-021).
     - `marshal` `csv` (MCP-verified dataformat, `camel-csv-starter:4.22.2`) with
       `header: order_no,line_no,sku,description,qty,unit_weight_g,ship_name,ship_street,ship_city,ship_postcode,ship_country,total_weight_g`,
       `delimiter: ","`, default quote `"` with minimal quoting (a value with a comma, a quote or a line break is
       quoted and inner quotes doubled, RFC 4180), and `recordSeparator` CRLF (default). Neither the warehouse's format
       nor the SFTP contract names the line ending, so CRLF is kept and confirmed with the warehouse through a
       non-blocking open question before the first real pick list (R3, C-25). Implementation checks the
       output byte for byte against `docs/messages/examples/pick-list/pl-tg-1060.json` plus a row whose street holds a
       comma and a quote.
     - `to sftp:{{warehouse.sftp.host}}:{{warehouse.sftp.port}}/{{warehouse.sftp.inbound-directory}}` (MCP-verified,
       `camel-ftp-starter:4.22.2`) with:
       - `fileName: "${exchangeProperty.file-name}"`;
       - `tempFileName: "${file:name}.tmp"` → `PL-x.csv.tmp`, renamed when complete (C-014, BR-004). An old `.tmp`
         from a broken attempt is replaced (A2);
       - `fileExist: Fail`: a second guard. The producer never overwrites a final file;
       - `charset: UTF-8`;
       - `strictHostKeyChecking: "yes"`, `knownHostsFile: "{{warehouse.sftp.known-hosts}}"` and
         `useUserKnownHostsFile: false` (the catalog default `no` is open to man-in-the-middle, and C-024 needs a
         verified server);
       - `username: "{{warehouse.sftp.username}}"` and `password: "RAW({{warehouse.sftp.password}})"`;
       - `connectTimeout: "{{warehouse.sftp.timeout-ms}}"` and `soTimeout: "{{warehouse.sftp.timeout-ms}}"` (10000),
         so a hung server cannot block the poller;
       - `autoCreate: false`. The folder is the warehouse's, and a missing folder must fail loudly.
     - log INFO `Pick list delivered orderNumber=… file=… correlationId=…`.
4. `to sql:classpath:sql/pick-list-delivered.sql`, one statement:
   - `UPDATE pick_list SET delivery_state='Delivered', delivered_at=now(), last_attempt_at=now(), attempts=LEAST(attempts+1,100000) WHERE id=:id AND delivery_state='Pending'`;
   - `UPDATE order_receipt SET order_state='Sent to Warehouse' WHERE id=:receipt AND order_state='Recorded'`;
   - insert `order_state_change` `Sent to Warehouse` (`ON CONFLICT (order_receipt_id, order_state) DO NOTHING`).

   If this statement fails (A4), the exchange fails, `onConsumeFailed` counts the attempt, and the row stays `Pending`.
   The next poll finds the file in step 2 and only records the state. Step 3 never runs again for a delivered file.

**Error handling:** `routeConfiguration` `pick-list-delivery-error-handling`: `onException` `java.lang.Exception`,
`handled: false` (so `onConsumeFailed` runs), `maximumRedeliveries: 0`, `logExhaustedMessageBody: false` (the CSV holds
names and addresses), `logExhaustedMessageHistory: false`. It logs WARN `Pick list not delivered, tried again on the
next poll orderNumber=… correlationId=… cause=<exception class>`. The SFTP server being down, in the maintenance window
or at any other time, is just a failed attempt, and the pick list is held in the database (A1, C-015, NFR-007). Every
pick list is retried every 30 s, so a held list goes out within 30 s of the server coming back (BR-006).

**Rationale:** `sftp` is the warehouse's only interface (C-011). The outbox row created in the recording statement
makes delivery survive any restart. The JSch `stat` bean exists only because Camel's file producer can check the target
folder (`fileExist`) but not a second folder. Its connection settings are the same `warehouse.sftp.*` properties as the
endpoint, including the known-hosts file. Implementation confirms that `com.github.mwiede:jsch` comes through
`camel-ftp-starter` and adds no dependency.

**Constraints:**
- Exactly once rests on four things: the existence check before every write, the atomic server-side rename, the order
  of the two checks, and one instance.
- If the warehouse ever deletes files from `processed` while one of our pick lists is still `Pending`, the check could
  miss a delivered file. That needs a DB outage after the rename, lasting longer than the warehouse keeps processed
  files. It is recorded as a risk, not designed for.
- During an outage each pending list costs one connect attempt per poll. A refused connect fails at once. A silent
  network drop costs up to `timeout-ms` each (50 × 10 s per poll at worst). The poll delay keeps polls from
  overlapping.

**Configuration properties** (shared):
```properties
pick-list.poll-delay-ms=30000
pick-list.batch-size=50
warehouse.sftp.inbound-directory=/inbound/picklists
warehouse.sftp.processed-directory=/inbound/picklists/processed
warehouse.sftp.timeout-ms=10000
```
Without a profile: `warehouse.sftp.host=${WAREHOUSE_SFTP_HOST}`, `.port=${WAREHOUSE_SFTP_PORT}`,
`.username=${WAREHOUSE_SFTP_USERNAME}`, `.password=${WAREHOUSE_SFTP_PASSWORD}`, `.known-hosts=${WAREHOUSE_SFTP_KNOWN_HOSTS}`.
`dev`: `localhost`, `2222`, `admin`, `admin`, `config/dev/warehouse_known_hosts`. Implementation writes that file from
`camel infra get sftp`. If the `camel infra` server maps the folders relative to the login directory, `dev` overrides
both directories (for example `inbound/picklists`). Implementation confirms this with one `stat` and records the result.

### Flow: partner registration (UC-007; new, Java, runs at startup)

**Purpose:** register the partners of the released Partner Configuration with their API Keys from the Secret Store,
checking UC-007 steps 5–8. A configuration that fails keeps the partners registered before.

**Why Java and not a route:** registration is a one-time startup check of a whole file. It is not a message flow, and
it must finish before the HTTP endpoint accepts a request. A Spring bean `PartnerRegistration`, initialised after
Flyway (`@DependsOn("flywayInitializer")`; implementation confirms the bean name), runs while the Spring context
refreshes. The Camel context, and with it the REST route, starts only after the refresh, so no partner request can
arrive before the registry is ready.

**Steps:**
1. Read `{{partner.configuration}}` (a file path). Validate it against `partner-configuration.schema.json` (networknt,
   as `json-validator` uses; the schema is copied to `classpath:schemas/` at build).
2. Check every problem and collect them all; it does not stop at the first:
   - an identifier used twice (A1);
   - `last < first`, and every overlapping pair, naming both partners (A2);
   - `orderContact` not matching `{{peakmart.email-pattern}}` (A3);
   - `apiKeyName` not a safe file name `^[A-Za-z0-9._-]{1,100}$` without `..`. This stops a path escaping the Secret
     Store directory (C-1);
   - the Secret Store has no non-empty file `<SECRET_STORE_DIR>/<apiKeyName>` (A4);
   - two partners whose API Keys have the same value. A key must identify one partner. This check is added; the report
     names the two partners and never the key (C-5).
3. **Valid:** in one transaction, upsert every partner into `partner` by `partner_code` (name, range, contact,
   `api_key_name`, `registered_at=now()` for new or changed rows, `deregistered_at=NULL`), and set `deregistered_at=now()`
   on partners missing from the file. Then build the in-memory registry from the file: key SHA-256 → partner. Log INFO
   `Partner Configuration registered partners=<n>` (no keys, no contacts).
4. **Invalid:** log ERROR `Partner Configuration not registered: <problem>` once per problem (the UC-007 report to the
   Integration Team; UC-006 alerts are out of scope). Change no row. Build the registry from the partners in `partner`
   with `deregistered_at IS NULL`, re-reading their keys from the Secret Store. A partner whose key is gone is left out
   and gets its own ERROR line, so no key is valid for a partner that is not registered. The application keeps
   running (BR-005, OQ-059 reading a; C-3).
5. A configuration file that does not exist or cannot be read counts as invalid in the same way. An unset
   `PARTNER_CONFIGURATION` is a missing variable and stops startup (carried item 3).

**`partnerRegistry`** (Spring bean): an immutable map, swapped once at startup. `authenticate(key)` is used by the route.
The API Keys exist only as hashes in memory, and they are never in the database, a log or an answer (UC-007 BR-003).

**Development Secret Store and configuration (C-1, C-2):**
- `config/dev/partner-configuration.json` = `docs/messages/examples/partner-configuration/summit-outfitters.json`
  (TC-001's partner, range 1000–1999, contact `orders@summit-outfitters.example.com`).
- `config/dev/secrets/partner-api-key-summit-outfitters` holds a development-only key that no real partner uses. It is
  committed under `config/`, outside the jar, for the same reason as the `test`/`test` credentials (carried item 7).
  `config/dev/secrets/README.md` says that this directory stands in for the Secret Store and must never hold a real key.

### Supporting change: Flyway V2

`src/main/resources/db/migration/V2__partners_and_pick_lists.sql`:
- `partner`: the entity model's columns (`partner_code` UNIQUE, `name`, `api_key_name` UNIQUE, `order_number_from`,
  `order_number_to`, `order_contact_email` with the same email CHECK as `notification`, `registered_at`), plus
  `deregistered_at timestamptz NULL` (C-4). `CHECK (order_number_from <= order_number_to)`, and both values ≥ 0 as
  `bigint`.
- `order_receipt.partner_id` gets `REFERENCES partner(id)`. V1 has no partner receipts, so the constraint validates.
- `CREATE UNIQUE INDEX order_receipt_partner_content_uq ON order_receipt (partner_id, order_number, content_hash)
  WHERE channel = 'Partner'` (the entity rule, keyed by partner as the contract's "orderId together with the Partner of
  the API Key", C-5).
- `CREATE UNIQUE INDEX order_receipt_accepted_number_uq ON order_receipt (order_number) WHERE order_state IN
  ('Recorded', 'Sent to Warehouse')`. This is the entity rule "at most one receipt per Order Number is Recorded or Sent
  to Warehouse". If the development data ever broke it, V2 would fail loudly.
- `pick_list`: the entity model's columns and rules (`customer_order_id` UNIQUE FK, `file_name` UNIQUE varchar(50),
  `total_weight_g` 0–2000000000, `delivery_state` Pending/Delivered, `attempts` 0–100000,
  `CHECK ((delivery_state = 'Delivered') = (delivered_at IS NOT NULL))`), plus an index on `(delivery_state, id)`.
- **Backfill:** one `INSERT … SELECT` creates a `Pending` pick list for every `customer_order` whose receipt is
  `Recorded` and that has none, with the total weight from its `order_line`s. The development database's test orders
  are Accepted Orders, and "for every Accepted Order" includes them (C-16). They go to the dev SFTP server on the first
  start, and the execution report lists them.

V2 follows the design where it differs from `docs/entity_model.md` (`deregistered_at` on `partner`, the receipt unique
per partner, order number and content). The entity model changes with it (R1, below).

### Supporting change: documentation (design review R1–R3, C-14)

The entity model and the open questions are part of the approved design, so this pipeline changes them together with
the code:
- **`docs/entity_model.md` (R1, C-4, C-5):**
  - `PARTNER`: add `deregistered_at` (Timestamp, Null), and the rule that a partner removed from the Partner
    Configuration is deregistered (`deregistered_at` set), never deleted, because receipts reference it; a partner with
    `deregistered_at` set has no valid API Key.
  - `ORDER_RECEIPT`: the uniqueness rule becomes "(partner_id, order_number, content_hash) are unique together for
    Partner receipts", the contract's "orderId together with the Partner of the API Key".
  - A change note in the file that says pipeline 003-partner-orders-pick-lists made these changes (2026-11-13, design
    review R1). The file has no change-note section yet, so implementation adds one at its end.
- **`docs/open_questions.md`:**
  - **OQ-074 (R2, C-7):** `**Status:** Answered`, the answer "Reading (b): the being-checked mark (`checking_since`)
    expires after 30 seconds", and `**Answered by:** order desk lead, design review of pipeline 003`. The `Assumed`
    line is replaced by the answer.
  - **New, non-blocking (R3, C-25):** the line ending of pick-list files (CRLF assumed), raised by design
    003-partner-orders-pick-lists (design review), to be confirmed with the Warehouse Team before the first real pick
    list. Element: UC-004, `warehouse-sftp.md`.
  - **New, non-blocking (C-14):** who terminates TLS for the partner endpoint, as already planned.
  - Both new questions use 002's format and the next free OQ numbers at the time of writing.

### Supporting change: Java beans

New in `com.trailheadgear.orderintegration`, declared in `IntegrationBeans` or as `@Component`:

| Bean | Role |
|---|---|
| `partnerRegistration` | UC-007 at startup (above) |
| `partnerRegistry` | `authenticate(key)` |
| `secretStore` | `read(apiKeyName)` from `secret-store.directory`. This interface is what a real Secret Store client replaces |
| `partnerOrders` | `cannotReadReason`, `readableReason`, `contentHash` |
| `orderAnswers` | builds the Order Answer and Problem bodies (Jackson) |
| `warehouseSftp` | `deliveredFolder(fileName)` (JSch `stat`, host-key verified, one reused session that reconnects when broken) |
| `catalogLookupPool` | thread pool for the parallel split (`catalog.lookup-threads`) |

`OrderChecks` changes as described above. `CannotReadReason` is unchanged.

### Carried item 3: one startup check that names every missing variable

- New `RequiredVariablesCheck`, a Spring Boot `EnvironmentPostProcessor`. Implementation confirms the Boot 4.1
  registration key in `META-INF/spring.factories`. It runs after the configuration files are loaded.
- It reads every config-file property source that is active (`application*.properties` from the classpath and from
  `./config/`), collects every `${NAME}` placeholder **without a default**, and checks `environment.containsProperty(NAME)`.
- If any is missing, it throws one exception whose message lists **all** missing names, sorted:
  `Missing environment variables: DB_NAME, DB_PASSWORD, DB_SSL_ROOT_CERT`. Startup stops before any datasource, Flyway,
  partner registration or route.
- The list is derived from the property files, so a variable added later is covered without editing a second list. An
  empty value still counts as set (002 B-3).
- **Acceptance:** without a profile and with all 26 variables set, the application gets past the check. Unsetting all
  26 at once gives one run whose output names all 26. Unsetting each one alone gives a run that names exactly that one.
  The `DB_PASSWORD`, `DB_NAME` and `DB_SSL_ROOT_CERT` runs of the 002 finding are among them. Values may be passed as
  `--NAME=value`, as in 002, if the session cannot set environment variables. The execution report records the runs.

The 26 variables: `KAFKA_BROKERS`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_ROOT_CERT`,
`CATALOG_HOST`, `CATALOG_PORT`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `ORDERDESK_MAILBOX`,
`ORDERDESK_SENDER` (002), and `PARTNER_HTTP_PORT`, `PARTNER_TLS_CERTIFICATE`, `PARTNER_TLS_PRIVATE_KEY`,
`PARTNER_CONFIGURATION`, `SECRET_STORE_DIR`, `PARTNER_MAIL_SENDER`, `WAREHOUSE_SFTP_HOST`, `WAREHOUSE_SFTP_PORT`,
`WAREHOUSE_SFTP_USERNAME`, `WAREHOUSE_SFTP_PASSWORD`, `WAREHOUSE_SFTP_KNOWN_HOSTS`.

### Carried item 7: development settings out of the jar

- `src/main/resources/application-dev.properties` moves to `config/application-dev.properties` (repository root).
  Spring Boot reads `./config/application-<profile>.properties` from the working directory by default. The project
  root is the working directory for `java -jar target/…jar`, `./mvnw spring-boot:run` and the kill test's
  `spring-boot:test-run`. So `--spring.profiles.active=dev` keeps working with no new flag.
- Everything development-only lives under `config/`: `application-dev.properties`, `dev/partner-configuration.json`,
  `dev/secrets/`, `dev/warehouse_known_hosts`.
- `application.properties` (shared, no secrets) and `application-default.properties` (placeholders only) stay in the
  jar.
- **Acceptance:** `unzip -l target/order-integration-0.1.0.jar` lists no `application-dev.properties` and nothing from
  `config/`. A search of `BOOT-INF/classes/` finds no `test`/`admin` password property and no API key. Started from the
  project root with `dev`, the application picks up the dev values; the startup log shows the `config/` file as a
  property source.
- `scenarios.sh` and `kill-test.sh` already start from the project root. Implementation verifies that they still
  start the `dev` profile.

---

## 4. Cross-Cutting Concerns

### Performance
- NFR-002 (95% ≤ 3 s, 100% ≤ 10 s, ≤ 30 lines): one receipt statement, parallel catalog lookups (10 threads, ≤ 2 s each,
  20/s shared), one record or reject statement. Worst case with a slow but answering catalog and 30 lines: about 3 × 2 s
  plus the DB time.
- Risk: during a Peakmart backlog re-check after a Catalog Outage, partner lookups share the 20/s and can wait. The
  measurement is monthly, and backlogs are rare (C-016). Not designed for (Not Doing).
- NFR-001: pick-list poll every 30 s.
- NFR-014: rejection emails polled every 5 s.
- NFR-003: 800 orders/h is far below every limit.

### Security
- **NFR-009:** the API Key is checked before any Order Check. The comparison is hash-based and constant time. Keys
  exist only in memory as SHA-256. `X-API-Key` is removed before any other step.
- **Header injection:** `removeHeaders "*"` right after authentication, because `CamelSqlQuery` would otherwise let a
  request header replace a query (sql catalog). The same is done before every response and before `smtp`. The strip
  is kept whether or not `platform-http` filters inbound `Camel*` headers; the execution report states which it is
  (R4).
- **NFR-010 / C-024:** TLS 1.2+ on the partner endpoint without a profile (PEM bundle). SFTP with strict host-key
  checking. DB `verify-full` (002).
- **NFR-011:**
  - Log lines carry the order number, partner id and correlation id only. No body, no contact, no key.
  - Every new error handler sets `logExhaustedMessageBody=false` and `logExhaustedMessageHistory=false`, and it logs
    the exception class, not the message.
  - `platform-http` `muteException` stays `true` (catalog default), so no stack trace reaches a partner.
- **C-023:** the Rejection Email holds only the order number and the reasons.
- **Path safety:**
  - `apiKeyName` is restricted to a safe file name.
  - The pick list file name is built only from an order number that passed `^(TG-\d{4,}|PM-\d+)$`
    (`order.schema.json` guard before recording).
  - The order number in a mail subject has no control characters (C-10 for partners; 002's intake guard for Peakmart).
- **Request size:** at most `partner.max-request-chars`.

### Monitoring
Every route has a `routeId` and a `description`. Every order log line carries `orderNumber` and `correlationId`
(NFR-013). The new WARN/ERROR lines (refused, unreadable, temporarily unavailable, pick list not delivered, configuration
not registered) stand in for the UC-006 alerts until that pipeline.

### Constraints
- YAML DSL routes. Java only for beans: checks, registry, Secret Store, answers, SFTP `stat`, startup check.
- Versions come only from `.camel-kit/config.properties`: Camel 4.22.2, Camel Spring Boot BOM 4.22.2, Spring Boot
  4.1.1, JDK 25. 002 R1 (parent POM) still holds.
- New dependencies, all catalog- or BOM-managed:
  - `camel-platform-http-starter`, `camel-rest-openapi-starter`, `camel-ftp-starter`, `camel-csv-starter` (4.22.2);
  - the Spring Boot web starter from the Boot 4.1.1 BOM, when `camel-platform-http-starter` does not already bring it.
- The `maven-resources-plugin` copy gains the contract files (§3) and `partner-configuration.schema.json`.
- Do not start or stop containers or `camel infra` services. Do not use ports 8080, 8081 or 5432.

### Acceptance criteria and scenario approach
Environment: the `dev` profile, the running services, the stub. **The scripts move pick lists to `processed` themselves**
(`sftp` `rename` as `admin`), because nothing does what the warehouse does. Mailpit is searched, never cleared (002
B-12). Each scenario uses its own order number from 1000–1999 so that reruns do not collide. The rerun guard of 002 is
kept.

| # | Scenario | Expectation |
|---|---|---|
| P-1 | TC-001 steps 1–5: `partner-order-sample.json` (TG-1060) with the dev key | 200 `accepted` and `orderNumber`. One `customer_order`, lines at catalog prices 249.00 and 39.95. History Received → Recorded → Sent to Warehouse. `PL-TG-1060.csv` is byte-equal to the expected CSV of `pl-tg-1060.json` (2 rows, total 2340, CRLF, header). No `.tmp` is left. No email |
| P-2 | The same request again (resend) | 200 again. No second receipt, history entry or pick list |
| P-3 | Rejected: quantity 25 on line 2 and country DE | 422 problem+json with 2 reasons in check order (quantity, then destination-country). One Mailpit message `Order TG-… rejected` from `partner.mail-sender` to the Order Contact, holding the order number and both reasons and no name or address. A resend gives 422 with the same reasons and no second email |
| P-4 | Corrected order with the same number as P-3 and valid content | Checked anew: 200, recorded, pick list |
| P-5 | Same number as P-1, different content | 422 with `order-number-already-used`. P-1's order is unchanged |
| P-6 | Number outside the range (`TG-2500`), and `TG-12` | 422 `order-number-range`; `TG-12`: `order-number-format` and `order-number-range` |
| P-7 | No key, unknown key, and `partnerId` of another partner | 401, 401, 403 plain Problem. No receipt, history or email. A WARN line without the key |
| P-8 | Unreadable: the invalid examples `order-id-missing`, `quantity-not-integer`, `unknown-field`; not JSON; a bad `orderedAt`; an over-long `orderId` | 400 with one reason without `orderCheck`, and `orderNumber` when readable. Nothing written |
| P-9 | Two identical requests in parallel (stub `slow`, 3 s) | Exactly one is checked; the other gets 503 (A8). The order is recorded once |
| P-10 | Catalog outage (stub stopped, or `invalid`) | 503 `temporarily-unavailable`, receipt `Received`, `checking_since` NULL, no email. After the stub is back, the resend is checked on the same receipt and recorded |
| P-11 | Header injection: a valid request carrying `CamelSqlQuery: DELETE FROM partner` (R4) | Normal answer (200 `accepted`), recorded like a request without the header. `partner` is unchanged (row count and content compared before and after). The execution report shows that the header changed nothing, and says plainly whether `platform-http` itself filtered it |
| P-12 | TC-005: SFTP unreachable (`warehouse.sftp.port` pointed at a closed port; no service is stopped), record an order, restart with port 2222 | `pick_list` stays `Pending` while `attempts` grows and WARN lines appear. After the restart exactly one file arrives, the state is Sent to Warehouse, and no `.tmp` is left |
| P-13 | Crash after the rename, before the state update. The script puts `PL-TG-x.csv` into `inbound` (and in a second run into `processed`), then marks the row `Pending` | The next poll counts it as delivered and writes nothing. The file is unchanged (checksum) |
| P-14 | A leftover `PL-TG-x.csv.tmp` with no final file | It is replaced, and the final file is complete |
| P-15 | Peakmart regression: 002 scenarios 1–6 and the kill test | Unchanged results. Every Peakmart order recorded during them also gets exactly one pick list and Sent to Warehouse |
| P-16 | V2 backfill | Every `customer_order` that was Recorded before V2 has one pick list, and each is delivered once |
| P-17 | UC-007: duplicate id, overlapping ranges, invalid contact, missing key file, `apiKeyName` `../x`, two partners with the same key — each in a copy of the dev config | One ERROR line per problem, naming the partners and never a key. The previously registered partner still gets 200 with its key. A new partner from the bad file gets 401 |
| P-18 | UC-007: a valid configuration with a second partner, then that partner removed | It is registered (200), then deregistered (401, `deregistered_at` set) |
| P-19 | Carried item 3 | §3 runs (all-at-once and one-at-a-time) |
| P-20 | Carried item 7 | §3 jar checks |
| P-21 | `./mvnw verify` | Passes, including `EmailPatternTest` and the new unit tests: `OrderChecks` partner reasons and catalog price, `PartnerRegistration` checks, `RequiredVariablesCheck`, CSV quoting |
| P-22 | Documentation (R1–R3, C-14) | `docs/entity_model.md` has `PARTNER.deregistered_at`, the per-partner receipt rule and a change note naming pipeline 003. In `docs/open_questions.md`, OQ-074 is Answered (reading b, 30 s) by the order desk lead in the design review of pipeline 003, and the line-ending and TLS-termination questions are open and non-blocking |

---

## 5. Constitution Compliance

- [x] Route Structure: every route has a source and a sink. The REST routes end in an HTTP response, and the pollers
  end in `sql` or `smtp`/`sftp`. `direct:` sub-routes are exempt.
- [x] Single Responsibility: submit, receive, check, answer, rejection email and pick-list delivery are separate routes.
- [x] Separation of Concerns:
  - Ingestion: REST, Kafka.
  - Processing: `order-check`.
  - Delivery: record/reject, `smtp`, `sftp`.
  - Rules live in beans and SQL in files.
- [x] Naming Conventions: route ids `<domain>-<action>` (`partner-order-submit`, `pick-list-delivery`, …).
- [x] Observability: routeId and description on every route, and the order number and correlation id on every order
  log line.
- [x] External Configuration: every host, port, path, credential, address, interval and limit is a property. Without a
  profile, they come from the environment, and the startup check covers them.
- [x] Component Support: all components, EIPs, data formats and languages are verified below.
- [x] Infrastructure via Forage: Forage is unavailable (no `forage.version`). Spring Boot's own `dataSource` and
  `server.*`/`spring.ssl.*` (rung 2). Beans are Spring `@Bean`s.

### Catalog Verification Evidence

Catalog binding: camel_catalog_components(limit=0); runtime=spring-boot; platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2; returned camelVersion=4.22.2; binding=MATCHED

| Artifact identity | Result | Verification provenance |
|---|---|---|
| component:platform-http | VERIFIED | camel_catalog_component_doc (path, consumes, muteException default true, headerFilterStrategy) + camel_catalog_component_maven (org.apache.camel.springboot:camel-platform-http-starter:4.22.2); requested component:platform-http; catalog binding=MATCHED |
| component:rest-openapi | VERIFIED | camel_catalog_component_doc (specificationUri with classpath:, missingOperation default fail, clientRequestValidation default false) + camel_catalog_component_maven (org.apache.camel.springboot:camel-rest-openapi-starter:4.22.2); requested component:rest-openapi; catalog binding=MATCHED |
| component:sftp | VERIFIED | camel_catalog_component_doc (tempFileName, fileExist incl. Fail, charset, knownHostsFile, useUserKnownHostsFile, strictHostKeyChecking default no, username, password, connectTimeout, soTimeout, fastExistsCheck) + camel_catalog_component_maven (org.apache.camel.springboot:camel-ftp-starter:4.22.2); requested component:sftp; catalog binding=MATCHED |
| component:sql | VERIFIED | camel_catalog_component_doc (onConsume, onConsumeFailed, breakBatchOnConsumeFail; headers CamelSqlQuery, CamelSqlUpdateCount, CamelSqlRowCount); requested component:sql; catalog binding=MATCHED |
| component:smtp | VERIFIED | camel_catalog_component_doc (useHeaderRecipients, useHeaderSubject); requested component:smtp; catalog binding=MATCHED |
| component:json-validator | VERIFIED | camel_catalog_component_doc (resourceUri classpath:, failOnNullBody); requested component:json-validator; catalog binding=MATCHED |
| component:http | VERIFIED | camel_catalog_component_doc (throwExceptionOnFailure); requested component:http; catalog binding=MATCHED |
| component:direct | VERIFIED | camel_catalog_component_doc; requested component:direct; catalog binding=MATCHED |
| component:bean | VERIFIED | camel_catalog_component_doc; requested component:bean; catalog binding=MATCHED |
| component:kafka | VERIFIED | camel_catalog_component_doc (allowManualCommit; unchanged flow); requested component:kafka; catalog binding=MATCHED |
| dataformat:csv | VERIFIED | camel_catalog_dataformat_doc (header, delimiter, quote, quoteMode, recordSeparator default CRLF; org.apache.camel.springboot:camel-csv-starter:4.22.2); requested dataformat:csv; catalog binding=MATCHED |
| eip:rest | VERIFIED | camel_catalog_eip_doc (openApi, bindingMode default off, clientRequestValidation); requested eip:rest; catalog binding=MATCHED |
| eip:openApi | VERIFIED | camel_catalog_eip_doc (specification, missingOperation, routeId); requested eip:openApi; catalog binding=MATCHED |
| eip:split | VERIFIED | camel_catalog_eip_doc (parallelProcessing, executorService, aggregationStrategy); requested eip:split; catalog binding=MATCHED |
| eip:throttle | VERIFIED | camel_catalog_eip_doc (mode TotalRequests, timePeriodMillis); requested eip:throttle; catalog binding=MATCHED |
| eip:marshal | VERIFIED | camel_catalog_eip_doc; requested eip:marshal; catalog binding=MATCHED |
| eip:removeHeaders | VERIFIED | camel_catalog_eip_doc (pattern, excludePattern); requested eip:removeHeaders; catalog binding=MATCHED |
| eip:routeConfiguration | VERIFIED | camel_catalog_eip_doc (onException); requested eip:routeConfiguration; catalog binding=MATCHED |
| eip:onException | VERIFIED | camel_catalog_eip_doc (handled, redeliveryPolicy); requested eip:onException; catalog binding=MATCHED |
| eip:doTry | VERIFIED | camel_catalog_eip_doc (doCatch); requested eip:doTry; catalog binding=MATCHED |
| eip:choice | VERIFIED | camel_catalog_eip_doc; requested eip:choice; catalog binding=MATCHED |
| eip:filter | VERIFIED | camel_catalog_eip_doc; requested eip:filter; catalog binding=MATCHED |
| eip:setHeader | VERIFIED | camel_catalog_eip_doc; requested eip:setHeader; catalog binding=MATCHED |
| eip:setBody | VERIFIED | camel_catalog_eip_doc; requested eip:setBody; catalog binding=MATCHED |
| eip:setProperty | VERIFIED | camel_catalog_eip_doc; requested eip:setProperty; catalog binding=MATCHED |
| eip:convertBodyTo | VERIFIED | camel_catalog_eip_doc (charset); requested eip:convertBodyTo; catalog binding=MATCHED |
| eip:log | VERIFIED | camel_catalog_eip_doc; requested eip:log; catalog binding=MATCHED |
| eip:bean | VERIFIED | camel_catalog_eip_doc; requested eip:bean; catalog binding=MATCHED |
| eip:to | VERIFIED | camel_catalog_eip_doc; requested eip:to; catalog binding=MATCHED |
| language:simple | VERIFIED | camel_catalog_language_doc (camel-core-languages 4.22.2); requested language:simple; catalog binding=MATCHED |
| language:groovy | VERIFIED | camel_catalog_language_doc (camel-groovy-starter 4.22.2); requested language:groovy; catalog binding=MATCHED |
| language:jsonpath | VERIFIED | camel_catalog_language_doc (camel-jsonpath-starter 4.22.2); requested language:jsonpath; catalog binding=MATCHED |
| language:bean | VERIFIED | camel_catalog_language_doc (model name `method`, camel-bean-starter 4.22.2); requested language:bean; catalog binding=MATCHED |
| language:constant | VERIFIED | camel_catalog_language_doc; requested language:constant; catalog binding=MATCHED |

Unchanged 002 artifacts not used by the new flows (`eip:script`, `eip:transform`) keep their 002 evidence.

These are not catalog artifacts. Implementation verifies them:
- the Spring Boot web starter and SSL bundle keys under Boot 4.1.1;
- `EnvironmentPostProcessor` registration in Boot 4.1;
- `flywayInitializer` as the bean to depend on;
- that JSch comes with `camel-ftp-starter`;
- that `swagger-parser` resolves the classpath `$ref`s;
- whether Camel 4.22.2's `platform-http` drops inbound `Camel*` headers. `removeHeaders "*"` is the control either
  way; the execution report records the answer plainly (R4).

---

## 6. Project Structure

```
trailhead-orders/
├── .camel-kit/{config.properties, pipeline.json}
├── config/                                         (development only; never packaged — carried item 7)
│   ├── application-dev.properties                  (moved from src/main/resources, + server, partner, sftp dev values)
│   └── dev/
│       ├── partner-configuration.json              (summit-outfitters, C-2)
│       ├── secrets/{partner-api-key-summit-outfitters, README.md}   (Secret Store stand-in, C-1)
│       └── warehouse_known_hosts                   (from `camel infra get sftp`)
├── docs/
│   ├── api/partner-orders.openapi.yaml             (copied to classpath:contract/api at build)
│   ├── messages/*.schema.json                      (partner-order, order-answer, rejection-reason → contract/messages; partner-configuration → schemas)
│   ├── entity_model.md                             (PARTNER.deregistered_at, ORDER_RECEIPT uniqueness, change note — R1)
│   ├── open_questions.md                           (OQ-074 Answered — R2; + pick-list line ending — R3; + partner TLS termination — C-14)
│   └── camel-kit/003-partner-orders-pick-lists/
│       ├── design-spec.md                          ← this file
│       ├── implementation-plan.md                  [created by camel-plan]
│       ├── execution-report.md                     [created by camel-execute]
│       └── validation-report.md                    [created by camel-validate]
├── pom.xml                                         (+4 Camel starters, web starter if needed, resource copies)
├── src/main/java/com/trailheadgear/orderintegration/
│   ├── CamelApplication.java, CannotReadReason.java            (unchanged)
│   ├── IntegrationBeans.java                                   (+ catalogLookupPool, new beans)
│   ├── OrderChecks.java                                        (channel-aware, partner reasons, catalog price)
│   ├── PartnerRegistration.java, PartnerRegistry.java, SecretStore.java (+ DirectorySecretStore)
│   ├── PartnerOrders.java, OrderAnswers.java, WarehouseSftp.java
│   └── RequiredVariablesCheck.java                              (carried item 3)
├── src/main/resources/
│   ├── META-INF/spring.factories                               (RequiredVariablesCheck)
│   ├── application.properties, application-default.properties  (+ new keys and variables)
│   ├── camel/
│   │   ├── peakmart-order-intake.camel.yaml, order-desk-notification.camel.yaml   (unchanged)
│   │   ├── peakmart-order-check.camel.yaml                      (outcome choice moved, parallel split)
│   │   ├── partner-order-api.camel.yaml                         (new)
│   │   ├── partner-rejection-email.camel.yaml                   (new)
│   │   └── pick-list-delivery.camel.yaml                        (new)
│   ├── sql/ (existing; order-reject and order-record changed)
│   │   + partner-receipt-claim, partner-receipt-release, accepted-content-select, rejection-reasons-select,
│   │     rejection-email-select, pick-list-select, pick-list-rows, pick-list-delivered
│   └── db/migration/{V1__order_database.sql, V2__partners_and_pick_lists.sql}
├── src/test/java/…   (existing + OrderChecksPartnerTest, PartnerRegistrationTest, RequiredVariablesCheckTest, PickListCsvTest)
└── src/test/scripts/ (scenarios.sh + partner-scenarios.sh, pick-list-scenarios.sh; kill-test.sh unchanged)
```

---

## 7. Assumptions Taken (non-interactive run — recommended option chosen)

| ID | Open decision | Taken |
|---|---|---|
| C-1 | What stands in for the Secret Store | A directory with one file per API Key, named by `apiKeyName` (`secret-store.directory`; `SECRET_STORE_DIR` without a profile, `config/dev/secrets` in dev). Real stores can mount secrets this way (Vault Agent, CSI driver, Kubernetes/Docker secrets). The `SecretStore` interface is the seam for a native client later. `apiKeyName` must be a safe file name. A development-only key is committed under `config/dev/secrets/`, outside the jar |
| C-2 | Where the Partner Configuration lives per environment | A file path (`PARTNER_CONFIGURATION`). The repository holds `config/dev/partner-configuration.json` (summit-outfitters). The production file arrives with the first real partner |
| C-3 | When the configuration is read, and what a failing one does (OQ-059) | At startup only. All problems are logged at ERROR, the partners registered before (`partner` rows not deregistered) stay registered with their keys re-read, and the application keeps running (reading a). If the very first configuration fails, no partner is registered and every request gets 401 |
| C-4 | How "registered before" survives a restart | The `partner` table with an added `deregistered_at` column, which deviates from the entity model. A removed partner is deregistered, not deleted, because receipts reference it. **Confirmed by the review (R1):** `docs/entity_model.md` is updated in this pipeline to match |
| C-5 | Request identity, and two partners with the same key | The receipt is unique per `(partner_id, order_number, content_hash)`, which is the contract's "orderId together with the Partner of the API Key". Two partners with the same key value make the configuration invalid. **Confirmed by the review (R1):** the entity model's rule `(order_number, content_hash)` is updated in this pipeline to match |
| C-6 | What "same content" means | The same JSON value: key order, whitespace and number notation are ignored. `unitPrice` counts as content although it is not used |
| C-7 | `checking_since` after a restart (OQ-074) | It expires after `partner.checking-expiry-seconds=30`, which is reading (b) with a margin over NFR-002's 10 s. No startup clean-up is needed. **Answered (R2):** the order desk lead, as product owner, accepts reading (b) with 30 s; OQ-074 is recorded as Answered |
| C-8 | Two requests for one receipt | A single atomic claim. The loser gets 503. A late loser after the expiry whose final update affects 0 rows also gets 503 and writes nothing |
| C-9 | Order of the refusals | 401 (key) → 400 (unreadable) → 403 (other partner): the partner id can only be compared once the order can be read |
| C-10 | What else makes a request unreadable | Not JSON; larger than 1 MiB; `orderedAt` not an offset date-time; `customerEmail` failing the shared email pattern; `orderId` over 30 characters or with control characters. These are the partner analogue of 002's Peakmart guards. JSON Schema `format` is not asserted, so the beans check these |
| C-11 | Range check of a badly formatted number | Applied when the number is `TG-` plus digits (any count). Otherwise only the format reason |
| C-12 | Reason texts | The existing 002 texts stay for the shared checks (the behaviour baseline). New texts only for the TG format, range and already-used. The example texts in `docs/messages/examples/` illustrate the shape and are not asserted word for word |
| C-13 | Catalog lookups within NFR-002 | A parallel split on a 10-thread pool, behind the unchanged shared 20/s throttle, for both channels |
| C-14 | TLS of the partner endpoint, and the dev port | The application terminates TLS without a profile (PEM bundle from the environment, TLS 1.2/1.3). Nothing says whether a load balancer does it instead, so implementation adds a non-blocking open question to `docs/open_questions.md`, in 002's format, next free OQ number. Dev: plain HTTP on 8082, which the review confirmed is free (R5); no fallback port |
| C-15 | Rejection Email sender | Its own variable `PARTNER_MAIL_SENDER` (OQ-041: configuration). Dev `orders@trailhead.example` |
| C-16 | Orders recorded before V2 | Backfilled with a pending pick list ("every Accepted Order") and delivered once |
| C-17 | SFTP login and server identity | Username and password from the environment (dev `admin`/`admin` in `config/`). Strict host-key checking against a known-hosts file. Key-based login can be added later through endpoint options |
| C-18 | How "already delivered" is detected | SFTP `stat` on inbound, then processed, before every attempt, through a JSch bean. Camel's producer can guard only its own folder (`fileExist: Fail` is kept as a second guard) |
| C-19 | Poll intervals | Pick lists 30 s (NFR-001 15 min). Rejection emails 5 s, the existing `notification.poll-delay-ms` (NFR-014) |
| C-20 | How the startup check finds the variables | Derived from `${NAME}` placeholders without a default in the active config files. All missing names in one failure |
| C-21 | Where dev settings go | `config/` at the project root, Spring Boot's default `./config/` location. No new flag needed |
| C-22 | Actuator with a web server present | HTTP management off (`management.server.port=-1`), as 002 had no actuator over HTTP |
| C-23 | Problem Details fields | `type about:blank`, standard reason phrase as `title`, fixed `detail` without personal data. No `instance` and no correlation reference to the partner (OQ-043) |
| C-24 | A partner order that passes the checks but cannot be recorded (data too long; OQ-075) | 503 `temporarily-unavailable`. It is not rejected while OQ-075 is open |
| C-25 | Pick-list CSV line ending and quoting | CRLF (the RFC 4180 and `csv` default), minimal quoting, no BOM. **Kept by the review (R3):** neither the warehouse's format nor the SFTP contract names the line ending, so a non-blocking open question asks the warehouse to confirm it before the first real pick list |
| C-26 | Peakmart order checks after the refactor | Same outcomes. Only the outcome choice moves and lookups run in parallel. 002 scenarios and the kill test are rerun (P-15) |
| C-27 | Scenario evidence | Scripts and manual runs against dev, as in 002. Mailpit searched, never cleared. Pick lists moved to `processed` by the scripts |

---

## 8. Design Review Decisions

Source: `docs/camel-kit/003-partner-orders-pick-lists/design-review.md` (integration architect, with the developer and,
for R2, the order desk lead as product owner, 2026-11-13). Everything the review does not list is accepted as written,
including C-1 to C-3, C-6, C-8 to C-13, C-15 to C-24, C-26 and C-27. The design is approved with these five changes.

| # | Decision | Where it is in this spec |
|---|---|---|
| R1 | The spec follows the design (C-4, C-5): `deregistered_at` on the partner, and a receipt unique per partner, order number and content. `docs/entity_model.md` (`PARTNER`, `ORDER_RECEIPT` and their rules) is updated in this pipeline, with a change note naming pipeline 003 | §3 Supporting change: Flyway V2, §3 Supporting change: documentation, §4 P-22, §6, §7 C-4, C-5 |
| R2 | OQ-074 is answered with reading (b): the "being checked" mark expires after 30 seconds. Recorded in `docs/open_questions.md` as Answered, "Answered by: order desk lead, design review of pipeline 003" (C-7) | §3 partner-order-receive, §3 Supporting change: documentation, §4 P-22, §6, §7 C-7 |
| R3 | Pick lists keep CRLF line endings (C-25), and a non-blocking open question asks the warehouse to confirm before the first real pick list | §3 pick-list-delivery step 3, §3 Supporting change: documentation, §4 P-22, §6, §7 C-25 |
| R4 | Header stripping stays. The execution report says whether Camel 4.22.2's `platform-http` already filters inbound `Camel*` headers (then the strip is a second guard) or not (said plainly). P-11 sends a `CamelSqlQuery` header and shows it changed nothing | §3 partner-order-submit step 3, §4 Security, §4 P-11, §5 items implementation verifies |
| R5 | Dev port 8082 is free on the development machine and is used; no fallback port (C-14) | §2, §7 C-14 |
