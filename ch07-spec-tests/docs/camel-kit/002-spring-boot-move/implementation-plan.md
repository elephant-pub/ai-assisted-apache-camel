---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T18:45:00Z"
  by: camel-plan
  from: design-spec.md
---
# Trailhead Order Integration — Spring Boot Move Implementation Plan

> **For agentic workers:** Use camel-execute to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the exported Spring Boot project start and behave exactly like the accepted Peakmart intake prototype.
The changes are: an SMTP Order Desk email instead of a file, a Flyway V1 schema, profile-based configuration that
refuses to start when a setting is missing, and a kill-test hook that is only on the test classpath.

**Architecture:** The routes stay as YAML DSL files under `src/main/resources/camel/` in a single Spring Boot 4.1.1
application at the repository root. Spring Boot owns the property files, the HikariCP datasource (`#dataSource`), the
Flyway migration and the Java beans (`IntegrationBeans`). SQL files and the three JSON schemas are loaded from the
classpath. The schemas are copied from `docs/messages/` at build time. The kill-test hook (`InterceptStrategy` on node
`commit-kafka-offset`) and the scenario and kill-test scripts live under `src/test/` and are not packaged.

**Tech Stack:** Apache Camel 4.22.2, Spring Boot 4.1.1, JDK 25, Camel Spring Boot BOM 4.22.2 (catalog
`org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2`). Components: kafka, sql, http, json-validator,
smtp (camel-mail-starter), direct, bean. Languages: simple, jsonpath, groovy, constant, bean. Flyway, HikariCP,
PostgreSQL JDBC (Boot-managed), JUnit (spring-boot-starter-test).

**Design Spec:** `docs/camel-kit/002-spring-boot-move/design-spec.md` (approved 2026-11-13, with design-review changes
R1–R4 applied)

---

## Planning Decisions and Assumptions (non-interactive run — recommended option taken)

| # | Decision | Reason |
|---|---|---|
| P-1 | **No Citrus test tasks.** The testing template was loaded but produces no tasks. | The approved design's `## Not Doing (and Why)` excludes Citrus tests in this pipeline. An exclusion listed there wins over the template default. The only test code is `EmailPatternTest` (Task 9) and the kill-test hook (Task 10), which the design explicitly allows. camel-execute's verification therefore has no Citrus suite to run. It runs the runtime acceptance checklist below instead. |
| P-2 | **No Docker Compose task.** | The services are already running (§2), and the design forbids starting or stopping containers (§2, §4 Constraints). §6 has no `docker-compose.yaml`. |
| P-3 | **No POM scaffold-from-template task.** Task 1 modifies the exported `pom.xml` and keeps its `spring-boot-starter-parent` 4.1.1 parent. | Design R1 (§3 Supporting change: the pom) is a reviewed exception to `pom-spring-boot.md` Rule "Do NOT use spring-boot-starter-parent". The pom guide is loaded for everything else. Reviewers must not report the parent as a finding. |
| P-4 | No "Verify Component Mappings" migration task. | This is a runtime move of Camel routes, not a vendor migration. Every component, EIP and language is already catalog-verified against the Spring Boot catalog (design §5 *Catalog Verification Evidence*). Each route task re-runs the catalog calls for the options it touches. `camel2-*`/`mule-*` guides do not apply. |
| P-5 | SQL resources (Task 2) and the Flyway migration (Task 3) are separate tasks that come before the route tasks. | Each can be verified on its own (a byte-for-byte `diff`, and a statement inventory). Three route tasks consume the SQL files. |
| P-6 | One consolidated properties task (Task 8) for the three `application*.properties` files, after all three route tasks. | All flows share the files. The profile split is one design unit (§3 Configuration). |
| P-7 | `EmailPatternTest`, the kill-test hook and the scripts use `implementation-engineer`, not `test-engineer`. | The shipped `test-engineer` persona only generates Citrus YAML tests. These are plain JUnit, Java config and shell. |
| P-8 | **Never apply V1 by hand.** No task may run `psql` DDL against the development database or create tables in it. | The development database is empty. If tables existed without Flyway history, Flyway would refuse to start (B-10). The first start during runtime verification applies V1. |
| P-9 | The no-profile "all 15 set" acceptance run counts as passing when the application gets past placeholder resolution, meaning no `Could not resolve placeholder` / `PlaceholderResolutionException` in the output. A later connection or TLS failure against placeholder values is recorded, not treated as a failure. | §3 Configuration says the 15 runs "need not reach the services". Without a profile, `verify-full` and STARTTLS cannot reach the local services, which have no TLS. |
| P-10 | No downstream artifacts exist yet (`execution-report.md`, `validation-report.md`), so staleness propagation after writing this plan is a no-op. | `shared/pipeline-infrastructure.md` cascade rules. |
| P-11 | There is no project graph (`.camel-kit/project-graph.json` is absent), so graph-context guides are not loaded. | Orchestrator Step 0 is conditional on that file. |

**Path resolution** (`camel-implement/guides/orchestrator.md` File Path Table, runtime `spring-boot`, Target Module =
repository root, so `{MODULE_PREFIX}` is empty): `ROUTE_DIR=src/main/resources/camel/`, `PROPS_DIR=src/main/resources/`,
`MODULE_DIR=` (project root). Design-specified non-table paths: `src/main/resources/sql/`,
`src/main/resources/db/migration/`, `src/main/java/com/trailheadgear/orderintegration/`,
`src/test/java/com/trailheadgear/orderintegration/`, `src/test/scripts/`, `prototype/run.sh`, `docs/open_questions.md`.

**Common MCP binding for every task (run once per task before any catalog detail call):**
`camel_catalog_components(limit=0, runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2")`.
The returned `camelVersion` must equal `4.22.2`. Below, "`B`" means
`runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2"`.

