---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T16:40:00Z"
  by: camel-plan
  from: design-spec.md
---
# Trailhead Peakmart Intake Prototype Implementation Plan

> **For agentic workers:** Use camel-execute to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take in every Peakmart `order.placed` event from `peakmart.orders.v2`, check it with the UC-003 Order Checks,
and either record it exactly once in the Order Database or report it to the Order Desk as rejected. No order may be lost
or recorded twice.

**Architecture:** This is a Camel Main (JBang) prototype in the `prototype/` module. A Kafka intake with a manual offset
commit writes each new order durably to PostgreSQL (`order_receipt.waiting_order`). An SQL poller then checks each
waiting order against a throttled Product Catalog and records it, rejects it, or leaves it waiting. A second SQL poller
delivers the Order Desk Notification outbox rows to plain-text files. A separate Camel Main module
(`prototype/stubs/product-catalog/`) serves the Product Catalog contract on port 8091.

**Tech Stack:** Apache Camel 4.22.2, Main runtime (Camel JBang, YAML DSL), platform BOM
`org.apache.camel:camel-catalog:4.22.2`. Components: kafka, sql, http, json-validator, file, timer, direct, bean,
platform-http (stub). Languages: simple, jsonpath, groovy. Two Java beans: `OrderChecks` and `CannotReadReason`.
Database: PostgreSQL 17.10.

**Design Spec:** `docs/camel-kit/001-peakmart-intake/design-spec.md` (approved 2026-11-13, with the changes of
`design-review.md` items 1–6 applied)

---

## Planning Decisions and Assumptions (non-interactive run)

| # | Decision | Reason |
|---|---|---|
| P-1 | **No Citrus test tasks.** The testing template normally adds one Citrus task per flow. Instead, Task 13 generates `prototype/scenarios.sh`. | The approved design (§4 *Acceptance criteria*, §6, A-8, design review item 5) explicitly says there are no Citrus tests and no `src/test/resources` in this pipeline. The approved design wins over the template default. camel-execute's verification therefore has no Citrus suite to run. Its runtime verification and execution report must record scenarios 1–10 and the intake kill-and-restart test (design §3 intake source, §4). |
| P-2 | **No Docker Compose task.** | The design forbids starting or stopping containers (§3 Not Doing, §4 Constraints). It uses the Kafka (9092) and PostgreSQL (15432) instances that `camel infra` is already running. §6 Project Structure has no `docker-compose.yaml`. Smoke verification must not start containers. |
| P-3 | One consolidated `prototype/application.properties` task (Task 8), placed after every route that reads it. | The three flows share one properties file and one datasource bean (`#orderDb`). |
| P-4 | SQL files are created in the same task as the route that executes them. Java beans get their own tasks. | Each SQL file belongs to one route. Each bean is a separately verifiable outcome that the routes consume. |
| P-5 | The plan uses the `jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run … --properties=application.properties` form from the shipped run-script guide. | Commands are taken from the shipped guides, not from the command text in the design. |
| P-6 | There is no project graph (`.camel-kit/project-graph.json` is absent), so graph-context guides are not loaded. | Orchestrator Step 0 is conditional on that file. |

**Common MCP binding for every task (run once per task before any catalog detail call):**
`camel_catalog_components(limit=0, runtime="main", platformBom="org.apache.camel:camel-catalog:4.22.2")`. The returned
`camelVersion` must equal `4.22.2`. Below, "`B`" means `runtime="main", platformBom="org.apache.camel:camel-catalog:4.22.2"`.

**Hard constraints for every task (design §2, §4):**
- Do not start or stop containers.
- Do not use ports 8080, 8081 or 5432.
- Do not modify anything under `docs/` except this pipeline's artifacts. The schemas are referenced in place.
- Never log message bodies or personal data.

---

