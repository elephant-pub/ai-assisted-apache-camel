---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T22:10:00Z"
  by: camel-plan
  from: design-spec.md
---
# Trailhead Order Integration — Executable Spec Tests (TC-001 to TC-005) Implementation Plan

> **For agentic workers:** Use camel-execute to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each of the five approved test cases TC-001 to TC-005 becomes one automated Citrus test that plays the
partner, Peakmart, the Product Catalog, the warehouse and the mailboxes against the packaged integration jar, runs in
`./mvnw verify` with only Docker available, and reports every step that waits for an unbuilt use case as "not yet
testable", with a traceability check and a generated matrix.

**Architecture:** No route changes; nothing under `src/main/` changes. Maven failsafe runs five `*IT` classes (Citrus
Java DSL, JUnit Jupiter extension) after `package`. A shared harness starts pinned Testcontainers (Kafka, PostgreSQL 16,
`atmoz/sftp`) and in-JVM Citrus servers (catalog `http-server`, `mail-server`) once per JVM, then starts the fat jar as a
child process with profile `spec-test`. A surefire unit test checks traceability statically; an `exec-maven-plugin`
execution at `post-integration-test` checks completeness and writes `target/spec-tests/traceability-matrix.{md,json}`.

**Tech Stack:** Apache Camel 4.22.2 (unchanged), Spring Boot 4.1.1 (Maven, Java 21), Camel Spring Boot BOM 4.22.2
(catalog `org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2`), Citrus 5.0.1 (`citrus-bom`), JUnit
Jupiter, Testcontainers 2.x (Boot 4.1.1 BOM), JSch (via `camel-ftp-starter`), commons-csv (via `camel-csv-starter`),
Jackson, maven-failsafe-plugin, exec-maven-plugin 3.5.1.

**Design Spec:** `docs/camel-kit/004-spec-tests/design-spec.md` (approved 2026-11-13, with design-review decisions 1–3
applied; `camel-kit doc check`: not stale, `generated.by: camel-brainstorm`)

---

## Planning Decisions and Assumptions (non-interactive run — recommended option taken)