**Hard constraints for every task (design §2, §4 Constraints, Not Doing):**
- Do not start or stop containers. Do not use ports 8080, 8081 or 5432. Add no web server starter.
- Do not change routes, route ids, node ids, SQL text, reason texts, log lines or the beans `OrderChecks` /
  `CannotReadReason`, except where a task says so ("where this spec does not mention a route step, the step moves over
  unchanged").
- Do not modify `prototype/` except the one guard line in `prototype/run.sh` (Task 13). Do not modify `docs/` except
  `docs/open_questions.md` (Task 14) and this pipeline's artifacts.
- Never log message bodies or personal data. No secrets in route URIs.
- Do not implement anything listed in the design's `## Not Doing (and Why)`. That includes UC-001/UC-004, Citrus, the
  retention purge, alert emails, Kafka TLS/SASL, HTTPS to the catalog, multi-instance locking, HTTP endpoints, porting
  the stub, Dockerfile/Jib changes, and removing `prototype/`.

---

```yaml plan-metadata
tasks:
  - id: 1
    title: Platform Migration Setup — pom.xml
    files:
      modifies:
        - pom.xml
      reads:
        - .camel-kit/config.properties
        - docs/messages/peakmart-order-placed.schema.json
        - docs/messages/product.schema.json
        - docs/messages/order.schema.json
    provides:
      schemas:
        - classpath:schemas/peakmart-order-placed.schema.json
        - classpath:schemas/product.schema.json
        - classpath:schemas/order.schema.json
      routeContracts:
        - maven-dependencies-ready
    consumes: {}
    dependsOn: []
  - id: 2
    title: Move SQL Files to the Classpath
    files:
      creates:
        - src/main/resources/sql/receipt-insert.sql
        - src/main/resources/sql/set-aside-insert.sql
        - src/main/resources/sql/waiting-select.sql
        - src/main/resources/sql/order-reject.sql
        - src/main/resources/sql/order-record.sql
        - src/main/resources/sql/notification-select.sql
      reads:
        - prototype/sql/receipt-insert.sql
        - prototype/sql/set-aside-insert.sql
        - prototype/sql/waiting-select.sql
        - prototype/sql/order-reject.sql
        - prototype/sql/order-record.sql
        - prototype/sql/notification-select.sql
    provides:
      routeContracts:
        - classpath:sql/receipt-insert.sql
        - classpath:sql/set-aside-insert.sql
        - classpath:sql/waiting-select.sql
        - classpath:sql/order-reject.sql
        - classpath:sql/order-record.sql
        - classpath:sql/notification-select.sql
    consumes: {}
    dependsOn: []
  - id: 3
    title: Flyway Migration V1 — Order Database
    files:
      creates:
        - src/main/resources/db/migration/V1__order_database.sql
      reads:
        - prototype/sql/schema.sql
        - docs/camel-kit/001-peakmart-intake/design-spec.md
    provides:
      schemas:
        - order-db-schema-v1
    consumes: {}
    dependsOn: []
  - id: 4
    title: Adapt Java Sources — IntegrationBeans
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
      reads:
        - src/main/java/com/trailheadgear/orderintegration/CamelApplication.java
        - src/main/java/com/trailheadgear/orderintegration/OrderChecks.java
        - src/main/java/com/trailheadgear/orderintegration/CannotReadReason.java
    provides:
      beans:
        - orderChecks
        - cannotReadReason
    consumes:
      routeContracts:
        - maven-dependencies-ready
    dependsOn:
      - 1
  - id: 5
    title: Generate Route YAML — peakmart-order-intake
    files:
      modifies:
        - src/main/resources/camel/peakmart-order-intake.camel.yaml
      reads:
        - prototype/peakmart-order-intake.camel.yaml
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
      routeContracts:
        - node:commit-kafka-offset
    consumes:
      schemas:
        - classpath:schemas/peakmart-order-placed.schema.json
      routeContracts:
        - classpath:sql/receipt-insert.sql
        - classpath:sql/set-aside-insert.sql
      beans:
        - cannotReadReason
      externalServices:
        - kafka-9092
        - postgresql-15432
    dependsOn:
      - 1
      - 2
      - 4
  - id: 6
    title: Generate Route YAML — peakmart-order-check
    files:
      modifies:
        - src/main/resources/camel/peakmart-order-check.camel.yaml
      reads:
        - prototype/peakmart-order-check.camel.yaml
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
        - classpath:schemas/product.schema.json
        - classpath:schemas/order.schema.json
      routeContracts:
        - classpath:sql/waiting-select.sql
        - classpath:sql/order-reject.sql
        - classpath:sql/order-record.sql
      beans:
        - orderChecks
      externalServices:
        - postgresql-15432
        - product-catalog-8091
    dependsOn:
      - 1
      - 2
      - 4
  - id: 7
    title: Generate Route YAML — order-desk-notification
    files:
      modifies:
        - src/main/resources/camel/order-desk-notification.camel.yaml
      reads:
        - prototype/order-desk-notification.camel.yaml
        - docs/api/external/order-desk-mail.md
    provides:
      routes:
        - order-desk-notification-send
        - order-desk-notification-error-handling
      endpoints:
        - smtp://{{smtp.host}}:{{smtp.port}}
    consumes:
      routeContracts:
        - classpath:sql/notification-select.sql
        - maven-dependencies-ready
      externalServices:
        - postgresql-15432
        - mailpit-1025
    dependsOn:
      - 1
      - 2
  - id: 8
    title: Generate Application Properties — profiles
    files:
      modifies:
        - src/main/resources/application.properties
      creates:
        - src/main/resources/application-default.properties
        - src/main/resources/application-dev.properties
      reads:
        - src/main/resources/camel/peakmart-order-intake.camel.yaml
        - src/main/resources/camel/peakmart-order-check.camel.yaml
        - src/main/resources/camel/order-desk-notification.camel.yaml
    provides:
      properties:
        - camel.main.name
        - camel.main.run-controller
        - camel.main.log-mask
        - camel.main.additional-sensitive-keywords
        - spring.flyway.locations
        - peakmart.topic
        - peakmart.group-id
        - peakmart.auto-offset-reset
        - peakmart.max-record-chars
        - peakmart.email-pattern
        - order-check.poll-delay-ms
        - order-check.batch-size
        - catalog.timeout-ms
        - catalog.max-calls-per-second
        - camel.component.http.connect-timeout
        - camel.component.http.response-timeout
        - camel.component.http.connection-request-timeout
        - notification.poll-delay-ms
        - smtp.timeout-ms
        - kafka.brokers
        - spring.datasource.url
        - spring.datasource.username
        - spring.datasource.password
        - catalog.host
        - catalog.port
        - smtp.host
        - smtp.port
        - smtp.starttls
        - camel.component.smtp.username
        - camel.component.smtp.password
        - orderdesk.mailbox
        - orderdesk.sender
      beans:
        - dataSource
    consumes:
      routes:
        - peakmart-order-intake
        - peakmart-order-check-poll
        - order-desk-notification-send
      schemas:
        - order-db-schema-v1
    dependsOn:
      - 3
      - 5
      - 6
      - 7
  - id: 9
    title: E7 Regression Unit Test — EmailPatternTest
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/EmailPatternTest.java
      reads:
        - src/main/resources/application.properties
        - docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json
        - docs/messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json
      tests:
        - src/test/java/com/trailheadgear/orderintegration/EmailPatternTest.java
    provides:
      testData:
        - EmailPatternTest
    consumes:
      properties:
        - peakmart.email-pattern
    dependsOn:
      - 1
      - 8
  - id: 10
    title: Kill-Test Hook — KillTestApplication and KillTestHook
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java
        - src/test/java/com/trailheadgear/orderintegration/KillTestHook.java
      reads:
        - src/main/java/com/trailheadgear/orderintegration/CamelApplication.java
        - src/main/resources/camel/peakmart-order-intake.camel.yaml
    provides:
      beans:
        - killTestInterceptStrategy
      routeContracts:
        - kill-test-hook
    consumes:
      routeContracts:
        - node:commit-kafka-offset
        - maven-dependencies-ready
    dependsOn:
      - 1
      - 5
  - id: 11
    title: Kill-Test Script — src/test/scripts/kill-test.sh
    files:
      creates:
        - src/test/scripts/kill-test.sh
      reads:
        - prototype/kill-test.sh
        - prototype/kill-test-results.md
    provides:
      routeContracts:
        - kill-test-script
    consumes:
      routeContracts:
        - kill-test-hook
      properties:
        - peakmart.topic
        - peakmart.group-id
      externalServices:
        - kafka-9092
        - postgresql-15432
    dependsOn:
      - 8
      - 10
  - id: 12
    title: Acceptance Scenario Script — src/test/scripts/scenarios.sh
    files:
      creates:
        - src/test/scripts/scenarios.sh
      reads:
        - prototype/scenarios.sh
        - docs/camel-kit/001-peakmart-intake/design-spec.md
        - docs/messages/examples/order-desk-notification/pm-50118.json
    provides:
      routeContracts:
        - scenarios-script
    consumes:
      routes:
        - peakmart-order-intake
        - peakmart-order-check-poll
        - order-desk-notification-send
      properties:
        - orderdesk.mailbox
        - orderdesk.sender
      externalServices:
        - kafka-9092
        - postgresql-15432
        - product-catalog-8091
        - mailpit-8025
    dependsOn:
      - 5
      - 6
      - 7
      - 8
  - id: 13
    title: Retire the Prototype — run.sh Guard
    files:
      modifies:
        - prototype/run.sh
    provides:
      routeContracts:
        - prototype-retired
    consumes: {}
    dependsOn: []
  - id: 14
    title: Record Open Question — Production Mail Server
    files:
      modifies:
        - docs/open_questions.md
    provides:
      routeContracts:
        - oq-mail-server
    consumes: {}
    dependsOn: []
```

---

### Task 1: Platform Migration Setup — pom.xml

**Agent:** migration-specialist

**Files:**
- Modify: `pom.xml` (the exported pom; it is not recreated or replaced)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table, Step 5 and Step 5.5 item 3 (Maven plugins)
- `camel-implement/guides/maven-dependencies.md`
- `camel-implement/guides/pom-spring-boot.md` — everything except the "no `spring-boot-starter-parent`" rule, which the design explicitly overrides (R1)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_maven(component="smtp", B)`: must return `org.apache.camel.springboot:camel-mail-starter:4.22.2`
- `camel_catalog_component_maven(component=…, B)` for `kafka`, `sql`, `http`, `json-validator`, `direct`, `bean` (confirm that the existing starters stay)
- `camel_catalog_language_doc(language="groovy", B)` and `camel_catalog_language_doc(language="jsonpath", B)` (confirm `camel-groovy-starter` and `camel-jsonpath-starter` stay)
- `camel_dependency_check(…)` on the resulting pom, with the parameters its schema requires and the project's Spring Boot binding

**Design Spec Section:** §3 *Startup defects* E10 and E6; §3 *Supporting change: the pom (R1)*; §4 Constraints

- [ ] **Step 1:** Read `.camel-kit/config.properties`: Camel 4.22.2, platform BOM 4.22.2, Spring Boot 4.1.1. Confirm the pom's parent (`spring-boot-starter-parent` 4.1.1), its `spring-boot-dependencies` 4.1.1 import and its `camel-spring-boot-bom` 4.22.2 import match. Keep all three unchanged (R1).
- [ ] **Step 2:** Remove `org.apache.camel.springboot:camel-file-starter` and `org.apache.camel.springboot:camel-timer-starter` (E10).
- [ ] **Step 3:** Add `org.apache.camel.springboot:camel-mail-starter` with no `<version>` (BOM-managed, catalog-verified in the MCP call above).
- [ ] **Step 4:** Add Spring Boot's Flyway starter and Flyway's PostgreSQL database module, both without `<version>`. Take the exact artifact coordinates from the Spring Boot 4.1.1 BOM: run `./mvnw -q help:effective-pom -Doutput=target/effective-pom.xml`, then search `target/effective-pom.xml` for `flyway` in `dependencyManagement`. Do not guess the names. Record the coordinates and the managed versions for the execution report.
- [ ] **Step 5:** Remove `<version>42.7.13</version>` from `org.postgresql:postgresql`, so the Boot BOM manages it. Record the resolved version from `./mvnw dependency:tree` for the execution report.
- [ ] **Step 6:** Add a `maven-resources-plugin` execution (`copy-resources` goal, phase `process-resources`, no explicit plugin version because the parent manages it). It copies exactly `peakmart-order-placed.schema.json`, `product.schema.json` and `order.schema.json` from `${project.basedir}/docs/messages` into `${project.build.outputDirectory}/schemas` (E6). Copy nothing else, and copy no file by hand into `src/`.
- [ ] **Step 7:** Leave everything else exactly as exported. That includes `spring-boot-starter-actuator`, both test dependencies, the `spring-boot-maven-plugin`, the `camel.debug` and `jib` profiles and `project.build.outputTimestamp`. Add no web server starter (Not Doing: HTTP endpoints).
- [ ] **Step 8:** Record the resolved versions of Jackson 2 (`com.fasterxml.jackson.core:jackson-databind`) and `com.networknt:json-schema-validator` from `./mvnw dependency:tree` for the execution report (§3 *Supporting change: beans*).
- [ ] **Step 9:** Verify: `./mvnw -q -DskipTests process-resources && test -s target/classes/schemas/peakmart-order-placed.schema.json && test -s target/classes/schemas/product.schema.json && test -s target/classes/schemas/order.schema.json && cmp docs/messages/order.schema.json target/classes/schemas/order.schema.json && echo SCHEMAS-OK` prints `SCHEMAS-OK`. Then `./mvnw dependency:tree` exits 0. Its output contains `camel-mail-starter:jar:4.22.2`, a `flyway` core artifact, the Flyway PostgreSQL module and `org.postgresql:postgresql`. It does not contain `camel-file-starter`, `camel-timer-starter` or `postgresql:jar:42.7.13` (unless the Boot BOM itself manages 42.7.13, in which case record that).

**Review:**
- [ ] Spec compliance: exactly the E10 changes plus the E6 resources copy. The parent, BOM imports, profiles and Dockerfile are untouched. The schemas come only from `docs/messages/`. The resolved versions are recorded.
- [ ] Code quality: no hardcoded versions on BOM-managed artifacts; valid pom structure. The parent is **not** a finding (R1 reviewed exception). There is no web server starter.

---

### Task 2: Move SQL Files to the Classpath

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/sql/receipt-insert.sql`
- Create: `src/main/resources/sql/set-aside-insert.sql`
- Create: `src/main/resources/sql/waiting-select.sql`
- Create: `src/main/resources/sql/order-reject.sql`
- Create: `src/main/resources/sql/order-record.sql`
- Create: `src/main/resources/sql/notification-select.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5 item 2 (non-route resources keep their content)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: confirm that `query` accepts the `classpath:` resource prefix

**Design Spec Section:** §3 *Startup defects* E5; §6 Project Structure (`notification-select.sql` itself unchanged)

- [ ] **Step 1:** Copy each of the six files from `prototype/sql/` to `src/main/resources/sql/` byte for byte. Do not change any statement. The D4 cap lives in the route's `onConsume`/`onConsumeFailed` options (Task 7), not in these files. Do **not** copy `prototype/sql/schema.sql` (Task 3 owns the schema).
- [ ] **Step 2:** Verify: `for f in receipt-insert set-aside-insert waiting-select order-reject order-record notification-select; do cmp prototype/sql/$f.sql src/main/resources/sql/$f.sql || exit 1; done; test ! -e src/main/resources/sql/schema.sql && echo SQL-OK` prints `SQL-OK`.

**Review:**
- [ ] Spec compliance: six files, identical content, no `schema.sql` on the classpath.
- [ ] Code quality: no edits to SQL text; file names match the endpoints Tasks 5–7 reference.

---

### Task 3: Flyway Migration V1 — Order Database

**Agent:** migration-specialist

**Files:**
- Create: `src/main/resources/db/migration/V1__order_database.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5 item 2

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only; Flyway is not a Camel catalog artifact)