```yaml plan-metadata
tasks:
  - id: 1
    title: Scaffold Project Structure
    files:
      creates:
        - prototype/
        - prototype/sql/
        - prototype/db/
        - prototype/stubs/product-catalog/
      references:
        - docs/camel-kit/001-peakmart-intake/design-spec.md
    provides:
      routeContracts:
        - prototype-module-layout
    consumes: {}
    dependsOn: []
  - id: 2
    title: Order Database Schema and Schema-Init Route
    files:
      creates:
        - prototype/sql/schema.sql
        - prototype/db/order-db-schema-init.camel.yaml
      reads:
        - docs/entity_model.md
    provides:
      routes:
        - order-db-schema-init
      endpoints:
        - timer:order-db-schema-init
      schemas:
        - order-db-schema
    consumes:
      routeContracts:
        - prototype-module-layout
      externalServices:
        - postgresql-15432
    dependsOn:
      - 1
  - id: 3
    title: Java Bean CannotReadReason
    files:
      creates:
        - prototype/CannotReadReason.java
    provides:
      beans:
        - CannotReadReason
    consumes:
      routeContracts:
        - prototype-module-layout
    dependsOn:
      - 1
  - id: 4
    title: Java Bean OrderChecks
    files:
      creates:
        - prototype/OrderChecks.java
      reads:
        - docs/messages/order.schema.json
        - docs/messages/rejection-reason.schema.json
        - docs/messages/examples/order-desk-notification/pm-50118.json
    provides:
      beans:
        - OrderChecks
    consumes:
      routeContracts:
        - prototype-module-layout
    dependsOn:
      - 1
  - id: 5
    title: Generate Route YAML — peakmart-order-intake
    files:
      creates:
        - prototype/peakmart-order-intake.camel.yaml
        - prototype/sql/receipt-insert.sql
        - prototype/sql/set-aside-insert.sql
      reads:
        - docs/messages/peakmart-order-placed.schema.json
        - docs/messages/order.schema.json
        - docs/messages/mappings/peakmart-order-placed-to-order.md
    provides:
      routes:
        - peakmart-order-intake
        - peakmart-event-read
        - peakmart-event-set-aside
        - peakmart-order-receive
      endpoints:
        - kafka:peakmart.orders.v2
        - direct:peakmart-event-read
        - direct:peakmart-event-set-aside
        - direct:peakmart-order-receive
    consumes:
      schemas:
        - order-db-schema
      beans:
        - CannotReadReason
      externalServices:
        - kafka-9092
        - postgresql-15432
    dependsOn:
      - 2
      - 3
  - id: 6
    title: Generate Route YAML — peakmart-order-check
    files:
      creates:
        - prototype/peakmart-order-check.camel.yaml
        - prototype/sql/waiting-select.sql
        - prototype/sql/order-reject.sql
        - prototype/sql/order-record.sql
      reads:
        - docs/messages/product.schema.json
        - docs/messages/order.schema.json
    provides:
      routes:
        - peakmart-order-check-poll
        - order-check
        - product-lookup
        - order-reject
        - order-record
      endpoints:
        - direct:order-check
        - direct:product-lookup
        - direct:order-reject
        - direct:order-record
    consumes:
      schemas:
        - order-db-schema
      beans:
        - OrderChecks
      externalServices:
        - postgresql-15432
        - product-catalog-8091
    dependsOn:
      - 2
      - 4
  - id: 7
    title: Generate Route YAML — order-desk-notification
    files:
      creates:
        - prototype/order-desk-notification.camel.yaml
        - prototype/sql/notification-select.sql
      reads:
        - docs/messages/order-desk-notification.schema.json
    provides:
      routes:
        - order-desk-notification-send
    consumes:
      schemas:
        - order-db-schema
      externalServices:
        - postgresql-15432
    dependsOn:
      - 2
  - id: 8
    title: Generate Application Properties — prototype
    files:
      creates:
        - prototype/application.properties
      reads:
        - prototype/peakmart-order-intake.camel.yaml
        - prototype/peakmart-order-check.camel.yaml
        - prototype/order-desk-notification.camel.yaml
        - prototype/db/order-db-schema-init.camel.yaml
        - prototype/OrderChecks.java
        - prototype/CannotReadReason.java
    provides:
      properties:
        - kafka.brokers
        - peakmart.topic
        - peakmart.group-id
        - peakmart.auto-offset-reset
        - peakmart.email-pattern
        - schemas.dir
        - sql.dir
        - order-check.poll-delay-ms
        - order-check.batch-size
        - catalog.base-url
        - catalog.timeout-ms
        - catalog.max-calls-per-second
        - orderdesk.mailbox
        - notification.poll-delay-ms
        - orderdesk.outbox-dir
        - db.url
        - db.username
        - db.password
      beans:
        - orderDb
        - orderChecks
        - cannotReadReason
    consumes:
      routes:
        - order-db-schema-init
        - peakmart-order-intake
        - peakmart-order-check-poll
        - order-desk-notification-send
      beans:
        - OrderChecks
        - CannotReadReason
    dependsOn:
      - 2
      - 3
      - 4
      - 5
      - 6
      - 7
  - id: 9
    title: Generate Route YAML — product-catalog-stub
    files:
      creates:
        - prototype/stubs/product-catalog/product-catalog-stub.camel.yaml
      reads:
        - docs/api/external/product-catalog.openapi.yaml
        - docs/messages/examples/product/tent-2p.json
        - docs/messages/examples/product/stake-al8.json
        - docs/messages/examples/product/lamp-hd200.json
        - docs/messages/examples/product/tent-1p-inactive.json
        - docs/messages/examples/product/invalid/active-missing.json
    provides:
      routes:
        - product-catalog-get-product
      endpoints:
        - direct:getProduct
      externalServices:
        - product-catalog-8091
    consumes:
      routeContracts:
        - prototype-module-layout
    dependsOn:
      - 1
  - id: 10
    title: Generate Application Properties — product-catalog-stub
    files:
      creates:
        - prototype/stubs/product-catalog/application.properties
      reads:
        - prototype/stubs/product-catalog/product-catalog-stub.camel.yaml
    provides:
      properties:
        - camel.server.port
        - stub.mode
        - stub.examples-dir
    consumes:
      routes:
        - product-catalog-get-product
    dependsOn:
      - 9
  - id: 11
    title: Generate Main Run Script — product-catalog-stub
    files:
      creates:
        - prototype/stubs/product-catalog/run.sh
      reads:
        - prototype/stubs/product-catalog/product-catalog-stub.camel.yaml
        - prototype/stubs/product-catalog/application.properties
    provides:
      routeContracts:
        - stub-launch
    consumes:
      routes:
        - product-catalog-get-product
      properties:
        - camel.server.port
    dependsOn:
      - 9
      - 10
  - id: 12
    title: Generate Main Run Script — prototype
    files:
      creates:
        - prototype/run.sh
      reads:
        - prototype/peakmart-order-intake.camel.yaml
        - prototype/peakmart-order-check.camel.yaml
        - prototype/order-desk-notification.camel.yaml
        - prototype/db/order-db-schema-init.camel.yaml
        - prototype/OrderChecks.java
        - prototype/CannotReadReason.java
        - prototype/application.properties
    provides:
      routeContracts:
        - prototype-launch
    consumes:
      routes:
        - order-db-schema-init
        - peakmart-order-intake
        - peakmart-order-check-poll
        - order-desk-notification-send
      properties:
        - sql.dir
        - db.url
    dependsOn:
      - 2
      - 3
      - 4
      - 5
      - 6
      - 7
      - 8
  - id: 13
    title: Generate Acceptance Scenario Script — scenarios.sh
    files:
      creates:
        - prototype/scenarios.sh
      reads:
        - docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json
        - docs/messages/examples/peakmart-order-placed/pm-50117-redelivered.json
        - docs/messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json
        - docs/messages/examples/peakmart-order-placed/invalid/other-event-type.json
        - docs/messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json
        - docs/messages/examples/peakmart-order-placed/invalid/price-as-number.json
        - docs/messages/examples/peakmart-order-placed/invalid/event-id-missing.json
        - docs/messages/examples/order/from-peakmart-order.json
        - docs/messages/examples/order-desk-notification/pm-50118.json
      tests:
        - prototype/scenarios.sh
    provides:
      testData:
        - scenarios-1-6
    consumes:
      routes:
        - peakmart-order-intake
        - peakmart-order-check-poll
        - order-desk-notification-send
      properties:
        - peakmart.topic
        - kafka.brokers
        - db.url
        - orderdesk.outbox-dir
      routeContracts:
        - prototype-launch
        - stub-launch
      externalServices:
        - kafka-9092
        - postgresql-15432
    dependsOn:
      - 8
      - 11
      - 12
```

---

### Task 1: Scaffold Project Structure

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/` (Target Module, Main runtime: `MODULE_DIR = ROUTE_DIR = PROPS_DIR = prototype/`)
- Create: `prototype/sql/`, `prototype/db/`
- Create: `prototype/stubs/product-catalog/` (second Main module, Target Module `prototype/stubs/product-catalog`)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` (file path table, Main column)

**MCP Tools:** none. Main runtime has no POM and needs no catalog lookup to create directories.

**Design Spec Section:** Section 6 (Project Structure); the Target Module fields in Section 3

- [ ] **Step 1:** Resolve the paths with the orchestrator Main column. Use `{MODULE_PREFIX}` = `prototype/` for the flows and `prototype/stubs/product-catalog/` for the stub.
- [ ] **Step 2:** Create the directories `prototype/`, `prototype/sql/`, `prototype/db/` and `prototype/stubs/product-catalog/`. Create no files in them yet.
- [ ] **Step 3:** Do NOT create `pom.xml`, `src/test/resources/`, `docker-compose.yaml` or any Maven wrapper changes. The runtime is Main, and the design excludes Citrus tests and Compose (planning decisions P-1 and P-2).
- [ ] **Step 4:** Verify: `test -d prototype/sql && test -d prototype/db && test -d prototype/stubs/product-catalog && test ! -e prototype/pom.xml && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: the directory tree matches §6. There is no `src/test/resources`, no POM and no Compose file.
- [ ] Code quality: the paths are project-relative and nothing under `docs/` was touched.

---

### Task 2: Order Database Schema and Schema-Init Route

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/sql/schema.sql`
- Create: `prototype/db/order-db-schema-init.camel.yaml`
- Read: `docs/entity_model.md` (table and column names, validation-rule CHECKs)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (version binding)
- `camel_catalog_component_doc(component="timer", B)` (option `repeatCount`)
- `camel_catalog_component_doc(component="sql", B)` (query `file:` prefix, `dataSource`)
- `camel_catalog_eip_doc(eip="log", B)`, `camel_catalog_eip_doc(eip="to", B)`
- `camel_validate_route(uri=<each endpoint URI>, route=<full file content>, B)`

