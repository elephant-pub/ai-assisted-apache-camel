---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T20:30:00Z"
  by: camel-plan
  from: design-spec.md
---
# Trailhead Order Integration — Partner Orders and Pick Lists Implementation Plan

> **For agentic workers:** Use camel-execute to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Partners submit orders over HTTP and get an immediate Order Answer, checked by the same Order Checks as
Peakmart orders. Partners are registered at startup from the reviewed Partner Configuration and the Secret Store. Every
Accepted Order of either channel reaches the Warehouse SFTP Server as exactly one pick list. The startup check names
every missing variable, and the development settings leave the jar.

**Architecture:** One Spring Boot 4.1.1 application at the repository root, YAML DSL routes under
`src/main/resources/camel/`. A contract-first Rest DSL (`openApi` on `platform-http`) feeds four partner routes that
reuse the shared, now channel-neutral `order-check` route. Two outboxes in PostgreSQL (`notification`, new `pick_list`)
are drained by `sql` pollers that send the Rejection Email (`smtp`) and the pick-list CSV (`sftp`). Java beans hold the
rules: partner registration, Secret Store, answers, readability guards, SFTP `stat`, and the startup variable check.

**Tech Stack:** Apache Camel 4.22.2, Spring Boot 4.1.1, JDK 25, Camel Spring Boot BOM 4.22.2 (catalog
`org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2`). New components: platform-http, rest-openapi,
sftp (camel-ftp-starter), csv dataformat. Existing: kafka, sql, http, json-validator, smtp, direct, bean; languages
simple, jsonpath, groovy, constant, bean. Flyway, HikariCP, PostgreSQL, JSch (through camel-ftp), JUnit.

**Design Spec:** `docs/camel-kit/003-partner-orders-pick-lists/design-spec.md` (approved 2026-11-13, with design-review
changes R1–R5 applied; `camel-kit doc check`: not stale, `generated.by: camel-brainstorm`)

---

## Planning Decisions and Assumptions (non-interactive run — recommended option taken)

| # | Decision | Reason |
|---|---|---|
| P-1 | **No Citrus test tasks.** The testing template was loaded but produces no tasks. | The approved design's `## Not Doing (and Why)` excludes Citrus tests in this pipeline, and an exclusion listed there wins over the template default. Behaviour is proven by JUnit unit tests (P-21 of the design), the scenario scripts (Tasks 19, 20) and the runtime acceptance checklist below. camel-execute's verification has no Citrus suite to run. |
| P-2 | **No Docker Compose task, no scaffold task.** Task 1 modifies the existing `pom.xml` and keeps its `spring-boot-starter-parent` 4.1.1 parent. | The project exists (pipeline 002). The services already run, and the design forbids starting or stopping containers or `camel infra` services (§2, §4 Constraints). 002 design R1 (parent POM) still holds (§4 Constraints), so reviewers must not report the parent as a finding. |
| P-3 | Every Java and test task uses `implementation-engineer`. | The shipped `test-engineer` persona generates Citrus YAML tests only. The unit tests here are plain JUnit. |
| P-4 | SQL statements (Tasks 3–5) and Flyway V2 (Task 2) come before the route tasks. | Each is verifiable on its own, and several routes consume them. |
| P-5 | **Never apply V2 by hand.** No task runs DDL against the development database. | Flyway applies V2 at the first start during runtime verification. Hand-made tables without Flyway history would stop startup. |
| P-6 | Bean declarations are owned by the Java task that creates the class; `IntegrationBeans.java` is modified by Tasks 6–9 in sequence. | The file overlap orders those tasks in the wave analysis; each task adds only its own `@Bean` methods. |
| P-7 | The dev SFTP folder check (§3 pick-list-delivery Configuration: "Implementation confirms this with one `stat`") and the known-hosts file are done in Task 16, before the properties task (Task 17). | Task 17 needs the result to decide whether `dev` overrides the two directories. `camel infra get sftp` only reads connection data; it starts nothing. |
| P-8 | The CSV unit test (Task 15) uses the same `csv` options as the route and a check that the route still has those options. | The design (P-21) asks for a CSV quoting unit test; the route itself starts from an `sql` poller, so the test exercises the data format with the identical configuration. |
| P-9 | Open questions use the next free numbers at the time of writing: OQ-077 (pick-list line ending) and OQ-078 (partner TLS termination), if still free. | The highest number at planning time is OQ-076. |
| P-10 | No downstream artifacts exist yet (`execution-report.md`, `validation-report.md`), so staleness propagation after writing this plan is a no-op. | `shared/pipeline-infrastructure.md` cascade rules: run only for children that exist. |
| P-11 | There is no project graph (`.camel-kit/project-graph.json` is absent), so graph-context guides are not loaded. | Orchestrator Step 0 is conditional on that file. |

**Path resolution** (`camel-implement/guides/orchestrator.md` File Path Table, runtime `spring-boot`, Target Module =
repository root, so `{MODULE_PREFIX}` is empty): `ROUTE_DIR=src/main/resources/camel/`, `PROPS_DIR=src/main/resources/`,
`MODULE_DIR=` (project root). Design-specified non-table paths: `src/main/resources/sql/`,
`src/main/resources/db/migration/`, `src/main/resources/META-INF/`, `src/main/java/com/trailheadgear/orderintegration/`,
`src/test/java/com/trailheadgear/orderintegration/`, `src/test/scripts/`, `config/`, `config/dev/`,
`docs/entity_model.md`, `docs/open_questions.md`.

**Common MCP binding for every task (run once per task before any catalog detail call):**
`camel_catalog_components(limit=0, runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2")`.
The returned `camelVersion` must equal `4.22.2`. Below, "`B`" means
`runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2"`.

**Hard constraints for every task (design §2, §4 Constraints, Not Doing):**
- Do not start or stop containers or `camel infra` services. Do not use ports 8080, 8081 or 5432. The partner endpoint is
  `8082` in `dev`.
- Do not change routes, node ids, SQL text, reason texts or log lines that a task does not name. Everything in 002 that
  the design does not mention stays as it is (002 R1–R4, B-1 to B-12).
- Do not modify `prototype/`. Under `docs/`, modify only `docs/entity_model.md`, `docs/open_questions.md` (Task 18) and
  this pipeline's artifacts. `docs/api/` and `docs/messages/` are read-only sources copied at build time.
- Never log message bodies, API keys, Order Contacts, names or addresses. No secrets in route URIs or in the jar.
- Do not implement anything listed in the design's `## Not Doing (and Why)`: UC-005/006/008, Citrus tests, a real Secret
  Store client, configuration reload at runtime, a production Partner Configuration, an OQ-075 rejection, the 90-day
  purge, multi-instance locking, actuator over HTTP, Kafka TLS / catalog HTTPS, partner-lookup priority.

---