**Design Spec Section:** §3 *Supporting change: Flyway migration V1*; pipeline 001 design §3 (unique constraints, partial unique index)

- [ ] **Step 1:** Read `prototype/sql/schema.sql`. Write `V1__order_database.sql` with the same tables, columns, types, defaults, constraints, CHECKs and indexes, one statement each. The tables are `order_receipt`, `order_state_change`, `rejection_reason`, `notification`, `customer_order`, `order_line` and `set_aside_order_event`. Include the partial unique index on Peakmart order numbers and every other unique constraint of pipeline 001 §3.
- [ ] **Step 2:** Make exactly two changes and nothing else: remove the `DO $$ … $$` wrapper (Flyway runs a script), and remove every `IF NOT EXISTS`. Keep the `notification.attempts` CHECK at 0–1000 unchanged (D4 changes only the route).
- [ ] **Step 3:** Do not run the script against any database (P-8). Flyway applies it at the first application start during runtime verification. Do not add `spring.flyway.baseline-on-migrate` (B-10).
- [ ] **Step 4:** Verify: `f=src/main/resources/db/migration/V1__order_database.sql; test -s $f && ! grep -qi 'IF NOT EXISTS' $f && ! grep -q '\$\$' $f && for t in order_receipt order_state_change rejection_reason notification customer_order order_line set_aside_order_event; do grep -qiE "CREATE TABLE +$t[ (]" $f || exit 1; done && echo V1-OK` prints `V1-OK`. Then list the `CREATE (UNIQUE )?INDEX`, `UNIQUE`, `CHECK`, `REFERENCES` and `PRIMARY KEY` occurrences in both `prototype/sql/schema.sql` and `V1__order_database.sql` (`grep -ciE '<keyword>'` per keyword). The per-keyword counts must be equal.