**Design Spec Section:** Section 3, "Supporting step: order-db-schema-init (one-shot)"; Section 3 (Not Doing, which lists the tables that are not created)

- [ ] **Step 1:** Write `prototype/sql/schema.sql` as **one** PostgreSQL `DO $$ … $$` block of `CREATE TABLE IF NOT EXISTS` / `CREATE UNIQUE INDEX IF NOT EXISTS` statements, so that it is a single statement and safe to repeat. Create exactly these tables, with names and columns from `docs/entity_model.md`: `order_receipt`, `order_state_change`, `rejection_reason`, `notification`, `customer_order`, `order_line`, `set_aside_order_event`. `order_receipt` must also have `waiting_for`, `waiting_order`, `partner_id`, `content_hash`, `checking_since` and `correlation_id`.
- [ ] **Step 2:** Add the constraints listed in the design:
  - partial unique index `order_receipt(order_number) WHERE channel='Peakmart'`
  - unique `order_receipt.correlation_id`
  - unique `order_state_change(order_receipt_id, order_state)`
  - unique `rejection_reason(order_receipt_id, position)`
  - unique `notification.order_receipt_id`
  - unique `customer_order.order_number` and unique `customer_order.order_receipt_id`
  - unique `order_line(customer_order_id, line_number)`
  - unique `set_aside_order_event.event_id`
  - the CHECK constraints from the entity model's validation rules
- [ ] **Step 3:** Do NOT create `partner`, `pick_list`, `alert`, `alerted_order` or `order_event_resubmission` (Not Doing). The file must contain no `#` character and no `:name` token, because camel-sql would read them as parameters. Check this with `grep -nE '#|:[a-zA-Z_]' prototype/sql/schema.sql`. The only acceptable matches are PostgreSQL `::` casts. If one appears, remove it or prove it is a `::` cast.
- [ ] **Step 4:** Generate `prototype/db/order-db-schema-init.camel.yaml` with:
  - route id `order-db-schema-init` and a description
  - `from timer:order-db-schema-init?repeatCount=1`
  - `to sql:file:{{sql.dir}}/schema.sql?dataSource=#orderDb`
  - a log line with no body
  
  Hard-code no values. Verify every option through MCP first.
- [ ] **Step 5:** Run the `route-validation.md` loop (`camel_validate_route`, max 3 attempts).
- [ ] **Step 6:** Verify: `test -s prototype/sql/schema.sql && test -s prototype/db/order-db-schema-init.camel.yaml && grep -c "CREATE TABLE IF NOT EXISTS" prototype/sql/schema.sql` prints `7`.

**Review:**
- [ ] Spec compliance: the 7 tables and every listed constraint exist, the excluded tables are absent, and the script is one repeatable `DO` block.
- [ ] Code quality: constitution rules 1–7 (routeId, description, `{{placeholders}}`), no `#` or `:name` tokens in the SQL, Kaoto-compatible YAML.

---

### Task 3: Java Bean CannotReadReason

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/CannotReadReason.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md` (bean resolution under `camel run`)
- `shared/camel-security-checklist.md` (no personal data in stored or logged reasons)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="json-validator", B)` (exception and validator-library facts)
- `camel_catalog_component_maven(component="json-validator", B)` (resolve the validator library artifact so that its error accessors can be checked)

**Design Spec Section:** Section 3, Flow peakmart-order-intake, route 2 `peakmart-event-read` (doCatch) and route 3; Section 4 Security; design review item 4; assumption A-16

- [ ] **Step 1:** Create the public class `CannotReadReason` in the default package. Give it a no-arg constructor and a method `of(Exception exception)` that returns a `String`.
- [ ] **Step 2:** When the exception is (or wraps) `org.apache.camel.component.jsonvalidator.JsonValidationException`, take the **first** error in the order the validator returns them and return only `<path>: <keyword>` (for example `$.buyer.email: format`). Use the instance-location path and the keyword accessors of the resolved validator library version. Check those accessors against the resolved artifact; do not assume them.
- [ ] **Step 3:** Never return, log or include the validator's message or any value from the event. When there are no errors or the accessors yield nothing, return the fixed text `unreadable` and do not fall back to the message.
- [ ] **Step 4:** Verify: `test -s prototype/CannotReadReason.java && grep -q "class CannotReadReason" prototype/CannotReadReason.java && ! grep -qiE "getMessage\(\)" prototype/CannotReadReason.java && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: the output has the form `<path>: <keyword>` from the first error only. The validator message is never used (design review item 4).
- [ ] Code quality: no personal data, null-safe, no logging of exception text, compiles under `camel run` (proved by the runtime smoke in camel-execute).

---

### Task 4: Java Bean OrderChecks

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/OrderChecks.java`
- Read: `docs/messages/order.schema.json`, `docs/messages/rejection-reason.schema.json`, `docs/messages/examples/order-desk-notification/pm-50118.json`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="bean", B)` (method invocation and parameter binding)

**Design Spec Section:** Section 3, Flow peakmart-order-check, route 2 `order-check` (bean steps), the "Rejection Reason texts" table, and the rationale for using a Java bean

- [ ] **Step 1:** Create the public class `OrderChecks` in the default package with a no-arg constructor.
- [ ] **Step 2:** Implement `structuralReasons(order)`. It returns an ordered list of Rejection Reasons shaped like `rejection-reason.schema.json` (`orderCheck`, optional `lineNumber`, `text`), in this check order:
  - `order-number-format` (`^PM-\d+$`)
  - `number-of-order-lines` (1–30)
  - `quantity` per line (1–20)
  - `shipping-address`, one reason per missing part, in the order recipient name, street line, city, postal code, country
  - `destination-country` (US/CA)
- [ ] **Step 3:** Implement `evaluate(order, structuralReasons, catalogAnswers)`. It returns `{outcome, reasons, order}` with these rules:
  - Any answer `OUTAGE` gives `CATALOG_OUTAGE`, even when other checks failed (BR-012).
  - Otherwise, append one `sku` reason per line whose product is `UNKNOWN` or inactive, after the structural reasons.
  - A non-empty reason list gives `REJECTED`.
  - Otherwise the outcome is `PASSED`, with each line completed with `productName` and `unitWeightGrams` from the catalog product. Keep the Peakmart price.
- [ ] **Step 4:** Use the reason texts **exactly** as in the design's "Rejection Reason texts" table. The inactive-SKU text must equal the text in `pm-50118.json`. The texts contain no names, emails or addresses.
- [ ] **Step 5:** Verify: `test -s prototype/OrderChecks.java && grep -q "class OrderChecks" prototype/OrderChecks.java && grep -q "structuralReasons" prototype/OrderChecks.java && grep -q "evaluate" prototype/OrderChecks.java && grep -q "is not an Active Product in the Product Catalog" prototype/OrderChecks.java && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: all eight rules are implemented in check order, the outage precedence holds, the reason texts match the table, and the Peakmart price is kept.
- [ ] Code quality: constitution rule 3 (business logic in a bean), no personal data in reasons, deterministic ordering, plain Java that can move to Spring Boot unchanged.