| # | Decision | Reason |
|---|---|---|
| P-1 | **Standalone mode.** The plan is written and the run stops; `camel-execute` is not auto-invoked. | `/camel-plan 004-spec-tests` was invoked directly in a new session, and `design-spec.md` exists on disk with valid provenance. |
| P-2 | **Test tasks are Citrus Java DSL classes run by failsafe, not `.camel.it.yaml` files run by `camel test run`.** The testing template is loaded for its role (one test task per "flow", after its inputs), but its YAML file names and its `camel test run` verification do not apply. | Design §3.2 and A-2 (approved) choose the Java DSL with the JUnit Jupiter extension. The "flows" of this design are the five test cases (§3, opening note). |
| P-3 | **No scaffold, no route, no properties-in-`src/main`, no Docker Compose task.** Task 1 modifies the existing `pom.xml`. | The project exists (pipelines 001–003). §2: only Testcontainers starts containers; §4: no change under `src/main/`. |
| P-4 | **Personas:** `test-engineer` for the five IT classes (Tasks 12–16); `implementation-engineer` for the build, configuration, harness, traceability and README tasks. | The IT classes are Citrus test vocabulary checked on the Citrus MCP server, which is the `test-engineer`'s tool set. The harness and the checks are plain Java/Maven work. |
| P-5 | **Item model location.** The item record (`id`, `name`, `run` / `notYetTestable(UC)`), the outcome enum and the use-case constants (`UC_001` … `UC_008`) are nested types in `SpecRun.java`. | Design §6 lists the files of the pipeline; no extra file is added for them. |
| P-6 | **Secret ownership.** `SpecEnvironment` generates the per-run partner API key (into `target/spec-tests/secret-store/partner-api-key-summit-outfitters`) and the per-run SFTP password; `Partner` reads the key file; `IntegrationProcess` writes paths/values into the run file. | §2 Secret Store row ("per run") and §3.1 (password only in the run file). One owner per secret keeps the key out of logs and command lines. |
| P-7 | **Run-file key list.** The generated `application-spec-test-run.properties` sets every key `config/application-dev.properties` sets that the static `application-spec-test.properties` does not: `kafka.brokers`, `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `catalog.host`, `catalog.port`, `smtp.host`, `smtp.port`, `server.port`, `partner.configuration`, `secret-store.directory`, `warehouse.sftp.host`, `warehouse.sftp.port`, `warehouse.sftp.password`, `warehouse.sftp.known-hosts`. | §3.1 lists the dynamic values; `dev` is the reference profile ("the profile works like `dev`"). |
| P-8 | **Image digests:** the multi-arch index (manifest list) digest of each chosen tag is pinned, resolved with `docker buildx imagetools inspect <image:tag>` (or `docker manifest inspect`). | The same pin must work on CI (amd64) and developer Macs (arm64). §2 says the values are chosen once at implementation. |
| P-9 | **Kafka endpoint type:** Citrus `kafka-asynchronous` (producer only). | The Citrus 5.0.1 server returns `kafka-asynchronous` and `kafka-synchronous` (no plain `kafka` endpoint); Peakmart only publishes. |
| P-10 | **Bind addresses (evidence below):** `http-server` reaches 127.0.0.1 only through its `connector`/`connectors` option (a Jetty connector bean with host `127.0.0.1`); `mail-server` returns no bind-address option. Task 6 uses a connector; Task 7 does not invent an option and records "mail-server binds all interfaces" for the execution report. | Design §4 Security: "bind to 127.0.0.1 where the endpoint allows it". An option the server does not return is not used. |
| P-11 | **Per-IT verification** runs `./mvnw verify -Dit.test=<Class>`. It is valid only before Task 18 adds the matrix execution (which fails when a result file of an Approved test case is missing while ITs ran). After Task 18, only the full `./mvnw verify` is the acceptance run. | Design §3.9 completeness rule. |
| P-12 | No downstream artifacts exist yet (`execution-report.md`, `validation-report.md`), so the staleness cascade after writing this plan is a no-op. There is no `.camel-kit/project-graph.json`, so graph-context guides are not loaded. | `shared/pipeline-infrastructure.md`; camel-test manifest condition. |
| P-13 | A test that exposes a defect in the integration is **not** weakened and `src/main/` is **not** changed: the task records the defect for the execution report (§4 Constraints, §7) and the reviewer accepts a red test whose failure is a confirmed integration defect against an Approved document. | Not Doing: "Where a document and the code disagree, the test fails and the disagreement is reported." |

**Path resolution** (`camel-implement/guides/orchestrator.md` path table, runtime `spring-boot`, Target Module = project
root, so `[MODULE_DIR]` is empty). Design-specified test paths: `src/test/java/com/trailheadgear/orderintegration/spec/`,
`src/test/java/com/trailheadgear/orderintegration/spec/support/`, `src/test/resources/spec-test/`,
`src/test/resources/testcontainers.properties`. Read-only sources: `docs/test_cases/`, `docs/use_cases/`,
`docs/messages/examples/`, `docs/inputs/`, `docs/api/`, `src/main/**`, `config/**`. Run output: `target/spec-tests/`.

**Common Camel MCP binding for every task (run once per task before any catalog detail call):**
`camel_catalog_components(limit=0, runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2")`;
the returned `camelVersion` must equal `4.22.2` (planning result: `4.22.2`, MATCHED). Below, "`B`" means
`runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2"`.

**Common Citrus MCP binding for every task that writes Citrus code:** `.mcp.json` server `citrus` runs `jbang` with
exactly one coordinate `org.citrusframework:citrus-mcp-server:5.0.1:runner`; `.camel-kit/config.properties` has
`citrus.version=5.0.1` and `citrus.mcp.version=5.0.1` → `CITRUS_MCP_VERSION == CITRUS_VERSION` (MATCHED at planning).
Every Citrus call below passes `version="5.0.1"`. A Citrus artifact or option the server does not return, or a
server/cache disagreement, stops that task as an open question (design §5); no other Citrus version is used.

### Citrus MCP verification evidence (planning; supersedes the design-time cache check of §5)

| Citrus artifact | Result | Tool / binding | Notes for execution |
|---|---|---|---|
| endpoint:http-client (`requestUrl`, `requestMethod`, `contentType`, `timeout`) | VERIFIED | `citrus_catalog_endpoint(name="http-client", version="5.0.1")`, module citrus-http; binding MATCHED | Partner (Task 10) |
| endpoint:http-server (`port`, `autoStart`, `endpointAdapter`, `defaultStatus`, `timeout`, `connector`/`connectors`) | VERIFIED | `citrus_catalog_endpoint(name="http-server", version="5.0.1")`, module citrus-http; MATCHED | No host option; 127.0.0.1 only via a connector (P-10) |
| endpoint:mail-server (`port`, `autoAccept`, `autoStart`, `splitMultipart`, `knownUsers`, `authRequired`, `javaMailProperties`, `timeout`) | VERIFIED | `citrus_catalog_endpoint(name="mail-server", version="5.0.1")`, module citrus-mail; MATCHED | No bind-address option (P-10) |
| endpoint:kafka-asynchronous (`server`/`bootstrapServers`, `topic`, `keySerializer`, `valueSerializer`, `producerProperties`, `timeout`) | VERIFIED | `citrus_catalog_endpoints(filter="kafka", version="5.0.1")`, module citrus-kafka; MATCHED | No plain `kafka` endpoint name (P-9) |
| action:send (`endpoint`, `message`, `fork`) | VERIFIED | `citrus_catalog_action(name="send", version="5.0.1")`; MATCHED | |
| action:receive (`endpoint`, `message`, `ignore`, `select`/`selector`, `timeout`, `validate`) | VERIFIED | `citrus_catalog_action(name="receive", version="5.0.1")`; MATCHED | Selector header names for mail are settled in Task 7 |
| action:http (`client`, `server`) | VERIFIED | `citrus_catalog_action(name="http", version="5.0.1")`, module citrus-http; MATCHED | |
| action:sql (`dataSource`, `statements` required, `validate`, `extract`, `transaction`, `ignoreErrors`) | VERIFIED | `citrus_catalog_action(name="sql", version="5.0.1")`, module citrus-sql; MATCHED | |
| action:expectTimeout (`endpoint` required, `select`/`selector`, `wait`) | VERIFIED | `citrus_catalog_action(name="expectTimeout", version="5.0.1")`; MATCHED | |
| action:waitFor (`action`, `file`, `http`, `message`, `interval`, `timeout`) | VERIFIED | `citrus_catalog_action(name="waitFor", version="5.0.1")`; MATCHED | No SFTP condition: wait on an `action` condition wrapping `WarehouseInbox` |
| action:repeatOnError (`actions` required, `until`, `autoSleep`, `times`, `index`) | VERIFIED | `citrus_catalog_action(name="repeatOnError", version="5.0.1")`; MATCHED | Alternative to waitFor for "file appears" |
| action:sleep (`milliseconds`, `seconds`) | VERIFIED | `citrus_catalog_action(name="sleep", version="5.0.1")`; MATCHED | Quiet period |
| endpoint:sftp-client, action:testcontainers-start-* | Not used (A-4, A-5) | — | — |

Camel catalog evidence of the design (§5: kafka, sql, sftp, smtp, platform-http, http under the 4.22.2 spring-boot
catalog, binding MATCHED) still applies; no Camel component is added or changed. Task 2 re-reads the component options
whose configuration keys it sets.

**Hard constraints for every task (design §2, §4 Constraints, Not Doing):**
- No change under `src/main/`, `config/`, `docs/` (other than this pipeline's artifacts), `prototype/`, or the 34
  existing unit tests and `src/test/scripts/`. No `.camel-kit/` change.
- Never bind ports 8080, 8081, 5432. Never start or stop a container outside Testcontainers; no `docker run`, no
  `docker compose`, no `camel infra`. (`docker buildx imagetools inspect` / `docker pull` to resolve digests in Task 2
  is allowed; it starts nothing.)
- No floating image tag anywhere (`latest`, `alpine`, `16-alpine`, `3`). Every image reference carries `@sha256:`.
- No real secret, no secret on a command line, in the environment or in a log. Per-run secrets live only under
  `target/spec-tests/`.
- Do not stub or fake UC-005, UC-006, UC-008; their items are declared `notYetTestable` and never executed.
- Do not implement anything in the design's `## Not Doing (and Why)`: TLS / production-profile tests, load or timing
  measurement, 15-minute waits, kill -9 restart, changes to test cases or use cases, rewriting existing tests or
  scripts, shared-environment runs, coverage reporting.

---

```yaml plan-metadata
tasks:
  - id: 1
    title: Build — Citrus BOM, Test Dependencies, Failsafe
    files:
      modifies:
        - pom.xml
      reads:
        - .camel-kit/config.properties
    provides:
      routeContracts:
        - spec-test-build
      properties:
        - spec-test.jar
        - spec-test.docs-dir
        - spec-test.work-dir
    consumes: {}
    dependsOn: []
  - id: 2
    title: Spec-Test Configuration and Pinned Images
    files:
      creates:
        - src/test/resources/spec-test/application-spec-test.properties
        - src/test/resources/spec-test/spec-test.properties
        - src/test/resources/testcontainers.properties
      reads:
        - src/main/resources/application.properties
        - src/main/resources/application-default.properties
        - config/application-dev.properties
        - src/main/resources/camel/order-desk-notification.camel.yaml
        - src/main/resources/camel/peakmart-order-check.camel.yaml
        - src/main/resources/camel/pick-list-delivery.camel.yaml
        - README.md
    provides:
      properties:
        - order-check.poll-delay-ms
        - notification.poll-delay-ms
        - pick-list.poll-delay-ms
        - spec-test.startup-timeout-ms
        - spec-test.arrival-timeout-ms
        - spec-test.quiet-period-ms
        - spec-test.excluded-ports
        - spec-test.image.kafka
        - spec-test.image.postgres
        - spec-test.image.sftp
        - ryuk.container.image
    consumes:
      routeContracts:
        - spec-test-build
    dependsOn:
      - 1
  - id: 3
    title: SpecTestCase Annotation and TestCaseDocument Parser
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecTestCase.java
        - src/test/java/com/trailheadgear/orderintegration/spec/TestCaseDocument.java
        - src/test/java/com/trailheadgear/orderintegration/spec/TestCaseDocumentTest.java
      reads:
        - docs/test_cases/TC-001-partner-order-to-warehouse.md
        - docs/test_cases/TC-002-peakmart-redelivery-shipped-once.md
        - docs/test_cases/TC-003-peakmart-order-rejected.md
        - docs/test_cases/TC-004-set-aside-order-event-reaches-warehouse.md
        - docs/test_cases/TC-005-pick-list-once-after-restart.md
        - docs/use_cases/UC-001-submit-partner-order.md
        - docs/use_cases/UC-005-look-up-order.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/TestCaseDocumentTest.java
    provides:
      beans:
        - SpecTestCase
        - TestCaseDocument
    consumes:
      routeContracts:
        - spec-test-build
    dependsOn:
      - 1
  - id: 4
    title: SpecRun — Items, Outcomes and Result Files
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java
    provides:
      beans:
        - SpecRun
      routeContracts:
        - spec-result-json
    consumes:
      routeContracts:
        - spec-test-build
    dependsOn:
      - 1
  - id: 5
    title: FreePorts and WarehouseHostKeys
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/FreePorts.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseHostKeys.java
    provides:
      beans:
        - FreePorts
        - WarehouseHostKeys
    consumes:
      properties:
        - spec-test.excluded-ports
      routeContracts:
        - spec-test-build
    dependsOn:
      - 1
      - 2
  - id: 6
    title: ProductCatalogStub — Citrus http-server
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/ProductCatalogStub.java
      reads:
        - docs/api/external/product-catalog.openapi.yaml
        - docs/messages/examples/product/tent-2p.json
        - docs/messages/examples/product/lamp-hd200.json
        - docs/messages/examples/product/stake-al8.json
        - docs/messages/examples/product/tent-1p-inactive.json
        - src/main/resources/camel/peakmart-order-check.camel.yaml
    provides:
      externalServices:
        - product-catalog-stub
      beans:
        - ProductCatalogStub
    consumes:
      routeContracts:
        - spec-test-build
    dependsOn:
      - 1
  - id: 7
    title: Mailboxes — Citrus mail-server
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/Mailboxes.java
      reads:
        - src/main/resources/camel/order-desk-notification.camel.yaml
        - src/main/resources/camel/partner-rejection-email.camel.yaml
        - docs/api/external/order-desk-mail.md
        - docs/api/external/partner-mail.md
    provides:
      externalServices:
        - mail-server
      beans:
        - Mailboxes
    consumes:
      routeContracts:
        - spec-test-build
    dependsOn:
      - 1
  - id: 8
    title: SpecEnvironment — Pinned Containers and In-JVM Servers
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java
      reads:
        - README.md
        - docs/messages/examples/partner-configuration/summit-outfitters.json
    provides:
      externalServices:
        - kafka-container
        - postgres-container
        - sftp-container
      beans:
        - SpecEnvironment
      testData:
        - secret-store-partner-api-key
        - sftp-password
    consumes:
      properties:
        - spec-test.image.kafka
        - spec-test.image.postgres
        - spec-test.image.sftp
        - spec-test.excluded-ports
      externalServices:
        - product-catalog-stub
        - mail-server
      beans:
        - FreePorts
        - WarehouseHostKeys
    dependsOn:
      - 2
      - 5
      - 6
      - 7
  - id: 9
    title: IntegrationProcess — The Packaged Jar as a Child Process
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java
      reads:
        - config/application-dev.properties
        - src/main/java/com/trailheadgear/orderintegration/CamelApplication.java
    provides:
      beans:
        - IntegrationProcess
      routeContracts:
        - spec-test-run-properties
    consumes:
      properties:
        - spec-test.jar
        - spec-test.work-dir
        - spec-test.startup-timeout-ms
      beans:
        - SpecEnvironment
        - FreePorts
      externalServices:
        - kafka-container
        - postgres-container
        - sftp-container
        - product-catalog-stub
        - mail-server
    dependsOn:
      - 2
      - 8
  - id: 10
    title: Partner and Peakmart — Citrus http-client and Kafka Producer
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/Partner.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/Peakmart.java
      reads:
        - docs/inputs/partner-order-sample.json
        - docs/messages/examples/order-answer/accepted.json
        - docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json
        - docs/api/partner-orders.openapi.yaml
    provides:
      beans:
        - Partner
        - Peakmart
    consumes:
      beans:
        - SpecEnvironment
      testData:
        - secret-store-partner-api-key
      externalServices:
        - kafka-container
    dependsOn:
      - 8
  - id: 11
    title: WarehouseInbox and OrderDatabase — Observers and Cleanup
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseInbox.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/OrderDatabase.java
      reads:
        - docs/messages/examples/pick-list/pl-tg-1060.json
        - docs/inputs/warehouse-picklist-format.md
        - src/main/resources/db/migration/V1__order_database.sql
        - src/main/resources/db/migration/V2__partners_and_pick_lists.sql
    provides:
      beans:
        - WarehouseInbox
        - OrderDatabase
    consumes:
      beans:
        - SpecEnvironment
      testData:
        - sftp-password
      externalServices:
        - sftp-container
        - postgres-container
    dependsOn:
      - 8
  - id: 12
    title: TC-001 Partner Order to Warehouse — Tc001PartnerOrderToWarehouseIT
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java
      reads:
        - docs/test_cases/TC-001-partner-order-to-warehouse.md
        - docs/messages/examples/pick-list/pl-tg-1060.json
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java
    provides:
      testData:
        - target/spec-tests/results/TC-001.json
    consumes:
      beans:
        - SpecTestCase
        - SpecRun
        - SpecEnvironment
        - IntegrationProcess
        - Partner
        - ProductCatalogStub
        - Mailboxes
        - WarehouseInbox
        - OrderDatabase
    dependsOn:
      - 3
      - 4
      - 9
      - 10
      - 11
  - id: 13
    title: TC-002 Peakmart Redelivery Shipped Once — Tc002PeakmartRedeliveryShippedOnceIT
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc002PeakmartRedeliveryShippedOnceIT.java
      reads:
        - docs/test_cases/TC-002-peakmart-redelivery-shipped-once.md
        - docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json
        - docs/messages/examples/peakmart-order-placed/pm-50117-redelivered.json
        - docs/messages/examples/pick-list/pl-pm-50117.json
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc002PeakmartRedeliveryShippedOnceIT.java
    provides:
      testData:
        - target/spec-tests/results/TC-002.json
    consumes:
      beans:
        - SpecTestCase
        - SpecRun
        - SpecEnvironment
        - IntegrationProcess
        - Peakmart
        - ProductCatalogStub
        - Mailboxes
        - WarehouseInbox
        - OrderDatabase
    dependsOn:
      - 3
      - 4
      - 9
      - 10
      - 11
      - 12
  - id: 14
    title: TC-003 Peakmart Order Rejected — Tc003PeakmartOrderRejectedIT
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc003PeakmartOrderRejectedIT.java
      reads:
        - docs/test_cases/TC-003-peakmart-order-rejected.md
        - docs/messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json
        - docs/messages/examples/order-desk-notification/pm-50118.json
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc003PeakmartOrderRejectedIT.java
    provides:
      testData:
        - target/spec-tests/results/TC-003.json
    consumes:
      beans:
        - SpecTestCase
        - SpecRun
        - SpecEnvironment
        - IntegrationProcess
        - Peakmart
        - ProductCatalogStub
        - Mailboxes
        - WarehouseInbox
        - OrderDatabase
    dependsOn:
      - 3
      - 4
      - 9
      - 10
      - 11
      - 13
  - id: 15
    title: TC-004 Set-Aside Order Event Reaches Warehouse — Tc004SetAsideOrderEventReachesWarehouseIT
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java
      reads:
        - docs/test_cases/TC-004-set-aside-order-event-reaches-warehouse.md
        - docs/messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java
    provides:
      testData:
        - target/spec-tests/results/TC-004.json
    consumes:
      beans:
        - SpecTestCase
        - SpecRun
        - SpecEnvironment
        - IntegrationProcess
        - Peakmart
        - ProductCatalogStub
        - WarehouseInbox
        - OrderDatabase
    dependsOn:
      - 3
      - 4
      - 9
      - 10
      - 11
      - 14
  - id: 16
    title: TC-005 Pick List Once After Restart — Tc005PickListOnceAfterRestartIT
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java
      reads:
        - docs/test_cases/TC-005-pick-list-once-after-restart.md
        - docs/messages/examples/pick-list/pl-tg-1060.json
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java
    provides:
      testData:
        - target/spec-tests/results/TC-005.json
    consumes:
      beans:
        - SpecTestCase
        - SpecRun
        - SpecEnvironment
        - IntegrationProcess
        - Partner
        - ProductCatalogStub
        - WarehouseInbox
        - WarehouseHostKeys
        - FreePorts
        - OrderDatabase
    dependsOn:
      - 3
      - 4
      - 5
      - 9
      - 10
      - 11
      - 15
  - id: 17
    title: SpecTraceabilityTest — Static Traceability Check
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecTraceabilityTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecTraceabilityTest.java
      reads:
        - docs/test_cases/TC-001-partner-order-to-warehouse.md
        - docs/use_cases/UC-005-look-up-order.md
    provides:
      routeContracts:
        - spec-traceability-check
    consumes:
      beans:
        - SpecTestCase
        - TestCaseDocument
        - SpecRun
    dependsOn:
      - 3
      - 4
      - 12
      - 13
      - 14
      - 15
      - 16
  - id: 18
    title: SpecMatrix — Completeness Check and Traceability Matrix
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java
      modifies:
        - pom.xml
    provides:
      routeContracts:
        - spec-traceability-matrix
    consumes:
      beans:
        - TestCaseDocument
        - SpecRun
      routeContracts:
        - spec-result-json
        - spec-traceability-check
      testData:
        - target/spec-tests/results/TC-001.json
        - target/spec-tests/results/TC-002.json
        - target/spec-tests/results/TC-003.json
        - target/spec-tests/results/TC-004.json
        - target/spec-tests/results/TC-005.json
    dependsOn:
      - 1
      - 12
      - 13
      - 14
      - 15
      - 16
      - 17
  - id: 19
    title: README — Build Section
    files:
      modifies:
        - README.md
    provides:
      routeContracts:
        - readme-build-section
    consumes:
      routeContracts:
        - spec-traceability-matrix
    dependsOn:
      - 18
```

---

### Task 1: Build — Citrus BOM, Test Dependencies, Failsafe

**Agent:** implementation-engineer

**Files:**
- Modify: `pom.xml` (the existing pom; keep the `spring-boot-starter-parent` 4.1.1 parent, the
  `spring-boot-dependencies` and `camel-spring-boot-bom` imports, `bundle-partner-contract`, the resources executions,
  and the `camel.debug` and `jib` profiles unchanged)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table, Step 5 (runtime artifacts)
- `camel-implement/guides/maven-dependencies.md`
- `camel-implement/guides/pom-spring-boot.md` — except its "no `spring-boot-starter-parent`" rule (002 R1 reviewed exception)
- `camel-test/guides/test-configuration.md` — Testcontainers and Citrus test dependencies

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding)
- `camel_dependency_check(…)` on the resulting pom, with the parameters its schema requires and binding `B`
- `citrus_catalog_endpoint(name="http-client", version="5.0.1")`, `citrus_catalog_endpoint(name="mail-server", version="5.0.1")`, `citrus_catalog_endpoints(filter="kafka", version="5.0.1")`, `citrus_catalog_action(name="sql", version="5.0.1")` — read each response's `module` to derive the module artifact list

**Design Spec Section:** §3.10 Build changes; §3.1 (failsafe after `package`); §7 "For implementation to confirm" (artifact names, version compatibility)

- [ ] **Step 1:** Read `.camel-kit/config.properties` (`citrus.version=5.0.1`, Camel 4.22.2, Spring Boot 4.1.1).
- [ ] **Step 2:** In `dependencyManagement`, import `org.citrusframework:citrus-bom:5.0.1` (`<type>pom</type>`, `<scope>import</scope>`) **after** the `spring-boot-dependencies` and `camel-spring-boot-bom` imports. Put the version in a `<citrus.version>5.0.1</citrus.version>` property.
- [ ] **Step 3:** Add test-scoped dependencies without `<version>`: the Citrus JUnit Jupiter integration (confirm the 5.0.1 artifact name from the imported BOM: `./mvnw -q help:effective-pom -Doutput=target/effective-pom.xml` then search `target/effective-pom.xml` for `citrus-junit`; the design's expected name is `citrus-junit5`), `citrus-http`, `citrus-kafka`, `citrus-mail`, `citrus-sql`, `citrus-validation-json`, `citrus-validation-text`. Each module must match a `module` value returned by the Citrus MCP calls above or exist in the BOM; stop as an open question otherwise.
- [ ] **Step 4:** Add test-scoped Testcontainers dependencies without `<version>`, names confirmed in `target/effective-pom.xml` (Testcontainers 2.x under the Boot 4.1.1 BOM): the JUnit Jupiter module (design: `testcontainers-junit-jupiter`), Kafka (`testcontainers-kafka`), PostgreSQL (`testcontainers-postgresql`). Record the resolved Testcontainers version.
- [ ] **Step 5:** Add `maven-failsafe-plugin` (version from the Boot parent) with goals `integration-test` and `verify`; `<includes><include>**/spec/*IT.java</include></includes>`; `runOrder=alphabetical`; `forkCount=1`; `reuseForks=true`; no `parallel`. `systemPropertyVariables`: `spec-test.jar=${project.build.directory}/${project.build.finalName}.jar`, `spec-test.docs-dir=${project.basedir}/docs`, `spec-test.work-dir=${project.build.directory}/spec-tests`. Do **not** add the SpecMatrix exec execution here (Task 18).
- [ ] **Step 6:** Configure `maven-surefire-plugin` to exclude `**/spec/*IT.java` (explicit, although failsafe naming already separates them).
- [ ] **Step 7:** Compatibility check (§7): run `./mvnw -q dependency:tree -Dverbose=false -DoutputFile=target/dep-tree.txt` and record for the execution report the resolved versions of `spring-core`, `camel-core-engine`, `kafka-clients`, `jackson-databind`, `jetty-server` and every `org.citrusframework` artifact. Spring Boot artifacts must stay 4.1.1-managed and Camel artifacts 4.22.2; if the Citrus BOM wins over either, fix the import order — never pin Spring/Camel versions by hand.
- [ ] **Step 8:** Verify: `./mvnw -q -DskipITs verify` exits 0 (the 34 existing tests still pass and `target/order-integration-0.1.0.jar` exists), and `grep -c 'org.citrusframework:citrus-' target/dep-tree.txt` is ≥ 7 with every Citrus line ending in `:5.0.1:test`, and `grep -q 'maven-failsafe-plugin' target/effective-pom.xml && echo FAILSAFE-OK` prints `FAILSAFE-OK`.

**Review:**
- [ ] Spec compliance: citrus-bom 5.0.1 imported after the Boot/Camel BOMs; exactly the §3.10 modules (test scope); failsafe includes, run order, single fork, the three system properties; surefire exclusion; findings of Step 3/4/7 recorded.
- [ ] Code quality: no hardcoded versions on BOM-managed artifacts; parent not reported as a finding; no change to existing executions or profiles; no `parallel` setting.

---

### Task 2: Spec-Test Configuration and Pinned Images

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/resources/spec-test/application-spec-test.properties`
- Create: `src/test/resources/spec-test/spec-test.properties`
- Create: `src/test/resources/testcontainers.properties`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/properties-generation.md`
- `shared/camel-security-checklist.md` — no secrets in committed files

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding)
- `camel_catalog_component_doc(component="sql", B)` — `delay` scheduler option, set via `*.poll-delay-ms`
- `camel_catalog_component_doc(component="kafka", B)` — `autoOffsetReset` allowed values (`earliest`)
- `camel_catalog_component_doc(component="sftp", B)` — directory options relative to login directory, `knownHostsFile`
- `camel_catalog_component_doc(component="smtp", B)` — STARTTLS off as in `dev`

**Design Spec Section:** §3.1 Static test settings and Test-side settings; §2 Pinned images table; A-21; review decision 2

- [ ] **Step 1:** Confirm in `src/main/resources/camel/*.camel.yaml` and `application.properties` that the routes read `order-check.poll-delay-ms`, `notification.poll-delay-ms`, `pick-list.poll-delay-ms`, `smtp.starttls`, `orderdesk.mailbox`, `orderdesk.sender`, `partner.mail-sender`, `warehouse.sftp.inbound-directory`, `warehouse.sftp.processed-directory`, `warehouse.sftp.username`, `peakmart.auto-offset-reset` by exactly those names. A name that does not exist stops the task as an open question (no `src/main` change).
- [ ] **Step 2:** Create `application-spec-test.properties` with exactly the §3.1 "Static test settings" keys and values (and its comment lines): `server.ssl.enabled=false`, `smtp.starttls=false`, the three mail addresses, the three `*.poll-delay-ms=1000`, the two SFTP directories (`inbound/picklists`, `inbound/picklists/processed`), `warehouse.sftp.username=admin`, `peakmart.auto-offset-reset=earliest`. No password, host, port or path (those go into the run file, Task 9).
- [ ] **Step 3:** Resolve the image pins (P-8). For each image: pick the tag (Kafka `apache/kafka:3.9.1`; PostgreSQL the newest `postgres:16.<minor>-alpine3.<n>` tag that exists today; `atmoz/sftp` its Alpine variant reference; Ryuk the exact `testcontainers/ryuk:<version>` that the resolved Testcontainers version of Task 1 uses by default), then resolve its index digest with `docker buildx imagetools inspect <ref>` (fallback `docker manifest inspect -v`). Record tag and digest of all four for the execution report.
- [ ] **Step 4:** Create `spec-test.properties` with `spec-test.startup-timeout-ms=120000`, `spec-test.arrival-timeout-ms=60000`, `spec-test.quiet-period-ms=10000`, `spec-test.excluded-ports=8080,8081,5432`, and `spec-test.image.kafka=apache/kafka:3.9.1@sha256:<64 hex>`, `spec-test.image.postgres=postgres:16.<minor>-alpine3.<n>@sha256:<64 hex>`, `spec-test.image.sftp=atmoz/sftp@sha256:<64 hex>` with the resolved values, plus the §3.1 comments.
- [ ] **Step 5:** Create `src/test/resources/testcontainers.properties` with `ryuk.container.image=testcontainers/ryuk:<version>@sha256:<64 hex>`. Confirm that Testcontainers 2.x reads the classpath `testcontainers.properties` key `ryuk.container.image` (from the resolved `org.testcontainers:testcontainers` jar's `TestcontainersConfiguration` or its documentation); record the evidence. If the key differs in 2.x, stop as an open question.
- [ ] **Step 6:** Verify: `f=src/test/resources/spec-test; test "$(grep -cE '^spec-test\.image\.(kafka|postgres|sftp)=[^ ]+@sha256:[0-9a-f]{64}$' $f/spec-test.properties)" = 3 && grep -qE '^spec-test\.image\.postgres=postgres:16\.[0-9]+-alpine3\.[0-9]+@sha256:' $f/spec-test.properties && grep -qE '^ryuk\.container\.image=testcontainers/ryuk:[0-9][^@]*@sha256:[0-9a-f]{64}$' src/test/resources/testcontainers.properties && grep -q '^pick-list.poll-delay-ms=1000$' $f/application-spec-test.properties && ! grep -qiE 'password|latest' $f/*.properties src/test/resources/testcontainers.properties && echo CONFIG-OK` prints `CONFIG-OK`.

**Review:**
- [ ] Spec compliance: every §3.1 key and value present, no extra keys; all four images pinned as in §2 (exact tag + digest; SFTP digest only); PostgreSQL major 16 (C-004, A-18).
- [ ] Code quality: no secret, host or port in committed files; no floating tag; comments explain the shortened polls (A-10).

---

### Task 3: SpecTestCase Annotation and TestCaseDocument Parser

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/SpecTestCase.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/TestCaseDocument.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/TestCaseDocumentTest.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only; no catalog artifact involved)

**Design Spec Section:** §3.2 (items `S<n>`, `V<n>`, `P<n>`, `P-cleanup`, `C-helpdesk`, `C-resubmit`); §3.9 (parsing of `**ID:**`, `**Status:**`, Flow table, Validation list, Postcondition bullets; parser unit-tested against the five real documents)

- [ ] **Step 1:** `SpecTestCase`: runtime-retained (`@Retention(RUNTIME)`), `@Target(TYPE)`, single `String value()` holding the test case ID (`TC-00n`).
- [ ] **Step 2:** `TestCaseDocument`: a pure parser (no Citrus, no Docker) with two factory methods, one for `docs/test_cases/*.md` and one for `docs/use_cases/*.md`, reading the docs directory from system property `spec-test.docs-dir` with a fallback to `docs/` relative to the working directory (surefire runs at the project root). Expose: `id`, `title` (from the H1/overview as the documents state it), `status`, the Flow steps (number, name, linked use case ID), the numbered Validation items, the Postcondition bullets (the "Cleanup order" bullet identified as `P-cleanup`), and the Preconditions bullets. Read the five real documents first and parse exactly their layout; fail with a message naming file and line on an unexpected layout.
- [ ] **Step 3:** `TestCaseDocumentTest` (JUnit Jupiter, surefire): for each of TC-001 … TC-005 assert ID, `Approved` status, title, and the exact counts of Flow steps, Validation items and Postcondition bullets as counted by hand from the documents (design: TC-001 has 7 steps, 4 validations, 4 postcondition bullets incl. cleanup); assert each step's use-case link for TC-001 (S6, S7 → UC-005); assert UC-001 … UC-008 parse with ID and status.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test -Dtest=TestCaseDocumentTest -Dsurefire.failIfNoSpecifiedTests=false` exits 0 and `target/surefire-reports/*TestCaseDocumentTest.txt` reports `Failures: 0, Errors: 0` with `Tests run` ≥ 6.

**Review:**
- [ ] Spec compliance: parser covers exactly the fields of §3.9; counts asserted against the real documents; documents unchanged.
- [ ] Code quality: no regex that silently skips rows; clear error messages; no Docker or network.

---

### Task 4: SpecRun — Items, Outcomes and Result Files

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.2 Items, Recording, Outcome; A-3; A-7

- [ ] **Step 1:** Nested types (P-5): `UseCase` constants `UC_001` … `UC_008` (ID and title as in `docs/use_cases/`); `Item` record (`id`, `name`, kind `RUN` or `NOT_YET_TESTABLE` with its `UseCase`); static factories `run(id, name)` and `notYetTestable(id, name, UseCase)`; `Outcome` = `PASSED`, `FAILED`, `NOT_YET_TESTABLE`, `NOT_RUN`.
- [ ] **Step 2:** `SpecRun` takes the test case ID, the class's static `ITEMS` list and JUnit's `TestReporter`. `execute(itemId, action)` runs `RUN` items in table order and refuses an item ID out of order or not in the table; after the first failure every later `RUN` item is `NOT_RUN` and the failure is rethrown so the `@Test` fails. `NOT_YET_TESTABLE` items are never executed: each is reported through `TestReporter` and the log as `TC-001 S6 Look up order: not yet testable, needs UC-005 Look Up Order (not built)`.
- [ ] **Step 3:** `finish()` (called from the test's `finally`) writes `${spec-test.work-dir}/results/<TC-id>.json` with Jackson: test case ID, class name, display name, and for each item ID, name, outcome, use case (if any), failure message (if any).
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q 'NOT_YET_TESTABLE' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java && grep -q 'NOT_RUN' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java && echo SPECRUN-OK` prints `SPECRUN-OK`. (Runtime behaviour is proven by Tasks 12–16 and 18.)

**Review:**
- [ ] Spec compliance: four outcomes; not-yet-testable never executes and never fails/skips the test; first failure stops later items; result file path and content as §3.2.
- [ ] Code quality: no static mutable state shared between classes; JSON written atomically; log message format exact.

---

### Task 5: FreePorts and WarehouseHostKeys

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/FreePorts.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseHostKeys.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `shared/camel-security-checklist.md` — strict host-key checking stays on

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", B)` — `knownHostsFile`, `strictHostKeyChecking`, `useUserKnownHostsFile`

**Design Spec Section:** §3.3 `FreePorts`, `WarehouseHostKeys`; §2 (ports never bound); §4 Security; §7 (JSch Ed25519 on Java 21)

- [ ] **Step 1:** `FreePorts.next()`: `new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))`, close it, retry while the port is in `spec-test.excluded-ports` (read from `spec-test.properties`); never returns 8080, 8081, 5432.
- [ ] **Step 2:** `WarehouseHostKeys.generate(dir)`: with JSch `KeyPair`, generate an Ed25519 and an RSA (3072) host key pair into `target/spec-tests/sftp/` in OpenSSH format named as `atmoz/sftp` expects (`ssh_host_ed25519_key`, `ssh_host_rsa_key` + `.pub`). If Ed25519 generation fails on Java 21, fall back to RSA only and record that (§7).
- [ ] **Step 3:** `writeKnownHosts(port, file)`: one line per key in the form `[127.0.0.1]:<port> <type> <base64>`.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q '127.0.0.1' src/test/java/com/trailheadgear/orderintegration/spec/support/FreePorts.java && grep -q 'excluded-ports' src/test/java/com/trailheadgear/orderintegration/spec/support/FreePorts.java && grep -q '\[127.0.0.1\]:' src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseHostKeys.java && echo PORTS-KEYS-OK` prints `PORTS-KEYS-OK`.

**Review:**
- [ ] Spec compliance: loopback-only ports; exclusions honoured; both host key types; known_hosts format with port.
- [ ] Code quality: no relaxation of `strictHostKeyChecking`; private keys only under `target/`.

---

### Task 6: ProductCatalogStub — Citrus http-server

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/ProductCatalogStub.java`

**Guides to Load:**
- `camel-test/guides/test-configuration.md` — mock endpoints for external HTTP APIs
- `camel-test/guides/test-generation.md` — Test Design Principles

**MCP Tools:**
- `citrus_catalog_endpoint(name="http-server", version="5.0.1")` and `citrus_catalog_endpoint_schema(name="http-server", version="5.0.1")` — `port`, `autoStart`, `endpointAdapter`, `defaultStatus`, `connector`/`connectors`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="http", B)` — `throwExceptionOnFailure` (404 read as "not found")

**Design Spec Section:** §2 Product Catalog row; §3.3 `ProductCatalogStub`; §4 Security (bind 127.0.0.1); P-10

- [ ] **Step 1:** Read `docs/api/external/product-catalog.openapi.yaml` for the path (`GET /products/{sku}`), content type and the 404 shape; read how `peakmart-order-check.camel.yaml` calls it (`catalog.host`, `catalog.port`).
- [ ] **Step 2:** Build a Citrus `http-server` on a `FreePorts` port with a custom endpoint adapter: for `TENT-2P`, `LAMP-HD200`, `STAKE-AL8`, `TENT-1P` return the bytes of `docs/messages/examples/product/<sku-lowercase>.json` (TENT-1P → `tent-1p-inactive.json`) with status 200 and the contract's content type; any other SKU → 404 as the contract states. Record every requested SKU in a thread-safe list with `requestedSkus()` and `clearRequests()`.
- [ ] **Step 3:** Bind to 127.0.0.1 through the `connector` option (a Jetty `ServerConnector` with host `127.0.0.1`), the only bind route the server returns. If the schema call shows `connector` cannot carry a host in 5.0.1, stop as an open question.
- [ ] **Step 4:** Expose `start(port)` / `port()`; `SpecEnvironment` (Task 8) owns the lifecycle.
- [ ] **Step 5:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q 'tent-1p-inactive.json' src/test/java/com/trailheadgear/orderintegration/spec/support/ProductCatalogStub.java && grep -q '127.0.0.1' src/test/java/com/trailheadgear/orderintegration/spec/support/ProductCatalogStub.java && echo CATALOG-OK` prints `CATALOG-OK`.

**Review:**
- [ ] Spec compliance: four SKUs from the examples byte for byte; 404 otherwise; SKU recording; loopback bind.
- [ ] Code quality: only Citrus options returned by the 5.0.1 server; no copy of example data into `src/test/resources`.

---

### Task 7: Mailboxes — Citrus mail-server

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/Mailboxes.java`

**Guides to Load:**
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-generation.md` — Test Design Principles

**MCP Tools:**
- `citrus_catalog_endpoint(name="mail-server", version="5.0.1")` and `citrus_catalog_endpoint_schema(name="mail-server", version="5.0.1")` — `port`, `autoAccept`, `autoStart`, `splitMultipart`, `authRequired`
- `citrus_catalog_action(name="receive", version="5.0.1")`, `citrus_catalog_action(name="expectTimeout", version="5.0.1")` — `select`/`selector`, `timeout`/`wait`
- `citrus_docs_index(version="5.0.1")` then `citrus_docs_page(...)` for the mail page — the message header names of a received mail (recipient, subject)
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="smtp", B)`

**Design Spec Section:** §2 mailbox row; §3.3 `Mailboxes`; A-20; §7 (mail header names verified on the Citrus MCP server); P-10

- [ ] **Step 1:** Settle the Citrus 5.0.1 mail header names used for selectors (recipient `To`, `Subject`) from the server's docs page; record the evidence (page and header constants). If the server does not return them, stop as an open question.
- [ ] **Step 2:** One `mail-server` on a `FreePorts` port: `autoAccept=true`, `splitMultipart=true`, `authRequired=false` (no SMTP login, as `dev`). There is no bind-address option (P-10): do not add one; record "mail-server binds all interfaces" for the execution report.
- [ ] **Step 3:** Helpers returning Citrus actions/builders: `receiveOne(recipient, subject, timeoutMs)` (a `receive` with a selector on recipient **and** subject, so other tests' mail is never consumed) and `expectNone(recipient, subjectContains, quietMs)` (`expectTimeout` with the same kind of selector). Constants for `order-desk@trailhead.example` and the partner contact `orders@summit-outfitters.example.com`. No Integration Team address (UC-006 not built).
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q 'expectTimeout' src/test/java/com/trailheadgear/orderintegration/spec/support/Mailboxes.java && grep -q 'order-desk@trailhead.example' src/test/java/com/trailheadgear/orderintegration/spec/support/Mailboxes.java && echo MAIL-OK` prints `MAIL-OK`.

**Review:**
- [ ] Spec compliance: one server for all mailboxes; selectors on recipient and subject; "exactly one" = receive + expectTimeout; no Integration Team mailbox.
- [ ] Code quality: header names from the server evidence, not guessed; no options the server did not return.

---

### Task 8: SpecEnvironment — Pinned Containers and In-JVM Servers

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java`

**Guides to Load:**
- `camel-test/guides/test-configuration.md` — Testcontainers setup
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", B)` — topic must exist before the consumer starts
- `camel_catalog_component_doc(component="sftp", B)`

**Design Spec Section:** §2 (whole table, pinned images, 127.0.0.1 SFTP, Ryuk); §3.3 `SpecEnvironment`; A-5, A-6, A-21; §7 (atmoz/sftp host keys and nested folders)

- [ ] **Step 1:** A JVM-wide lazy singleton (`SpecEnvironment.get()`), started once and shared by all five classes; containers are removed by Ryuk (no explicit stop needed beyond Testcontainers defaults).
- [ ] **Step 2:** Image guard: read the three `spec-test.image.*` values; any reference without `@sha256:` throws before any container starts (§2). Use `DockerImageName.parse(ref)` with `asCompatibleSubstituteFor` where the module requires it.
- [ ] **Step 3:** Kafka (`apache/kafka` image via the Testcontainers Kafka module) on the default random mapping; create topic `peakmart.orders.v2` with `AdminClient` before returning.
- [ ] **Step 4:** PostgreSQL 16 container (random mapping), database/user/password generated per run; expose JDBC URL, user, password and a `DataSource` (a simple `PGSimpleDataSource` or Hikari from the classpath). Do not run Flyway; the integration does.
- [ ] **Step 5:** SFTP: generate host keys (`WarehouseHostKeys`), a random password, and start `atmoz/sftp` with user spec `admin:<password>:::inbound/picklists,inbound/picklists/processed` (verify nested folders are created; otherwise add a `/etc/sftp.d/` script as §7 allows), the host keys copied to `/etc/ssh/ssh_host_*`, and the port bound to `127.0.0.1` only (fixed host port from `FreePorts`, `withCreateContainerCmdModifier` port binding on `127.0.0.1`). Write `known_hosts` for that port. Provide a factory `startSftpOn(port)` reused by TC-005 (Task 16) for its own container.
- [ ] **Step 6:** Start `ProductCatalogStub` and `Mailboxes` servers on `FreePorts` ports.
- [ ] **Step 7:** Secret Store: create `target/spec-tests/secret-store/` with file `partner-api-key-summit-outfitters` holding a random key (32 bytes, URL-safe Base64), owner-only permissions; never log it.
- [ ] **Step 8:** Log (without secrets) every started image reference with digest, for the execution report.
- [ ] **Step 9:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q '@sha256:' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && grep -q 'peakmart.orders.v2' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && grep -q '127.0.0.1' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && ! grep -qE '"(latest|alpine|16-alpine)"' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && echo ENV-OK` prints `ENV-OK`. Runtime start is proven by Task 12.

**Review:**
- [ ] Spec compliance: singleton; digest guard; topic created first; PostgreSQL 16 pinned; SFTP on 127.0.0.1 with per-run host keys and password; catalog and mail servers; per-run key file.
- [ ] Code quality: no fixed ports except the loopback SFTP mapping; no secrets in logs; no Citrus `testcontainers-start-*` (A-5).

---

### Task 9: IntegrationProcess — The Packaged Jar as a Child Process

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `shared/camel-security-checklist.md` — no secret on a command line or in the environment

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)` — consumer reached on `server.port`

**Design Spec Section:** §3.1 (whole section: process start, configuration, profile, readiness, stop); P-7; §7 (`Started CamelApplication` marker)

- [ ] **Step 1:** `start(tcId)`: run directory `target/spec-tests/run/<TC-id>/`; write `application-spec-test-run.properties` there with the P-7 keys from `SpecEnvironment` values (Kafka bootstrap, JDBC URL/user/password, catalog host `127.0.0.1` and port, SMTP host/port, `server.port` from `FreePorts`, `partner.configuration` = absolute path of `docs/messages/examples/partner-configuration/summit-outfitters.json`, `secret-store.directory`, SFTP host `127.0.0.1`, port, password, known-hosts path); file permissions owner-only. Copy `src/test/resources/spec-test/application-spec-test.properties` from the test classpath into the run directory.
- [ ] **Step 2:** Launch `ProcessHandle.current().info().command()` with `-jar ${spec-test.jar} --spring.profiles.active=spec-test --spring.config.additional-location=<run dir>/,<run dir>/application-spec-test-run.properties`. The directory location loads `application-spec-test.properties` for profile `spec-test`; the explicit file location loads the run file regardless of profile, so the profile stays exactly `spec-test` (§3.1). On the first real start (Task 12) confirm both files took effect (the integration listens on the generated `server.port` and polls the pick list every second) and record it; working directory = run directory; the environment inherits nothing secret and gets nothing added. Redirect stdout and stderr to `target/spec-tests/logs/<TC-id>[-<n>].log` (`-2` for the TC-005 restart).
- [ ] **Step 3:** Readiness: within `spec-test.startup-timeout-ms` wait until the log contains `Started CamelApplication` (confirm the exact line on first run, §7) **and** a TCP connect to `127.0.0.1:server.port` succeeds; else stop the process and fail with the last 50 log lines.
- [ ] **Step 4:** `stop()`: `destroy()` (SIGTERM), wait up to 30 s, then `destroyForcibly()`. `restart()` = `stop()` + `start()` with the same run directory and ports. Callers stop it in `finally`.
- [ ] **Step 5:** After start, assert the log contains neither the partner key nor the DB/SFTP passwords (NFR-011 masking unchanged).
- [ ] **Step 6:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q 'Started CamelApplication' src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java && grep -q 'destroyForcibly' src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java && grep -q 'spec-test' src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java && echo PROCESS-OK` prints `PROCESS-OK`. Runtime start is proven by Task 12.

**Review:**
- [ ] Spec compliance: same `java` as the test JVM; profile `spec-test`; run directory as working dir so `./config/` is not read; static + generated settings; readiness rule; graceful stop.
- [ ] Code quality: no secret in argv/env/log; process always stopped; log files kept.

---

### Task 10: Partner and Peakmart — Citrus http-client and Kafka Producer

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/Partner.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/Peakmart.java`

**Guides to Load:**
- `camel-test/guides/test-generation.md` — Test Design Principles
- `camel-test/guides/test-configuration.md`

**MCP Tools:**
- `citrus_catalog_endpoint(name="http-client", version="5.0.1")`, `citrus_catalog_endpoint_schema(name="http-client", version="5.0.1")`
- `citrus_catalog_endpoints(filter="kafka", version="5.0.1")`, `citrus_catalog_endpoint_schema(name="kafka-asynchronous", version="5.0.1")`
- `citrus_catalog_action(name="send", version="5.0.1")`, `citrus_catalog_action(name="receive", version="5.0.1")`, `citrus_catalog_action(name="http", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="kafka", B)`

**Design Spec Section:** §3.3 `Partner`, `Peakmart`; A-19; P-9

- [ ] **Step 1:** `Partner`: Citrus `http-client` with `requestUrl=http://127.0.0.1:<server.port>`; action builders `submitOrder(bodyFile)` (POST `/orders`, `Content-Type: application/json`, header `X-API-Key` read from the secret file, body = `docs/inputs/partner-order-sample.json` byte for byte) and `expectAccepted(orderNumber)` (receive status 200, body validated against `docs/messages/examples/order-answer/accepted.json` with run-dependent fields set to `@ignore@`; check the order number). Count requests sent (`requestsSent()`) for TC-005 V3. Never log the key.
- [ ] **Step 2:** `Peakmart`: Citrus `kafka-asynchronous` producer on topic `peakmart.orders.v2`, String serializer, no key; `publish(file)` sends the file bytes unchanged. `endOffsets()` via Kafka `AdminClient` (sum of end offsets of the topic) for "the integration published nothing" snapshots.
- [ ] **Step 3:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q 'X-API-Key' src/test/java/com/trailheadgear/orderintegration/spec/support/Partner.java && grep -q 'peakmart.orders.v2' src/test/java/com/trailheadgear/orderintegration/spec/support/Peakmart.java && echo SENDERS-OK` prints `SENDERS-OK`.

**Review:**
- [ ] Spec compliance: example bodies unchanged; key header; accepted-answer validation with `@ignore@` only on run-dependent fields; no Kafka key; offset snapshot.
- [ ] Code quality: only server-returned Citrus options; no secret in logs.

---

### Task 11: WarehouseInbox and OrderDatabase — Observers and Cleanup

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseInbox.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/OrderDatabase.java`

**Guides to Load:**
- `camel-test/guides/test-generation.md` — Test Design Principles
- `shared/camel-security-checklist.md` — parameter binding in SQL

**MCP Tools:**
- `citrus_catalog_action(name="sql", version="5.0.1")`, `citrus_catalog_action_schema(name="sql", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="sftp", B)`; `camel_catalog_dataformat_doc(dataformat="csv", B)`

**Design Spec Section:** §3.3 `WarehouseInbox`, `OrderDatabase`; A-4; A-14; A-15

- [ ] **Step 1:** `WarehouseInbox` (JSch, strict host-key checking against the run's `known_hosts`): `count(fileName)` across `inbound/picklists` and `inbound/picklists/processed`; `tmpExists(fileName)`; `read(fileName)` from whichever folder holds it; `parse` with commons-csv RFC 4180; `assertEqualsExample(fileName, exampleJson)` compares header and rows field by field with `docs/messages/examples/pick-list/pl-*.json` (read `docs/inputs/warehouse-picklist-format.md` for column order); `remove(fileName)` for cleanup in both folders.
- [ ] **Step 2:** `OrderDatabase`: Citrus `sql` action builders on `SpecEnvironment`'s `DataSource`: read-only query builders used by the ITs, and `cleanup(orderNumber)` deleting in the design's order — `order_state_change`, `rejection_reason`, `notification`, `pick_list`, `order_line`, then `customer_order`, then `order_receipt` — all keyed by the order number via joins; `cleanupSetAside(eventId)` for `set_aside_order_event`. Read V1/V2 for exact column names. Never touch `partner`.
- [ ] **Step 3:** Verify: `./mvnw -q -DskipITs test-compile` exits 0 and `grep -q 'inbound/picklists/processed' src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseInbox.java && grep -q 'set_aside_order_event' src/test/java/com/trailheadgear/orderintegration/spec/support/OrderDatabase.java && ! grep -qiE 'delete from +partner' src/test/java/com/trailheadgear/orderintegration/spec/support/OrderDatabase.java && echo OBSERVERS-OK` prints `OBSERVERS-OK`.

**Review:**
- [ ] Spec compliance: two-folder count and `.tmp` check; field-by-field comparison with examples; cleanup order exactly as §3.3; partner configuration untouched.
- [ ] Code quality: bound parameters, no string-concatenated SQL from data; JSch sessions closed.

---

### Task 12: TC-001 Partner Order to Warehouse — Tc001PartnerOrderToWarehouseIT

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java`

**Guides to Load:**
- `camel-test/guides/route-analysis.md` — behaviours of `partner-order-api`, `peakmart-order-check`, `pick-list-delivery`, `partner-rejection-email` routes exercised
- `camel-test/guides/test-generation.md` — Test Design Principles (YAML file layout not applicable, P-2)
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md` — execution and failure triage (run command per P-11)

**MCP Tools:**
- `citrus_catalog_action(name="send"|"receive"|"http"|"sql"|"expectTimeout"|"waitFor"|"sleep", version="5.0.1")` — re-check every action and option actually written
- `citrus_catalog_endpoint(name="http-client"|"http-server"|"mail-server", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="platform-http", B)`; `camel_catalog_component_doc(component="sftp", B)`

**Design Spec Section:** §3.4 (all rows); §3.2; §3.3 Waiting rule; A-9, A-10, A-15

- [ ] **Step 1:** Read `docs/test_cases/TC-001-partner-order-to-warehouse.md`. Class annotated `@SpecTestCase("TC-001")`, `@CitrusSupport`, one `@Test` with `@DisplayName("TC-001 Partner Order to Warehouse")` (title exactly as the document), injected `TestCaseRunner` and `TestReporter`.
- [ ] **Step 2:** Static `ITEMS` table in document order: `C-setup` run (preconditions established, Step 3); `C-helpdesk` notYetTestable UC_005; `S1`–`S5` run; `S6`, `S7` notYetTestable UC_005; `V1` run; `V2` notYetTestable UC_005; `V3` run; `V4` notYetTestable UC_006; `P1`, `P2`, `P3` run; `P-cleanup` run. Item names exactly as the document's step/validation wording.
- [ ] **Step 3:** Preconditions (item `C-setup`, before S1): `OrderDatabase.cleanup("TG-1060")`; `WarehouseInbox.remove` and assert `count("PL-TG-1060.csv")==0`; catalog stub `clearRequests()`; start `IntegrationProcess("TC-001")`.
- [ ] **Step 4:** S1 `Partner.submitOrder`; S2 catalog asked for exactly `TENT-2P`, `LAMP-HD200` and `order_line` holds product names, weights and prices 249.00 / 39.95; S3 `expectAccepted("TG-1060")` with no `rejectionReasons`; S4 recorded as run (outcome checked in S5); S5 `waitFor` (action condition on `WarehouseInbox`) up to `spec-test.arrival-timeout-ms`, then compare with `pl-tg-1060.json` (header + 2 rows, `total_weight_g` 2340 on both, no `.tmp`) and assert elapsed since S1 < 15 min.
- [ ] **Step 5:** After `sleep(spec-test.quiet-period-ms)`: V1 exactly one file across both folders; V3 `Mailboxes.expectNone("orders@summit-outfitters.example.com", "TG-1060", quiet)`; P1 `customer_order` `TG-1060`, partner `summit-outfitters`, lines TENT-2P×1@249.00, LAMP-HD200×2@39.95 USD, address Lee Martin, 18 Canyon Road, Moab 84532, US; P2 `order_state_change` Received, Recorded, Sent to Warehouse once each in order; P3 file present.
- [ ] **Step 6:** `finally`: P-cleanup (DB cleanup in order, file removed, partner config and catalog untouched), `IntegrationProcess.stop()`, `SpecRun.finish()`.
- [ ] **Step 7:** Verify: `./mvnw verify -Dit.test=Tc001PartnerOrderToWarehouseIT` exits 0 with failsafe `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; `target/spec-tests/results/TC-001.json` has 12 `PASSED` run items (`C-setup`, S1–S5, V1, V3, P1–P3, P-cleanup) and 5 `NOT_YET_TESTABLE` items (UC-005 ×4, UC-006 ×1) — adjust only if the document counts differ, never by dropping an item; `target/spec-tests/logs/TC-001.log` exists. If the test fails on an integration defect, apply P-13.

**Review:**
- [ ] Spec compliance: every §3.4 row present with the stated status; nothing UC-005/UC-006 faked; preconditions established by the test; cleanup in `finally`.
- [ ] Code quality: Citrus actions/options re-checked on the 5.0.1 server; no fixed sleeps except the quiet period; no secret in logs.

---

### Task 13: TC-002 Peakmart Redelivery Shipped Once — Tc002PeakmartRedeliveryShippedOnceIT

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/Tc002PeakmartRedeliveryShippedOnceIT.java`

**Guides to Load:**
- `camel-test/guides/route-analysis.md` — `peakmart-order-intake`, `peakmart-order-check`, `pick-list-delivery` behaviours (de-duplication)
- `camel-test/guides/test-generation.md` — Test Design Principles (YAML layout not applicable, P-2)
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `citrus_catalog_action(name="send"|"sql"|"expectTimeout"|"waitFor"|"sleep", version="5.0.1")`
- `citrus_catalog_endpoint_schema(name="kafka-asynchronous", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="kafka", B)`

**Design Spec Section:** §3.5 (all rows); §3.2; §3.3 Waiting rule

- [ ] **Step 1:** Read `docs/test_cases/TC-002-peakmart-redelivery-shipped-once.md`. `@SpecTestCase("TC-002")`, `@CitrusSupport`, one `@Test`, `@DisplayName("TC-002 Peakmart Redelivery Shipped Once")`.
- [ ] **Step 2:** `ITEMS` in document order: `C-setup` run; `C-helpdesk` notYetTestable UC_005; `S1`–`S6` run; `S7`, `S8` notYetTestable UC_005; `V1` run; `V2` notYetTestable UC_005; `V3a` run; `V3b` notYetTestable UC_006; `P1`–`P4` run; `P-cleanup` run.
- [ ] **Step 3:** Preconditions: cleanup `PM-50117`; no `PL-PM-50117.csv`; catalog `clearRequests()`; `Peakmart.endOffsets()` snapshot; start `IntegrationProcess("TC-002")`.
- [ ] **Step 4:** S1 publish `peakmart-order-placed-sample.json`; S2 catalog asked for TENT-2P and STAKE-AL8, `order_line` prices 229.00 and 12.50; S3/S4 `waitFor` file then compare with `pl-pm-50117.json` (two rows, street `2240 Pearl Street, Apt 4`, total weight 2390), elapsed < 15 min; S5 publish `pm-50117-redelivered.json`; S6 after the quiet period exactly one file, no `.tmp`.
- [ ] **Step 5:** V1 one file and the single `pick_list` row `Delivered`; V3a `Mailboxes.expectNone(order-desk, "PM-50117", quiet)`; P1 one `customer_order` `PM-50117` channel Peakmart, TENT-2P×1@229.00, STAKE-AL8×2@12.50, address as the document states, and one `order_receipt`; P2 history Received, Recorded, Sent to Warehouse once each; P3 file present; P4 end offsets grew by exactly 2.
- [ ] **Step 6:** `finally`: P-cleanup, stop process, `finish()`.
- [ ] **Step 7:** Verify: `./mvnw verify -Dit.test=Tc002PeakmartRedeliveryShippedOnceIT` exits 0 with `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; `target/spec-tests/results/TC-002.json` exists with no `FAILED`/`NOT_RUN` item and `NOT_YET_TESTABLE` items naming only UC-005 and UC-006. P-13 applies to integration defects.

**Review:**
- [ ] Spec compliance: every §3.5 row; V3 split into V3a/V3b exactly; redelivery has a new event id from the example; offsets +2.
- [ ] Code quality: server-verified Citrus vocabulary; selectors never consume other tests' mail.

---

### Task 14: TC-003 Peakmart Order Rejected — Tc003PeakmartOrderRejectedIT

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/Tc003PeakmartOrderRejectedIT.java`

**Guides to Load:**
- `camel-test/guides/route-analysis.md` — `peakmart-order-check` rejection and `order-desk-notification` behaviours
- `camel-test/guides/test-generation.md` — Test Design Principles (YAML layout not applicable, P-2)
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `citrus_catalog_action(name="send"|"receive"|"sql"|"expectTimeout"|"sleep", version="5.0.1")`
- `citrus_catalog_endpoint_schema(name="mail-server", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="smtp", B)`

**Design Spec Section:** §3.6 (all rows); §3.2; A-16

- [ ] **Step 1:** Read `docs/test_cases/TC-003-peakmart-order-rejected.md` and `docs/messages/examples/order-desk-notification/pm-50118.json`. `@SpecTestCase("TC-003")`, `@DisplayName("TC-003 Peakmart Order Rejected")`.
- [ ] **Step 2:** `ITEMS`: `C-setup` run; `C-helpdesk` notYetTestable UC_005; `S1`–`S3` run; `S4`, `S5` notYetTestable UC_005; `V1` run; `V2` notYetTestable UC_005; `V3` run; `V4` notYetTestable UC_005; `P1`, `P2` run; `P-cleanup` run.
- [ ] **Step 3:** Preconditions: cleanup `PM-50118`; zero-wait selector check that no Order Desk mail for `PM-50118` is queued; no file; catalog `clearRequests()`; start `IntegrationProcess("TC-003")`.
- [ ] **Step 4:** S1 publish `pm-50118-inactive-sku.json`; S2 catalog asked for TENT-1P and one `rejection_reason` (`sku`, line 1); S3 `Mailboxes.receiveOne("order-desk@trailhead.example", "Peakmart order PM-50118 rejected", arrival)` — plain text, body contains `PM-50118` and the reason text of the example verbatim with Order Line 1, and none of the buyer's name, email or address fields taken from the event file; elapsed < 5 min.
- [ ] **Step 5:** After the quiet period: V1 no `PL-PM-50118.csv` or `.tmp` in either folder; V3 `expectNone` for a second notification; P1 `order_receipt` Rejected, history Received, Rejected only, one reason, no `customer_order`; P2 `notification.sent_at` set and S3 received.
- [ ] **Step 6:** `finally`: P-cleanup (`notification`, `rejection_reason` before history and receipt), stop, `finish()`.
- [ ] **Step 7:** Verify: `./mvnw verify -Dit.test=Tc003PeakmartOrderRejectedIT` exits 0 with `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; `target/spec-tests/results/TC-003.json` has no `FAILED`/`NOT_RUN` item and every `NOT_YET_TESTABLE` item names UC-005. P-13 applies.

**Review:**
- [ ] Spec compliance: every §3.6 row; no personal data in the mail asserted absent; reason text verbatim (A-16).
- [ ] Code quality: mail header names from Task 7 evidence; exactly-one = receive + expectTimeout.

---

### Task 15: TC-004 Set-Aside Order Event Reaches Warehouse — Tc004SetAsideOrderEventReachesWarehouseIT

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java`

**Guides to Load:**
- `camel-test/guides/route-analysis.md` — `peakmart-order-intake` set-aside behaviour
- `camel-test/guides/test-generation.md` — Test Design Principles (YAML layout not applicable, P-2)
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `citrus_catalog_action(name="send"|"sql"|"sleep", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="kafka", B)`

**Design Spec Section:** §3.7 (all rows); §3.2; A-14

- [ ] **Step 1:** Read `docs/test_cases/TC-004-set-aside-order-event-reaches-warehouse.md`. `@SpecTestCase("TC-004")`, `@DisplayName("TC-004 Set-Aside Order Event Reaches Warehouse")`.
- [ ] **Step 2:** `ITEMS` in document order: `C-setup` run; `C-resubmit` notYetTestable UC_008; `S1` run; `S2` notYetTestable UC_006; `S3` run (file part) — the Alert part declared as a separate notYetTestable UC_006 item so both checks count; `S4`–`S8` notYetTestable UC_008; `V1`, `V2` notYetTestable UC_008; `V3` notYetTestable UC_006; `V4` run; `P1`, `P2`, `P4` notYetTestable UC_008; `P3` notYetTestable UC_006; `P-cleanup` run. Use item IDs `S3` and `S3-alert` and record that ID choice for Task 18's completeness rule.
- [ ] **Step 3:** Preconditions: cleanup `PM-50119` and set-aside event `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f`; no file; offset snapshot; start `IntegrationProcess("TC-004")`.
- [ ] **Step 4:** S1 publish `invalid/pm-50119-buyer-email-not-an-email.json`; poll up to arrival for one `set_aside_order_event` with that event id, order number `PM-50119`, reason `$.buyer.email: format`, state `Set Aside`, and no `order_receipt` for `PM-50119`. S3 after the quiet period: no `PL-PM-50119.csv` in either folder. V4 offsets grew by exactly 1.
- [ ] **Step 5:** `finally`: P-cleanup (set-aside record removed), stop, `finish()`.
- [ ] **Step 6:** Verify: `./mvnw verify -Dit.test=Tc004SetAsideOrderEventReachesWarehouseIT` exits 0 with `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; `target/spec-tests/results/TC-004.json` has `PASSED` for `C-setup`, `S1`, `S3`, `V4`, `P-cleanup` and the rest `NOT_YET_TESTABLE` naming UC-006 or UC-008. P-13 applies.

**Review:**
- [ ] Spec compliance: every §3.7 row; no resubmission or alert faked; set-aside evidence from the table only (A-14).
- [ ] Code quality: event id and reason asserted exactly; cleanup in `finally`.

---

### Task 16: TC-005 Pick List Once After Restart — Tc005PickListOnceAfterRestartIT

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java`

**Guides to Load:**
- `camel-test/guides/route-analysis.md` — `pick-list-delivery` retry/hold behaviour (UC-004 A1)
- `camel-test/guides/test-generation.md` — Test Design Principles (YAML layout not applicable, P-2)
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `citrus_catalog_action(name="send"|"receive"|"http"|"sql"|"waitFor"|"sleep", version="5.0.1")`
- `camel_catalog_components(limit=0, B)`; `camel_catalog_component_doc(component="sftp", B)` — connection refused handling, `knownHostsFile`

**Design Spec Section:** §3.8 (all rows); A-11, A-12, A-13

- [ ] **Step 1:** Read `docs/test_cases/TC-005-pick-list-once-after-restart.md`. `@SpecTestCase("TC-005")`, `@DisplayName("TC-005 Pick List Once After Restart")`.
- [ ] **Step 2:** `ITEMS`: `C-setup` run; `C-helpdesk` notYetTestable UC_005; `S1`–`S3` run; `S4` run (data part) plus `S4-lookup` notYetTestable UC_005; `S5`–`S7` run; `S8`, `S9` notYetTestable UC_005; `V1` run; `V2` notYetTestable UC_005; `V3` run; `P1`–`P4` run; `P-cleanup` run.
- [ ] **Step 3:** Preconditions: cleanup `TG-1060`; pick port P with `FreePorts` and confirm nothing listens; write `known_hosts` for P with the run's host keys; start `IntegrationProcess("TC-005")` with SFTP host 127.0.0.1 and port P (override of the singleton SFTP port for this class only).
- [ ] **Step 4:** S1–S3 as TC-001 (submit, catalog asked, accepted). S4 poll until `order_receipt` Recorded, history Received, Recorded, `pick_list` row `Pending` with `attempts >= 1`.
- [ ] **Step 5:** S5 `IntegrationProcess.restart()` (SIGTERM) — ready again, `pick_list` still `Pending`; then `SpecEnvironment.startSftpOn(P)` (this class's own container, 127.0.0.1:P, same host keys and password). S6/S7 `waitFor` the file within arrival of the server start and < 15 min of S1; compare with `pl-tg-1060.json`, total 2340, no `.tmp`.
- [ ] **Step 6:** After the quiet period: V1 exactly one file, no `.tmp`; V3 `Partner.requestsSent()==1` and one `order_receipt` for `TG-1060`; P1–P3 as TC-001; P4 the TC-005 container is running.
- [ ] **Step 7:** `finally`: P-cleanup, stop process (log `TC-005-2.log` kept), `finish()`; the TC-005 container is left to Testcontainers.
- [ ] **Step 8:** Verify: `./mvnw verify -Dit.test=Tc005PickListOnceAfterRestartIT` exits 0 with `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; `target/spec-tests/results/TC-005.json` has no `FAILED`/`NOT_RUN` item and every `NOT_YET_TESTABLE` names UC-005; `target/spec-tests/logs/TC-005.log` and `TC-005-2.log` exist. P-13 applies.

**Review:**
- [ ] Spec compliance: unavailable server = nothing listening on P (A-12); graceful restart (A-13); no clock dependency (A-11); every §3.8 row.
- [ ] Code quality: no port change across restart; container bound to 127.0.0.1; process stopped in `finally`.

---

### Task 17: SpecTraceabilityTest — Static Traceability Check

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/SpecTraceabilityTest.java`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 Static check (rules 1–5); A-7

- [ ] **Step 1:** Discover classes in package `com.trailheadgear.orderintegration.spec` named `*IT` from `target/test-classes` (file scan; no new dependency). Read their `@SpecTestCase` and `@DisplayName`, and their static `ITEMS` field by reflection.
- [ ] **Step 2:** Implement rules 1–5 of §3.9 as a pure function `List<String> violations(documents, useCases, classes)` and one `@Test` that asserts it returns no violation for the real documents and classes.
- [ ] **Step 3:** Add negative `@Test`s on in-memory inputs, one per rule (missing class, duplicate class, missing annotation/unknown ID/non-Approved document, name or display-name mismatch, unknown or non-Approved use case), each asserting the expected violation message.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test -Dtest=SpecTraceabilityTest -Dsurefire.failIfNoSpecifiedTests=false` exits 0 and `target/surefire-reports/*SpecTraceabilityTest.txt` shows `Failures: 0, Errors: 0` with `Tests run` ≥ 6.

**Review:**
- [ ] Spec compliance: all five rules; runs in surefire without Docker; reads `ITEMS`, not a duplicate list.
- [ ] Code quality: clear violation messages naming class and document.

---

### Task 18: SpecMatrix — Completeness Check and Traceability Matrix

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java`
- Modify: `pom.xml` (add execution `spec-traceability-matrix` to the existing `exec-maven-plugin`)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/maven-dependencies.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 Completeness check and matrix; §3.10 (exec execution); A-8

- [ ] **Step 1:** `SpecMatrix.main(docsDir, workDir, itsSkipped)`: read the documents (`TestCaseDocument`), the `ITEMS` tables, and `target/spec-tests/results/*.json`.
- [ ] **Step 2:** Fail (non-zero exit / exception) when an item of a test case (step, validation, postcondition, precondition marker `C-setup` / `C-helpdesk` / `C-resubmit`) is neither a run item nor a not-yet-testable item, or when a result file is missing for an Approved test case while ITs ran. Split items (`V3a`/`V3b`, `S3`/`S3-alert`, `S4`/`S4-lookup`) cover their parent item.
- [ ] **Step 3:** Write `target/spec-tests/traceability-matrix.md` and `.json`: (a) use case → test case step → class → outcome (`passed`, `failed`, `not run`, `not yet testable`), UC-007 listed as precondition-only, use cases without a step as "no test case"; (b) per test case every item with status and a summary line in the form `TC-001: 12 run (12 passed), 5 not yet testable (UC-005 ×4, UC-006 ×1)`; (c) "Not yet testable, by use case".
- [ ] **Step 4:** With `skipITs` set, write the matrix with every run item `not run` and exit 0.
- [ ] **Step 5:** `pom.xml`: second `exec-maven-plugin` execution `spec-traceability-matrix`, phase `post-integration-test`, goal `java`, `classpathScope=test` (as `bundle-partner-contract`), main class `com.trailheadgear.orderintegration.spec.SpecMatrix`, arguments `${project.basedir}/docs`, `${project.build.directory}/spec-tests`, `${skipITs}` (define `<skipITs>false</skipITs>` in `<properties>`). Keep `bundle-partner-contract` unchanged.
- [ ] **Step 6:** Verify (the acceptance run): `./mvnw verify` exits 0; failsafe reports `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`; `test -s target/spec-tests/traceability-matrix.md && test -s target/spec-tests/traceability-matrix.json && grep -cE '^TC-00[1-5]: ' target/spec-tests/traceability-matrix.md` prints `5`. Then `./mvnw verify -DskipITs` exits 0 and the matrix shows run items as `not run`. P-13 applies to integration defects.

**Review:**
- [ ] Spec compliance: completeness rule and missing-result rule; three matrix sections; `.md` and `.json`; `-DskipITs` behaviour; runs after failsafe and before `failsafe:verify`.
- [ ] Code quality: existing exec execution unchanged; matrix not committed (under `target/`).

---

### Task 19: README — Build Section

**Agent:** implementation-engineer

**Files:**
- Modify: `README.md` (§ Build only)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.10 last bullet; §3.9 (matrix location); A-17

- [ ] **Step 1:** Update § Build: `./mvnw verify` needs Docker, takes several minutes, runs the five spec tests and writes `target/spec-tests/traceability-matrix.md` (and `.json`); without Docker it fails (A-17); `./mvnw verify -DskipITs` runs the unit tests only. Update the test count line to state unit tests and five spec tests. Change no other section.
- [ ] **Step 2:** Verify: `grep -q 'skipITs' README.md && grep -q 'traceability-matrix' README.md && grep -qi 'docker' README.md && git diff --stat README.md` shows only README changes, and `echo README-OK` prints `README-OK`.

**Review:**
- [ ] Spec compliance: Docker requirement, duration, matrix location, `-DskipITs`.
- [ ] Code quality: other README sections unchanged.

---

## Runtime Acceptance (performed by camel-execute after all tasks)

`./mvnw verify` with Docker running: 34 existing unit tests + `TestCaseDocumentTest` + `SpecTraceabilityTest` pass,
five ITs pass, the matrix is written. The execution report records (design §7 "Required in the execution report"):
every image with tag and digest (Kafka, PostgreSQL, SFTP, Ryuk); the Citrus MCP evidence for every endpoint, action and
option the committed tests use (this plan's table plus execution re-checks, including the mail header names and the
bind-address findings of P-10); the finding that `dev` runs PostgreSQL 17 while C-004 and the tests use 16 (left to
chapter 9); every defect a test exposes (P-13); the §7 confirmations (artifact names, Testcontainers 2.x and
`ryuk.container.image`, atmoz/sftp host keys and nested folders, JSch Ed25519, `Started CamelApplication`).