**Review:**
- [ ] Spec compliance: the same schema as the prototype, without the `DO $$` wrapper and without `IF NOT EXISTS`; no extra tables (no `partner`, `pick_list`, which are V2 in the next pipeline); the CHECK on attempts is unchanged.
- [ ] Code quality: one statement per DDL; deterministic order (tables before the indexes and foreign keys that need them); no data inserts; no baseline settings.

---

### Task 4: Adapt Java Sources — IntegrationBeans

**Agent:** migration-specialist

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5 items 1 and 4
- `shared/forage.md` — Custom Bean Construction policy (Forage is unavailable; Spring `@Bean` is the framework rung)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding)

**Design Spec Section:** §3 *Startup defects* E4; §3 *Supporting change: beans and application class*; §5 *Infrastructure via Forage*

- [ ] **Step 1:** Create `IntegrationBeans` in package `com.trailheadgear.orderintegration`, annotated `@Configuration`. Give it two methods: `@Bean("orderChecks")` returning `new OrderChecks()`, and `@Bean("cannotReadReason")` returning `new CannotReadReason()`. Construct each class the way the prototype's `camel.beans.*=#class:` did, with its no-argument constructor. If a class has no public no-argument constructor, stop and report `NEEDS_CONTEXT`. Do not change the class.
- [ ] **Step 2:** Do not modify `CamelApplication.java`, `OrderChecks.java` or `CannotReadReason.java`. Do not declare a `DataSource` bean, because Spring Boot's auto-configured `dataSource` is used (E3).
- [ ] **Step 3:** Verify: `./mvnw -q -DskipTests compile` exits 0, and `git diff --quiet -- src/main/java/com/trailheadgear/orderintegration/OrderChecks.java src/main/java/com/trailheadgear/orderintegration/CannotReadReason.java src/main/java/com/trailheadgear/orderintegration/CamelApplication.java && echo BEANS-UNCHANGED` prints `BEANS-UNCHANGED`.

**Review:**
- [ ] Spec compliance: the bean names are exactly `orderChecks` and `cannotReadReason`; the existing classes are unchanged; there is no datasource bean.
- [ ] Code quality: Jakarta/Spring 7 imports only; no field injection; no logic in the configuration class.

---

### Task 5: Generate Route YAML — peakmart-order-intake

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/peakmart-order-intake.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: `query` with the `classpath:` prefix, `dataSource`
- `camel_catalog_component_doc(component="json-validator", B)`: `resourceUri` with the `classpath:` prefix
- `camel_catalog_component_doc(component="kafka", B)`: confirm the unchanged `breakOnFirstError`, `autoCommitEnable`, `allowManualCommit`, `groupId`, and the header `CamelKafkaManualCommit`
- `camel_catalog_eip_doc(eip="script", B)`
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with their schema parameters and binding `B`

**Design Spec Section:** §3 Flow: peakmart-order-intake (Change D5, Endpoint changes); §3 *Startup defects* E5, E6, E8

- [ ] **Step 1:** Change D5: delete the `delay` step `delay-before-commit` (the one using `{{peakmart.test.commit-delay-ms}}`). Keep the next step `commit-kafka-offset` (`script` calling `${header.CamelKafkaManualCommit.commit()}`) with its node id `commit-kafka-offset` and its position as the last step of route `peakmart-order-intake`. The kill-test hook in Task 10 depends on that id.
- [ ] **Step 2:** Endpoint changes. Use the exact forms below. Keep every parameter not listed:
  - `sql:file:{{sql.dir}}/set-aside-insert.sql` becomes `sql:classpath:sql/set-aside-insert.sql`.
  - `sql:file:{{sql.dir}}/receipt-insert.sql` becomes `sql:classpath:sql/receipt-insert.sql`.
  - `json-validator:file:{{schemas.dir}}/peakmart-order-placed.schema.json` becomes `json-validator:classpath:schemas/peakmart-order-placed.schema.json`.
  - Every `dataSource: "#orderDb"` becomes `dataSource: "#dataSource"`.
- [ ] **Step 3:** Change nothing else. The routes `peakmart-order-intake`, `peakmart-event-read`, `peakmart-event-set-aside` and `peakmart-order-receive`, the `routeConfiguration` `peakmart-intake-error-handling`, the DataMapper `kaoto-datamapper-5c1e9a27`, the size guard `peakmart.max-record-chars`, the email-pattern check, the U+0000 escape, the unstorable guard, every log line and every node id stay as exported. Run `diff prototype/peakmart-order-intake.camel.yaml src/main/resources/camel/peakmart-order-intake.camel.yaml` and confirm that the only differences are the Step 1 and Step 2 changes (and any comment line that named the removed delay or `sql.dir`/`schemas.dir`).
- [ ] **Step 4:** Run the `route-validation.md` loop on the file until it passes.
- [ ] **Step 5:** Verify: `f=src/main/resources/camel/peakmart-order-intake.camel.yaml; ! grep -qE 'delay-before-commit|commit-delay|sql\.dir|schemas\.dir|#orderDb|sql:file:|json-validator:file:' $f && grep -q 'id: commit-kafka-offset' $f && grep -q 'sql:classpath:sql/receipt-insert.sql' $f && grep -q 'sql:classpath:sql/set-aside-insert.sql' $f && grep -q 'json-validator:classpath:schemas/peakmart-order-placed.schema.json' $f && grep -q '"#dataSource"' $f && echo INTAKE-OK` prints `INTAKE-OK`.

**Review:**
- [ ] Spec compliance: D5 applied; `commit-kafka-offset` is kept and is still last; all endpoint changes are present; there is no other behavioral diff from the prototype.
- [ ] Code quality: constitution rules 1–8; no test hook in production code; placeholders only; Kaoto-compatible YAML; route validation passed.

---

### Task 6: Generate Route YAML — peakmart-order-check

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/peakmart-order-check.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: `query` with `classpath:`, `dataSource`, `delay`, `maxMessagesPerPoll`
- `camel_catalog_component_doc(component="json-validator", B)`: `resourceUri` with `classpath:`
- `camel_catalog_component_doc(component="http", includeOptions="all", B)`: confirm that `connectTimeout`, `responseTimeout` and `connectionRequestTimeout` are component-level options (so they stay `camel.component.http.*` in Task 8), and that `throwExceptionOnFailure` is unchanged
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with binding `B`

**Design Spec Section:** §3 Flow: peakmart-order-check (Endpoint changes); §3 *Startup defects* E5, E6

- [ ] **Step 1:** Endpoint changes. Keep every parameter not listed:
  - `sql:file:{{sql.dir}}/waiting-select.sql` (the consumer of `peakmart-order-check-poll`) becomes `sql:classpath:sql/waiting-select.sql`.
  - `sql:file:{{sql.dir}}/order-reject.sql` becomes `sql:classpath:sql/order-reject.sql`.
  - `sql:file:{{sql.dir}}/order-record.sql` becomes `sql:classpath:sql/order-record.sql`.
  - `json-validator:file:{{schemas.dir}}/product.schema.json` becomes `json-validator:classpath:schemas/product.schema.json`.
  - `json-validator:file:{{schemas.dir}}/order.schema.json` becomes `json-validator:classpath:schemas/order.schema.json`.
  - Every `dataSource: "#orderDb"` becomes `dataSource: "#dataSource"`.