```yaml plan-metadata
tasks:
  - id: 1
    title: Maven Dependencies and Contract Resources — pom.xml
    files:
      modifies:
        - pom.xml
      reads:
        - .camel-kit/config.properties
        - docs/api/partner-orders.openapi.yaml
        - docs/messages/partner-order.schema.json
        - docs/messages/order-answer.schema.json
        - docs/messages/rejection-reason.schema.json
        - docs/messages/partner-configuration.schema.json
    provides:
      schemas:
        - classpath:contract/api/partner-orders.openapi.yaml
        - classpath:contract/messages/partner-order.schema.json
        - classpath:contract/messages/order-answer.schema.json
        - classpath:contract/messages/rejection-reason.schema.json
        - classpath:schemas/partner-configuration.schema.json
      routeContracts:
        - maven-dependencies-ready
    consumes: {}
    dependsOn: []
  - id: 2
    title: Flyway Migration V2 — Partners and Pick Lists
    files:
      creates:
        - src/main/resources/db/migration/V2__partners_and_pick_lists.sql
      reads:
        - src/main/resources/db/migration/V1__order_database.sql
        - docs/entity_model.md
    provides:
      schemas:
        - order-db-schema-v2
    consumes: {}
    dependsOn: []
  - id: 3
    title: Shared Reject and Record Statements
    files:
      modifies:
        - src/main/resources/sql/order-reject.sql
        - src/main/resources/sql/order-record.sql
    provides:
      routeContracts:
        - classpath:sql/order-reject.sql#notification-type-recipient
        - classpath:sql/order-record.sql#pick-list-outbox
    consumes:
      schemas:
        - order-db-schema-v2
    dependsOn:
      - 2
  - id: 4
    title: Partner SQL Statements
    files:
      creates:
        - src/main/resources/sql/partner-receipt-claim.sql
        - src/main/resources/sql/partner-receipt-release.sql
        - src/main/resources/sql/accepted-content-select.sql
        - src/main/resources/sql/rejection-reasons-select.sql
        - src/main/resources/sql/rejection-email-select.sql
      reads:
        - src/main/resources/sql/notification-select.sql
        - docs/messages/rejection-reason.schema.json
    provides:
      routeContracts:
        - classpath:sql/partner-receipt-claim.sql
        - classpath:sql/partner-receipt-release.sql
        - classpath:sql/accepted-content-select.sql
        - classpath:sql/rejection-reasons-select.sql
        - classpath:sql/rejection-email-select.sql
    consumes:
      schemas:
        - order-db-schema-v2
    dependsOn:
      - 2
  - id: 5
    title: Pick-List SQL Statements
    files:
      creates:
        - src/main/resources/sql/pick-list-select.sql
        - src/main/resources/sql/pick-list-rows.sql
        - src/main/resources/sql/pick-list-delivered.sql
      reads:
        - docs/messages/mappings/order-to-pick-list.md
        - docs/inputs/warehouse-picklist-format.md
    provides:
      routeContracts:
        - classpath:sql/pick-list-select.sql
        - classpath:sql/pick-list-rows.sql
        - classpath:sql/pick-list-delivered.sql
    consumes:
      schemas:
        - order-db-schema-v2
    dependsOn:
      - 2
  - id: 6
    title: OrderChecks — Channel-Aware Checks, Partner Reasons, Catalog Price, Lookup Pool
    files:
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/OrderChecks.java
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
      creates:
        - src/test/java/com/trailheadgear/orderintegration/OrderChecksPartnerTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/OrderChecksPartnerTest.java
    provides:
      beans:
        - orderChecks#partnerNumberReasons
        - catalogLookupPool
    consumes:
      routeContracts:
        - maven-dependencies-ready
    dependsOn:
      - 1
  - id: 7
    title: Partner Registration — SecretStore, PartnerRegistry, PartnerRegistration
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/SecretStore.java
        - src/main/java/com/trailheadgear/orderintegration/DirectorySecretStore.java
        - src/main/java/com/trailheadgear/orderintegration/PartnerRegistry.java
        - src/main/java/com/trailheadgear/orderintegration/PartnerRegistration.java
        - src/test/java/com/trailheadgear/orderintegration/PartnerRegistrationTest.java
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/PartnerRegistrationTest.java
    provides:
      beans:
        - secretStore
        - partnerRegistry
        - partnerRegistration
    consumes:
      schemas:
        - classpath:schemas/partner-configuration.schema.json
        - order-db-schema-v2
      properties:
        - peakmart.email-pattern
    dependsOn:
      - 1
      - 2
      - 6
  - id: 8
    title: PartnerOrders and OrderAnswers Beans
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/PartnerOrders.java
        - src/main/java/com/trailheadgear/orderintegration/OrderAnswers.java
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
      reads:
        - src/main/java/com/trailheadgear/orderintegration/CannotReadReason.java
        - docs/api/partner-orders.openapi.yaml
        - docs/messages/order-answer.schema.json
    provides:
      beans:
        - partnerOrders
        - orderAnswers
    consumes:
      properties:
        - peakmart.email-pattern
    dependsOn:
      - 1
      - 7
  - id: 9
    title: WarehouseSftp Bean — Delivered-File Check
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
    provides:
      beans:
        - warehouseSftp
    consumes:
      routeContracts:
        - maven-dependencies-ready
    dependsOn:
      - 1
      - 8
  - id: 10
    title: Startup Check — RequiredVariablesCheck
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/RequiredVariablesCheck.java
        - src/main/resources/META-INF/spring.factories
        - src/test/java/com/trailheadgear/orderintegration/RequiredVariablesCheckTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/RequiredVariablesCheckTest.java
    provides:
      beans:
        - requiredVariablesCheck
    consumes:
      routeContracts:
        - maven-dependencies-ready
    dependsOn:
      - 1
  - id: 11
    title: Route YAML — peakmart-order-check (channel-neutral order-check)
    files:
      modifies:
        - src/main/resources/camel/peakmart-order-check.camel.yaml
    provides:
      routes:
        - peakmart-order-check-poll
        - order-check
        - product-lookup
        - order-reject
        - order-record
      endpoints:
        - direct:order-check
      routeContracts:
        - order-check-returns-evaluation
    consumes:
      beans:
        - orderChecks#partnerNumberReasons
        - catalogLookupPool
      routeContracts:
        - classpath:sql/order-reject.sql#notification-type-recipient
        - classpath:sql/order-record.sql#pick-list-outbox
    dependsOn:
      - 3
      - 6
  - id: 12
    title: Route YAML — partner-order-api
    files:
      creates:
        - src/main/resources/camel/partner-order-api.camel.yaml
      reads:
        - docs/api/partner-orders.openapi.yaml
        - docs/messages/mappings/partner-order-to-order.md
        - docs/messages/mappings/partner-order-to-order-answer.md
    provides:
      routes:
        - partner-orders-rest
        - partner-order-submit
        - partner-order-receive
        - partner-order-check
        - partner-order-answer
        - partner-order-error-handling
      endpoints:
        - platform-http:/orders
        - direct:submitPartnerOrder
        - direct:partner-order-receive
        - direct:partner-order-check
        - direct:partner-order-answer
    consumes:
      schemas:
        - classpath:contract/api/partner-orders.openapi.yaml
        - classpath:contract/messages/partner-order.schema.json
      routeContracts:
        - order-check-returns-evaluation
        - classpath:sql/partner-receipt-claim.sql
        - classpath:sql/partner-receipt-release.sql
        - classpath:sql/accepted-content-select.sql
        - classpath:sql/rejection-reasons-select.sql
        - classpath:sql/order-reject.sql#notification-type-recipient
        - classpath:sql/order-record.sql#pick-list-outbox
      beans:
        - partnerRegistry
        - partnerOrders
        - orderAnswers
        - orderChecks#partnerNumberReasons
      properties:
        - peakmart.email-pattern
    dependsOn:
      - 1
      - 4
      - 7
      - 8
      - 11
  - id: 13
    title: Route YAML — partner-rejection-email
    files:
      creates:
        - src/main/resources/camel/partner-rejection-email.camel.yaml
      reads:
        - src/main/resources/camel/order-desk-notification.camel.yaml
        - docs/api/external/partner-mail.md
    provides:
      routes:
        - partner-rejection-email-send
        - partner-rejection-email-error-handling
    consumes:
      routeContracts:
        - classpath:sql/rejection-email-select.sql
      properties:
        - notification.poll-delay-ms
        - smtp.host
        - smtp.port
        - smtp.timeout-ms
        - smtp.starttls
      externalServices:
        - postgresql-15432
        - mailpit-1025
    dependsOn:
      - 4
  - id: 14
    title: Route YAML — pick-list-delivery
    files:
      creates:
        - src/main/resources/camel/pick-list-delivery.camel.yaml
      reads:
        - docs/api/external/warehouse-sftp.md
    provides:
      routes:
        - pick-list-delivery
        - pick-list-delivery-error-handling
      endpoints:
        - sftp:{{warehouse.sftp.host}}:{{warehouse.sftp.port}}/{{warehouse.sftp.inbound-directory}}
      routeContracts:
        - pick-list-csv-options
    consumes:
      routeContracts:
        - classpath:sql/pick-list-select.sql
        - classpath:sql/pick-list-rows.sql
        - classpath:sql/pick-list-delivered.sql
        - maven-dependencies-ready
      beans:
        - warehouseSftp
      externalServices:
        - postgresql-15432
        - sftp-2222
    dependsOn:
      - 1
      - 5
      - 9
  - id: 15
    title: Unit Test — PickListCsvTest
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/PickListCsvTest.java
      reads:
        - docs/messages/examples/pick-list/pl-tg-1060.json
        - src/main/resources/camel/pick-list-delivery.camel.yaml
      tests:
        - src/test/java/com/trailheadgear/orderintegration/PickListCsvTest.java
    provides:
      testData:
        - PickListCsvTest
    consumes:
      routeContracts:
        - pick-list-csv-options
    dependsOn:
      - 14
  - id: 16
    title: Development Configuration Files under config/dev
    files:
      creates:
        - config/dev/partner-configuration.json
        - config/dev/secrets/partner-api-key-summit-outfitters
        - config/dev/secrets/README.md
        - config/dev/warehouse_known_hosts
      reads:
        - docs/messages/examples/partner-configuration/summit-outfitters.json
    provides:
      testData:
        - dev-partner-configuration
        - dev-api-key-summit-outfitters
      routeContracts:
        - dev-sftp-directory-layout
    consumes:
      externalServices:
        - sftp-2222
    dependsOn: []
  - id: 17
    title: Application Properties — shared, default and dev (moved to config/)
    files:
      modifies:
        - src/main/resources/application.properties
        - src/main/resources/application-default.properties
      creates:
        - config/application-dev.properties
      deletes:
        - src/main/resources/application-dev.properties
      reads:
        - src/main/resources/camel/peakmart-order-check.camel.yaml
        - src/main/resources/camel/partner-order-api.camel.yaml
        - src/main/resources/camel/partner-rejection-email.camel.yaml
        - src/main/resources/camel/pick-list-delivery.camel.yaml
        - src/test/scripts/scenarios.sh
        - src/test/scripts/kill-test.sh
    provides:
      properties:
        - partner.max-request-chars
        - partner.checking-expiry-seconds
        - catalog.lookup-threads
        - management.server.port
        - server.ssl.enabled-protocols
        - pick-list.poll-delay-ms
        - pick-list.batch-size
        - warehouse.sftp.inbound-directory
        - warehouse.sftp.processed-directory
        - warehouse.sftp.timeout-ms
        - server.port
        - server.ssl.enabled
        - server.ssl.bundle
        - spring.ssl.bundle.pem.partner-endpoint.keystore.certificate
        - spring.ssl.bundle.pem.partner-endpoint.keystore.private-key
        - partner.configuration
        - secret-store.directory
        - partner.mail-sender
        - warehouse.sftp.host
        - warehouse.sftp.port
        - warehouse.sftp.username
        - warehouse.sftp.password
        - warehouse.sftp.known-hosts
    consumes:
      routes:
        - partner-order-submit
        - partner-rejection-email-send
        - pick-list-delivery
      testData:
        - dev-partner-configuration
      routeContracts:
        - dev-sftp-directory-layout
    dependsOn:
      - 11
      - 12
      - 13
      - 14
      - 16
  - id: 18
    title: Documentation — Entity Model and Open Questions
    files:
      modifies:
        - docs/entity_model.md
        - docs/open_questions.md
    provides:
      routeContracts:
        - doc-entity-model-r1
        - doc-open-questions-r2-r3-c14
    consumes: {}
    dependsOn: []
  - id: 19
    title: Scenario Script — src/test/scripts/partner-scenarios.sh
    files:
      creates:
        - src/test/scripts/partner-scenarios.sh
      reads:
        - src/test/scripts/scenarios.sh
        - docs/messages/examples/partner-order/partner-order-sample.json
        - docs/test_cases/TC-001-partner-order-to-warehouse.md
    provides:
      routeContracts:
        - partner-scenarios-script
    consumes:
      routes:
        - partner-order-submit
        - partner-rejection-email-send
        - pick-list-delivery
      testData:
        - dev-api-key-summit-outfitters
      properties:
        - server.port
        - partner.mail-sender
    dependsOn:
      - 12
      - 13
      - 16
      - 17
  - id: 20
    title: Scenario Script — src/test/scripts/pick-list-scenarios.sh
    files:
      creates:
        - src/test/scripts/pick-list-scenarios.sh
      reads:
        - docs/test_cases/TC-005-pick-list-once-after-restart.md
        - docs/messages/examples/pick-list/pl-tg-1060.json
    provides:
      routeContracts:
        - pick-list-scenarios-script
    consumes:
      routes:
        - pick-list-delivery
      routeContracts:
        - dev-sftp-directory-layout
      properties:
        - warehouse.sftp.inbound-directory
        - warehouse.sftp.processed-directory
    dependsOn:
      - 14
      - 16
      - 17
      - 19
```

---

### Task 1: Maven Dependencies and Contract Resources — pom.xml

**Agent:** implementation-engineer