---

### Task 5: Generate Route YAML — peakmart-order-intake

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/peakmart-order-intake.camel.yaml` (routes `peakmart-order-intake`, `peakmart-event-read`, `peakmart-event-set-aside`, `peakmart-order-receive`; inline Groovy DataMapper `kaoto-datamapper-5c1e9a27`)
- Create: `prototype/sql/receipt-insert.sql`
- Create: `prototype/sql/set-aside-insert.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` (Step 1 DataMapper, then Step 2)
- `camel-implement/guides/datamapper-validation.md`
- `camel-implement/guides/datamapper-groovy.md` (Transformation Engine: Groovy, inline)
- `shared/datamapper-canonicalize.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/advanced-patterns.md` (the design has Security/Performance sections)
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", includeHeaders=true, includeOptions="all", B)`: `brokers`, `groupId`, `autoOffsetReset`, `breakOnFirstError`, `autoCommitEnable`, `allowManualCommit`; headers `CamelKafkaManualCommit`, `CamelKafkaPartition`, `CamelKafkaOffset`
- `camel_catalog_component_doc(component="json-validator", B)`
- `camel_catalog_component_doc(component="sql", includeHeaders=true, B)`: header `CamelSqlUpdateCount`
- `camel_catalog_component_doc(component="direct", B)`
- `camel_catalog_component_doc(component="bean", B)`
- `camel_catalog_eip_doc(eip=…, B)` for each of `setProperty`, `doTry`, `choice`, `script`, `bean`, `setBody`, `log`, `onException`, `to`
- `camel_catalog_language_doc(language=…, B)` for each of `simple`, `jsonpath` (option `suppressExceptions`), `groovy`
- `camel_validate_route(uri=<each endpoint URI>, route=<full file content>, B)`

**Design Spec Section:** Section 3, Flow: peakmart-order-intake (Source, Routes 1–4, DataMapper `kaoto-datamapper-5c1e9a27`, Error Handling, Configuration Properties); Section 4 Security; design review items 2, 3 and 4

- [ ] **Step 1:** Read design §3 for peakmart-order-intake and `docs/messages/mappings/peakmart-order-placed-to-order.md`. Where the mapping file and the design's table differ, the mapping file wins.
- [ ] **Step 2:** DataMapper (orchestrator Step 1, Groovy branch). Generate the inline Groovy `setBody` that maps `peakmart-order-placed` to the canonical Order:
  - all 18 field mappings, the conditional mappings and the `items[]`→`lines[]` collection mapping
  - parameters `correlation-id` and `received-at` (UTC now, ISO-8601)
  - `streetLine` joined from the non-empty parts with `", "`
  - `stateRegion` only when present and non-empty
  - `unitPrice.currency` = `USD`
  - no product fields
  
  Run the datamapper-validation pre- and post-checks against `docs/messages/order.schema.json` (product fields are absent at intake).
- [ ] **Step 3:** Route `peakmart-order-intake`:
  - The source is `kafka:{{peakmart.topic}}` with `brokers={{kafka.brokers}}`, `groupId={{peakmart.group-id}}`, `autoOffsetReset={{peakmart.auto-offset-reset}}`, `breakOnFirstError=true`, `autoCommitEnable=false` and `allowManualCommit=true`.
  - Set properties `correlation-id` (`${uuid}`) and `kafka-position` (`${header.CamelKafkaPartition}-${header.CamelKafkaOffset}`).
  - Add a `doTry` that sets property `event-type` with jsonpath `$.event_type` (`suppressExceptions=true`). Its `doCatch java.lang.Exception` sets `not-json=true`. An empty value counts as not JSON. If `suppressExceptions` also hides parse errors, add a separate jsonpath `$` with `suppressExceptions=false` inside the same `doTry`.
  - Add a `choice` with three branches:
    - `not-json`: set `cannot-read-reason=not JSON`, then `direct:peakmart-event-set-aside`.
    - `event-type == 'order.placed'`: `direct:peakmart-event-read`.
    - `otherwise`: DEBUG log of `kafka-position` only.
  - The **last** step is a `script` (simple) `${header.CamelKafkaManualCommit.commit()}`.
- [ ] **Step 4:** Route `peakmart-event-read` (direct) has two top-level steps:
  - A `doTry` that calls `to json-validator:file:{{schemas.dir}}/peakmart-order-placed.schema.json`. Its `doCatch org.apache.camel.component.jsonvalidator.JsonValidationException` calls the `bean` `cannotReadReason` method `of(${exception})`, puts the result into `cannot-read-reason`, sets `readable=false` and calls `direct:peakmart-event-set-aside`.
  - A `choice`:
    - not readable: do nothing.
    - `$.buyer.email` is present and does not match `{{peakmart.email-pattern}}` (jsonpath with `suppressExceptions=true` plus simple `regex`): set `cannot-read-reason=$.buyer.email: format`, then call set-aside.
    - `otherwise`: run the DataMapper `setBody`, then call `direct:peakmart-order-receive`.
- [ ] **Step 5:** Route `peakmart-event-set-aside` (direct):
  - `to sql:file:{{sql.dir}}/set-aside-insert.sql?dataSource=#orderDb`
  - then a WARN log `Order event set aside eventId=… orderNumber=… reason=…` (no personal data)
  
  Write `prototype/sql/set-aside-insert.sql` as `INSERT … ON CONFLICT (event_id) DO NOTHING` with named parameters for these columns:
  - `event_id`: the event id, or `kafka-<partition>-<offset>` when the id is unreadable or the record is not JSON
  - `order_number`: only when readable
  - `event_content`: the raw record as text, or an empty string for an empty value
  - `cannot_read_reason`
  - `arrived_at`
  - state `Set Aside`
- [ ] **Step 6:** Route `peakmart-order-receive` (direct):
  - `to sql:file:{{sql.dir}}/receipt-insert.sql?dataSource=#orderDb`
  - a `choice` on `CamelSqlUpdateCount`: `== 0` logs INFO `Duplicate Peakmart order skipped orderNumber=… eventId=… correlationId=…`; otherwise log INFO `Peakmart order received orderNumber=… correlationId=…`
  
  Write `prototype/sql/receipt-insert.sql` as the single CTE from the design:
  - Insert into `order_receipt` with `order_state='Received'`, `waiting_for='Check'`, `waiting_order=:#order` and `content_hash=encode(sha256(convert_to(:#order,'UTF8')),'hex')`.
  - Use `ON CONFLICT (order_number) WHERE channel='Peakmart' DO NOTHING RETURNING id, received_at`.
  - Then insert `order_state_change(Received)` from the returned row.