- [ ] **Step 2:** The catalog call: `uri: "http://{{catalog.host}}"` becomes `uri: "http://{{catalog.host}}:{{catalog.port}}"`. Keep the `http` scheme visible in the URI (developer review decision 9) and keep the path and query construction unchanged. Add no timeout options to the URI.
- [ ] **Step 3:** Change nothing else. The split with `GroupedBodyAggregationStrategy`, the shared `throttle` (`TotalRequests`, `{{catalog.max-calls-per-second}}` per 1000 ms), the 200/404/other mapping, `IOException` → OUTAGE, the reject/record CTEs, the `{{orderdesk.mailbox}}` recipient in `order-reject`, `routeConfiguration` `order-check-error-handling`, the reason texts and the log lines stay as exported. Run `diff prototype/peakmart-order-check.camel.yaml src/main/resources/camel/peakmart-order-check.camel.yaml` and confirm that only the Step 1 and Step 2 changes differ.
- [ ] **Step 4:** Run the `route-validation.md` loop on the file until it passes.
- [ ] **Step 5:** Verify: `f=src/main/resources/camel/peakmart-order-check.camel.yaml; ! grep -qE 'sql\.dir|schemas\.dir|#orderDb|sql:file:|json-validator:file:' $f && grep -q 'http://{{catalog.host}}:{{catalog.port}}' $f && grep -q 'sql:classpath:sql/waiting-select.sql' $f && grep -q 'sql:classpath:sql/order-reject.sql' $f && grep -q 'sql:classpath:sql/order-record.sql' $f && grep -q 'json-validator:classpath:schemas/product.schema.json' $f && grep -q 'json-validator:classpath:schemas/order.schema.json' $f && echo CHECK-OK` prints `CHECK-OK`.

**Review:**
- [ ] Spec compliance: all five endpoint changes plus the catalog URI with separate host and port; no other behavioral diff.
- [ ] Code quality: constitution rules 1–8; `http://` is kept (Not Doing: HTTPS); no hardcoded host or port; route validation passed.

---

### Task 7: Generate Route YAML — order-desk-notification

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/order-desk-notification.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md` — header hygiene before a mail sink, credentials as component options
- `shared/patterns-error-handling.md` — `routeConfiguration` + `onException`, not handled
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="smtp", includeOptions="all", B)`: `from`, `useHeaderRecipients`, `useHeaderSubject`, `contentType`, `connectionTimeout`, `additionalJavaMailProperties` (prefix `mail.`), `useJavaMailSessionPropertiesFromHeaders`, component options `username`/`password`, headers `To`/`Subject`
- `camel_catalog_component_doc(component="sql", includeOptions="all", B)`: `onConsume`, `onConsumeFailed`, `delay`, `dataSource`, `query` with `classpath:`
- `camel_catalog_eip_doc(eip=…, B)` for each of `routeConfiguration`, `onException`, `removeHeaders`, `setHeader`, `setProperty`, `setBody`, `log`, `to`
- `camel_catalog_language_doc(language="simple", B)`
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with binding `B`

**Design Spec Section:** §3 Flow: order-desk-notification (Source, Change D4, Routes, Rationale, Constraints); §4 Security

- [ ] **Step 1:** Source:
  - `sql:file:{{sql.dir}}/notification-select.sql` becomes `sql:classpath:sql/notification-select.sql`.
  - `dataSource: "#orderDb"` becomes `"#dataSource"`.
  - Keep `delay: "{{notification.poll-delay-ms}}"` and the `RAW(...)` wrapping, with its comment.
  - Change D4: in both `onConsume` and `onConsumeFailed`, replace `attempts = attempts + 1` with `attempts = LEAST(attempts + 1, 1000)`. Change nothing else in those statements.
- [ ] **Step 2:** Add a `routeConfiguration` with id `order-desk-notification-error-handling` at the top of the file. It holds one `onException` for `java.lang.Exception` with `maximumRedeliveries: 0`, `handled` = `false` (constant), `logExhaustedMessageBody: false` and `logExhaustedMessageHistory: false`. Use only the option names the `onException`/redelivery-policy EIP doc returns. Its steps log WARN `Order Desk notification not sent, tried again on the next poll orderNumber=${exchangeProperty.order-number} correlationId=${exchangeProperty.correlation-id}`. It must stay **not handled**: a handled failure would run `onConsume` and mark an unsent notification as sent. Set `routeConfigurationId: order-desk-notification-error-handling` on route `order-desk-notification-send`.
- [ ] **Step 3:** Steps of `order-desk-notification-send`, in this order:
  1. Keep `set-notification-order-number` and `set-notification-correlation-id` unchanged.
  2. Delete the `set-file-order-number` groovy step (no file name any more; design: the subject cannot carry a header injection).
  3. `removeHeaders` with pattern `*`.
  4. `setHeader` `To` = simple `${body[recipient_email]}` (the body is still the row here).
  5. `setHeader` `Subject` = simple `Peakmart order ${exchangeProperty.order-number} rejected`.
  6. `setBody` (simple), exactly two lines: `Order number: ${exchangeProperty.order-number}`, then `${body[reason_lines]}`. Remove the old `To:`/`Subject:` lines and the blank line.
  7. `to` with uri `smtp://{{smtp.host}}:{{smtp.port}}` and these parameters:
     - `from: "{{orderdesk.sender}}"`
     - `useHeaderRecipients: true`
     - `useHeaderSubject: true`
     - `contentType: "text/plain; charset=UTF-8"`
     - `connectionTimeout: "{{smtp.timeout-ms}}"`
     - through the `mail.`-prefixed `additionalJavaMailProperties`: `mail.smtp.timeout`, `mail.smtp.writetimeout` = `{{smtp.timeout-ms}}`, and `mail.smtp.starttls.enable`, `mail.smtp.starttls.required` = `{{smtp.starttls}}`

     Leave `useJavaMailSessionPropertiesFromHeaders` at its default `false`. Put no username or password in the URI. Give the step a node id, e.g. `to-order-desk-mailbox`.
  8. Keep `log-order-desk-notified` (INFO `Order Desk notified orderNumber=… correlationId=…`) unchanged.
- [ ] **Step 4:** Update the route `description` and the file header comment to describe the email delivery instead of the file outbox. Keep the `id` `order-desk-notification-send`.
- [ ] **Step 5:** Run the `route-validation.md` loop on the file until it passes.
- [ ] **Step 6:** Verify: `f=src/main/resources/camel/order-desk-notification.camel.yaml; ! grep -qE 'uri: file:|sql:file:|fileExist|file-order-number|sql\.dir|#orderDb|outbox-dir|attempts = attempts \+ 1' $f && grep -q 'sql:classpath:sql/notification-select.sql' $f && [ "$(grep -c 'LEAST(attempts + 1, 1000)' $f)" = 2 ] && grep -q 'id: order-desk-notification-error-handling' $f && grep -q 'routeConfigurationId: order-desk-notification-error-handling' $f && grep -q 'smtp://{{smtp.host}}:{{smtp.port}}' $f && grep -q 'removeHeaders' $f && grep -q 'useHeaderRecipients' $f && ! grep -qiE 'password|username' $f && echo NOTIFY-OK` prints `NOTIFY-OK`.

**Review:**
- [ ] Spec compliance: the D4 cap in both statements; the new not-handled error handling with body and history logging off and the WARN line; step order removeHeaders → To → Subject → body → smtp; the exact body text; every smtp option present; credentials not in the route.
- [ ] Code quality: constitution rules 1–8; no personal data in the email or the logs (C-023, NFR-011); `useJavaMailSessionPropertiesFromHeaders` not enabled; timeouts set; route validation passed.

---

### Task 8: Generate Application Properties — profiles

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/application-default.properties`
- Create: `src/main/resources/application-dev.properties`

**Guides to Load:**
- `camel-implement/guides/properties-generation.md` — Spring Boot uses `${key}` for property-to-property references, and the §5.4 validation gate
- `shared/camel-security-checklist.md` — secrets only from the environment
- `shared/forage.md` — Configuration Ladder rung 2 (framework properties `spring.datasource.*`)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="http", B)` (component options `connect-timeout`, `response-timeout`, `connection-request-timeout`)
- `camel_catalog_component_doc(component="smtp", B)` (component options `username`, `password`)
- `camel_configuration_validate(…)` on each of the three files, with binding `B` (gate in properties-generation §5.4)
- `camel_properties_translate(…)` when needed, to confirm the Spring Boot names of `camel.main.log-mask`, `camel.main.additional-sensitive-keywords` and `camel.main.run-controller`