**Files:**
- Modify: `pom.xml` (the existing pom; not recreated)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table, Step 5 (runtime artifacts)
- `camel-implement/guides/maven-dependencies.md`
- `camel-implement/guides/pom-spring-boot.md` — everything except the "no `spring-boot-starter-parent`" rule (002 R1 reviewed exception, P-2)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_maven(component="platform-http", B)`: must return `org.apache.camel.springboot:camel-platform-http-starter:4.22.2`
- `camel_catalog_component_maven(component="rest-openapi", B)`: must return `org.apache.camel.springboot:camel-rest-openapi-starter:4.22.2`
- `camel_catalog_component_maven(component="sftp", B)`: must return `org.apache.camel.springboot:camel-ftp-starter:4.22.2`
- `camel_catalog_dataformat_doc(dataformat="csv", B)`: confirm `org.apache.camel.springboot:camel-csv-starter:4.22.2`
- `camel_dependency_check(…)` on the resulting pom, with the parameters its schema requires and binding `B`

**Design Spec Section:** §3 Flow: partner-order-api (Source, Constraints: `$ref` copy, servlet web server); §3 Flow: partner registration step 1; §4 Constraints (new dependencies, resources copy)

- [ ] **Step 1:** Read `.camel-kit/config.properties` (Camel 4.22.2, BOM 4.22.2, Spring Boot 4.1.1). Keep the parent, the `spring-boot-dependencies` import and the `camel-spring-boot-bom` import unchanged.
- [ ] **Step 2:** Add, without `<version>`: `camel-platform-http-starter`, `camel-rest-openapi-starter`, `camel-ftp-starter`, `camel-csv-starter` (group `org.apache.camel.springboot`).
- [ ] **Step 3:** Servlet web server: run `./mvnw -q dependency:tree -Dincludes=org.springframework.boot` after Step 2. If `camel-platform-http-starter` already brings a Spring Boot web starter (servlet, e.g. `spring-boot-starter-web` or its Boot 4.1 equivalent) record it and add nothing. Otherwise add the servlet web starter from the Boot 4.1.1 BOM without `<version>`, taking the exact artifactId from `target/effective-pom.xml` (`./mvnw -q help:effective-pom -Doutput=target/effective-pom.xml`). Record the choice for the execution report.
- [ ] **Step 4:** JSch: confirm `com.github.mwiede:jsch` appears in `./mvnw -q dependency:tree` through `camel-ftp-starter`. Add no JSch dependency. Record the resolved version.
- [ ] **Step 5:** Extend the existing `maven-resources-plugin` configuration (keep the existing `schemas` execution and its three includes):
  - add `partner-configuration.schema.json` to the includes copied into `${project.build.outputDirectory}/schemas`;
  - add an execution that copies `${project.basedir}/docs/api/partner-orders.openapi.yaml` into `${project.build.outputDirectory}/contract/api`;
  - add an execution that copies `partner-order.schema.json`, `order-answer.schema.json` and `rejection-reason.schema.json` from `${project.basedir}/docs/messages` into `${project.build.outputDirectory}/contract/messages`.
  The relative layout (`api/` beside `messages/`) keeps the contract's `../messages/*.schema.json` `$ref`s valid. Copy nothing into `src/`.
- [ ] **Step 6:** Verify: `./mvnw -q -DskipTests process-resources && cmp docs/api/partner-orders.openapi.yaml target/classes/contract/api/partner-orders.openapi.yaml && for f in partner-order order-answer rejection-reason; do cmp docs/messages/$f.schema.json target/classes/contract/messages/$f.schema.json || exit 1; done && cmp docs/messages/partner-configuration.schema.json target/classes/schemas/partner-configuration.schema.json && test -s target/classes/schemas/order.schema.json && echo RESOURCES-OK` prints `RESOURCES-OK`. Then `./mvnw dependency:tree` exits 0 and contains `camel-platform-http-starter:jar:4.22.2`, `camel-rest-openapi-starter:jar:4.22.2`, `camel-ftp-starter:jar:4.22.2`, `camel-csv-starter:jar:4.22.2` and `com.github.mwiede:jsch`.

**Review:**
- [ ] Spec compliance: exactly four Camel starters plus the web starter only when needed; the contract and schema copies from `docs/` with the relative layout; JSch not added; findings recorded.
- [ ] Code quality: no hardcoded versions on BOM-managed artifacts; the parent is **not** a finding; no unrelated plugin or profile changes.

---

### Task 2: Flyway Migration V2 — Partners and Pick Lists

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/db/migration/V2__partners_and_pick_lists.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5 (non-route resources)
- `shared/camel-security-checklist.md` — constraints that keep personal data bounded

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only; Flyway is not a catalog artifact)

**Design Spec Section:** §3 *Supporting change: Flyway V2*; §3 Flow: partner-order-receive (Concurrency); `docs/entity_model.md` (PARTNER, PICK_LIST)

- [ ] **Step 1:** Read `V1__order_database.sql` for the existing columns of `order_receipt` (including `partner_id`, `content_hash`, `checking_since`), the `notification` email CHECK, and naming conventions. Read the PARTNER and PICK_LIST entities in `docs/entity_model.md`.
- [ ] **Step 2:** Create table `partner`: `id` (identity PK), `partner_code` UNIQUE, `name`, `api_key_name` UNIQUE, `order_number_from` and `order_number_to` as `bigint` each `>= 0`, `CHECK (order_number_from <= order_number_to)`, `order_contact_email` with the same email CHECK as `notification`, `registered_at timestamptz NOT NULL`, `deregistered_at timestamptz NULL`. Column sizes follow the entity model.
- [ ] **Step 3:** `ALTER TABLE order_receipt ADD CONSTRAINT … FOREIGN KEY (partner_id) REFERENCES partner(id)`.
- [ ] **Step 4:** `CREATE UNIQUE INDEX order_receipt_partner_content_uq ON order_receipt (partner_id, order_number, content_hash) WHERE channel = 'Partner'` and `CREATE UNIQUE INDEX order_receipt_accepted_number_uq ON order_receipt (order_number) WHERE order_state IN ('Recorded', 'Sent to Warehouse')`. Use the exact channel and state literals V1 uses.
- [ ] **Step 5:** Create table `pick_list` with the entity model's columns: `id`, `customer_order_id` UNIQUE FK to `customer_order`, `file_name varchar(50)` UNIQUE, `total_weight_g` CHECK 0–2000000000, `delivery_state` CHECK in (`Pending`, `Delivered`), `created_at`, `delivered_at`, `last_attempt_at`, `attempts` CHECK 0–100000 default 0, and `CHECK ((delivery_state = 'Delivered') = (delivered_at IS NOT NULL))`. Add `CREATE INDEX … ON pick_list (delivery_state, id)`.
- [ ] **Step 6:** Backfill (C-16): one `INSERT INTO pick_list … SELECT` for every `customer_order` whose receipt is `Recorded` and that has no pick list: `file_name = 'PL-' || order_number || '.csv'`, `total_weight_g = SUM(quantity × unit weight grams)` from its `order_line`s, `delivery_state = 'Pending'`, `created_at = now()`, `attempts = 0`.
- [ ] **Step 7:** No `IF NOT EXISTS`, no `DO $$` block, no baseline settings. Do not run the script against any database (P-5).
- [ ] **Step 8:** Verify: `f=src/main/resources/db/migration/V2__partners_and_pick_lists.sql; test -s $f && ! grep -qi 'IF NOT EXISTS' $f && grep -qiE 'CREATE TABLE +partner[ (]' $f && grep -qiE 'CREATE TABLE +pick_list[ (]' $f && grep -q 'deregistered_at' $f && grep -q 'order_receipt_partner_content_uq' $f && grep -q 'order_receipt_accepted_number_uq' $f && grep -qiE 'REFERENCES +partner' $f && grep -qiE 'INSERT INTO +pick_list' $f && echo V2-OK` prints `V2-OK`.

**Review:**
- [ ] Spec compliance: both tables, the FK, both partial unique indexes, every CHECK, the `(delivery_state, id)` index and the backfill exactly as §3 V2; `deregistered_at` present (R1).
- [ ] Code quality: one statement per DDL, tables before dependent indexes and FKs, literals consistent with V1, no destructive statement against existing data.

---

### Task 3: Shared Reject and Record Statements

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/sql/order-reject.sql`
- Modify: `src/main/resources/sql/order-record.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `shared/camel-security-checklist.md` — parameter binding, never string concatenation

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: named parameters `:#${…}` with simple expressions, `CamelSqlUpdateCount`

**Design Spec Section:** §3 *Flow: shared reject and record statements*; §3 partner-order-check step 5

- [ ] **Step 1:** `order-reject.sql`: replace the literal notification type `Order Desk Notification` and the recipient parameter that `order-reject` sets today (property `mailbox`) with `:#${exchangeProperty.notification-type}` and `:#${exchangeProperty.recipient}`. Add `checking_since = NULL` to the `UPDATE` of `order_receipt`. Keep the CTE, the reasons `WITH ORDINALITY`, and the guard "only from `Received`" unchanged.
- [ ] **Step 2:** `order-record.sql`: add `checking_since = NULL` to the `UPDATE` of `order_receipt`. Add one CTE that inserts `pick_list(customer_order_id, file_name, total_weight_g, delivery_state, created_at, attempts)` with `'PL-' || <order number> || '.csv'`, `Σ quantity × unitWeightGrams` from the order document, `'Pending'`, `now()`, `0`, chained from the inserted `customer_order` row so it runs in the same statement. Keep everything else unchanged.
- [ ] **Step 3:** Both statements must still update 0 rows when the receipt is no longer `Received` (partner-order-check step 5 depends on that count). Do not run them against the database.
- [ ] **Step 4:** Verify: `cd src/main/resources/sql && grep -q 'exchangeProperty.notification-type' order-reject.sql && grep -q 'exchangeProperty.recipient' order-reject.sql && ! grep -q "'Order Desk Notification'" order-reject.sql && grep -q 'checking_since *= *NULL' order-reject.sql && grep -q 'checking_since *= *NULL' order-record.sql && grep -qi 'INSERT INTO pick_list' order-record.sql && grep -q "'PL-'" order-record.sql && echo SHARED-SQL-OK` prints `SHARED-SQL-OK`; `git diff --stat -- src/main/resources/sql/` lists only these two files.

**Review:**
- [ ] Spec compliance: parameters for type and recipient; `checking_since` cleared in both; the pick-list row inserted atomically with recording; the `Received` guard unchanged.
- [ ] Code quality: only bound parameters; one statement per file; no change to reason ordering.

---

### Task 4: Partner SQL Statements

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/sql/partner-receipt-claim.sql`
- Create: `src/main/resources/sql/partner-receipt-release.sql`
- Create: `src/main/resources/sql/accepted-content-select.sql`
- Create: `src/main/resources/sql/rejection-reasons-select.sql`
- Create: `src/main/resources/sql/rejection-email-select.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `shared/camel-security-checklist.md` — bound parameters only

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: `outputType` `SelectOne`/`SelectList`, named parameters, `onConsume`

**Design Spec Section:** §3 Flow: partner-order-api, route 2 `partner-order-receive` (outcomes, Concurrency) and route 3 step 1 and CATALOG_OUTAGE branch; §3 Flow: partner-rejection-email (Source)

- [ ] **Step 1:** `partner-receipt-claim.sql` — one statement (CTEs allowed) returning one row `outcome`, `receipt_id`, `order_state`, parameters: the partner's database id, order number, content hash, correlation id, received-at, and `{{partner.checking-expiry-seconds}}` passed as a bound parameter (read by the route into a property). Outcomes:
  - `IN_PROGRESS`: any receipt of this partner and order number, any content, with `checking_since > now() - expiry`;
  - `DUPLICATE`: the receipt with the same `(partner_id, order_number, content_hash)` is `Rejected`, `Recorded` or `Sent to Warehouse` (return its state);
  - `CLAIMED`: that receipt is `Received` with `checking_since IS NULL OR checking_since < now() - expiry`; it is set to `now()` by `UPDATE … WHERE` that same condition;
  - `NEW`: insert receipt `Received`, `channel='Partner'`, `partner_id`, `order_number`, `content_hash`, `correlation_id`, `received_at`, `checking_since=now()` with `ON CONFLICT (partner_id, order_number, content_hash) WHERE channel = 'Partner' DO NOTHING`, plus the history row `Received`.
  Two identical concurrent requests must yield exactly one `NEW`/`CLAIMED` (C-8).
- [ ] **Step 2:** `partner-receipt-release.sql`: `UPDATE order_receipt SET checking_since = NULL WHERE id = :#${exchangeProperty.receipt-id} AND order_state = 'Received'`.
- [ ] **Step 3:** `accepted-content-select.sql`: the `content_hash` of the receipt of a `customer_order` with the given order number, or no row.
- [ ] **Step 4:** `rejection-reasons-select.sql`: the reasons of a receipt by `position`, each with its text and the order check mapped back to the contract enum of `docs/messages/rejection-reason.schema.json` (one `CASE` per stored check name).
- [ ] **Step 5:** `rejection-email-select.sql`: copy `notification-select.sql` and change only the filter to `notification_type = 'Rejection Email'`; keep `recipient_email`, order number, correlation id and the `reason_lines` aggregation (`- <text>` per reason, `position` order).
- [ ] **Step 6:** Verify: `cd src/main/resources/sql && for f in partner-receipt-claim partner-receipt-release accepted-content-select rejection-reasons-select rejection-email-select; do test -s $f.sql || exit 1; done && grep -q 'IN_PROGRESS' partner-receipt-claim.sql && grep -q 'DUPLICATE' partner-receipt-claim.sql && grep -q 'CLAIMED' partner-receipt-claim.sql && grep -q 'NEW' partner-receipt-claim.sql && grep -qi 'ON CONFLICT' partner-receipt-claim.sql && grep -q "Rejection Email" rejection-email-select.sql && ! grep -q 'Order Desk Notification' rejection-email-select.sql && echo PARTNER-SQL-OK` prints `PARTNER-SQL-OK`.

**Review:**
- [ ] Spec compliance: the four outcomes and their exact conditions; the 30 s expiry as a parameter; release only from `Received`; the reasons enum mapping; the email select differs from 002's only in the type filter.
- [ ] Code quality: bound parameters only; single atomic statement for the claim; no API key or contact selected where not needed.

---

### Task 5: Pick-List SQL Statements

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/sql/pick-list-select.sql`
- Create: `src/main/resources/sql/pick-list-rows.sql`
- Create: `src/main/resources/sql/pick-list-delivered.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `shared/camel-security-checklist.md` — select only the fields the warehouse needs

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: `outputType` `SelectList`, `maxMessagesPerPoll`, `onConsumeFailed`

**Design Spec Section:** §3 Flow: pick-list-delivery (Source, step 3 first bullet, step 4); `docs/messages/mappings/order-to-pick-list.md`

- [ ] **Step 1:** `pick-list-select.sql`: pending pick lists oldest first (`ORDER BY id`) with `id`, `file_name`, `customer_order_id`, `order_receipt_id`, `order_number`, `correlation_id`.
- [ ] **Step 2:** `pick-list-rows.sql`: one row per order line of `:#${exchangeProperty.customer-order-id}` (or the property name the route uses) in `line_number` order, with exactly the aliases `order_no, line_no, sku, description, qty, unit_weight_g, ship_name, ship_street, ship_city, ship_postcode, ship_country, total_weight_g`, in that order. Follow `order-to-pick-list.md`: `ship_street` is the street lines joined by `', '`; never select `state_region`, prices or email (C-021).
- [ ] **Step 3:** `pick-list-delivered.sql`: one statement with three effects: `UPDATE pick_list SET delivery_state='Delivered', delivered_at=now(), last_attempt_at=now(), attempts=LEAST(attempts+1,100000) WHERE id=… AND delivery_state='Pending'`; `UPDATE order_receipt SET order_state='Sent to Warehouse' WHERE id=… AND order_state='Recorded'`; insert `order_state_change` `Sent to Warehouse` with `ON CONFLICT (order_receipt_id, order_state) DO NOTHING`.
- [ ] **Step 4:** Verify: `cd src/main/resources/sql && grep -q 'order_no' pick-list-rows.sql && grep -q 'total_weight_g' pick-list-rows.sql && ! grep -qiE 'state_region|price|email' pick-list-rows.sql && grep -q "Sent to Warehouse" pick-list-delivered.sql && grep -q 'LEAST(attempts *+ *1, *100000)' pick-list-delivered.sql && grep -qi 'ON CONFLICT' pick-list-delivered.sql && grep -qi "delivery_state *= *'Pending'" pick-list-select.sql && echo PICKLIST-SQL-OK` prints `PICKLIST-SQL-OK`.

**Review:**
- [ ] Spec compliance: the twelve columns in order; no state/price/email; the delivered statement is idempotent and conditional on `Pending`/`Recorded`.
- [ ] Code quality: bound parameters; deterministic order; single statement for step 4.

---

### Task 6: OrderChecks — Channel-Aware Checks, Partner Reasons, Catalog Price, Lookup Pool

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/java/com/trailheadgear/orderintegration/OrderChecks.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java` (add `catalogLookupPool`)
- Create: `src/test/java/com/trailheadgear/orderintegration/OrderChecksPartnerTest.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5 (Java beans)
- `shared/forage.md` — Custom Bean Construction policy (Forage unavailable; Spring `@Bean`)
- `camel-implement/guides/advanced-patterns.md` — split with a custom executor service

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_eip_doc(eip="split", B)`: `executorService` reference type

**Design Spec Section:** §3 Flow: shared order checks — *Changes to `OrderChecks`* and change 3 (pool); §7 C-11, C-12, C-13

- [ ] **Step 1:** Order-number format by the canonical order's `channel`: `partner` → `^TG-\d{4,}$` with the new text from §3; `peakmart` → `^PM-\d+$` with the unchanged 002 text.
- [ ] **Step 2:** `structuralReasons(order, orderNumberReasons)`: new second parameter (a list, null for Peakmart). Insert its reasons right after the format reason, keeping check order (UC-003 steps 1–6, BR-010). Keep a one-argument overload only if another caller needs it.
- [ ] **Step 3:** New `partnerNumberReasons(order, partnerRangeFirst, partnerRangeLast, acceptedContentHash, contentHash)`: `order-number-range` when the number is `TG-` + digits (any count) and the `BigInteger` value is outside `[first, last]`; `order-number-already-used` when `acceptedContentHash` is not null and differs from `contentHash`. Texts exactly as §3. A number without digits after `TG-` yields no range reason (C-11).
- [ ] **Step 4:** In `evaluate`, when the outcome is PASSED and `channel` is `partner`, set every line's `unitPrice` to `{"amount": listPrice (BigDecimal, scale 2, HALF_UP) as string, "currency": "USD"}` in the shape `order.schema.json` requires. Peakmart lines are unchanged.
- [ ] **Step 5:** All other reason texts stay as they are. `CannotReadReason.java` is not touched.
- [ ] **Step 6:** In `IntegrationBeans`, add `@Bean("catalogLookupPool")` returning a fixed `ExecutorService` of `${catalog.lookup-threads}` threads (inject the property), with a recognisable thread-name prefix and `destroyMethod` `shutdown`.
- [ ] **Step 7:** `OrderChecksPartnerTest` (JUnit, no Spring or Camel context): partner format accepted/rejected, `TG-12` gives format **and** range, `TG-2500` outside 1000–1999, `TG-` alone gives only format, a 40-digit number does not overflow, already-used only when the hashes differ, reasons in check order, partner price `249.00` from `listPrice` `249`, Peakmart price unchanged, and Peakmart format text unchanged.
- [ ] **Step 8:** Verify: `./mvnw -Dtest='OrderChecksPartnerTest,EmailPatternTest' test` ends with `BUILD SUCCESS` and Surefire shows `Failures: 0, Errors: 0` for both classes; `git diff --quiet -- src/main/java/com/trailheadgear/orderintegration/CannotReadReason.java && echo CRR-UNCHANGED` prints `CRR-UNCHANGED`.

**Review:**
- [ ] Spec compliance: both formats; range and already-used rules and texts; reasons order; catalog price for partners only; pool size from the property.
- [ ] Code quality: `BigInteger`/`BigDecimal` correctness; no logging of order data; the pool is shut down with the context; tests deterministic.

---

### Task 7: Partner Registration — SecretStore, PartnerRegistry, PartnerRegistration

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/SecretStore.java` (interface)
- Create: `src/main/java/com/trailheadgear/orderintegration/DirectorySecretStore.java`
- Create: `src/main/java/com/trailheadgear/orderintegration/PartnerRegistry.java`
- Create: `src/main/java/com/trailheadgear/orderintegration/PartnerRegistration.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/PartnerRegistrationTest.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java` (bean declarations, if not `@Component`)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `shared/forage.md` — Custom Bean Construction policy
- `shared/camel-security-checklist.md` — secrets handling, path traversal, constant-time comparison, no secret logging

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding)
- `camel_catalog_component_doc(component="json-validator", B)`: confirm the networknt validator library the component uses, so the bean uses the same one

**Design Spec Section:** §3 Flow: partner registration (UC-007) steps 1–5, `partnerRegistry`; §3 Supporting change: Java beans; §7 C-1, C-3, C-4, C-5

- [ ] **Step 1:** `SecretStore` interface `read(apiKeyName)` → `Optional<String>`. `DirectorySecretStore` reads `<secret-store.directory>/<apiKeyName>`, trims a trailing newline, treats an empty file as missing, and rejects any name not matching `^[A-Za-z0-9._-]{1,100}$` or containing `..`; it also checks that the resolved path stays inside the directory.
- [ ] **Step 2:** `PartnerRegistry`: holds an immutable `Map<sha256Hex, Partner>` set once. `authenticate(String key)` returns the partner (`id`, `partnerId`, `rangeFirst`, `rangeLast`, `orderContact`) or null for null/blank/unknown keys. It hashes the given key with SHA-256 and compares with `MessageDigest.isEqual` against each registered hash (no early return on match position that leaks timing beyond the loop). It never logs, stores or returns the key.
- [ ] **Step 3:** `PartnerRegistration`: Spring bean with `@DependsOn` on the Flyway initializer bean. Confirm the bean name in Spring Boot 4.1.1 (`flywayInitializer` expected) from the Boot auto-configuration sources or `./mvnw dependency:sources` + a search; record it.
- [ ] **Step 4:** Read `{{partner.configuration}}`; validate it against `classpath:schemas/partner-configuration.schema.json` with networknt. Collect **all** problems: duplicate `partnerId` (A1); `last < first` and every overlapping pair naming both partners (A2); `orderContact` not matching `peakmart.email-pattern` (A3); unsafe `apiKeyName` (C-1); missing or empty key file (A4); two partners with the same key value (C-5, naming both partners, never the key). A missing or unreadable file is invalid in the same way.
- [ ] **Step 5:** Valid: in one transaction (Spring `JdbcTemplate` + `TransactionTemplate` on `dataSource`), upsert by `partner_code` (`name`, range, contact, `api_key_name`, `registered_at=now()` for new or changed rows, `deregistered_at=NULL`) and set `deregistered_at=now()` on rows missing from the file. Build the registry from the file. Log INFO `Partner Configuration registered partners=<n>`.
- [ ] **Step 6:** Invalid: log ERROR `Partner Configuration not registered: <problem>` once per problem; change no row; build the registry from `partner` rows with `deregistered_at IS NULL`, re-reading keys from the Secret Store; a partner whose key is gone is left out with its own ERROR line. The application keeps running.
- [ ] **Step 7:** `PartnerRegistrationTest` (JUnit, temporary directories, no database: put validation and problem collection in a method testable without JDBC): one test per problem of Step 4, a valid file, all problems collected in one run, `../x` rejected, and `authenticate` returning null for an unknown key and the partner for the right key. No assertion message prints a key.
- [ ] **Step 8:** Verify: `./mvnw -Dtest=PartnerRegistrationTest test` ends with `BUILD SUCCESS`, `Failures: 0, Errors: 0`; `! grep -rnE 'log\.[a-z]+\(.*(apiKey|key\b|orderContact)' src/main/java/com/trailheadgear/orderintegration/Partner*.java src/main/java/com/trailheadgear/orderintegration/*SecretStore.java && echo NO-SECRET-LOGS` prints `NO-SECRET-LOGS`.

**Review:**
- [ ] Spec compliance: every UC-007 check plus C-1 and C-5; collect-all; valid → upsert and deregister; invalid → keep previous partners with re-read keys; registry ready before Camel starts.
- [ ] Code quality: constant-time compare; no key in logs, DB or exceptions; path-escape protection; immutable registry; single transaction.

---

### Task 8: PartnerOrders and OrderAnswers Beans

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/PartnerOrders.java`
- Create: `src/main/java/com/trailheadgear/orderintegration/OrderAnswers.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `shared/forage.md` — Custom Bean Construction policy
- `shared/camel-security-checklist.md` — no value echo in error texts, JSON built with a serializer

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="json-validator", B)`: the exception type thrown on validation failure (`JsonValidationException`) and how its errors are exposed

**Design Spec Section:** §3 Flow: partner-order-api, route 1 step 5 (Readable) and step 7 (`contentHash`), route 4 (`partner-order-answer`); §7 C-6, C-10, C-23

- [ ] **Step 1:** `PartnerOrders.cannotReadReason(exception)`: like `CannotReadReason` (read it), names the JSON path and keyword without values: e.g. `The request cannot be read as a Partner Order: the field orderId is missing.`, `… the field giftWrap is not part of the Partner Order.`; a non-JSON body gives `… the request is not JSON.`
- [ ] **Step 2:** `PartnerOrders.readableReason(body)`: returns null or one reason: `orderedAt` not an ISO-8601 date-time with offset; `customerEmail` present and not matching `peakmart.email-pattern` (inject the property); `orderId` longer than 30 characters or containing control characters.
- [ ] **Step 3:** `PartnerOrders.contentHash(body)`: SHA-256 hex of the canonical JSON value (keys sorted recursively, no whitespace, numbers by value via `BigDecimal.stripTrailingZeros`), so reformatting gives the same hash (C-6).
- [ ] **Step 4:** `OrderAnswers.body(status, orderNumber, reasonsJson)` with Jackson only:
  - 200: `{"orderNumber": …, "result": "accepted"}`;
  - 400/422/503: Problem with `type` `about:blank`, `title` (`Bad Request`, `Unprocessable Content`, `Service Unavailable`), `status`, fixed `detail` without personal data, `orderNumber` when known, `result` (`rejected`/`temporarily-unavailable`), `rejectionReasons` only for `rejected`;
  - 401/403: plain Problem (`Unauthorized`/`Forbidden`) without Order Answer members.
  Also expose the matching content type (`application/json` or `application/problem+json`). Field names and enums follow `docs/api/partner-orders.openapi.yaml` (`OrderProblem`) and `order-answer.schema.json`.
- [ ] **Step 5:** Declare `partnerOrders` and `orderAnswers` beans.
- [ ] **Step 6:** Verify: `./mvnw -q -DskipTests compile` exits 0 and `grep -q 'contentHash' src/main/java/com/trailheadgear/orderintegration/PartnerOrders.java && grep -q 'about:blank' src/main/java/com/trailheadgear/orderintegration/OrderAnswers.java && ! grep -qE '"\{\\"|\+ *"\\"' src/main/java/com/trailheadgear/orderintegration/OrderAnswers.java && echo ANSWERS-OK` prints `ANSWERS-OK` (no hand-built JSON).

**Review:**
- [ ] Spec compliance: every C-10 guard; canonical hash; all answer shapes per status and the contract; no `instance`, no correlation id in answers (C-23).
- [ ] Code quality: Jackson serialization; no value echo in reasons; no logging of bodies.

---

### Task 9: WarehouseSftp Bean — Delivered-File Check

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `shared/forage.md` — Custom Bean Construction policy
- `shared/camel-security-checklist.md` — strict host-key checking, credentials from properties

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", B)`: `knownHostsFile`, `strictHostKeyChecking`, `useUserKnownHostsFile`, `connectTimeout`, `soTimeout` (the bean mirrors these settings)

**Design Spec Section:** §3 Flow: pick-list-delivery step 2 and Rationale; §7 C-17, C-18

- [ ] **Step 1:** `deliveredFolder(fileName)`: with JSch (`com.jcraft.jsch`, from `com.github.mwiede:jsch`), `stat` `<inbound>/<fileName>` **first**, then `<processed>/<fileName>`; return `"inbound"`, `"processed"`, or null. "No such file" is a miss; every other SFTP error propagates as an exception (the attempt fails).
- [ ] **Step 2:** Connection settings from the same properties as the endpoint: `warehouse.sftp.host`, `.port`, `.username`, `.password`, `.known-hosts` (`StrictHostKeyChecking=yes`, known hosts from that file only), `.timeout-ms` for connect and socket timeouts, and the two directory properties.
- [ ] **Step 3:** One reused session and channel; reconnect when broken; close on bean destroy. Thread-safe for the single poller (synchronised method).
- [ ] **Step 4:** Validate `fileName` against `^PL-(TG-\d{4,}|PM-\d+)\.csv$` before use (path safety).
- [ ] **Step 5:** Never log the password. Declare the `warehouseSftp` bean.
- [ ] **Step 6:** Verify: `./mvnw -q -DskipTests compile` exits 0, and `f=src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java; grep -q 'StrictHostKeyChecking' $f && grep -q '"yes"' $f && grep -q 'stat' $f && grep -q 'warehouse.sftp.known-hosts' $f && echo SFTP-BEAN-OK` prints `SFTP-BEAN-OK`.

**Review:**
- [ ] Spec compliance: inbound before processed; check before every attempt is possible (stateless per call); same settings as the endpoint; no listing.
- [ ] Code quality: host key verified; timeouts set; reconnect on failure; no secrets logged; file-name validation.

---

### Task 10: Startup Check — RequiredVariablesCheck

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/RequiredVariablesCheck.java`
- Create: `src/main/resources/META-INF/spring.factories`
- Create: `src/test/java/com/trailheadgear/orderintegration/RequiredVariablesCheckTest.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Step 5.5
- `camel-implement/guides/properties-generation.md` — Spring `${…}` placeholder semantics

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 *Carried item 3: one startup check that names every missing variable*

- [ ] **Step 1:** Confirm the Boot 4.1.1 `EnvironmentPostProcessor` interface package and the `spring.factories` key from the Spring Boot 4.1.1 jar (`unzip -p` its `META-INF/spring.factories` or the class in the local Maven repository). Record the key.
- [ ] **Step 2:** Implement: run after config-data loading (order after `ConfigDataEnvironmentPostProcessor`). Iterate the environment's property sources whose name marks them as config files (`application*.properties` from classpath and `./config/`). Collect every `${NAME}` placeholder without a `:default`. Check `environment.containsProperty(NAME)`; an empty value counts as set.
- [ ] **Step 3:** If any are missing, throw one `IllegalStateException` with `Missing environment variables: ` + sorted, comma-separated names. Never print a value.
- [ ] **Step 4:** Register it in `META-INF/spring.factories` under the confirmed key.
- [ ] **Step 5:** `RequiredVariablesCheckTest`: a `StandardEnvironment` with a `MapPropertySource`-backed fake config source holding `a=${X}`, `b=${Y}`, `c=${Z:d}`; nothing set → message lists `X, Y` sorted and not `Z`; all set (one empty) → no exception.
- [ ] **Step 6:** Verify: `./mvnw -Dtest=RequiredVariablesCheckTest test` ends with `BUILD SUCCESS`, `Failures: 0, Errors: 0`; `grep -q 'RequiredVariablesCheck' src/main/resources/META-INF/spring.factories && echo FACTORIES-OK` prints `FACTORIES-OK`.

**Review:**
- [ ] Spec compliance: derived from placeholders (no second list); all missing in one failure; sorted; empty counts as set; runs before datasource, Flyway, registration and routes.
- [ ] Code quality: no values printed; ordering relative to config data correct; registration key verified for Boot 4.1.

---

### Task 11: Route YAML — peakmart-order-check (channel-neutral order-check)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/peakmart-order-check.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/advanced-patterns.md` — parallel split with an executor service
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_eip_doc(eip="split", B)`: `parallelProcessing`, `executorService`, `aggregationStrategy`
- `camel_catalog_eip_doc(eip="choice", B)`, `camel_catalog_eip_doc(eip="setProperty", B)`
- `camel_catalog_language_doc(language="bean", B)` (model name `method`)
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with binding `B`

**Design Spec Section:** §3 Flow: shared order checks — *Changes to `order-check`* 1–3; §3 *Flow: shared reject and record statements* (Peakmart `order-reject` sets type and recipient); §7 C-26

- [ ] **Step 1:** Move the `choice` node `choice-check-outcome` (with `when-catalog-outage`, `when-rejected`, `when-passed`, their node ids and steps) unchanged from route `order-check` to the end of route `peakmart-order-check-poll`, after `to-order-check`. `order-check` ends with `bean-evaluate`; its body is the evaluation map.
- [ ] **Step 2:** `set-structural-reasons` calls `orderChecks.structuralReasons(${exchangeProperty.order}, ${exchangeProperty.order-number-reasons})`. Peakmart sets no such property (null).
- [ ] **Step 3:** `split-order-lines`: add `parallelProcessing: true` and `executorService: "#catalogLookupPool"`. Keep `GroupedBodyAggregationStrategy` and the single `throttle-product-catalog` in `product-lookup` unchanged.
- [ ] **Step 4:** Route `order-reject`: replace `set-mailbox` with two `setProperty` steps: `notification-type` = constant `Order Desk Notification` and `recipient` = constant `{{orderdesk.mailbox}}` (node ids `set-notification-type`, `set-recipient`). Keep `to-order-reject-sql` and `log-order-rejected`.
- [ ] **Step 5:** Change nothing else (`product-lookup`, `order-record`, the error handling, texts, log lines). `git diff` shows only Steps 1–4.
- [ ] **Step 6:** Run the `route-validation.md` loop until it passes.
- [ ] **Step 7:** Verify: `f=src/main/resources/camel/peakmart-order-check.camel.yaml; grep -q 'parallelProcessing: true' $f && grep -q '#catalogLookupPool' $f && grep -q 'order-number-reasons' $f && grep -q 'id: set-notification-type' $f && grep -q 'id: set-recipient' $f && ! grep -q 'id: set-mailbox' $f && [ "$(grep -c 'id: choice-check-outcome' $f)" = 1 ] && awk '/id: peakmart-order-check-poll/{p=1} /id: order-check$/{p=0} p' $f | grep -q 'choice-check-outcome' && echo ORDERCHECK-OK` prints `ORDERCHECK-OK`.

**Review:**
- [ ] Spec compliance: choice moved unchanged; `order-check` returns the evaluation; the structural-reasons second argument; parallel split on the pool behind the one throttle; Peakmart reject still notifies the Order Desk.
- [ ] Code quality: constitution rules 1–8; node ids kept; no other diff; route validation passed.

---

### Task 12: Route YAML — partner-order-api

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/camel/partner-order-api.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — Steps 1–2 (DataMapper, then route)
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/datamapper-validation.md`
- `camel-implement/guides/datamapper-groovy.md` — Transformation Engine is Groovy (inline)
- `shared/datamapper-canonicalize.md`
- `shared/camel-security-checklist.md` — header stripping, input size, auth before processing
- `shared/patterns-error-handling.md` — `routeConfiguration` + `onException`, handled
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_eip_doc(eip="rest", B)` and `camel_catalog_eip_doc(eip="openApi", B)`: `specification`, `missingOperation`, `clientRequestValidation` default
- `camel_catalog_component_doc(component="platform-http", B)`: `muteException`, `headerFilterStrategy`, and whether inbound `Camel*` headers are filtered (record the answer for R4)
- `camel_catalog_component_doc(component="rest-openapi", B)`: `specificationUri` with `classpath:`
- `camel_catalog_component_doc(component="json-validator", B)`, `camel_catalog_component_doc(component="sql", B)` (`outputType`, `CamelSqlUpdateCount`)
- `camel_catalog_eip_doc(eip=…, B)` for `routeConfiguration`, `onException`, `doTry`, `choice`, `filter`, `removeHeaders`, `setHeader`, `setProperty`, `setBody`, `convertBodyTo`, `log`, `to`
- `camel_catalog_language_doc(language=…, B)` for `simple`, `jsonpath`, `groovy`, `bean`, `constant`
- `camel_openapi_validate(…)` on `docs/api/partner-orders.openapi.yaml` with its schema parameters
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with binding `B`

**Design Spec Section:** §3 Flow: partner-order-api (Source, routes 1–4, routeConfiguration, Error Handling summary); §3 DataMapper: kaoto-datamapper-7d3b2e91; §4 Security; §7 C-6–C-10, C-23, C-24

- [ ] **Step 1:** `rest` with id `partner-orders-rest` and `openApi` `specification: classpath:contract/api/partner-orders.openapi.yaml`, `missingOperation: fail`. `clientRequestValidation` stays default (off). Confirm the operation id routes to `direct:submitPartnerOrder`.
- [ ] **Step 2:** `routeConfiguration` `partner-order-error-handling`: `onException` `java.lang.Exception`, `handled: true`, `maximumRedeliveries: 0`, `logExhaustedMessageBody: false`, `logExhaustedMessageHistory: false`; log WARN `Partner order temporarily unavailable after a failure orderNumber=… correlationId=… cause=${exception.class.name}` (class only); a `doTry` around `sql:classpath:sql/partner-receipt-release.sql` whose failure is ignored (only when `receipt-id` is set); then answer 503 `temporarily-unavailable` through `direct:partner-order-answer`. All four routes use it.
- [ ] **Step 3:** Route `partner-order-submit` (`from: direct:submitPartnerOrder`), steps in this order exactly as §3 route 1: `correlation-id` and `received-at`; `partner` = `method(partnerRegistry, authenticate(${header.X-API-Key}))`; `removeHeaders` `*`; `choice` partner null → WARN (no key) → 401; Readable `doTry` (`convertBodyTo` String UTF-8; size `filter` > `{{partner.max-request-chars}}`; `json-validator:classpath:contract/messages/partner-order.schema.json`; `doCatch` → `partnerOrders.cannotReadReason(exception)`; `partnerOrders.readableReason(body)`) → on failure INFO line → 400; `partnerId` (jsonpath) ≠ the key's partner → WARN → 403; DataMapper; `content-hash` from the original body; `order-number`; `to direct:partner-order-receive`. Keep the original body in a property before mapping.
- [ ] **Step 4:** DataMapper `kaoto-datamapper-7d3b2e91`: follow the datamapper guides; Groovy inline mapping of the 13 leaf fields exactly per the §3 table and `docs/messages/mappings/partner-order-to-order.md` (that file wins on difference): `channel` = `partner`, `receivedAt`/`correlationId` from parameters, `lineNumber` from position, `unitPrice` dropped, absent `shipTo` parts omitted, `stateRegion` not set. The script logs nothing.
- [ ] **Step 5:** Route `partner-order-receive`: `sql:classpath:sql/partner-receipt-claim.sql` with `outputType: SelectOne`, `dataSource: "#dataSource"`, `{{partner.checking-expiry-seconds}}` passed as a parameter; set `receipt-id`; `choice` on `outcome`: `IN_PROGRESS` → 503; `DUPLICATE` with `Recorded`/`Sent to Warehouse` → 200; `DUPLICATE` with `Rejected` → `sql:classpath:sql/rejection-reasons-select.sql` → 422 with the same reasons; `NEW`/`CLAIMED` → `direct:partner-order-check`.
- [ ] **Step 6:** Route `partner-order-check`: `accepted-content-select.sql` → `accepted-content-hash`; `order-number-reasons` = `orderChecks.partnerNumberReasons(...)` with the partner's range; `to direct:order-check`; `choice` on `${body[outcome]}`: CATALOG_OUTAGE → release → WARN → 503; REJECTED → `notification-type` = `Rejection Email`, `recipient` = partner `orderContact` → `sql:classpath:sql/order-reject.sql` (the shared statement, called directly; not the Peakmart `order-reject` route, which sets the Order Desk values) → INFO → 422 with every reason; PASSED → `json-validator:classpath:schemas/order.schema.json` → `sql:classpath:sql/order-record.sql` → INFO → 200. If the reject or record statement's `CamelSqlUpdateCount` is 0 → 503, nothing more written.
- [ ] **Step 7:** Route `partner-order-answer`: `removeHeaders` `*`, then `setHeader` `CamelHttpResponseCode` and `Content-Type` from `orderAnswers`, body from `orderAnswers.body(status, orderNumber, reasonsJson)`. No `Retry-After`.
- [ ] **Step 8:** Every route has `routeId` and `description`; every log line carries `orderNumber` (when known) and `correlationId`, never the body, key, contact or address. No secret in any URI.
- [ ] **Step 9:** Run the `route-validation.md` loop until it passes.
- [ ] **Step 10:** Verify: `f=src/main/resources/camel/partner-order-api.camel.yaml; for id in partner-orders-rest partner-order-submit partner-order-receive partner-order-check partner-order-answer partner-order-error-handling; do grep -q "id: $id" $f || exit 1; done && grep -q 'classpath:contract/api/partner-orders.openapi.yaml' $f && grep -q 'missingOperation: fail' $f && grep -q 'kaoto-datamapper-7d3b2e91' $f && grep -q 'X-API-Key' $f && [ "$(grep -c 'removeHeaders' $f)" -ge 2 ] && grep -q 'logExhaustedMessageBody: false' $f && grep -q 'partner-receipt-claim.sql' $f && ! grep -q 'clientRequestValidation: true' $f && echo PARTNER-API-OK` prints `PARTNER-API-OK`.

**Review:**
- [ ] Spec compliance: refusal order 401 → 400 → 403 (C-9); header strip right after authentication and before every response; every outcome and status per §3; DataMapper field-for-field; 0-row updates → 503; error handler releases best-effort and answers 503.
- [ ] Code quality: constitution rules 1–8; no personal data or key in logs (NFR-011); exception class only; no hand-built JSON; Kaoto-compatible YAML; route validation passed.

---

### Task 13: Route YAML — partner-rejection-email

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/camel/partner-rejection-email.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md` — header hygiene before a mail sink
- `shared/patterns-error-handling.md` — not-handled `onException` with an `sql` consumer
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: `delay`, `onConsume`, `onConsumeFailed`, `dataSource`
- `camel_catalog_component_doc(component="smtp", B)`: `from`, `useHeaderRecipients`, `useHeaderSubject`, `contentType`, `connectionTimeout`, `additionalJavaMailProperties`
- `camel_catalog_eip_doc(eip=…, B)` for `routeConfiguration`, `onException`, `removeHeaders`, `setHeader`, `setProperty`, `setBody`, `log`
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with binding `B`

**Design Spec Section:** §3 Flow: partner-rejection-email (Source, Steps, Error handling, Configuration)

- [ ] **Step 1:** Read `order-desk-notification.camel.yaml` (002) and mirror its structure: `routeConfiguration` `partner-rejection-email-error-handling` (`onException` `java.lang.Exception`, `handled` false, 0 redeliveries, `logExhaustedMessageBody: false`, `logExhaustedMessageHistory: false`, WARN `Rejection email not sent, tried again on the next poll orderNumber=… correlationId=…`).
- [ ] **Step 2:** Route `partner-rejection-email-send` from `sql:classpath:sql/rejection-email-select.sql` with `dataSource: "#dataSource"`, `delay: "{{notification.poll-delay-ms}}"`, and `onConsume`/`onConsumeFailed` exactly as in `order-desk-notification.camel.yaml` (including `LEAST(attempts + 1, 1000)` and `RAW(...)`).
- [ ] **Step 3:** Steps: set `order-number` and `correlation-id` → `removeHeaders "*"` → `setHeader To` = `${body[recipient_email]}` → `setHeader Subject` = `Order ${exchangeProperty.order-number} rejected` → `setBody` exactly `Order number: ${exchangeProperty.order-number}` newline `${body[reason_lines]}` → `to smtp://{{smtp.host}}:{{smtp.port}}` with `from: "{{partner.mail-sender}}"`, `useHeaderRecipients: true`, `useHeaderSubject: true`, `contentType: "text/plain; charset=UTF-8"`, and the same timeout and STARTTLS options as 002 → INFO `Rejection email sent orderNumber=… correlationId=…`.
- [ ] **Step 4:** Run the `route-validation.md` loop until it passes.
- [ ] **Step 5:** Verify: `f=src/main/resources/camel/partner-rejection-email.camel.yaml; grep -q 'id: partner-rejection-email-send' $f && grep -q 'routeConfigurationId: partner-rejection-email-error-handling' $f && grep -q 'sql:classpath:sql/rejection-email-select.sql' $f && [ "$(grep -c 'LEAST(attempts + 1, 1000)' $f)" = 2 ] && grep -q '{{partner.mail-sender}}' $f && grep -q 'removeHeaders' $f && grep -q 'Order ${exchangeProperty.order-number} rejected' $f && ! grep -qiE 'password|username' $f && echo REJECTION-EMAIL-OK` prints `REJECTION-EMAIL-OK`.

**Review:**
- [ ] Spec compliance: selection, subject, sender, body, options and the not-handled error handling as §3; delivery at least once.
- [ ] Code quality: constitution rules 1–8; the Order Contact never logged; no personal data in the email (C-023); route validation passed.

---

### Task 14: Route YAML — pick-list-delivery

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/camel/pick-list-delivery.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md` — strict host key, secrets via `RAW()` placeholders
- `shared/patterns-error-handling.md` — not-handled `onException` so `onConsumeFailed` runs
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`: `delay`, `maxMessagesPerPoll`, `onConsumeFailed`, `outputType`
- `camel_catalog_component_doc(component="sftp", B)`: `fileName`, `tempFileName`, `fileExist` (`Fail`), `charset`, `strictHostKeyChecking`, `knownHostsFile`, `useUserKnownHostsFile`, `username`, `password`, `connectTimeout`, `soTimeout`, `autoCreate`
- `camel_catalog_dataformat_doc(dataformat="csv", B)`: `header`, `delimiter`, `quote`, `quoteMode`, `recordSeparator`, and how a list of maps is marshalled
- `camel_catalog_eip_doc(eip=…, B)` for `routeConfiguration`, `onException`, `choice`, `marshal`, `setProperty`, `log`, `to`
- `camel_catalog_language_doc(language="bean", B)`
- `camel_validate_route(…)` and `camel_validate_yaml_dsl(…)` on the full file content, with binding `B`

**Design Spec Section:** §3 Flow: pick-list-delivery (Source, Steps 1–4, Error handling, Constraints); §7 C-18, C-25

- [ ] **Step 1:** `routeConfiguration` `pick-list-delivery-error-handling`: `onException` `java.lang.Exception`, `handled: false`, `maximumRedeliveries: 0`, `logExhaustedMessageBody: false`, `logExhaustedMessageHistory: false`, WARN `Pick list not delivered, tried again on the next poll orderNumber=… correlationId=… cause=${exception.class.name}`.
- [ ] **Step 2:** Route `pick-list-delivery` from `sql:classpath:sql/pick-list-select.sql` with `dataSource: "#dataSource"`, `delay: "{{pick-list.poll-delay-ms}}"`, `maxMessagesPerPoll: "{{pick-list.batch-size}}"`, `onConsumeFailed: "RAW(UPDATE pick_list SET attempts = LEAST(attempts + 1, 100000), last_attempt_at = now() WHERE id = :#id)"`. No `onConsume`.
- [ ] **Step 3:** Set `order-number`, `correlation-id`, `file-name`, `pick-list-id` (and the receipt and customer-order ids the SQL files need). Then `delivered-folder` = `method(warehouseSftp, deliveredFolder(${exchangeProperty.file-name}))`.
- [ ] **Step 4:** `choice`: not null → INFO `Pick list already on the warehouse server, counted as delivered orderNumber=… folder=… correlationId=…`; otherwise → `sql:classpath:sql/pick-list-rows.sql` (`outputType: SelectList`) → `marshal` `csv` with `header` the twelve column names in order, `delimiter: ","`, default quote, minimal quoting, default CRLF record separator → `to sftp:{{warehouse.sftp.host}}:{{warehouse.sftp.port}}/{{warehouse.sftp.inbound-directory}}` with `fileName: "${exchangeProperty.file-name}"`, `tempFileName: "${file:name}.tmp"`, `fileExist: Fail`, `charset: UTF-8`, `strictHostKeyChecking: "yes"`, `knownHostsFile: "{{warehouse.sftp.known-hosts}}"`, `useUserKnownHostsFile: false`, `username: "{{warehouse.sftp.username}}"`, `password: "RAW({{warehouse.sftp.password}})"`, `connectTimeout` and `soTimeout` `{{warehouse.sftp.timeout-ms}}`, `autoCreate: false` → INFO `Pick list delivered orderNumber=… file=… correlationId=…`.
- [ ] **Step 5:** After the choice, both branches: `to sql:classpath:sql/pick-list-delivered.sql`.
- [ ] **Step 6:** Run the `route-validation.md` loop until it passes.
- [ ] **Step 7:** Verify: `f=src/main/resources/camel/pick-list-delivery.camel.yaml; grep -q 'id: pick-list-delivery' $f && grep -q 'routeConfigurationId: pick-list-delivery-error-handling' $f && grep -q 'deliveredFolder' $f && grep -q 'order_no,line_no,sku,description,qty,unit_weight_g,ship_name,ship_street,ship_city,ship_postcode,ship_country,total_weight_g' $f && grep -q 'fileExist: Fail' $f && grep -q 'strictHostKeyChecking: "yes"' $f && grep -q 'useUserKnownHostsFile: false' $f && grep -q 'autoCreate: false' $f && grep -q 'tempFileName' $f && grep -q 'RAW({{warehouse.sftp.password}})' $f && grep -q 'pick-list-delivered.sql' $f && grep -q 'handled' $f && echo PICKLIST-ROUTE-OK` prints `PICKLIST-ROUTE-OK`.

**Review:**
- [ ] Spec compliance: existence check before every write; temp-file rename; `Fail` guard; strict host key; record step on both branches; not-handled errors so `onConsumeFailed` counts.
- [ ] Code quality: constitution rules 1–8; no body or history logged (CSV holds names and addresses); timeouts set; no secrets in the URI; route validation passed.

---

### Task 15: Unit Test — PickListCsvTest

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/PickListCsvTest.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules (test sources not packaged)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_dataformat_doc(dataformat="csv", B)`: Java option names matching the YAML options

**Design Spec Section:** §3 Flow: pick-list-delivery step 3 (`marshal csv` and the byte-for-byte check); §4 P-21; §7 C-25

- [ ] **Step 1:** Build a `DefaultCamelContext` (no Spring) with one test route `direct:csv` → `marshal` `CsvDataFormat` configured with the same header, delimiter, quote, quote mode and record separator as `pick-list-delivery.camel.yaml`.
- [ ] **Step 2:** Read `docs/messages/examples/pick-list/pl-tg-1060.json` with Jackson into a list of ordered maps (column order as the header). Expected bytes: the header line plus the two rows, each terminated by CRLF, UTF-8, no BOM. Assert byte equality.
- [ ] **Step 3:** A second test with one row whose `ship_street` is `18 Canyon Road, Unit "B"`: the field is quoted and inner quotes doubled (`"18 Canyon Road, Unit ""B"""`); unquoted fields stay unquoted.
- [ ] **Step 4:** A third test reads the route YAML as text and asserts it still contains the same header string and no `recordSeparator` other than the default, so the test cannot drift from the route.
- [ ] **Step 5:** Verify: `./mvnw -Dtest=PickListCsvTest test` ends with `BUILD SUCCESS`, `Tests run: 3, Failures: 0, Errors: 0`.

**Review:**
- [ ] Spec compliance: byte-for-byte against the example; CRLF; RFC 4180 minimal quoting.
- [ ] Code quality: no Spring context; expected data read from `docs/`; deterministic.

---

### Task 16: Development Configuration Files under config/dev

**Agent:** implementation-engineer

**Files:**
- Create: `config/dev/partner-configuration.json`
- Create: `config/dev/secrets/partner-api-key-summit-outfitters`
- Create: `config/dev/secrets/README.md`
- Create: `config/dev/warehouse_known_hosts`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — scope rules
- `shared/camel-security-checklist.md` — development-only credentials, never real secrets

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 Flow: partner registration — *Development Secret Store and configuration*; §3 Flow: pick-list-delivery (Configuration `dev`); §3 Carried item 7; §7 C-1, C-2, C-17

- [ ] **Step 1:** `config/dev/partner-configuration.json`: an exact copy of `docs/messages/examples/partner-configuration/summit-outfitters.json`.
- [ ] **Step 2:** `config/dev/secrets/partner-api-key-summit-outfitters`: a newly generated random development key (e.g. `openssl rand -hex 32`), one line, no other content. It must not appear anywhere else in the repository.
- [ ] **Step 3:** `config/dev/secrets/README.md`: this directory stands in for the Secret Store in development (one file per API Key, named by `apiKeyName`) and must never hold a real key.
- [ ] **Step 4:** `config/dev/warehouse_known_hosts`: run `camel infra get sftp` (read-only; it starts nothing) to get the dev server's host key, and write it in OpenSSH known-hosts format for `[localhost]:2222`. If the output does not include a host key, obtain it with `ssh-keyscan -p 2222 localhost` and record which source was used.
- [ ] **Step 5:** Dev folder layout (P-7): with `sftp -P 2222 -o UserKnownHostsFile=config/dev/warehouse_known_hosts admin@localhost` (password `admin`) in batch mode, run one `stat` (or `ls -d`) on `/inbound/picklists` and on `inbound/picklists`. Record which resolves. Create no folder. If only the relative path exists, Task 17 sets the dev directory overrides.
- [ ] **Step 6:** Verify: `cmp docs/messages/examples/partner-configuration/summit-outfitters.json config/dev/partner-configuration.json && test -s config/dev/secrets/partner-api-key-summit-outfitters && [ "$(wc -l < config/dev/secrets/partner-api-key-summit-outfitters)" -le 1 ] && test -s config/dev/secrets/README.md && grep -q '2222' config/dev/warehouse_known_hosts && k=$(cat config/dev/secrets/partner-api-key-summit-outfitters) && [ "$(git grep -lF "$k" -- . ':!config/dev/secrets' | wc -l)" = 0 ] && echo DEVCONFIG-OK` prints `DEVCONFIG-OK`; the folder-layout result is recorded.

**Review:**
- [ ] Spec compliance: config equals TC-001's partner; key file named by `apiKeyName`; README states the rule; known-hosts for the dev server; layout recorded.
- [ ] Code quality: nothing under `src/`; no service started or stopped; the key is not echoed into logs or reports.

---

### Task 17: Application Properties — shared, default and dev (moved to config/)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-default.properties`
- Create: `config/application-dev.properties` (moved with `git mv` from `src/main/resources/application-dev.properties`)
- Delete: `src/main/resources/application-dev.properties`

**Guides to Load:**
- `camel-implement/guides/properties-generation.md` — Spring Boot `${…}` references, §5.4 validation gate
- `shared/camel-security-checklist.md` — secrets only from the environment
- `shared/forage.md` — Configuration Ladder rung 2 (`server.*`, `spring.ssl.*`)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)` (port and TLS come from Spring Boot `server.*`)
- `camel_configuration_validate(…)` on each of the three files, with binding `B`

**Design Spec Section:** §3 Flow: partner-order-api (*Configuration properties*); §3 Flow: partner-rejection-email (Configuration); §3 Flow: pick-list-delivery (*Configuration properties*); §3 Carried items 3 and 7; §2 table; §7 C-14, C-15, C-21, C-22

- [ ] **Step 1:** `git mv src/main/resources/application-dev.properties config/application-dev.properties`.
- [ ] **Step 2:** Add to `application.properties` (shared): `partner.max-request-chars=1048576`, `partner.checking-expiry-seconds=30`, `catalog.lookup-threads=10`, `management.server.port=-1`, `server.ssl.enabled-protocols=TLSv1.3,TLSv1.2`, `pick-list.poll-delay-ms=30000`, `pick-list.batch-size=50`, `warehouse.sftp.inbound-directory=/inbound/picklists`, `warehouse.sftp.processed-directory=/inbound/picklists/processed`, `warehouse.sftp.timeout-ms=10000`. Keep every existing key.
- [ ] **Step 3:** Add to `application-default.properties` exactly these 13 keys, each environment variable without a default: `server.port=${PARTNER_HTTP_PORT}`, `server.ssl.enabled=true`, `server.ssl.bundle=partner-endpoint`, `spring.ssl.bundle.pem.partner-endpoint.keystore.certificate=${PARTNER_TLS_CERTIFICATE}`, `spring.ssl.bundle.pem.partner-endpoint.keystore.private-key=${PARTNER_TLS_PRIVATE_KEY}`, `partner.configuration=${PARTNER_CONFIGURATION}`, `secret-store.directory=${SECRET_STORE_DIR}`, `partner.mail-sender=${PARTNER_MAIL_SENDER}`, `warehouse.sftp.host=${WAREHOUSE_SFTP_HOST}`, `warehouse.sftp.port=${WAREHOUSE_SFTP_PORT}`, `warehouse.sftp.username=${WAREHOUSE_SFTP_USERNAME}`, `warehouse.sftp.password=${WAREHOUSE_SFTP_PASSWORD}`, `warehouse.sftp.known-hosts=${WAREHOUSE_SFTP_KNOWN_HOSTS}`. Confirm the Boot 4.1.1 SSL bundle key names against the Boot configuration metadata and record them.
- [ ] **Step 4:** Add to `config/application-dev.properties`: `server.port=8082`, `server.ssl.enabled=false`, `partner.configuration=config/dev/partner-configuration.json`, `secret-store.directory=config/dev/secrets`, `partner.mail-sender=orders@trailhead.example`, `warehouse.sftp.host=localhost`, `warehouse.sftp.port=2222`, `warehouse.sftp.username=admin`, `warehouse.sftp.password=admin`, `warehouse.sftp.known-hosts=config/dev/warehouse_known_hosts`. Only if Task 16 recorded the relative layout, also set `warehouse.sftp.inbound-directory=inbound/picklists` and `warehouse.sftp.processed-directory=inbound/picklists/processed`.
- [ ] **Step 5:** Run `camel_configuration_validate` on each file; fix every finding that is not a Spring `${…}` false positive; record the result (§5.4 gate).
- [ ] **Step 6:** Cross-check: every `{{key}}` in `src/main/resources/camel/*.yaml` (`grep -ohE '\{\{[a-z0-9.-]+\}\}' src/main/resources/camel/*.yaml | sort -u`) and every `@Value`/`Environment` key read by the new beans is defined in `application.properties` or in both `application-default.properties` and `config/application-dev.properties`. List none missing.
- [ ] **Step 7:** Confirm `src/test/scripts/scenarios.sh` and `src/test/scripts/kill-test.sh` start the application from the project root with the `dev` profile (read only; record the line that shows it). Change them only if they `cd` elsewhere.
- [ ] **Step 8:** Verify: `test ! -e src/main/resources/application-dev.properties && test -s config/application-dev.properties && grep -q '^server.port=8082$' config/application-dev.properties && grep -q '^management.server.port=-1$' src/main/resources/application.properties && grep -q '^partner.checking-expiry-seconds=30$' src/main/resources/application.properties && [ "$(grep -oE '\$\{[A-Z_]+\}' src/main/resources/application-default.properties | sort -u | wc -l | tr -d ' ')" = 26 ] && ! grep -qE '\$\{[A-Z_]+:' src/main/resources/application-default.properties && ! grep -rqE 'admin|=test$' src/main/resources/application*.properties && echo PROPS-OK` prints `PROPS-OK` (26 distinct environment variables, none with a default; no dev credential in `src/main/resources`).

**Review:**
- [ ] Spec compliance: every key and value of §3; 26 variables without defaults; dev file moved out of `src/`; actuator HTTP off; TLS 1.2/1.3 without a profile.
- [ ] Code quality: no secret literal in the jar; Spring `${…}` only inside properties; configuration validation gate passed.

---

### Task 18: Documentation — Entity Model and Open Questions

**Agent:** implementation-engineer

**Files:**
- Modify: `docs/entity_model.md`
- Modify: `docs/open_questions.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — scope rules (docs changes only where the design requires them)

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3 *Supporting change: documentation (design review R1–R3, C-14)*; §8 R1–R3; §4 P-22

- [ ] **Step 1:** `docs/entity_model.md` `PARTNER`: add attribute `deregistered_at` (Timestamp, Null) and the rule that a partner removed from the Partner Configuration is deregistered (`deregistered_at` set), never deleted, because receipts reference it; a deregistered partner has no valid API Key.
- [ ] **Step 2:** `ORDER_RECEIPT`: the uniqueness rule becomes "(partner_id, order_number, content_hash) are unique together for Partner receipts". Update every diagram or table row of the file that states the old rule.
- [ ] **Step 3:** Append a change-note section at the end of `docs/entity_model.md`: pipeline 003-partner-orders-pick-lists, 2026-11-13, design review R1, listing the two changes.
- [ ] **Step 4:** `docs/open_questions.md` OQ-074: `**Status:** Answered`; replace the `**Assumed:**` line with `**Answer:** Reading (b): the being-checked mark (\`checking_since\`) expires after 30 seconds.` and add `**Answered by:** order desk lead, design review of pipeline 003`. Keep the heading and body text.
- [ ] **Step 5:** Append two entries in OQ-076's format (P-9 numbers):
  - `## OQ-077: Which line ending do pick-list files use?` — Element: UC-004, warehouse-sftp.md; Raised by: design 003-partner-orders-pick-lists (design review, R3), 2026-11-13; Blocking: No; Status: Open; text: CRLF is assumed (RFC 4180); to be confirmed with the Warehouse Team before the first real pick list.
  - `## OQ-078: Who terminates TLS for the partner endpoint?` — Element: UC-001, partner-orders.openapi.yaml; Raised by: design 003-partner-orders-pick-lists (C-14), 2026-11-13; Blocking: No; Status: Open; text: the application terminates TLS without a profile (PEM bundle, TLS 1.2/1.3); operations confirms whether a load balancer does it instead.
- [ ] **Step 6:** Verify: `grep -q 'deregistered_at' docs/entity_model.md && grep -q '003-partner-orders-pick-lists' docs/entity_model.md && sed -n '/^## OQ-074/,/^## OQ-075/p' docs/open_questions.md | grep -q '^\*\*Status:\*\* Answered' && sed -n '/^## OQ-074/,/^## OQ-075/p' docs/open_questions.md | grep -q 'order desk lead' && ! sed -n '/^## OQ-074/,/^## OQ-075/p' docs/open_questions.md | grep -q '^\*\*Assumed:' && grep -c '^## OQ-07[78]:' docs/open_questions.md | grep -qx 2 && git diff --name-only -- docs/ | grep -vE '^docs/(entity_model|open_questions)\.md$|^docs/camel-kit/003-partner-orders-pick-lists/' | wc -l | grep -qx ' *0' && echo DOCS-OK` prints `DOCS-OK`.

**Review:**
- [ ] Spec compliance: R1 entity changes with a change note; OQ-074 answered as R2; two new non-blocking questions (R3, C-14) in the existing format.
- [ ] Code quality: no other entry changed; consistent formatting.

---

### Task 19: Scenario Script — src/test/scripts/partner-scenarios.sh

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/scripts/partner-scenarios.sh`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules
- `shared/camel-security-checklist.md` — the script prints counts and statuses, not keys or personal data

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §4 *Acceptance criteria and scenario approach* P-1 to P-8, P-11; TC-001 steps 1–5

- [ ] **Step 1:** Expect the application already running with `dev` on `localhost:8082` (started by the verifier). Read the dev key from `config/dev/secrets/partner-api-key-summit-outfitters`; never print it.
- [ ] **Step 2:** Scenarios P-1 to P-8 and P-11, each with its own order number from 1000–1999 and the 002 rerun guard (skip or pick a fresh number when the number already has a receipt). Assert status, content type and body fields with `jq`; assert DB effects with read-only `docker exec camel-postgres psql` (receipts, history, `customer_order`, `pick_list`, `partner` row count and content for P-11); assert Mailpit messages for P-3 by counting before and after (`http://localhost:8025/api/v1/search`), never deleting.
- [ ] **Step 3:** P-1 also waits (bounded, ≤ 90 s) for `PL-TG-1060.csv` on the SFTP server and compares it byte for byte with the expected CSV built from `docs/messages/examples/pick-list/pl-tg-1060.json`; no `.tmp` left. If TG-1060 already exists from an earlier run, the guard records that and checks the existing file.
- [ ] **Step 4:** Use `set -euo pipefail`, resolve the project root from the script's location, print PASS/FAIL per scenario, exit non-zero on any failure. Make it executable. Do **not** run it in this task. P-9, P-10, P-17, P-18 stay manual (runtime checklist).
- [ ] **Step 5:** Verify: `f=src/test/scripts/partner-scenarios.sh; test -x $f && bash -n $f && grep -q 'localhost:8082' $f && grep -q 'X-API-Key' $f && grep -q 'CamelSqlQuery' $f && grep -q 'localhost:8025/api/v1' $f && ! grep -qE 'docker (start|stop|run|rm|compose)|camel infra (run|stop)|-X *DELETE' $f && ! grep -qE 'echo .*\$(KEY|key|API_KEY)' $f && echo PARTNER-SCRIPT-OK` prints `PARTNER-SCRIPT-OK`.

**Review:**
- [ ] Spec compliance: P-1–P-8 and P-11 expectations exactly as §4; Mailpit counted, never cleared.
- [ ] Code quality: strict shell flags; bounded waits; no key or personal data printed; read-only service access.

---

### Task 20: Scenario Script — src/test/scripts/pick-list-scenarios.sh

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/scripts/pick-list-scenarios.sh`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path rules
- `shared/camel-security-checklist.md` — no personal data printed

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §4 P-12, P-13, P-14, P-16; TC-005; §4 scenario approach ("the scripts move pick lists to `processed` themselves")

- [ ] **Step 1:** Helpers for SFTP as `admin` with `config/dev/warehouse_known_hosts` (batch mode): put, rename to `processed`, checksum, list `.tmp`. Directories come from the properties in `config/application-dev.properties` or `application.properties`.
- [ ] **Step 2:** P-13: place `PL-TG-x.csv` in inbound (second run: processed) for an order whose `pick_list` row is set back to `Pending` via `psql`; wait one poll; assert the row is `Delivered`, the receipt `Sent to Warehouse`, and the file checksum unchanged.
- [ ] **Step 3:** P-14: place `PL-TG-y.csv.tmp` with partial content and no final file for a pending pick list; after the poll assert the final file is complete and the `.tmp` is gone.
- [ ] **Step 4:** P-16: assert every `customer_order` Recorded before V2 has exactly one `pick_list` and, after polling, one file on the server.
- [ ] **Step 5:** P-12 (TC-005) needs two application starts with different `warehouse.sftp.port`; the script provides the DB and SFTP assertions as separate subcommands (`before-restart`, `after-restart`) that the verifier calls around its restarts. It never starts or stops the application or any service itself.
- [ ] **Step 6:** `set -euo pipefail`, PASS/FAIL per scenario, non-zero exit on failure, executable. Do **not** run it in this task.
- [ ] **Step 7:** Verify: `f=src/test/scripts/pick-list-scenarios.sh; test -x $f && bash -n $f && grep -q 'warehouse_known_hosts' $f && grep -q 'processed' $f && grep -q '\.tmp' $f && grep -q 'after-restart' $f && ! grep -qE 'docker (start|stop|run|rm|compose)|camel infra (run|stop)|kill ' $f && echo PICKLIST-SCRIPT-OK` prints `PICKLIST-SCRIPT-OK`.

**Review:**
- [ ] Spec compliance: P-12, P-13 (both folders), P-14, P-16 as §4; the scripts do the warehouse's move.
- [ ] Code quality: strict flags; bounded waits; host key verified; no personal data printed.

---

## Runtime Acceptance Checklist (for camel-execute's internal verification — not a plan task)

camel-execute's build-and-smoke verification runs once after all tasks, with commands selected from the shipped
verify guides. These are the outcomes the design requires (§4 *Acceptance criteria*); the execution report records each:

1. **P-21:** `./mvnw verify` passes with `EmailPatternTest`, `OrderChecksPartnerTest`, `PartnerRegistrationTest`,
   `RequiredVariablesCheckTest` and `PickListCsvTest`.
2. **Startup (dev):** started from the project root with `--spring.profiles.active=dev`, Flyway applies V2 before routes
   start; the log shows `config/application-dev.properties` as a property source; `Partner Configuration registered
   partners=1`; all routes start; `POST /orders` is served on 8082 and the contract loaded from the classpath `$ref`s
   (if not, stop and record — the fallback needs a design amendment); only port 8082 listens.
3. **R4:** the execution report states plainly whether Camel 4.22.2 `platform-http` filtered the inbound `CamelSqlQuery`
   header (P-11).
4. **P-1 to P-8, P-11:** `src/test/scripts/partner-scenarios.sh` passes.
5. **P-9, P-10, P-17, P-18:** run by hand as §4 describes (parallel `curl`s with the stub's `slow` mode; catalog outage;
   six invalid configuration copies; add and remove a second partner — each needing a restart).
6. **P-12 to P-14, P-16:** `src/test/scripts/pick-list-scenarios.sh` passes; P-12 uses a closed port for
   `warehouse.sftp.port` (no service stopped) and a restart with 2222.
7. **P-15:** `src/test/scripts/scenarios.sh` (002 scenarios 1–6) and `src/test/scripts/kill-test.sh` pass unchanged,
   and every Peakmart order they record gets exactly one pick list and `Sent to Warehouse`.
8. **P-19:** without a profile, all 26 variables set → past the check; all 26 unset → one failure naming all 26; each
   unset alone → names exactly that one (values may be passed as `--NAME=value`).
9. **P-20:** `unzip -l target/order-integration-0.1.0.jar` lists no `application-dev.properties` and nothing from
   `config/`; `BOOT-INF/classes/` holds no `test`/`admin` password property and no API key.
10. **P-22:** Task 18's documentation checks hold.
11. The execution report records: the web starter choice, JSch version, `flywayInitializer` bean name, the
    `EnvironmentPostProcessor` registration key, the SSL bundle keys, the dev SFTP folder layout, and the V2 backfilled
    pick lists.

Reviewers apply Iron Law 4 in order to every task: spec compliance first, then code quality.