- [ ] **Step 7:** Error handling: add a route-scoped `onException` for `java.sql.SQLException` and `org.springframework.dao.DataAccessException`:
  - `maximumRedeliveries=3`, `redeliveryDelay=1000`, `useExponentialBackOff=true`, `backOffMultiplier=2`
  - `logExhaustedMessageBody=false`, `logExhaustedMessageHistory=false`
  - **not handled**, so the manual commit is never reached
  
  There is no dead-letter channel.
- [ ] **Step 8:** Give every route an `id` and a `description`. No route logs a body. Every host, path and pattern is a `{{property}}`.
- [ ] **Step 9:** Run the `route-validation.md` loop on every endpoint URI.
- [ ] **Step 10:** Verify: `test -s prototype/peakmart-order-intake.camel.yaml && test -s prototype/sql/receipt-insert.sql && test -s prototype/sql/set-aside-insert.sql && grep -c "id: peakmart-" prototype/peakmart-order-intake.camel.yaml` prints a count of at least `4`. Also, `grep -q "CamelKafkaManualCommit.commit()" prototype/peakmart-order-intake.camel.yaml && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: four routes with the exact IDs; the Kafka options from design review item 2; the commit is the last step of `peakmart-order-intake`; non-JSON records are set aside as `kafka-<p>-<o>` (item 3); the reason is `<path>: <keyword>` only (item 4); the explicit email check; the DataMapper matches the mapping file; both SQL statements are atomic and idempotent; the onException is not handled.
- [ ] Code quality: constitution rules 1–7; no body or personal data in logs; `logExhausted*` flags are false; Kaoto-compatible YAML; no hard-coded values; named SQL parameters only.

---

### Task 6: Generate Route YAML — peakmart-order-check

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/peakmart-order-check.camel.yaml` (routes `peakmart-order-check-poll`, `order-check`, `product-lookup`, `order-reject`, `order-record`)
- Create: `prototype/sql/waiting-select.sql`, `prototype/sql/order-reject.sql`, `prototype/sql/order-record.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/advanced-patterns.md` (throttle, split/aggregation)
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", includeHeaders=true, includeOptions="all", B)`: consumer `delay`, `maxMessagesPerPoll`, `dataSource`
- `camel_catalog_component_doc(component="http", includeHeaders=true, includeOptions="all", B)`: `throwExceptionOnFailure`, `connectTimeout`, `responseTimeout`, `connectionRequestTimeout`; headers `CamelHttpResponseCode`, `CamelHttpPath`
- `camel_catalog_component_doc(component="json-validator", B)`, `camel_catalog_component_doc(component="direct", B)`, `camel_catalog_component_doc(component="bean", B)`
- `camel_catalog_eip_doc(eip=…, B)` for each of `setProperty`, `setBody`, `setHeader`, `bean`, `split` (option `aggregationStrategy`), `throttle` (options `mode`, `timePeriodMillis`), `doTry`, `choice`, `log`, `onException`, `to`
- `camel_catalog_language_doc(language=…, B)` for each of `simple`, `jsonpath`
- `camel_validate_route(uri=<each endpoint URI>, route=<full file content>, B)`

**Design Spec Section:** Section 3, Flow: peakmart-order-check (Source, Routes 1–5, Rejection Reason texts, Error Handling, Configuration Properties)

- [ ] **Step 1:** Write `prototype/sql/waiting-select.sql`. It selects `order_receipt` rows with `waiting_for = 'Check'`, ordered oldest first by `received_at`, and returns the id, order number, correlation id and `waiting_order`. It ignores `waiting_for='Recording'`.
- [ ] **Step 2:** Route `peakmart-order-check-poll`:
  - from `sql:file:{{sql.dir}}/waiting-select.sql?dataSource=#orderDb&delay={{order-check.poll-delay-ms}}&maxMessagesPerPoll={{order-check.batch-size}}`
  - set properties `correlation-id`, `receipt-id` and `order-number` from the row
  - set the body to `waiting_order`
  - `to direct:order-check`
- [ ] **Step 3:** Route `order-check` (direct) has four top-level steps:
  1. `bean orderChecks` method `structuralReasons`, with the result in property `structural-reasons`.
  2. `split` on jsonpath `$.lines` with `aggregationStrategy=org.apache.camel.processor.aggregate.GroupedBodyAggregationStrategy`, run sequentially, calling `direct:product-lookup`. Check that this class exists in camel-core 4.22.2 before you use it.
  3. `bean orderChecks` method `evaluate`.
  4. A `choice` on the outcome:
     - `CATALOG_OUTAGE`: WARN `Peakmart order waits for Product Catalog orderNumber=… correlationId=…`, and write nothing.
     - `REJECTED`: `direct:order-reject`.
     - `PASSED`: `direct:order-record`.
- [ ] **Step 4:** Route `product-lookup` (direct):
  - `throttle` with `mode=TotalRequests`, expression `{{catalog.max-calls-per-second}}` and `timePeriodMillis=1000`. This one node is shared by every check and re-check.
  - `setHeader CamelHttpPath` = `/products/<URL-encoded sku>`
  - A `doTry` around `to http:{{catalog.base-url}}?throwExceptionOnFailure=false&connectTimeout={{catalog.timeout-ms}}&responseTimeout={{catalog.timeout-ms}}&connectionRequestTimeout={{catalog.timeout-ms}}`, followed by a `choice` on `CamelHttpResponseCode`:
    - `200`: validate with `json-validator:file:{{schemas.dir}}/product.schema.json`. A pass gives `{lineNumber, sku, status: FOUND, product}`. A `doCatch JsonValidationException` gives `OUTAGE`.
    - `404`: `UNKNOWN`.
    - otherwise: `OUTAGE`.
    
    A `doCatch java.io.IOException` gives `OUTAGE`.
  - Remove the inbound HTTP headers after the call, following yaml-catalog-rules Rule 0e where it applies.
- [ ] **Step 5:** Route `order-reject` (direct):
  - `to sql:file:{{sql.dir}}/order-reject.sql?dataSource=#orderDb`
  - then INFO `Peakmart order rejected orderNumber=… reasons=<count> correlationId=…`
  
  Write `prototype/sql/order-reject.sql` as **one** CTE that does all of the following:
  - updates the receipt to `Rejected`, guarded by `AND order_state='Received'`, and clears `waiting_for` and `waiting_order`
  - inserts `order_state_change(Rejected)`
  - inserts every `rejection_reason` with its `position` from `jsonb_to_recordset(CAST(:#reasons AS jsonb))`
  - inserts `notification` with type `Order Desk Notification`, recipient `:#mailbox` (bound from `{{orderdesk.mailbox}}`) and `attempts = 0`
  
  Use `CAST(… AS …)`, not `::`, wherever there is a named parameter.