**Design Spec Section:** §3 *Configuration: profiles and refusing to start*; the *Configuration properties* blocks of the three flows; §3 *Startup defects* E1, E2, E3, E7, E8, E9

- [ ] **Step 1:** Replace the whole content of `application.properties` (shared by all profiles). Group by flow with comments. It holds exactly:
  - from §3 Configuration: `camel.main.name=trailhead-order-integration`, `camel.main.run-controller=true`, `camel.main.log-mask=true`, `camel.main.additional-sensitive-keywords=email,phone,ssn,creditCard`, `spring.flyway.locations=classpath:db/migration`
  - intake: `peakmart.topic=peakmart.orders.v2`, `peakmart.group-id=trailhead-order-integration`, `peakmart.auto-offset-reset=earliest`, `peakmart.max-record-chars=1048576`, and `peakmart.email-pattern=^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$` (E7: doubled backslashes in the file)
  - check: `order-check.poll-delay-ms=5000`, `order-check.batch-size=50`, `catalog.timeout-ms=2000`, `catalog.max-calls-per-second=20`, and `camel.component.http.connect-timeout`, `camel.component.http.response-timeout`, `camel.component.http.connection-request-timeout`, each `=${catalog.timeout-ms}` (E1)
  - notification: `notification.poll-delay-ms=5000`, `smtp.timeout-ms=10000`
- [ ] **Step 2:** Make sure these keys are **gone** from every properties file:
  - `peakmart.test.commit-delay-ms` (E8)
  - `schemas.dir`, `sql.dir` (E5/E6)
  - `orderdesk.outbox-dir`
  - `db.url`, `db.username`, `db.password` (E2/E3)
  - every `camel.beans.*` (E3/E4)
  - the camelCase `camel.main.logMask` / `camel.main.additionalSensitiveKeywords` duplicates
  - `catalog.host=localhost:8091`

  No value may use `{{env:…}}` or `{{…}}` syntax (E2). Route files keep `{{…}}`.
- [ ] **Step 3:** Create `application-default.properties` (used only when no profile is active) with exactly the 13 keys of §3 Configuration item 2. Every host, port and secret is an environment variable with **no default**:
  - `kafka.brokers=${KAFKA_BROKERS}`
  - `spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=verify-full&sslrootcert=${DB_SSL_ROOT_CERT}`
  - `spring.datasource.username=${DB_USERNAME}`, `spring.datasource.password=${DB_PASSWORD}`
  - `catalog.host=${CATALOG_HOST}`, `catalog.port=${CATALOG_PORT}`
  - `smtp.host=${SMTP_HOST}`, `smtp.port=${SMTP_PORT}`, `smtp.starttls=true`
  - `camel.component.smtp.username=${SMTP_USERNAME}`, `camel.component.smtp.password=${SMTP_PASSWORD}`
  - `orderdesk.mailbox=${ORDERDESK_MAILBOX}`, `orderdesk.sender=${ORDERDESK_SENDER}`

  Never write `${VAR:default}`.
- [ ] **Step 4:** Create `application-dev.properties` with exactly the 11 keys of §3 Configuration item 3:
  - `kafka.brokers=localhost:9092`
  - `spring.datasource.url=jdbc:postgresql://localhost:15432/postgres`, `spring.datasource.username=test`, `spring.datasource.password=test`
  - `catalog.host=127.0.0.1`, `catalog.port=8091`
  - `smtp.host=localhost`, `smtp.port=1025`, `smtp.starttls=false`
  - `orderdesk.mailbox=order-desk@trailhead.example`, `orderdesk.sender=order-integration@trailhead.example`

  It sets **no** smtp credentials. The `test`/`test` credentials are allowed here only (B-4).
- [ ] **Step 5:** Run `camel_configuration_validate` on each file and fix every finding that is not a Spring property-reference (`${…}`) false positive. Record the result or the documented manual fallback (§5.4 gate). Confirm the kebab-case `camel.main.*` keys are valid Camel Spring Boot keys. If one is not, use the Spring Boot name the catalog or `camel_properties_translate` gives, and record it.
- [ ] **Step 6:** Cross-check against the routes. Every `{{key}}` in `src/main/resources/camel/*.yaml` must be defined in `application.properties`, or in both `application-default.properties` and `application-dev.properties`:
  - `grep -ohE '\{\{[a-z0-9.-]+\}\}' src/main/resources/camel/*.yaml | sort -u`
  - compare each key with the property files and list any that are missing (there must be none)
- [ ] **Step 7:** Verify: `cd src/main/resources && ! grep -qE 'commit-delay|schemas\.dir|sql\.dir|outbox-dir|camel\.beans\.|\{\{|^db\.|logMask|additionalSensitiveKeywords' application*.properties && grep -qF 'peakmart.email-pattern=^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$' application.properties && grep -q '^camel.main.name=trailhead-order-integration$' application.properties && grep -q '^camel.component.http.connect-timeout=${catalog.timeout-ms}$' application.properties && [ "$(grep -cE '^[a-z]' application-default.properties)" = 13 ] && [ "$(grep -cE '^[a-z]' application-dev.properties)" = 11 ] && ! grep -qE '\$\{[A-Z_]+:' application-default.properties && ! grep -q 'smtp.username\|smtp.password' application-dev.properties && echo PROPS-OK` prints `PROPS-OK`.

**Review:**
- [ ] Spec compliance: the three files hold exactly the design keys and values; E1, E2, E7, E8 and E9 are fixed; the default profile has 15 distinct environment variables, none with a default; dev has no smtp credentials; `verify-full` and STARTTLS apply only without a profile.
- [ ] Code quality: Spring `${…}` interpolation inside the properties; no secret literal outside the dev file; no unused keys; configuration validation gate passed.

---

### Task 9: E7 Regression Unit Test — EmailPatternTest

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/EmailPatternTest.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules (test sources stay out of the packaged jar)
- `shared/camel-security-checklist.md` — no personal data printed in test output

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 *Supporting change: E7 regression test (R2)*; §3 *Startup defects* E7; §4 Acceptance (first bullet)

- [ ] **Step 1:** Write a plain JUnit (Jupiter, from `spring-boot-starter-test`) test class. It starts no Spring context and no Camel context, and uses no services.
- [ ] **Step 2:** Load the classpath resource `application.properties` with Spring Boot's `org.springframework.boot.env.PropertiesPropertySourceLoader` (`load("application", new ClassPathResource("application.properties"))`), so the backslash escapes are read exactly as at run time. Read `peakmart.email-pattern` from the returned property source. Do not read the file as plain text, and do not repeat the pattern literal in the test.
- [ ] **Step 3:** Read `buyer.email` with Jackson 2 (`com.fasterxml.jackson.databind.ObjectMapper`) from two files, relative to the project directory where Surefire runs. Do not copy the values into the test.
  - `docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json`
  - `docs/messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json`

  If the JSON path to the buyer's email differs, find it in `docs/messages/peakmart-order-placed.schema.json`.
- [ ] **Step 4:** Two tests. The compiled pattern must `matches()` the sample's email, which contains an `s`. It must **not** match the pm-50119 email. Also assert that the sample email contains `s`, so the test keeps guarding against the `^[^@s]+@…` regression. Assertion messages state which file failed. They do not print the email address.
- [ ] **Step 5:** Verify: `./mvnw -Dtest=EmailPatternTest test` exits 0 with `BUILD SUCCESS`, and its Surefire summary for `EmailPatternTest` reads `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` with `N` ≥ 2 (one accept test, one reject test).

**Review:**
- [ ] Spec compliance: the pattern is loaded the way Spring Boot loads it; the emails come from the example files; one test accepts and one rejects; the test runs in `./mvnw verify` through Surefire.
- [ ] Code quality: no Spring or Camel context; no copied literals; no personal data in the output; deterministic.

---

### Task 10: Kill-Test Hook — KillTestApplication and KillTestHook

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/KillTestHook.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules
- `camel-implement/guides/advanced-patterns.md` — interceptor and processor wrapping

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding)
- `camel_catalog_eip_doc(eip="script", B)` (the node `commit-kafka-offset` the hook targets)

**Design Spec Section:** §3 Flow: kill-test support — D5; §7 B-1

- [ ] **Step 1:** `KillTestApplication` (test main class) has a `main(String[] args)` that runs `SpringApplication.from(CamelApplication::main).with(KillTestHook.class).run(args)`.
- [ ] **Step 2:** `KillTestHook` is a `@Configuration` with `@Profile("kill-test")`. It declares one `org.apache.camel.spi.InterceptStrategy` bean:
  - It wraps only the processor whose `NamedNode` id is `commit-kafka-offset`. It returns the target unchanged for every other node.
  - Before delegating, it sleeps `kill-test.commit-delay-ms` milliseconds (read from the Spring `Environment`, default 0) on the same thread, so the commit stays on the Kafka poll thread.
  - At startup it logs exactly `Kill-test hook active commitDelayMs=<value>` (INFO).
- [ ] **Step 3:** Fallback: during runtime verification, the startup line or the delayed commit may show that Camel Spring Boot does not pick up `InterceptStrategy` beans from the context. In that case, register the same strategy through a `org.apache.camel.spring.boot.CamelContextConfiguration` bean (`beforeApplicationStart` → `camelContext.getCamelContextExtension().addInterceptStrategy(…)`) inside `KillTestHook`, and record which mechanism was used.
- [ ] **Step 4:** Nothing under `src/main/` may refer to the hook, the profile or the property.
- [ ] **Step 5:** Verify: `./mvnw -q -DskipTests test-compile` exits 0, and `! grep -rqE 'kill-test|commit-delay|peakmart\.test\.commit-delay-ms' src/main/ && echo MAIN-CLEAN` prints `MAIN-CLEAN`.

**Review:**
- [ ] Spec compliance: test classpath only; profile-gated; only node `commit-kafka-offset`; synchronous delay; the exact startup log line.
- [ ] Code quality: no effect without the `kill-test` profile; the interrupt is restored if the sleep is interrupted; no production code touched.

---

### Task 11: Kill-Test Script — src/test/scripts/kill-test.sh

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/scripts/kill-test.sh`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules
- `shared/camel-security-checklist.md` — the script prints counts, not order content

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 Flow: kill-test support — D5 (`kill-test.sh` bullets, Pass criterion); §2 (read-only `docker exec`)

- [ ] **Step 1:** Start from `prototype/kill-test.sh` (the final version, run 120414 in `prototype/kill-test-results.md`). Keep all counts and checks: received at kill, committed offset moved, gap, `N` recorded, 0 recorded twice, `2N` history entries, and Duplicates skipped after the restart = gap. Keep the exit codes: 0 pass, 1 a check failed, 2 the kill missed the gap.
- [ ] **Step 2:** Replace the prototype start with:
  - First start: `./mvnw -q spring-boot:test-run -Dspring-boot.run.profiles=dev,kill-test`, with `kill-test.commit-delay-ms=500` passed as an application argument (e.g. `-Dspring-boot.run.arguments=--kill-test.commit-delay-ms=500`).
  - Second start: only the `dev` profile and no delay.

  Confirm in the Spring Boot 4.1.1 Maven plugin documentation that `test-run` uses the test classpath and accepts the main class `com.trailheadgear.orderintegration.KillTestApplication` (`-Dspring-boot.run.main-class=…`). Use the plugin's property names.
- [ ] **Step 3:** Wait for `Started peakmart-order-intake` in the log. On the first start, also wait for `Kill-test hook active commitDelayMs=500`. Confirm the exact Camel route-started marker during runtime verification, and adjust only the marker string if it differs.
- [ ] **Step 4:** Find the forked application JVM by `KillTestApplication` in its command line (`pgrep -f`), and `kill -9` only that PID. Never kill Maven itself first, and never kill a container.
- [ ] **Step 5:** Read Kafka and PostgreSQL only with `docker exec camel-postgres psql …` and `docker exec camel-kafka kafka-consumer-groups.sh …` / `kafka-console-producer.sh …`. Never start or stop a container. Write each run's output to `target/kill-test/<run>/result.txt` and print the path.
- [ ] **Step 6:** Use `set -euo pipefail` and resolve the project root from the script's location. Make it executable (`chmod +x`). Do **not** run it in this task; runtime verification runs it.
- [ ] **Step 7:** Verify: `test -x src/test/scripts/kill-test.sh && bash -n src/test/scripts/kill-test.sh && grep -q 'spring-boot:test-run' src/test/scripts/kill-test.sh && grep -q 'KillTestApplication' src/test/scripts/kill-test.sh && grep -q 'target/kill-test/' src/test/scripts/kill-test.sh && ! grep -qE 'docker (start|stop|run|rm|compose)|camel infra' src/test/scripts/kill-test.sh && echo KILLSCRIPT-OK` prints `KILLSCRIPT-OK`.

**Review:**
- [ ] Spec compliance: same checks and exit codes as the prototype; the delay only on the first start; the forked JVM is killed by `KillTestApplication`; results are kept under `target/kill-test/`.
- [ ] Code quality: strict shell flags; only read-only `docker exec`; no personal data printed; no hardcoded secrets beyond the dev profile.

---

### Task 12: Acceptance Scenario Script — src/test/scripts/scenarios.sh

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/scripts/scenarios.sh`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules
- `shared/camel-security-checklist.md` — the script checks for the absence of personal data in the email

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §4 *Acceptance criteria and scenario approach* (scenarios 1–6, Scenario 3 Mailpit bullet); pipeline 001 design §4 (scenarios 1–6 expectations); §7 B-12

- [ ] **Step 1:** Start from `prototype/scenarios.sh`. Point it at the Spring Boot application: it expects the application to be already running with `--spring.profiles.active=dev` (started by the verifier), and it no longer starts the prototype. Keep scenarios 1–6 with the same inputs and expectations.
- [ ] **Step 2:** Scenario 3 (PM-50118 rejected): replace the outbox-file check with Mailpit checks:
  - Before producing the event, count `GET http://localhost:8025/api/v1/search?query=<subject query for "Peakmart order PM-50118 rejected">`.
  - After producing, poll until the count has grown by exactly 1 (bounded wait, e.g. 60 s).
  - Fetch the new message and assert:
    - Subject = `Peakmart order PM-50118 rejected`
    - To = `order-desk@trailhead.example` and From = `order-integration@trailhead.example` (the dev `orderdesk.*` values)
    - the content type is plain text
    - the body contains `Order number: PM-50118` and the reason text(s) of `docs/messages/examples/order-desk-notification/pm-50118.json`, read from that file
    - the body contains no buyer name, email address or Shipping Address from the PM-50118 input event
  - Also assert that `target/outbox` holds no file.
  - Never delete Mailpit messages (shared service).