- [ ] **Step 6:** Route `order-record` (direct):
  - `to json-validator:file:{{schemas.dir}}/order.schema.json` as a guard. A failure is a defect: log ERROR with order number and correlation id only, write nothing, and the order stays waiting.
  - `to sql:file:{{sql.dir}}/order-record.sql?dataSource=#orderDb`
  - INFO `Peakmart order recorded orderNumber=… lines=… correlationId=…`
  
  Write `prototype/sql/order-record.sql` as **one** CTE that:
  - inserts `customer_order`
  - inserts every `order_line` with `jsonb_to_recordset` over `lines`, using `unit_price` = the Peakmart price
  - updates the receipt to `Recorded`, guarded by `order_state='Received'`, and clears the waiting fields
  - inserts `order_state_change(Recorded)`
- [ ] **Step 7:** Error handling: add a route-scoped `onException` for `java.sql.SQLException` and `org.springframework.dao.DataAccessException`:
  - `maximumRedeliveries=2`, `redeliveryDelay=1000`, `useExponentialBackOff=true`
  - **handled**
  - a WARN log with order number and correlation id only
  - `logExhaustedMessageBody=false`
  
  A catalog outage is NOT an exception path. Use no circuit breaker and no `transacted`.
- [ ] **Step 8:** Give every route an `id` and a `description`. Every value is a `{{property}}`. Run the `route-validation.md` loop.
- [ ] **Step 9:** Verify: `test -s prototype/peakmart-order-check.camel.yaml && test -s prototype/sql/waiting-select.sql && test -s prototype/sql/order-reject.sql && test -s prototype/sql/order-record.sql && grep -q "GroupedBodyAggregationStrategy" prototype/peakmart-order-check.camel.yaml && grep -q "TotalRequests" prototype/peakmart-order-check.camel.yaml && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: five routes with the exact IDs; the checks run in UC-003 order; the outage precedence (BR-012); 200/404/other/IOException map to FOUND/UNKNOWN/OUTAGE; schema-invalid 200 responses count as OUTAGE; one shared throttle at 20/s; the 2 s timeouts; reject and record are each one atomic guarded CTE; nothing is written on CATALOG_OUTAGE.
- [ ] Code quality: constitution rules 1–7; no body or personal data in logs; named parameters safe against `::` conflicts; Kaoto-compatible YAML; no hard-coded host or port.

---

### Task 7: Generate Route YAML — order-desk-notification

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/order-desk-notification.camel.yaml` (route `order-desk-notification-send`)
- Create: `prototype/sql/notification-select.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", includeOptions="all", B)`: `delay`, `onConsume`, `onConsumeFailed`, `dataSource`
- `camel_catalog_component_doc(component="file", B)`: `fileName`, `fileExist`
- `camel_catalog_eip_doc(eip=…, B)` for each of `setProperty`, `setBody`, `log`, `to`
- `camel_catalog_language_doc(language="simple", B)`
- `camel_validate_route(uri=<each endpoint URI>, route=<full file content>, B)`

**Design Spec Section:** Section 3, Flow: order-desk-notification (Source, Transformation, Sink, Error Handling, Configuration Properties)

- [ ] **Step 1:** Write `prototype/sql/notification-select.sql`. It selects `notification` rows with `sent_at IS NULL` and type `Order Desk Notification`, joins the receipt (order number, correlation id), and aggregates the reasons with `string_agg(… ORDER BY position)`, keeping each reason's line number.
- [ ] **Step 2:** Route `order-desk-notification-send`:
  - from `sql:file:{{sql.dir}}/notification-select.sql?dataSource=#orderDb&delay={{notification.poll-delay-ms}}&onConsume=…&onConsumeFailed=…`
  - `onConsume` sets `sent_at=now(), attempts=attempts+1` by notification id
  - `onConsumeFailed` sets `attempts=attempts+1`
  - set property `order-number`
- [ ] **Step 3:** Use simple `setBody` to render plain English text containing only:
  - `To: <mailbox>`
  - `Subject: Peakmart order <n> rejected`
  - a blank line
  - `Order number: <n>`
  - one line per reason: `- [Order Line i] <text>`, with the bracket part only when there is a line number
  
  Include no name, email or address.
- [ ] **Step 4:** Sink: `to file:{{orderdesk.outbox-dir}}?fileName=peakmart-order-${exchangeProperty.order-number}-rejected.txt&fileExist=Override`. Then an INFO log `Order Desk notified orderNumber=… correlationId=…`. Add no alerting.
- [ ] **Step 5:** Give the route an `id` and a `description`. Run the `route-validation.md` loop.
- [ ] **Step 6:** Verify: `test -s prototype/order-desk-notification.camel.yaml && test -s prototype/sql/notification-select.sql && grep -q "fileExist" prototype/order-desk-notification.camel.yaml && grep -q "onConsumeFailed" prototype/order-desk-notification.camel.yaml && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: outbox polling with `sent_at IS NULL`; onConsume and onConsumeFailed update as specified; the file name pattern and `Override`; the content is limited to the Order Number and reasons (C-023).
- [ ] Code quality: constitution rules 1–7; no personal data; placeholders for the directory and interval; Kaoto-compatible YAML.

---

### Task 8: Generate Application Properties — prototype

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/application.properties`

**Guides to Load:**
- `camel-implement/guides/properties-generation.md`
- `shared/forage.md` (Configuration Ladder; rung-3 custom bean with a one-line reason comment)
- `shared/camel-security-checklist.md` (credential overrides)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", B)`, `camel_catalog_component_doc(component="sql", B)`, `camel_catalog_component_doc(component="http", B)` (only when `camel.component.*` keys are emitted)
- `camel_catalog_component_maven(component=…, B)` for `kafka`, `sql`, `http`, `json-validator`, `file`, `timer`, `bean`, `direct`
- `camel_catalog_language_doc(language="groovy", B)` and `camel_catalog_language_doc(language="jsonpath", B)` (Maven coordinates)
- `camel_configuration_validate(properties=<file content>, B)` (gate in properties-generation §5.4)

**Design Spec Section:** Section 3 "Configuration Properties" of peakmart-order-intake, peakmart-order-check and order-desk-notification; Section 4 Constraints (datasource bean, db properties) and Security

- [ ] **Step 1:** Write every key from the three flow sections and from §4, with the design values:
  - `kafka.brokers`, `peakmart.topic`, `peakmart.group-id`, `peakmart.auto-offset-reset`, `peakmart.email-pattern`, `schemas.dir`, `sql.dir`
  - `order-check.poll-delay-ms`, `order-check.batch-size`, `catalog.base-url`, `catalog.timeout-ms`, `catalog.max-calls-per-second`, `orderdesk.mailbox`
  - `notification.poll-delay-ms`, `orderdesk.outbox-dir`
  - `db.url`, `db.username`, `db.password`
  
  Group them by flow with comments.
- [ ] **Step 2:** Make the database credentials overridable from the environment (`DB_USERNAME`, `DB_PASSWORD`), using the Main runtime's property-placeholder syntax that properties-generation.md allows. Inside this file, reference other properties as `{{key}}`, never `${key}`.
- [ ] **Step 3:** Beans:
  - `camel.beans.orderDb=#class:org.postgresql.ds.PGSimpleDataSource` with `url`, `user` and `password` bound from `{{db.url}}`, `{{db.username}}` and `{{db.password}}`. Add a one-line reason comment: Forage is unavailable (no `forage.version`, and Camel 4.22.2 is not in the shipped mapping). Check the `PGSimpleDataSource` setters against the resolved pgjdbc version.
  - The beans `orderChecks` (`#class:OrderChecks`) and `cannotReadReason` (`#class:CannotReadReason`), each with a reason comment.
- [ ] **Step 4:** Main only: `camel.jbang.dependencies` lists the Camel artifacts the routes need and `org.postgresql:postgresql`. Pin pgjdbc to the version that the Camel 4.22.2 build manages, resolved during this task; do not guess it. Do not create a POM.
- [ ] **Step 5:** Run `camel_configuration_validate` on the file and fix any finding (properties-generation §5.4 gate).
- [ ] **Step 6:** Verify: `test -s prototype/application.properties && grep -q "^peakmart.topic=peakmart.orders.v2" prototype/application.properties && grep -q "^catalog.base-url=http://localhost:8091" prototype/application.properties && grep -q "^camel.beans.orderDb=" prototype/application.properties && grep -q "^camel.jbang.dependencies" prototype/application.properties && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: every design property is present with the design value; the datasource is a rung-3 bean with a reason comment; the bean names match the route references (`orderDb`, `orderChecks`, `cannotReadReason`); no port 8080, 8081 or 5432.
- [ ] Code quality: no invented `camel.component.*` keys; Main `{{}}` interpolation; credentials overridable; validation gate passed.

---

### Task 9: Generate Route YAML — product-catalog-stub

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/stubs/product-catalog/product-catalog-stub.camel.yaml` (route `product-catalog-get-product`, contract-first `rest` with `openApi`)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` (Target Module `prototype/stubs/product-catalog`)
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)`
- `camel_catalog_component_doc(component="direct", B)`
- `camel_catalog_eip_doc(eip=…, B)` for each of `rest` (option `openApi`), `setBody`, `setHeader`, `choice`, `delay`, `log`
- `camel_catalog_language_doc(language="groovy", B)`, `camel_catalog_language_doc(language="simple", B)`
- `camel_validate_route(uri=<each endpoint URI>, route=<full file content>, B)`

**Design Spec Section:** Section 3, "Supporting app: product-catalog-stub" (contract-first REST, fault modes, fallback); assumption A-3; design review item 1

- [ ] **Step 1:** Declare `rest` with `openApi` `specification: ../../../docs/api/external/product-catalog.openapi.yaml`, used as is and never copied. Implement operation `getProduct` with `direct:getProduct`. Do not use `missingOperation: mock`.
- [ ] **Step 2:** Route `product-catalog-get-product` (from `direct:getProduct`). A Groovy expression reads `{{stub.examples-dir}}` and returns the example whose `sku` equals the path parameter, with status 200 and `Content-Type: application/json`. When nothing matches, return 404 with no body. Serve all four product examples unchanged.
- [ ] **Step 3:** Fault modes are selected by `{{stub.mode}}`:
  - `normal`
  - `invalid`: serve `invalid/active-missing.json` for every SKU
  - `slow`: delay 3 s before answering
- [ ] **Step 4:** Fallback, only if the OpenAPI loader cannot resolve the spec's external refs during validation or the smoke run: a plain `rest: get /products/{sku}` that calls the same route. Record which variant was used.
- [ ] **Step 5:** Give the route an `id` and a `description`. Run the `route-validation.md` loop.
- [ ] **Step 6:** Verify: `test -s prototype/stubs/product-catalog/product-catalog-stub.camel.yaml && grep -q "getProduct" prototype/stubs/product-catalog/product-catalog-stub.camel.yaml && grep -q "stub.mode" prototype/stubs/product-catalog/product-catalog-stub.camel.yaml && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: contract-first REST with the spec used in place; 200 or 404 (never 204) for unknown SKUs; the three fault modes; all four examples served.
- [ ] Code quality: constitution rules 1–7; placeholders for the directory and mode; no path traversal from the `sku` parameter (match by the `sku` field, never build a file path from input).

---

### Task 10: Generate Application Properties — product-catalog-stub

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/stubs/product-catalog/application.properties`

**Guides to Load:**
- `camel-implement/guides/properties-generation.md` (platform-http port rule for Main: `camel.server.*`)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_maven(component="platform-http", B)`
- `camel_catalog_language_doc(language="groovy", B)`
- `camel_configuration_validate(properties=<file content>, B)`

**Design Spec Section:** Section 3, "Supporting app: product-catalog-stub"; Section 6 (stub `application.properties`)

- [ ] **Step 1:** Write:
  - `camel.server.enabled=true`
  - `camel.server.port=8091`
  - `stub.mode=normal`
  - `stub.examples-dir=../../../docs/messages/examples/product`
- [ ] **Step 2:** Main only: add `camel.jbang.dependencies` for the stub's needs (`platform-http`, the OpenAPI REST support, groovy). Never write `camel.component.platform-http.port`.
- [ ] **Step 3:** Run `camel_configuration_validate` and fix any finding.
- [ ] **Step 4:** Verify: `grep -q "^camel.server.port=8091" prototype/stubs/product-catalog/application.properties && grep -q "^stub.mode=" prototype/stubs/product-catalog/application.properties && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: port 8091 (design review item 1); `stub.mode` and `stub.examples-dir` present.
- [ ] Code quality: no non-existent platform-http options; validation gate passed.

---

### Task 11: Generate Main Run Script — product-catalog-stub

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/stubs/product-catalog/run.sh`

**Guides to Load:**
- `camel-implement/guides/run-script.md`

**MCP Tools:** none (the run-script guide is static).

**Design Spec Section:** Section 3, "Supporting app: product-catalog-stub"; Section 6

- [ ] **Step 1:** Inventory `ROUTE_FILES` = `product-catalog-stub.camel.yaml` and `XSL_FILES` = none.
- [ ] **Step 2:** Generate `run.sh` from the run-script guide template:
  - `set -euo pipefail`
  - `cd` to the script directory
  - a jbang check
  - `jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run product-catalog-stub.camel.yaml --properties=application.properties`
  
  Allow the `stub.mode` to be overridden at start (`normal`, `invalid`, `slow`) through a property argument. Its default is the value from properties.
- [ ] **Step 3:** `chmod +x prototype/stubs/product-catalog/run.sh`.
- [ ] **Step 4:** Verify: `test -x prototype/stubs/product-catalog/run.sh && bash -n prototype/stubs/product-catalog/run.sh && grep -q "product-catalog-stub.camel.yaml" prototype/stubs/product-catalog/run.sh && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: launches the stub only, on 8091, with a mode that can be chosen.
- [ ] Code quality: JBang alias, properties argument and strict shell flags as in the run-script guide.