- [ ] **Step 3:** Use only read-only `docker exec camel-postgres psql`, `docker exec camel-kafka kafka-console-producer.sh` and `curl` to `localhost:8025` and `127.0.0.1:8091`. Never start or stop a container. Print PASS/FAIL per scenario. Exit non-zero if any scenario fails. Scenarios 7–12 stay manual (they are in the runtime acceptance checklist below), so the script does not include them.
- [ ] **Step 4:** Use `set -euo pipefail` and make it executable. Do **not** run it in this task.
- [ ] **Step 5:** Verify: `test -x src/test/scripts/scenarios.sh && bash -n src/test/scripts/scenarios.sh && grep -q 'localhost:8025/api/v1' src/test/scripts/scenarios.sh && grep -q 'PM-50118' src/test/scripts/scenarios.sh && ! grep -qE 'outbox-dir|prototype/run.sh|docker (start|stop|run|rm|compose)|api/v1/messages.*DELETE|-X *DELETE' src/test/scripts/scenarios.sh && echo SCENARIOS-OK` prints `SCENARIOS-OK`.

**Review:**
- [ ] Spec compliance: scenarios 1–6 unchanged; scenario 3 verified in Mailpit by counting before and after; no deletion; no outbox file.
- [ ] Code quality: strict shell flags; bounded waits; no personal data printed; expected texts read from the example files.

---

### Task 13: Retire the Prototype — run.sh Guard

**Agent:** implementation-engineer

**Files:**
- Modify: `prototype/run.sh` (one line after the shebang; nothing else)

**Guides to Load:**
- `camel-implement/guides/run-script.md` — strict shell conventions of the existing script (read only; the script is not regenerated)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 *Supporting change: retiring the prototype (R4)*; Not Doing (*Removing `prototype/`*)

- [ ] **Step 1:** Insert exactly one line as line 2 of `prototype/run.sh`, directly after `#!/bin/bash`. If `PROTOTYPE_ANYWAY` is not `1`, it prints to stderr why the script refuses: the prototype is retired, the Spring Boot project now consumes with the same consumer group `trailhead-order-integration`, the two must not run at the same time, and `PROTOTYPE_ANYWAY=1` overrides. Then it exits non-zero (e.g. `[ "${PROTOTYPE_ANYWAY:-}" = 1 ] || { echo "…" >&2; exit 1; }`).
- [ ] **Step 2:** Do not touch `prototype/kill-test.sh`, which starts the prototype through `run.sh` and is therefore covered too. Do not touch `prototype/stubs/product-catalog/run.sh`, which stays unguarded. Do not touch any other prototype file.
- [ ] **Step 3:** Verify:
  - `git diff --numstat -- prototype/ | awk '{a+=$1; d+=$2} END {print a, d}'` prints `1 0`, and the only changed file is `prototype/run.sh`.
  - `bash prototype/run.sh > /tmp/guard.out 2>&1; echo "exit=$?"` prints a non-zero `exit=`, `/tmp/guard.out` contains `PROTOTYPE_ANYWAY=1` and `trailhead-order-integration`, and `pgrep -f camel.*peakmart` shows no new JVM.
  - `PROTOTYPE_ANYWAY=1 bash -c "$(sed -n 2p prototype/run.sh)"; echo "exit=$?"` prints `exit=0` (the guard line on its own lets the run through, without starting the prototype).
  - Record all three outputs for the execution report.

**Review:**
- [ ] Spec compliance: one line, right after the shebang; non-zero exit and the stated reason; the override works; the stub script is untouched.
- [ ] Code quality: the message goes to stderr; no JVM is started on refusal; the guard does not break `set -u` later in the script.

---

### Task 14: Record Open Question — Production Mail Server

**Agent:** implementation-engineer

**Files:**
- Modify: `docs/open_questions.md` (append one entry)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — scope rules (docs changes only where the design requires them)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 *Configuration* — "The production mail server is not specified (B-7, R3)"; §7 B-7; §8 R3

- [ ] **Step 1:** Find the highest `## OQ-NNN` number in `docs/open_questions.md` (OQ-075 at planning time). Use OQ-076 if it is still free, otherwise the next free number.
- [ ] **Step 2:** Append the entry in the file's existing format (copy the layout of OQ-075, including separators and field order):
  - heading `## OQ-076: How is the production mail server reached?`
  - `**Element:** UC-002 (Order Desk Notification), order-desk-mail.md`
  - `**Raised by:** design 002-spring-boot-move (design review, B-7), 2026-11-13`
  - `**Blocking:** No`
  - `**Status:** Open`
  - text: the application assumes STARTTLS is required and that it logs in with `SMTP_USERNAME`/`SMTP_PASSWORD`. Operations is asked to confirm the host, port, TLS mode and authentication before chapter 9 deploys anything. If the server takes no login, an amendment makes the credentials optional.

  Change no other entry.
- [ ] **Step 3:** Verify: `grep -c '^## OQ-076: How is the production mail server reached?$' docs/open_questions.md` prints `1`. `git diff --numstat -- docs/open_questions.md` shows only added lines (`<n> 0`). `git diff --name-only -- docs/ | grep -v '^docs/camel-kit/002-spring-boot-move/'` prints only `docs/open_questions.md`.

**Review:**
- [ ] Spec compliance: the exact heading, the element, raised-by, Blocking No and Status Open; the next free number; the text covers the assumption and the confirmation request.
- [ ] Code quality: matches the file's existing format; append only.

---

## Runtime Acceptance Checklist (for camel-execute's internal verification — not a plan task)

camel-execute's build-and-smoke verification runs once after all tasks. Commands are chosen from the shipped
verify guides. This list gives the expected outcomes the design requires (§4 *Acceptance criteria*), and the execution
report records each one:

1. `./mvnw verify` passes, and its output shows `EmailPatternTest` ran and passed.
2. With the stub (`prototype/stubs/product-catalog/run.sh`, 127.0.0.1:8091) and the local services running,
   `./mvnw clean package` builds and `java -jar target/order-integration-0.1.0.jar --spring.profiles.active=dev` starts.
   - Flyway applies V1 to the empty database, and the log shows the Flyway migration **before** the Camel routes start.
   - All routes start.
   - The process listens on no TCP port (`lsof -nP -iTCP -sTCP:LISTEN -a -p <pid>` is empty).
   - The resolved Spring Boot names of `camel.main.*` take effect (the context name is `trailhead-order-integration`).
3. Refusal test: without a profile, a run with all 15 variables set gets past placeholder resolution (P-9). Then 15 runs,
   each with one variable unset, each exit non-zero before any route consumes and name that variable. The variables are
   `KAFKA_BROKERS`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_ROOT_CERT`, `CATALOG_HOST`,
   `CATALOG_PORT`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `ORDERDESK_MAILBOX` and `ORDERDESK_SENDER`.
4. `src/test/scripts/scenarios.sh` passes scenarios 1–6, including the Mailpit check of scenario 3. Against Mailpit (no
   credentials in `dev`), no SMTP `AUTH` is attempted.
5. Scenarios 7–10 are run by hand as in pipeline 001. In scenario 9, the rejection email appears only after the stub is
   started again.
6. Scenario 11 (mail server down): the application runs with `--smtp.port=<closed port>`, then an order is rejected.
   - `sent_at` stays NULL and `attempts` grows on every poll.
   - The WARN line appears without a body.
   - After a restart with `smtp.port=1025`, exactly one email arrives and `sent_at` is set.
7. Scenario 12 (D4): a notification row with `attempts = 1000` and `sent_at` NULL is sent once and marked sent, and
   it is not sent again on the next poll.
8. `src/test/scripts/kill-test.sh` passes at least once, with exit 0 and a gap ≥ 1. The execution report quotes
   `target/kill-test/<run>/result.txt`.
9. Neither `src/main/` nor the built jar contains `kill-test` or `commit-delay`:
   `unzip -p target/order-integration-0.1.0.jar 'BOOT-INF/classes/*' | grep -acE 'kill-test|commit-delay'` returns `0`.
10. The `prototype/run.sh` guard behaves as Task 13 checks, and `docs/open_questions.md` holds the mail-server question
    (Task 14).

Reviewers apply Iron Law 4 in order to every task: spec compliance first, then code quality.