---

### Task 12: Generate Main Run Script — prototype

**Agent:** implementation-engineer

**Files:**
- Create: `prototype/run.sh`

**Guides to Load:**
- `camel-implement/guides/run-script.md`

**MCP Tools:** none (the run-script guide is static).

**Design Spec Section:** Section 3 (all prototype flows and "Supporting step: order-db-schema-init"); Section 6

- [ ] **Step 1:** Inventory the module:
  - `ROUTE_FILES` = `peakmart-order-intake.camel.yaml`, `peakmart-order-check.camel.yaml`, `order-desk-notification.camel.yaml`
  - Java beans `OrderChecks.java` and `CannotReadReason.java`
  - `XSL_FILES` = none
  
  The schema-init route `db/order-db-schema-init.camel.yaml` runs separately.
- [ ] **Step 2:** Generate `run.sh` from the run-script guide (strict flags, `cd` to the script directory, jbang check). It runs two commands in order:
  1. The one-shot schema init: `jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run db/order-db-schema-init.camel.yaml --properties=application.properties --max-messages=1`. The script stops if this fails.
  2. The integration: a single `jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run` listing all three route files and both Java files, with `--properties=application.properties`.
- [ ] **Step 3:** The script starts no containers and uses no port 8080, 8081 or 5432. `chmod +x prototype/run.sh`.
- [ ] **Step 4:** Verify: `test -x prototype/run.sh && bash -n prototype/run.sh && grep -q "order-db-schema-init.camel.yaml" prototype/run.sh && grep -q "peakmart-order-intake.camel.yaml" prototype/run.sh && grep -q "peakmart-order-check.camel.yaml" prototype/run.sh && grep -q "order-desk-notification.camel.yaml" prototype/run.sh && grep -q "OrderChecks.java" prototype/run.sh && grep -q "CannotReadReason.java" prototype/run.sh && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: the schema is initialised before the integration starts (A-12), and the complete module is launched, not one flow.
- [ ] Code quality: JBang alias, properties argument and strict shell flags as in the guide; no container commands.

---

### Task 13: Generate Acceptance Scenario Script — scenarios.sh

**Agent:** implementation-engineer (the shipped `test-engineer` persona is Citrus-specific, and the approved design excludes Citrus; see P-1)

**Files:**
- Create: `prototype/scenarios.sh`

**Guides to Load:**
- `camel-test/guides/route-analysis.md` (identify the observable behaviours per route that the scenarios assert)
- `shared/camel-security-checklist.md` (printed output must not show personal data beyond what the scenario checks)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", B)` (producer options if a one-shot `camel` JBang publish is chosen)

**Design Spec Section:** Section 4, "Acceptance criteria and scenario approach" (scenarios 1–6); assumptions A-8 and A-15; design review item 5

- [ ] **Step 1:** Choose and record (in a header comment) how the script publishes to `peakmart.orders.v2` on the **already running** Kafka at `localhost:9092`, and how it queries PostgreSQL at `localhost:15432`. Allowed choices are one-shot `camel` JBang runs, or the client tools inside the running `camel infra` containers (A-15). The script must not start or stop containers, and must not use port 8080, 8081 or 5432.
- [ ] **Step 2:** Publish, in this order, with a wait for the poll interval between dependent steps:
  1. `peakmart-order-placed-sample.json`
  2. `pm-50117-redelivered.json`
  3. `pm-50118-inactive-sku.json`
  4. `invalid/other-event-type.json`
  5. `invalid/pm-50119-buyer-email-not-an-email.json`
  6. `invalid/price-as-number.json`, `invalid/event-id-missing.json`, and one literal non-JSON text record
  
  Read the examples in place from `docs/messages/examples/peakmart-order-placed/`.
- [ ] **Step 3:** Afterwards, print what the Order Database holds for these scenarios: receipts and states, state history, `customer_order` and `order_line` for PM-50117, rejection reasons for PM-50118, the `notification` row, and the `set_aside_order_event` keys and reasons. Also list `target/outbox/order-desk/` (the PM-50118 file and its subject line). Next to each section, print the expected result from design §4 so that the execution report can record pass or fail.
- [ ] **Step 4:** Use `set -euo pipefail`. Fail fast with a clear message if Kafka or PostgreSQL cannot be reached. `chmod +x prototype/scenarios.sh`.
- [ ] **Step 5:** Verify: `test -x prototype/scenarios.sh && bash -n prototype/scenarios.sh && grep -q "peakmart-order-placed-sample.json" prototype/scenarios.sh && grep -q "event-id-missing.json" prototype/scenarios.sh && ! grep -qE "docker (compose )?(run|up|start|stop)|:8080|:8081|:5432([^0-9]|$)" prototype/scenarios.sh && echo OK` prints `OK`.

**Review:**
- [ ] Spec compliance: covers scenarios 1–6 exactly, including the literal non-JSON record; prints the database state and outbox per scenario together with the expected results.
- [ ] Code quality: no container lifecycle commands, no forbidden ports, strict shell flags, idempotent output queries.

---

## Execution Notes for camel-execute (requirements from the approved design)

- Runtime verification is a Camel Main startup smoke against the **already running** Kafka (9092) and PostgreSQL (15432), plus the stub on 8091. Do not start containers (P-2). There is no Citrus suite (P-1).
- The execution report must record:
  - the results of scenarios 1–6 (from `prototype/scenarios.sh`)
  - scenarios 7–10, run by hand: stub stopped, stub `invalid` and `slow` modes, a structural failure while the stub is stopped, and an integration restart
  - the intake kill-and-restart test: kill the process between the receipt insert and the commit, restart, and expect the event to be consumed again and skipped as a Duplicate
  
  (Design §3 intake source; §4.)
- Facts to verify at implementation, with no guessing:
  - the `PGSimpleDataSource` setters
  - that `GroupedBodyAggregationStrategy` exists in camel-core
  - `KafkaManualCommit#commit()`
  - the `JsonValidationException` error accessors
  - whether jsonpath `suppressExceptions` hides parse errors (scenario 6)

## Plan review (developer, 2026-11-13)

- **Pin the Camel JBang version in both run scripts.** `jbang camel@apache/camel` runs the version named in the
  alias's script on GitHub, not the project's version: on this machine it starts Camel 4.22.0. Law 4 says the Camel
  version comes only from `.camel-kit/config.properties`, so every `camel@apache/camel` command in the run scripts and
  in `scenarios.sh` passes `-Dcamel.jbang.version=4.22.2` (the value of `project.camelVersion`), as written in Tasks 11
  and 12. This is a deliberate exception to the form in `run-script.md`; reviewers keep it.
