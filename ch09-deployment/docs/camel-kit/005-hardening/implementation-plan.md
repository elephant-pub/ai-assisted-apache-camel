---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-14T03:20:00Z"
  by: camel-plan
  from: design-spec.md
---
# Trailhead Order Integration — Hardening (005) Implementation Plan

> **For agentic workers:** Use camel-execute to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every failure of every route ends in a recorded, rejected, delivered, set-aside or waiting state with its
reason, a partner always gets the contract answer within 10 s, and the kill guarantees of NFR-006/NFR-007 are proven
in every `./mvnw verify`. Each change has a test that was seen failing without it.

**Architecture:** Two plain Java beans in `IntegrationBeans` — `errorContainment` (an `InterceptStrategy` that turns a
step's `Error` into `UnexpectedErrorException`, or halts the JVM with 70 on a `VirtualMachineError`) and
`failureKinds` (classifies a failure as wait / set aside / park and describes it without message text). The six route
files' `routeConfiguration` clauses use them to give each failure its end state; Flyway V3 adds the reason columns;
pgjdbc/Hikari time limits plus a 9 s last-resort MVC answer bound the partner path. A test-only fault hook, packaged as
a separate jar and loaded through `PropertiesLauncher`, lets failsafe ITs inject `Error`s, `OutOfMemoryError`s and
kills into the unchanged packaged jar.

**Tech Stack:** Apache Camel 4.22.2 (unchanged), Spring Boot 4.1.1, Java 21, Camel Spring Boot BOM 4.22.2, PostgreSQL 16
(pgjdbc, Hikari, Flyway), Kafka, JSch (`camel-ftp-starter`), Angus/Jakarta Mail (`camel-mail-starter`), JUnit Jupiter,
`camel-test-spring-junit6`, Testcontainers 2.x, Citrus 5.0.1 (existing spec harness only), maven-surefire/failsafe,
maven-jar-plugin.

**Design Spec:** `docs/camel-kit/005-hardening/design-spec.md` (approved 2026-11-13 with design-review decisions 1–5
applied; `camel-kit doc check`: not stale, `generated.by: camel-brainstorm`)

---

## Planning Decisions and Assumptions (non-interactive run — recommended option taken)

| # | Decision | Reason |
|---|---|---|
| P-1 | **Standalone mode.** The plan is written and the run stops; `camel-execute` is not auto-invoked. | `/camel-plan 005-hardening` was invoked directly in a new session; `design-spec.md` exists with valid `camel-brainstorm` provenance and is not stale. |
| P-2 | **One plan, not split**, although it has 38 tasks. | All changes harden one integration and share `IntegrationBeans`, V3, the route error clauses and one test harness; a split plan could not be tested on its own (H1 without the route clauses changes no end state). |
| P-3 | **Test tasks are JUnit/failsafe Java tests as design §3.9/§3.10 names them, not `.camel.it.yaml` files run by `camel test run`.** The testing template is loaded for its role (test tasks after the artifacts they need, metadata `testData`/tests) only. | The approved design fixes test names, kinds and harness (§3.9); pipeline 004 established the same deviation (its P-2). |
| P-4 | **Red before green (decision 2) is planned as test task → change task.** Each test task records its red run; the change task that completes the behaviour records the green run. Tests of existing code (T1, T4, T5, T8) record red with their fault applied (2.0.1 pin removed, F5, F6, F7) and green in the same task. The four small §3.10 harness items have one test task (Task 17, red) and one change task each (Tasks 31–34). | Design §3.9 "Red before green". |
| P-5 | **Red for a new class** is obtained by creating the class with its final public signatures in the test task, each body `throw new UnsupportedOperationException("design 005: not implemented")`. The red run then fails on assertions/that message, not on compilation. | A compilation failure records no assertion or message (decision 2 asks for test, assertion, message). |
| P-6 | **Red/green and build-time records** are appended to `docs/camel-kit/005-hardening/evidence/red-green-runs.md` (one row per run: test, code state/fault, result, assertion, message, duration) and `docs/camel-kit/005-hardening/evidence/build-times.md`. `camel-execute` copies them into `execution-report.md`. | Both are pipeline evidence (design "Required in the execution report"); keeping them in the pipeline directory survives an execute resume. |
| P-7 | **`failureKinds.describe` returns at most 450 characters**, so `cannot be taken in: ` / `cannot be recorded: ` + kind stays within the 500-character columns. T2 checks ≤ 450. | Design §3.5: describe ≤ 500 and the column is `varchar(500)`; the prefix must fit too. |
| P-8 | **Interceptor order:** `ErrorContainment` and `FaultHook` both implement `org.apache.camel.Ordered`; `ErrorContainment` returns `Ordered.HIGHEST` (outermost), `FaultHook` `Ordered.LOWEST` (innermost, so what it throws reaches H1 as a step's own `Error`). Task 19 confirms with T3's two-strategy case that this is the effective order in Camel 4.22.2 and records it. | Design §3.9 fault hook: "the hook wraps inside `errorContainment`; T3 and T7 fail if the order is wrong". |
| P-9 | **Flyway's own connection (decision 3):** `spring.flyway.url=${spring.datasource.url}`, `spring.flyway.user=${spring.datasource.username}`, `spring.flyway.password=${spring.datasource.password}` in the shared `application.properties`. Spring Boot then gives Flyway its own data source, without the Hikari `data-source-properties`, and the per-profile URLs stay unchanged. T12 proves it. | The design's example mechanism; it keeps `RequiredVariablesCheck`'s variable list unchanged. |
| P-10 | **Failure ITs run in a second failsafe execution after the spec ITs** (`failure-tests`, includes `**/failure/*IT.java`, its own `summaryFile`; the `verify` goal reads both summary files). Surefire excludes `**/failure/*IT.java`. | `runOrder=alphabetical` alone would run `…/failure/` before `…/spec/`; design §3.9: "run by failsafe after the spec ITs". |
| P-11 | **Per-IT command** (valid in every task, also after the matrix execution exists, because it bypasses the lifecycle): `./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=<Class>`. The final acceptance remains the full `./mvnw verify`. Task 3 confirms the command works on first use. | `SpecMatrix` runs at `post-integration-test` and fails when spec result files are missing; invoking the failsafe goals directly skips it. |
| P-12 | **Shared DB-only test helper** `src/test/java/com/trailheadgear/orderintegration/failure/SqlStatements.java` (created in Task 6): starts the pinned PostgreSQL image `spec-test.image.postgres` from `spec-test/spec-test.properties`, runs Flyway over `classpath:db/migration`, and runs a `src/main/resources/sql/*.sql` file verbatim, translating only Camel's `:#name` parameters to `:name` for `NamedParameterJdbcTemplate`. Used by T5, T6, T13. | DRY across three ITs; the statements tested are the production files, byte for byte. |
| P-13 | **K1 has two kill points** in `KillIT`: before `commit-kafka-offset` (receipt written, not committed → Duplicate after restart, recorded once) and before `to-receipt-insert` (nothing written → recorded once). F7 (developer-checks 004: the commit moved before the receipt is written) makes the second one lose the order. | Developer-checks 004: "an early commit loses an order only if the process dies between the commit and the write"; design T8 "F7 makes K1 fail (a lost order)". |
| P-14 | **Split items at planning time** (re-read from the ITs in Task 33 and compared with design 004 §3): `TC-002=V3a,V3b`, `TC-004=S3-alert`, `TC-005=S4-lookup`. | Design §3.10: "the plan copies the full list from the ITs and checks it against design 004 §3". |
| P-15 | Guides are selected from the active `camel-implement` and `camel-test` manifests only. `.camel-kit/project-graph.json` does not exist, so no graph guide is loaded. No downstream artifact (`execution-report.md`, `validation-report.md`) exists yet, so the staleness cascade after writing this plan is a no-op. | `camel-plan` guide rules; `shared/pipeline-infrastructure.md`. |
| P-16 | A test that exposes a defect beyond its own change is **not** weakened: the task records it for the execution report; `src/main/` changes only where this plan's tasks name them. | Design §3.9: "A test that passes against the code without its change is a finding: the test is fixed before the change is made." |

**Path resolution** (`camel-implement/guides/orchestrator.md` path table, runtime `spring-boot`, Target Module = project
root, so `[MODULE_DIR]` is empty): routes `src/main/resources/camel/`, properties `src/main/resources/`, SQL
`src/main/resources/sql/`, migrations `src/main/resources/db/migration/`, Java
`src/main/java/com/trailheadgear/orderintegration/`, tests `src/test/java/com/trailheadgear/orderintegration/`
(sub-packages `fault/`, `failure/`, `spec/`, `spec/support/`), test resources `src/test/resources/`. Abbreviations in
the task bodies: `J/` = `src/main/java/com/trailheadgear/orderintegration/`, `TJ/` =
`src/test/java/com/trailheadgear/orderintegration/` — the **Files** lists and the metadata always give full paths.

**Common Camel MCP binding for every task (run once per task before any catalog detail call):**
`camel_catalog_components(limit=0, runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2")`;
the returned `camelVersion` must equal `4.22.2` (planning result: `4.22.2`, MATCHED). Below, "`B`" means
`runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2"`.
Planning re-checks (MATCHED): `camel_catalog_component_doc(component="sql", B)` returns `onConsume`,
`onConsumeFailed`, `onConsumeBatchComplete`, `breakBatchOnConsumeFail` (default false);
`camel_catalog_eip_doc(eip="onException", B)` returns `exception` (required), `onWhen`, `handled`, `redeliveryPolicy`.
The design's §5 catalog evidence (kafka, sql, http, smtp, sftp, platform-http, json-validator, direct, onException,
routeConfiguration, doTry, split, choice, filter, simple, groovy, jsonpath, csv) still applies; no component is added.

**Hard constraints for every task (design Not Doing, §4):**
- No UC-005/UC-006/UC-008 behaviour, no alert sent, no dead-letter topic/queue, no circuit breaker, no new Order Check
  or Rejection Reason (OQ-075 reading a), no TLS, no load test, no deployment/monitoring/health/restart work, no
  backing-off of the mail and SFTP retries.
- No new component and no new dependency in the production jar. The fault hook lives only in the test classes and
  in `target/order-integration-0.1.0-fault-hook.jar`.
- No message text in any new log line or column: exception/`Error` class names, SQLState and reply codes only
  (NFR-011, C-021). Every new log line carries `orderNumber` (when known) and `correlationId`.
- Never bind 8080, 8081, 5432; containers only through Testcontainers (`pause`/`exec` through its Docker client is
  allowed). Every image reference carries `@sha256:` (reuse `spec-test/spec-test.properties`).
- A fault applied for a red run is one exact, reversible text edit; it is undone by reversing that edit (never
  `git checkout` of a file with this pipeline's uncommitted changes) and the task's verify step proves it is gone.

---

```yaml plan-metadata
tasks:
  - id: 1
    title: Baseline Build Times (before)
    files:
      creates:
        - docs/camel-kit/005-hardening/evidence/build-times.md
      reads:
        - pom.xml
    provides:
      testData:
        - build-times-before
    consumes: {}
    dependsOn: []
  - id: 2
    title: FaultHook and the Fault-Hook Jar
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/fault/FaultHook.java
      modifies:
        - pom.xml
      reads:
        - src/test/java/com/trailheadgear/orderintegration/KillTestHook.java
        - docs/camel-kit/005-hardening/evidence/failure-lab-runs.md
    provides:
      beans:
        - faultHook
      routeContracts:
        - fault-hook-jar
      properties:
        - fault.points
    consumes: {}
    dependsOn:
      - 1
  - id: 3
    title: IntegrationProcess.withFaults and Failsafe for the Failure Tests
    files:
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java
        - pom.xml
    provides:
      routeContracts:
        - integration-process-with-faults
        - failure-its-build
      properties:
        - spec-test.fault-hook-jar
    consumes:
      routeContracts:
        - fault-hook-jar
    dependsOn:
      - 2
  - id: 4
    title: T1 JsonValidatorPinTest — Guard of the 2.0.1 Pin
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/JsonValidatorPinTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/JsonValidatorPinTest.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - pom.xml
        - docs/messages/examples/order/from-peakmart-order.json
        - docs/messages/order.schema.json
    provides:
      testData:
        - red-green-T1
    consumes: {}
    dependsOn:
      - 3
  - id: 5
    title: T4 WarehouseSftp RemoteFiles Seam and WarehouseSftpLookupTest
    files:
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      creates:
        - src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java
    provides:
      beans:
        - warehouseSftp
      testData:
        - red-green-T4
    consumes: {}
    dependsOn:
      - 4
  - id: 6
    title: T5 NotificationTextIT and the SqlStatements Test Helper
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/SqlStatements.java
        - src/test/java/com/trailheadgear/orderintegration/failure/NotificationTextIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/NotificationTextIT.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/main/resources/sql/order-reject.sql
        - src/main/resources/sql/notification-select.sql
        - src/main/resources/sql/rejection-email-select.sql
        - src/test/resources/spec-test/spec-test.properties
    provides:
      routeContracts:
        - sql-statements-helper
      testData:
        - red-green-T5
    consumes:
      routeContracts:
        - failure-its-build
    dependsOn:
      - 3
      - 5
  - id: 7
    title: T8 KillIT — Kill Points in ./mvnw verify
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java
      deletes:
        - src/test/java/com/trailheadgear/orderintegration/KillTestHook.java
        - src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java
        - src/test/scripts/kill-test.sh
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/main/resources/camel/peakmart-order-intake.camel.yaml
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java
        - docs/camel-kit/004-spec-tests/developer-checks.md
    provides:
      testData:
        - red-green-T8
    consumes:
      routeContracts:
        - integration-process-with-faults
      beans:
        - faultHook
    dependsOn:
      - 6
  - id: 8
    title: T2 FailureKindsTest (red) with FailureKinds Signatures
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/FailureKinds.java
        - src/main/java/com/trailheadgear/orderintegration/UnexpectedErrorException.java
        - src/test/java/com/trailheadgear/orderintegration/FailureKindsTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/FailureKindsTest.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routeContracts:
        - failure-kinds-api
      testData:
        - red-T2
    consumes: {}
    dependsOn:
      - 7
  - id: 9
    title: T3 ErrorContainmentTest (red) with ErrorContainment Signatures
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/ErrorContainment.java
        - src/test/java/com/trailheadgear/orderintegration/ErrorContainmentTest.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/ErrorContainmentTest.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/test/java/com/trailheadgear/orderintegration/fault/FaultHook.java
        - src/main/java/com/trailheadgear/orderintegration/UnexpectedErrorException.java
    provides:
      routeContracts:
        - error-containment-api
      testData:
        - red-T3
    consumes:
      beans:
        - faultHook
    dependsOn:
      - 8
  - id: 10
    title: T6 WaitingSelectIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/WaitingSelectIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/WaitingSelectIT.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/main/resources/sql/waiting-select.sql
    provides:
      testData:
        - red-T6
    consumes:
      routeContracts:
        - sql-statements-helper
    dependsOn:
      - 9
  - id: 11
    title: T12 ConnectionSettingsIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/ConnectionSettingsIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/ConnectionSettingsIT.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/main/resources/application.properties
    provides:
      testData:
        - red-T12
    consumes:
      routeContracts:
        - failure-its-build
    dependsOn:
      - 10
  - id: 12
    title: T7 UnexpectedErrorIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/UnexpectedErrorIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/UnexpectedErrorIT.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      testData:
        - red-T7
    consumes:
      routeContracts:
        - integration-process-with-faults
      beans:
        - faultHook
    dependsOn:
      - 11
  - id: 13
    title: T9 DatabaseFrozenIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/DatabaseFrozenIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/DatabaseFrozenIT.java
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routeContracts:
        - spec-environment-container-control
      testData:
        - red-T9
    consumes:
      routeContracts:
        - failure-its-build
    dependsOn:
      - 12
  - id: 14
    title: T10 UnstorableOrderIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/UnstorableOrderIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/UnstorableOrderIT.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json
        - docs/messages/examples/partner-order/partner-order-sample.json
    provides:
      testData:
        - red-T10
    consumes:
      routeContracts:
        - failure-its-build
    dependsOn:
      - 13
  - id: 15
    title: T11 DeliveryFailureIT and RejectingSmtpServer (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/DeliveryFailureIT.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/RejectingSmtpServer.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/DeliveryFailureIT.java
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      testData:
        - red-T11
    consumes:
      routeContracts:
        - spec-environment-container-control
        - integration-process-with-faults
    dependsOn:
      - 14
  - id: 16
    title: T13 EndStateQueriesIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/EndStateQueriesIT.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/EndStateQueriesIT.java
      modifies:
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - README.md
    provides:
      testData:
        - red-T13
    consumes:
      routeContracts:
        - sql-statements-helper
    dependsOn:
      - 15
  - id: 17
    title: Harness Tests of §3.10 (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironmentTopicTest.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRunCleanupMessageTest.java
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecTraceabilityTest.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironmentTopicTest.java
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRunCleanupMessageTest.java
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecTraceabilityTest.java
    provides:
      testData:
        - red-harness
    consumes:
      routeContracts:
        - spec-environment-container-control
    dependsOn:
      - 16
  - id: 18
    title: FailureKinds — Implementation and Bean
    files:
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/FailureKinds.java
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      beans:
        - failureKinds
    consumes:
      routeContracts:
        - failure-kinds-api
      testData:
        - red-T2
    dependsOn:
      - 17
  - id: 19
    title: ErrorContainment — Implementation and Bean (H1)
    files:
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/ErrorContainment.java
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      beans:
        - errorContainment
    consumes:
      routeContracts:
        - error-containment-api
      testData:
        - red-T3
    dependsOn:
      - 18
  - id: 20
    title: Flyway V3 — Failure Reasons
    files:
      creates:
        - src/main/resources/db/migration/V3__failure_reasons.sql
      reads:
        - src/main/resources/db/migration/V1__order_database.sql
        - src/main/resources/db/migration/V2__partners_and_pick_lists.sql
    provides:
      schemas:
        - order_receipt.waiting_reason
        - order_receipt.last_check_at
        - pick_list.last_failure
        - notification.last_failure
        - notification.last_attempt_at
    consumes: {}
    dependsOn:
      - 17
  - id: 21
    title: Check SQL — waiting-select, order-wait, order-park, order-record, order-reject
    files:
      creates:
        - src/main/resources/sql/order-wait.sql
        - src/main/resources/sql/order-park.sql
      modifies:
        - src/main/resources/sql/waiting-select.sql
        - src/main/resources/sql/order-record.sql
        - src/main/resources/sql/order-reject.sql
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routeContracts:
        - sql-order-wait
        - sql-order-park
        - sql-waiting-select-v3
    consumes:
      schemas:
        - order_receipt.waiting_reason
        - order_receipt.last_check_at
      testData:
        - red-T6
    dependsOn:
      - 20
  - id: 22
    title: Outbox SQL — pick-list-failed, notification-failed, pick-list-delivered
    files:
      creates:
        - src/main/resources/sql/pick-list-failed.sql
        - src/main/resources/sql/notification-failed.sql
      modifies:
        - src/main/resources/sql/pick-list-delivered.sql
    provides:
      routeContracts:
        - sql-pick-list-failed
        - sql-notification-failed
    consumes:
      schemas:
        - pick_list.last_failure
        - notification.last_failure
        - notification.last_attempt_at
    dependsOn:
      - 20
  - id: 23
    title: Application Properties — Database Time Limits and Flyway's Own Connection (H3)
    files:
      modifies:
        - src/main/resources/application.properties
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/main/resources/application-default.properties
        - config/application-dev.properties
        - src/main/java/com/trailheadgear/orderintegration/RequiredVariablesCheck.java
    provides:
      properties:
        - spring.datasource.hikari.connection-timeout
        - spring.datasource.hikari.data-source-properties.connectTimeout
        - spring.datasource.hikari.data-source-properties.socketTimeout
        - spring.flyway.url
        - spring.flyway.user
        - spring.flyway.password
    consumes:
      testData:
        - red-T12
    dependsOn:
      - 22
  - id: 24
    title: PartnerTimeoutAnswer — Last-Resort Answer (H2)
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/PartnerTimeoutAnswer.java
      modifies:
        - src/main/resources/application.properties
      reads:
        - src/main/java/com/trailheadgear/orderintegration/OrderAnswers.java
        - docs/messages/order-answer.schema.json
    provides:
      properties:
        - spring.mvc.async.request-timeout
      beans:
        - partnerTimeoutAnswer
    consumes: {}
    dependsOn:
      - 23
  - id: 25
    title: Route YAML — partner-order-api Error Clauses (H2)
    files:
      modifies:
        - src/main/resources/camel/partner-order-api.camel.yaml
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routes:
        - partner-order-submit
        - partner-order-receive
        - partner-order-check
        - partner-order-answer
      routeContracts:
        - partner-order-error-handling-v005
    consumes:
      beans:
        - failureKinds
        - errorContainment
        - partnerTimeoutAnswer
      properties:
        - spring.datasource.hikari.connection-timeout
        - spring.mvc.async.request-timeout
      testData:
        - red-T9
    dependsOn:
      - 19
      - 23
      - 24
  - id: 26
    title: Route YAML — peakmart-order-intake Error Clauses (H4)
    files:
      modifies:
        - src/main/resources/camel/peakmart-order-intake.camel.yaml
        - src/main/resources/application.properties
    provides:
      routes:
        - peakmart-order-intake
        - peakmart-event-read
        - peakmart-event-set-aside
        - peakmart-order-receive
      properties:
        - peakmart.set-aside-redeliveries
      routeContracts:
        - peakmart-intake-error-handling-v005
    consumes:
      beans:
        - failureKinds
        - errorContainment
    dependsOn:
      - 25
  - id: 27
    title: Route YAML — peakmart-order-check Error Clauses (H5)
    files:
      modifies:
        - src/main/resources/camel/peakmart-order-check.camel.yaml
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routes:
        - peakmart-order-check-poll
        - order-check
        - product-lookup
        - order-reject
        - order-record
      routeContracts:
        - order-check-error-handling-v005
    consumes:
      beans:
        - failureKinds
        - errorContainment
      routeContracts:
        - sql-order-wait
        - sql-order-park
        - sql-waiting-select-v3
      testData:
        - red-T10
    dependsOn:
      - 21
      - 26
  - id: 28
    title: Route YAML — pick-list-delivery Failure Reason (H6)
    files:
      modifies:
        - src/main/resources/camel/pick-list-delivery.camel.yaml
    provides:
      routes:
        - pick-list-delivery
    consumes:
      beans:
        - failureKinds
        - errorContainment
      routeContracts:
        - sql-pick-list-failed
    dependsOn:
      - 22
      - 27
  - id: 29
    title: Route YAML — order-desk-notification Failure Reason (H6)
    files:
      modifies:
        - src/main/resources/camel/order-desk-notification.camel.yaml
    provides:
      routes:
        - order-desk-notification-send
    consumes:
      beans:
        - failureKinds
        - errorContainment
      routeContracts:
        - sql-notification-failed
    dependsOn:
      - 28
  - id: 30
    title: Route YAML — partner-rejection-email Failure Reason (H6)
    files:
      modifies:
        - src/main/resources/camel/partner-rejection-email.camel.yaml
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routes:
        - partner-rejection-email-send
    consumes:
      beans:
        - failureKinds
        - errorContainment
      routeContracts:
        - sql-notification-failed
      testData:
        - red-T7
        - red-T11
    dependsOn:
      - 29
  - id: 31
    title: SpecEnvironment.createTopic — Wait for the Broker, Retry
    files:
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routeContracts:
        - spec-environment-topic-creation
    consumes:
      testData:
        - red-harness
    dependsOn:
      - 30
  - id: 32
    title: SpecRun — Cleanup Message Choice
    files:
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routeContracts:
        - spec-run-cleanup-message
    consumes:
      testData:
        - red-harness
    dependsOn:
      - 31
  - id: 33
    title: SpecMatrix — Named Split Items Only
    files:
      creates:
        - src/test/resources/spec-test/split-items.properties
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc002PeakmartRedeliveryShippedOnceIT.java
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java
        - docs/camel-kit/004-spec-tests/design-spec.md
    provides:
      testData:
        - split-items
    consumes:
      testData:
        - red-harness
    dependsOn:
      - 32
  - id: 34
    title: SpecMatrix and TC-004 — C-alert Marker
    files:
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java
        - src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
      reads:
        - docs/test_cases/TC-004-set-aside-order-event-reaches-warehouse.md
    provides:
      routeContracts:
        - spec-matrix-c-alert
    consumes:
      testData:
        - split-items
    dependsOn:
      - 33
  - id: 35
    title: README — Finding and Moving Stuck Orders; Build and Kill Test
    files:
      modifies:
        - README.md
        - docs/camel-kit/005-hardening/evidence/red-green-runs.md
    provides:
      routeContracts:
        - readme-end-state-queries
    consumes:
      testData:
        - red-T13
      schemas:
        - order_receipt.waiting_reason
        - pick_list.last_failure
        - notification.last_failure
    dependsOn:
      - 34
  - id: 36
    title: Spec Documents — Open Questions and Entity Model
    files:
      modifies:
        - docs/open_questions.md
        - docs/entity_model.md
    provides:
      routeContracts:
        - spec-docs-005
    consumes:
      schemas:
        - order_receipt.waiting_reason
        - order_receipt.last_check_at
        - pick_list.last_failure
        - notification.last_failure
        - notification.last_attempt_at
    dependsOn:
      - 35
  - id: 37
    title: Proposed Test Cases TC-006 and TC-007
    files:
      creates:
        - docs/test_cases/TC-006-pick-list-retried-after-moved-to-processed.md
        - docs/test_cases/TC-007-partner-order-rejected-with-two-reasons.md
      reads:
        - docs/test_cases/TC-005-pick-list-once-after-restart.md
        - docs/test_cases/TC-001-partner-order-to-warehouse.md
        - src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java
    provides:
      testData:
        - proposed-tc-006-007
    consumes: {}
    dependsOn:
      - 36
  - id: 38
    title: Build Times After
    files:
      modifies:
        - docs/camel-kit/005-hardening/evidence/build-times.md
    provides:
      testData:
        - build-times-after
    consumes:
      testData:
        - build-times-before
    dependsOn:
      - 37
```

---

### Task 1: Baseline Build Times (before)

**Agent:** implementation-engineer

**Files:**
- Create: `docs/camel-kit/005-hardening/evidence/build-times.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §4 Performance ("Build time is measured, not estimated", decision 5); §7 "Required in the execution report"

- [ ] **Step 1:** Confirm the working tree equals the design's starting code: `git status --porcelain -- src pom.xml` prints nothing. No file of this plan has been changed yet.
- [ ] **Step 2:** Run `/usr/bin/time -p ./mvnw -B verify -DskipITs` and then `/usr/bin/time -p ./mvnw -B verify` (Docker running). Record exit codes and `real` seconds. From `target/failsafe-reports/TEST-*.xml` record each IT's `time` attribute.
- [ ] **Step 3:** Write `build-times.md` with a "Before (commit `9db4985` code)" table: command, exit code, seconds; then the per-IT times. Leave an empty "After" heading for Task 38. If either build fails, record the failure output summary; do not fix anything here.
- [ ] **Step 4:** Verify: `grep -q 'verify -DskipITs' docs/camel-kit/005-hardening/evidence/build-times.md && grep -q 'Before' docs/camel-kit/005-hardening/evidence/build-times.md && grep -q 'After' docs/camel-kit/005-hardening/evidence/build-times.md && echo BASELINE-OK` prints `BASELINE-OK`.

**Review:**
- [ ] Spec compliance: both builds measured on unchanged code; per-IT times present.
- [ ] Code quality: numbers taken from the actual runs, not estimated; no source file changed.

---

### Task 2: FaultHook and the Fault-Hook Jar

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/fault/FaultHook.java`
- Modify: `pom.xml` (one `maven-jar-plugin` execution)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/maven-dependencies.md`
- `camel-implement/guides/pom-spring-boot.md`
- `shared/camel-security-checklist.md` — test-only code never in the production jar

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 "Fault hook (test-only)"; §4 Security; P-8

- [ ] **Step 1:** `FaultHook`: `@Configuration @Profile("fault-injection")` declaring one `InterceptStrategy` bean that also implements `org.apache.camel.Ordered` returning `Ordered.LOWEST` (P-8). It reads `fault.points` = comma list of `<nodeId>:<error|oom|halt>:<times>` (reject malformed entries at startup with an exception naming the entry). It wraps only processors whose `NamedNode.getId()` is a listed node; before the node runs, while that point's remaining count is > 0 (atomic decrement): `error` → `throw new NoSuchMethodError("fault-injection")`, `oom` → `throw new OutOfMemoryError("fault-injection")`, `halt` → `Runtime.getRuntime().halt(137)`. Other processors are returned unchanged. The strategy is a public static nested class `FaultHook.Strategy(String points)` so a unit test (T3) can construct it without Spring. Wrap as an `AsyncProcessor` that throws synchronously before delegating `process(exchange, callback)`, so async EIPs keep their behaviour. Log once at INFO `Fault hook active points=<value>`.
- [ ] **Step 2:** `pom.xml`: add a `maven-jar-plugin` execution `fault-hook-jar`, phase `package`, goal `test-jar`, `classifier` `fault-hook`, `includes` only `com/trailheadgear/orderintegration/fault/**`. The result is `target/order-integration-0.1.0-fault-hook.jar`. Do not change the Spring Boot repackage.
- [ ] **Step 3:** Verify: `./mvnw -q -DskipTests package && unzip -l target/order-integration-0.1.0-fault-hook.jar | grep -q 'fault/FaultHook.class' && ! unzip -l target/order-integration-0.1.0.jar | grep -q 'FaultHook' && [ "$(unzip -l target/order-integration-0.1.0-fault-hook.jar | grep -c '\.class')" -ge 1 ] && echo FAULT-HOOK-JAR-OK` prints `FAULT-HOOK-JAR-OK`.

**Review:**
- [ ] Spec compliance: three fault kinds with counts; profile-gated; separate jar holding only the `fault` package; production jar without the hook.
- [ ] Code quality: synchronous throw before delegation; no other node touched; `Ordered.LOWEST`.

---

### Task 3: IntegrationProcess.withFaults and Failsafe for the Failure Tests

**Agent:** implementation-engineer

**Files:**
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java`
- Modify: `pom.xml` (surefire excludes, failsafe executions and system property)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/pom-spring-boot.md`
- `camel-test/guides/test-configuration.md` — Testcontainers/process setup
- `camel-test/guides/test-runner.md`
- `shared/camel-security-checklist.md` — no secret on the command line

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 (packaged jar, `IntegrationProcess.withFaults(points)`, `PropertiesLauncher`, `loader.path`, profiles `spec-test,fault-injection`, failsafe after the spec ITs); P-10, P-11

- [ ] **Step 1:** `IntegrationProcess.withFaults(String points)`: returns a process configured like the existing one, except the command is `<java> -cp <spec-test.jar> -Dloader.path=<spec-test.fault-hook-jar> org.springframework.boot.loader.launch.PropertiesLauncher`, the profiles are `spec-test,fault-injection`, and `fault.points=<points>` goes into the run properties file (not argv). The existing `start()`/`stop()`/`restart()` paths for the spec ITs stay byte-identical in behaviour.
- [ ] **Step 2:** Add `Optional<Integer> waitForExit(Duration)` (exit code of a process that ends by itself, e.g. a halt) and a public `lastStopGraceful()` accessor for the existing field; a halted process is never reported graceful.
- [ ] **Step 3:** `pom.xml`: surefire `excludes` add `**/failure/*IT.java`; failsafe plugin-level `systemPropertyVariables` add `<spec-test.fault-hook-jar>${project.build.directory}/${project.build.finalName}-fault-hook.jar</spec-test.fault-hook-jar>`; keep the existing execution for `**/spec/*IT.java` and add a second execution `failure-tests` (goal `integration-test`, includes `**/failure/*IT.java`, `summaryFile` `${project.build.directory}/failsafe-reports/failsafe-summary-failure.xml`); the `verify` goal reads both summary files (`summaryFiles`). The spec execution runs first (declaration order).
- [ ] **Step 4:** Confirm `SpecMatrix` reads only spec-test result files (it must ignore `failure` ITs); record the line that ensures it, or the finding.
- [ ] **Step 5:** Verify: `./mvnw -q -DskipITs test-compile && ./mvnw -q help:effective-pom -Doutput=target/effective-pom.xml && grep -q 'failure-tests' target/effective-pom.xml && grep -q 'failsafe-summary-failure.xml' target/effective-pom.xml && grep -q 'PropertiesLauncher' src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java && grep -q 'fault-injection' src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java && echo FAILSAFE-OK` prints `FAILSAFE-OK`. The P-11 per-IT command is proven on first use in Task 6.

**Review:**
- [ ] Spec compliance: same packaged jar, fault jar on `loader.path`, both profiles; failure ITs after the spec ITs; surefire never runs them.
- [ ] Code quality: no secret in argv/env; spec IT path unchanged; summary files not overwritten.

---

### Task 4: T1 JsonValidatorPinTest — Guard of the 2.0.1 Pin

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/JsonValidatorPinTest.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md` (create on first use with header: test, code state/fault, result, assertion, message, duration)

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="json-validator", B)` — URI syntax with `classpath:`

**Design Spec Section:** §3.9 T1; §3.1.6 X5; decision 2 of 004; P-4

- [ ] **Step 1:** Surefire unit test: a plain `DefaultCamelContext` with route `direct:validate` → `json-validator:classpath:schemas/order.schema.json`; send the text of `docs/messages/examples/order/from-peakmart-order.json` (path relative to the project base directory); assert no exception and the body is unchanged. Add a second case with `docs/messages/examples/order/invalid/no-lines.json` asserting `JsonValidationException` (proves the validator really runs).
- [ ] **Step 2:** Red (fault "pin removed"): delete exactly the `com.networknt:json-schema-validator` `2.0.1` `<dependency>` block in `dependencyManagement`; run `./mvnw -q -DskipITs test -Dtest=JsonValidatorPinTest`; expect failure with `NoSuchMethodError`; append the row to `red-green-runs.md`. Restore the block exactly.
- [ ] **Step 3:** Green: run the same command; append the pass row.
- [ ] **Step 4:** Verify: `grep -q '<artifactId>json-schema-validator</artifactId>' pom.xml && grep -A1 'json-schema-validator</artifactId>' pom.xml | grep -q '2.0.1' && ./mvnw -q -DskipITs test -Dtest=JsonValidatorPinTest && grep -q 'JsonValidatorPinTest' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T1-OK` prints `T1-OK`.

**Review:**
- [ ] Spec compliance: runs without Docker in `-DskipITs`; same classpath resolution as the jar; red recorded with the pin removed.
- [ ] Code quality: pin restored byte-identically; test independent of the network.

---

### Task 5: T4 WarehouseSftp RemoteFiles Seam and WarehouseSftpLookupTest

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", B)` — confirm the bean, not the endpoint, does the stat check

**Design Spec Section:** §3.9 T4; §3.1.4 D4; F5

- [ ] **Step 1:** Add a package-private interface `RemoteFiles` (nested in `WarehouseSftp`) with `boolean exists(String path) throws SftpException`. Production adapter: the current `exists(ChannelSftp, path)` logic (no-such-file → false, other failures thrown). `deliveredFolder(fileName)` validates the name, then delegates to a package-private `deliveredFolder(String fileName, RemoteFiles files)` holding the inbound-then-processed lookup; the connection/close-on-failure behaviour is unchanged.
- [ ] **Step 2:** `WarehouseSftpLookupTest` with a recording fake `RemoteFiles`: (a) in inbound → `INBOUND`, only the inbound path looked up; (b) in processed → `PROCESSED`, lookups exactly `[inbound/x, processed/x]` in that order; (c) in neither → null, same two lookups; (d) a failure from the fake is thrown; (e) an invalid file name → `IllegalArgumentException`, no lookup.
- [ ] **Step 3:** Red (F5): remove the processed lookup (the `if (exists(… processedDirectory …)) return PROCESSED;` statement); run `./mvnw -q -DskipITs test -Dtest=WarehouseSftpLookupTest`; expect (b) to fail; record. Restore it.
- [ ] **Step 4:** Green: run the test and the whole unit suite `./mvnw -q -DskipITs test`; record.
- [ ] **Step 5:** Verify: `grep -q 'processedDirectory' src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java && grep -q 'RemoteFiles' src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java && ./mvnw -q -DskipITs test && grep -q 'WarehouseSftpLookupTest' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T4-OK` prints `T4-OK`.

**Review:**
- [ ] Spec compliance: inbound before processed, never another order; no SFTP server needed; red with F5.
- [ ] Code quality: behaviour-preserving refactor; constructor and bean wiring unchanged; seam package-private.

---

### Task 6: T5 NotificationTextIT and the SqlStatements Test Helper

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/SqlStatements.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/NotificationTextIT.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md` — Testcontainers PostgreSQL
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)` — `:#name` parameter syntax the helper translates

**Design Spec Section:** §3.9 T5; F6; P-12

- [ ] **Step 1:** `SqlStatements` (P-12): starts one `PostgreSQLContainer` with the image from `spec-test.image.postgres` in classpath resource `spec-test/spec-test.properties`; runs Flyway with location `classpath:db/migration`; `run(String sqlFile, Map<String,Object> params)` reads `src/main/resources/sql/<file>` and executes it with `NamedParameterJdbcTemplate`, translating only `:#name` → `:name`; exposes a `JdbcTemplate` for fixtures and `run(String sqlText, …)` for README blocks (Task 16). No other rewriting of the statement.
- [ ] **Step 2:** `NotificationTextIT`: insert a received Peakmart receipt and a received Partner receipt (fixture columns read from V1/V2), run `order-reject.sql` for each with three Rejection Reasons in check order (use the parameters `order-reject.sql` declares, notification type and recipient as the routes set them); then `notification-select.sql` and `rejection-email-select.sql` must list the three `- <reason>` lines in check order.
- [ ] **Step 3:** First use of P-11: `./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=NotificationTextIT`. Record that the command works (or the corrected command, used from then on, in `red-green-runs.md`).
- [ ] **Step 4:** Red (F6): change `ORDER BY rr.position` to `ORDER BY rr.position DESC` in both `notification-select.sql` and `rejection-email-select.sql`; run; expect failure; record. Restore both.
- [ ] **Step 5:** Green: run; record.
- [ ] **Step 6:** Verify: `! grep -q 'rr.position DESC' src/main/resources/sql/notification-select.sql src/main/resources/sql/rejection-email-select.sql && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=NotificationTextIT && grep -q 'NotificationTextIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T5-OK` prints `T5-OK`.

**Review:**
- [ ] Spec compliance: PostgreSQL only; production statements executed verbatim; three reasons in check order; red with F6.
- [ ] Code quality: pinned image digest; container stopped after the class; no fixture beyond what the statements need.

---

### Task 7: T8 KillIT — Kill Points in ./mvnw verify

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java`
- Delete: `src/test/java/com/trailheadgear/orderintegration/KillTestHook.java`, `src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java`, `src/test/scripts/kill-test.sh`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", B)` — `allowManualCommit`, `breakOnFirstError`
- `camel_catalog_component_doc(component="sftp", B)` — `fileExist`, `tempFileName`

**Design Spec Section:** §3.9 T8; §3.1 P8, P9, I8, C6, D4, D7, M4, X1; evidence K1–K6; P-13

- [ ] **Step 1:** Each kill point: start with `IntegrationProcess.withFaults("<node>:halt:1")` (shared `SpecEnvironment`, `Peakmart`, `Partner`, `ProductCatalogStub`, `Mailboxes`, `WarehouseInbox`, `OrderDatabase`), trigger the flow, `waitForExit` → exit code 137, then a normal `start()` and assert:
  - K1a `commit-kafka-offset`: receipt written, offset not committed; after restart `Duplicate Peakmart order skipped` logged, the order recorded once, one pick list.
  - K1b `to-receipt-insert` (P-13): nothing written; after restart the order recorded once.
  - K2 `to-pick-list-delivered`: file uploaded; the test moves it to `processed/` (as the Warehouse does); after restart Delivered and exactly one file.
  - K3 `log-order-desk-notified`: after restart the notification row is marked sent and at least one email arrived (OQ-081: a second one is accepted).
  - K4 `log-partner-order-recorded`: partner connection ends; resend → 200 accepted from the receipt; one Accepted Order.
  - K5 `to-partner-order-check`: resend within 30 s → 503 contract answer; after the 30 s expiry → 200, recorded once.
  - K6 `to-product-lookup`: after restart checked again, recorded once, one pick list.
- [ ] **Step 2:** Every kill point uses its own order numbers and cleans up with `OrderDatabase.cleanup` in `finally`; the process is always stopped.
- [ ] **Step 3:** Delete `KillTestHook.java`, `KillTestApplication.java` and `src/test/scripts/kill-test.sh` (the README lines go in Task 35).
- [ ] **Step 4:** Red (F7, then F5, each separately): F7 = move the `commit-kafka-offset` step of `peakmart-order-intake` so it runs before `to-peakmart-event-read` (the exact change developer-checks 004 names); run `KillIT`; expect K1b to fail (lost order); record; undo. F5 = remove the processed lookup in `WarehouseSftp.deliveredFolder`; expect K2 to fail (second file); record; undo.
- [ ] **Step 5:** Green: `./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=KillIT`; record the pass and duration.
- [ ] **Step 6:** Verify: `test ! -e src/test/scripts/kill-test.sh && test ! -e src/test/java/com/trailheadgear/orderintegration/KillTestHook.java && test ! -e src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java && ! grep -rq 'KillTest' src && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=KillIT && grep -q 'KillIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T8-OK` prints `T8-OK`.

**Review:**
- [ ] Spec compliance: K1–K6 as §3.9 states, in `./mvnw verify`; script, hook and test main gone; red with F7 and F5.
- [ ] Code quality: no sleep-timed kill (the halt is exact); faults undone; cleanup always runs.

---

### Task 8: T2 FailureKindsTest (red) with FailureKinds Signatures

**Agent:** test-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/FailureKinds.java` (signatures only, P-5)
- Create: `src/main/java/com/trailheadgear/orderintegration/UnexpectedErrorException.java` (complete: `RuntimeException` holding the `Error` as cause; message `Unexpected error <Error class name>`, no Error message)
- Create: `src/test/java/com/trailheadgear/orderintegration/FailureKindsTest.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="json-validator", B)` — `JsonValidationException` class
- `camel_catalog_component_doc(component="smtp", B)` — the mail exception carrying the reply code

**Design Spec Section:** §3.5 H4 table and `<kind>`; §3.6 H5 C3; §3.7 H6 examples; §4 Security; P-5, P-7

- [ ] **Step 1:** `FailureKinds` public API (bodies throw `UnsupportedOperationException("design 005: not implemented")`): `boolean databaseUnavailable(Throwable)`, `boolean kafka(Throwable)`, `boolean waits(Throwable)` (= database unavailable or Kafka), `boolean cannotBeRecorded(Throwable)`, `String describe(Throwable)`.
- [ ] **Step 2:** `FailureKindsTest`, a table test over the cause chain (wrapped at least once, e.g. in `CamelExecutionException`/`UncategorizedSQLException`):
  - `databaseUnavailable` true for `SQLTransientConnectionException`, `SQLRecoverableException`, `CannotGetJdbcConnectionException`, Spring `QueryTimeoutException`, `SocketTimeoutException`, an `SQLException` with SQLState `08006`, `53300`, `57P01`; false for `DataIntegrityViolationException`/SQLState `23505`, `22001`.
  - `kafka` true for any `org.apache.kafka.common.KafkaException` in the chain.
  - `cannotBeRecorded` true for a `NonTransientDataAccessException` that is not database-unavailable (e.g. `DataIntegrityViolationException` with `22001`) and for `JsonValidationException`; false for every database-unavailable case.
  - `waits` false for `UnexpectedErrorException` and for `IllegalStateException`.
  - `describe`: root cause simple class name, plus SQLState (`PSQLException 22001`) or SMTP reply code (`SMTPAddressFailedException 550`); for an `UnexpectedErrorException` chain `unexpected error: NoSuchMethodError`; never contains the exception message (assert a message with an email address and a name does not appear); length ≤ 450 (P-7) for a pathological class chain.
- [ ] **Step 3:** Red: `./mvnw -q -DskipITs test -Dtest=FailureKindsTest`; expect failures with `design 005: not implemented`; record.
- [ ] **Step 4:** Verify: `grep -q 'not implemented' src/main/java/com/trailheadgear/orderintegration/FailureKinds.java && ./mvnw -q -DskipITs test-compile && ! ./mvnw -q -DskipITs test -Dtest=FailureKindsTest && grep -q 'FailureKindsTest' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T2-RED-OK` prints `T2-RED-OK`.

**Review:**
- [ ] Spec compliance: every H4/H5 classification and the describe rules are asserted; red recorded.
- [ ] Code quality: tests use real exception classes from the dependencies; no message text checked as allowed output.

---

### Task 9: T3 ErrorContainmentTest (red) with ErrorContainment Signatures

**Agent:** test-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/ErrorContainment.java` (signatures only, P-5)
- Read: `src/main/java/com/trailheadgear/orderintegration/UnexpectedErrorException.java` (created in Task 8)
- Create: `src/test/java/com/trailheadgear/orderintegration/ErrorContainmentTest.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_eip_doc(eip="onException", B)`
- `camel_catalog_eip_doc(eip="split", B)` — async EIP the wrapper must not change

**Design Spec Section:** §3.2 H1; §3.9 T3 and fault hook; decision 1; A-5; P-5, P-8

- [ ] **Step 1:** `ErrorContainment` signatures: `public ErrorContainment(java.util.function.IntConsumer halt)`, `implements InterceptStrategy, Ordered`; `wrapProcessorInInterceptors(...)` and `getOrder()` throw `UnsupportedOperationException("design 005: not implemented")`.
- [ ] **Step 2:** `ErrorContainmentTest` (`camel-test-spring-junit6` or plain `CamelContext`, no Docker), the strategy registered with a recording halt:
  - a route with `onException(java.lang.Exception)` (handled, to `mock:handled`) whose processor step throws `NoSuchMethodError` → `mock:handled` receives it with `UnexpectedErrorException` (cause `NoSuchMethodError`), the exchange completes within 5 s, in-flight 0, exactly one ERROR line `Unexpected error routeId=… nodeId=… error=java.lang.NoSuchMethodError orderNumber=… correlationId=…` (no Error message); the same with `AssertionError`;
  - the same inside a `split` with `parallelProcessing` (async EIP unchanged);
  - steps throwing `StackOverflowError` and `OutOfMemoryError` → the halt is called exactly once with `70`, one `Fatal error routeId=… nodeId=… error=<class> — the process stops` line, `mock:handled` receives nothing, the callback is not completed;
  - two-strategy case (P-8): `FaultHook`'s strategy (constructed with `fault.points=<node>:error:1`) and `ErrorContainment` both registered → the fault reaches `mock:handled` as `UnexpectedErrorException`; with `<node>:oom:1` → halt(70).
- [ ] **Step 3:** Red: `./mvnw -q -DskipITs test -Dtest=ErrorContainmentTest`; expect failures (`design 005: not implemented`); record.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test-compile && ! ./mvnw -q -DskipITs test -Dtest=ErrorContainmentTest && grep -q 'ErrorContainmentTest' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T3-RED-OK` prints `T3-RED-OK`.

**Review:**
- [ ] Spec compliance: contained cases, fatal cases (70, one line, no handler), log content, interceptor order; red recorded.
- [ ] Code quality: no real `halt`; log assertions on class names only; deterministic waits.

---

### Task 10: T6 WaitingSelectIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/WaitingSelectIT.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)` — consumer `maxMessagesPerPoll`

**Design Spec Section:** §3.9 T6; §3.6 H5 "Order of the poll" (C4)

- [ ] **Step 1:** With `SqlStatements`: insert receipts waiting for Check — A (oldest, `last_check_at` = 10 min ago), B (newer, never checked), C (newest, never checked), D (`last_check_at` = 1 min ago), E (`waiting_for = 'Recording'`, `waiting_reason` set). Run `waiting-select.sql`: expect B, C, A, D (never-checked first by `received_at, id`, then by `last_check_at`), never E.
- [ ] **Step 2:** Red: run with P-11 `-Dit.test=WaitingSelectIT`; expect failure (no `last_check_at` column before V3, or oldest-first order); record assertion and message.
- [ ] **Step 3:** Verify: `./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=WaitingSelectIT && grep -q 'WaitingSelectIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T6-RED-OK` prints `T6-RED-OK`.

**Review:**
- [ ] Spec compliance: order rule and `Recording` exclusion asserted; red recorded.
- [ ] Code quality: fixture times relative to `now()`; class cleans its rows.

---

### Task 11: T12 ConnectionSettingsIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/ConnectionSettingsIT.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.3 H3 and "Flyway's connection" (decision 3); §3.9 T12; P-9

- [ ] **Step 1:** Failsafe IT: a Spring `ApplicationContextRunner` with the Spring Boot 4.1.1 DataSource, Hikari and Flyway auto-configurations, the shared `application.properties` loaded from the classpath, and `spring.datasource.url/username/password` pointing at a pinned PostgreSQL container (image `spec-test.image.postgres`). A Flyway `Callback` bean records `context.getConnection().getNetworkTimeout()` in `beforeMigrate`.
- [ ] **Step 2:** Assert: a pool connection has `getNetworkTimeout()` = 5000 ms; `HikariDataSource.getConnectionTimeout()` = 2000; the Flyway connection's network timeout = 0. Record the mechanism found (P-9) for the execution report.
- [ ] **Step 3:** Red: P-11 `-Dit.test=ConnectionSettingsIT`; expect failure (pool network timeout 0, connection timeout 30000); record.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=ConnectionSettingsIT && grep -q 'ConnectionSettingsIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T12-RED-OK` prints `T12-RED-OK`.

**Review:**
- [ ] Spec compliance: both data paths measured, three values asserted; red recorded.
- [ ] Code quality: no Camel/Kafka start needed; pinned image.

---

### Task 12: T7 UnexpectedErrorIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/UnexpectedErrorIT.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)` — answer path of the partner endpoint
- `camel_catalog_component_doc(component="kafka", B)` — consumer resumes after a handled exchange

**Design Spec Section:** §3.9 T7 (including the fatal case, decision 1); §3.1 P7, I6, I7, C5, D6, M3, X2, X6; §3.8

- [ ] **Step 1:** Contained cases, one process with `withFaults("to-validate-product:error:1,to-validate-partner-completed-order:error:1,to-validate-order-placed:error:1000,to-validate-order:error:2,marshal-pick-list-csv:error:2,to-order-desk-mailbox:error:2")` (or one process per case if the counts interfere; record the choice):
  - two partner orders (one per partner fault) → each answered with the 503 contract answer (`temporarily-unavailable` body valid against `order-answer.schema.json`) in < 10 s;
  - a Peakmart event faulting at `to-validate-order-placed` on every attempt → a `set_aside_order_event` row with `cannot_read_reason` starting `cannot be taken in: ` and containing `unexpected error: NoSuchMethodError`; the next event published after it is recorded;
  - a Peakmart order faulting twice at `to-validate-order` → `waiting_reason = 'unexpected error: NoSuchMethodError'` observed while waiting, then recorded once;
  - its pick list faulting twice at `marshal-pick-list-csv` → `pick_list.last_failure` observed set, then Delivered once; its notification faulting twice at `to-order-desk-mailbox` → `notification.last_failure` set, then sent once;
  - the log has `Unexpected error routeId=` lines; a SIGTERM stop is graceful (`lastStopGraceful()` true).
- [ ] **Step 2:** Fatal case: a second process with `withFaults("to-validate-order-placed:oom:1")`, publish one event → the process exits with a non-zero code within 5 s and the log has `Fatal error`; then a normal `start()` → the event is recorded once.
- [ ] **Step 3:** Red: P-11 `-Dit.test=UnexpectedErrorIT`; expect failure (no answer within 10 s / topic stopped / process does not exit); record each failing assertion.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=UnexpectedErrorIT && grep -q 'UnexpectedErrorIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T7-RED-OK` prints `T7-RED-OK`.

**Review:**
- [ ] Spec compliance: every T7 case of §3.9 incl. fatal; end states and log lines as §3.8.
- [ ] Code quality: bounded waits; per-case order numbers; processes always stopped; cleanup in `finally`.

---

### Task 13: T9 DatabaseFrozenIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/DatabaseFrozenIT.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java` (accessors to pause/unpause the PostgreSQL container and to `execInContainer` on the SFTP container, through the Testcontainers Docker client)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)` — consumer behaviour after `Failed polling endpoint`

**Design Spec Section:** §3.9 T9; §3.1 P3, I3; §3.3; §3.4

- [ ] **Step 1:** Add to `SpecEnvironment`: `pausePostgres()`, `unpausePostgres()` (Testcontainers `DockerClient` pause/unpause on the container id) and `execInSftp(String... command)`. No other change.
- [ ] **Step 2:** IT: normal start; pause PostgreSQL; a partner order → 503 contract answer in ≤ 10 s (measure); publish a Peakmart event during the freeze; unpause (always, in `finally`); the event is recorded; a new partner order is accepted and its pick list delivered (every poller resumed).
- [ ] **Step 3:** Red: P-11 `-Dit.test=DatabaseFrozenIT`; expect ~31 s and a non-contract body; record.
- [ ] **Step 4:** Verify: `grep -q 'pausePostgres' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && ./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=DatabaseFrozenIT && grep -q 'DatabaseFrozenIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T9-RED-OK` prints `T9-RED-OK`.

**Review:**
- [ ] Spec compliance: ≤ 10 s and contract body; event after unpause recorded; pollers resume.
- [ ] Code quality: unpause guaranteed; no `docker` CLI; spec ITs unaffected by the new accessors.

---

### Task 14: T10 UnstorableOrderIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/UnstorableOrderIT.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 T10; §3.1 P4, C3; §3.4; §3.6

- [ ] **Step 1:** Peakmart: an event built from `peakmart-order-placed-sample.json` with a 150-character recipient name (passes every check) → within the poll window its receipt has `waiting_for = 'Recording'`, `waiting_reason` starting `cannot be recorded: `, and the log has an ERROR with `kind=unstorable` and its order number; an event published after it is recorded.
- [ ] **Step 2:** Partner: `partner-order-sample.json` with the same 150-character name → 503 contract answer; ERROR `kind=unstorable` with its order number; nothing recorded.
- [ ] **Step 3:** Red: P-11 `-Dit.test=UnstorableOrderIT`; expect failure (`waiting_for = 'Check'`, WARN only); record.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=UnstorableOrderIT && grep -q 'UnstorableOrderIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T10-RED-OK` prints `T10-RED-OK`.

**Review:**
- [ ] Spec compliance: park, no re-poll, no rejection, newer order moves; partner 503 + ERROR.
- [ ] Code quality: name generated, no personal data in assertions' output; cleanup.

---

### Task 15: T11 DeliveryFailureIT and RejectingSmtpServer (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/DeliveryFailureIT.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/RejectingSmtpServer.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java` (`withProperty(String key, String value)` written into the run file, used to point `smtp.host`/`smtp.port` at the rejecting server)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="smtp", B)` — `connectionTimeout`
- `camel_catalog_component_doc(component="sftp", B)` — failure of a write to a read-only folder

**Design Spec Section:** §3.9 T11; §3.1 D1, D2, M1, M2; §3.7 H6

- [ ] **Step 1:** `RejectingSmtpServer`: a minimal in-JVM SMTP server on 127.0.0.1 (free port) with a switch: `reject550()` answers `550` at `RCPT TO`; `accept()` accepts and stores messages (count observable). No real mail is sent.
- [ ] **Step 2:** IT, pick list: `execInSftp("chmod", "a-w", <inbound picklists dir>)`; a partner order that is accepted → `pick_list` Pending, `attempts` > 0, `last_failure` set (no file name or path text beyond the kind); restore write rights (in `finally`) → Delivered once, one file.
- [ ] **Step 3:** IT, mail: process started `withProperty("smtp.port", …)`; a Peakmart order rejected (Order Desk Notification) with the server in `reject550()` → `notification.last_failure` contains `550`, `last_attempt_at` set, `sent_at` NULL; `accept()` → sent once (one message).
- [ ] **Step 4:** Red: P-11 `-Dit.test=DeliveryFailureIT`; expect failure (columns do not exist); record.
- [ ] **Step 5:** Verify: `./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=DeliveryFailureIT && grep -q 'DeliveryFailureIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T11-RED-OK` prints `T11-RED-OK`.

**Review:**
- [ ] Spec compliance: both outboxes: reason recorded while failing, delivered/sent once after recovery.
- [ ] Code quality: server binds 127.0.0.1 only; rights restored in `finally`; no third-party SMTP library added to the production jar.

---

### Task 16: T13 EndStateQueriesIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/EndStateQueriesIT.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.8 "Getting a parked order out" and the paragraph on `-- end-state:` blocks; §3.9 T13; decision 4

- [ ] **Step 1:** Parse `README.md` (project base directory): every fenced `sql` block whose first line is `-- end-state: <name>` inside the section `Finding and moving stuck orders`. Required names (the README task uses exactly these): `set-aside`, `waiting-for-check`, `waiting-for-recording`, `release-recording`, `pick-list-not-delivered`, `notification-not-sent`, `partner-unstorable`. Fail when the section or any name is missing.
- [ ] **Step 2:** With `SqlStatements`, insert one row in each end state plus one healthy row of each table; run each query block: it returns its row and no other. Run `release-recording` with `:id` of the `Recording` order: afterwards `waiting_for = 'Check'`, `waiting_reason` NULL, `last_check_at` NULL, and `waiting-select.sql` returns it first; run it for an order in `Check` and for a `Recorded` order: 0 rows changed.
- [ ] **Step 3:** Red: P-11 `-Dit.test=EndStateQueriesIT`; expect failure (section not found); record.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipTests package && ! ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=EndStateQueriesIT && grep -q 'EndStateQueriesIT' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo T13-RED-OK` prints `T13-RED-OK`.

**Review:**
- [ ] Spec compliance: every §3.8 query and the release statement executed from the README text.
- [ ] Code quality: parser strict about the block marker; no statement copied into the test.

---

### Task 17: Harness Tests of §3.10 (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironmentTopicTest.java`
- Create: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRunCleanupMessageTest.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/SpecTraceabilityTest.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java`, `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java`, `src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java` — signatures only (P-5): `SpecEnvironment.createTopic(Admin admin, String topic, Duration startupTimeout)` (package-private, testable with a stub `Admin`), `SpecRun.cleanupMessage(String partnerCode, boolean registeredAtSetup)` (static), `SpecMatrix.covers(String tcId, String required, String itemId)` overload reading split items
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.10 (rows 1–3; row 4 is proven by the matrix in Task 34)

- [ ] **Step 1:** `SpecEnvironmentTopicTest`: a stub `Admin` whose `describeCluster` fails once then answers, and whose `createTopics` fails twice then succeeds → `createTopic` succeeds after 3 tries; a stub answering `TopicExistsException` → success; a stub failing three times → an exception naming the topic.
- [ ] **Step 2:** `SpecRunCleanupMessageTest`: `cleanupMessage("summit-outfitters", false)` = `Partner summit-outfitters was not registered at the start (see C-setup)`; `true` = `Partner summit-outfitters was touched by the cleanup`.
- [ ] **Step 3:** `SpecTraceabilityTest`: a new case: an `ITEMS` id `V3c` for TC-002 (not in `split-items.properties`) is a violation; `V3a` is accepted.
- [ ] **Step 4:** Red: `./mvnw -q -DskipITs test -Dtest='SpecEnvironmentTopicTest,SpecRunCleanupMessageTest,SpecTraceabilityTest'`; expect failures; record each.
- [ ] **Step 5:** Verify: `./mvnw -q -DskipITs test-compile && ! ./mvnw -q -DskipITs test -Dtest='SpecEnvironmentTopicTest,SpecRunCleanupMessageTest' && grep -q 'SpecRunCleanupMessageTest' docs/camel-kit/005-hardening/evidence/red-green-runs.md && echo HARNESS-RED-OK` prints `HARNESS-RED-OK`.

**Review:**
- [ ] Spec compliance: each §3.10 item has a failing test before its change.
- [ ] Code quality: stubs, no Docker in these unit tests.

---

### Task 18: FailureKinds — Implementation and Bean

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/java/com/trailheadgear/orderintegration/FailureKinds.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java` (`@Bean("failureKinds")`)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `shared/camel-security-checklist.md` — no personal data in logs/columns

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", B)`, `camel_catalog_component_doc(component="sql", B)`, `camel_catalog_component_doc(component="smtp", B)` — exception types each can raise

**Design Spec Section:** §3.5 H4 (matches, `<kind>`); §3.6 C3; §3.7; §4 Security; P-7

- [ ] **Step 1:** Implement the five methods by walking the cause chain (cycle-safe): database-unavailable classes and SQLState classes `08`, `53`, `57` per §3.5; Kafka = any `org.apache.kafka.common.KafkaException`; `cannotBeRecorded` = non-transient data access that is not database-unavailable, or `JsonValidationException`; `describe` = `unexpected error: <Error simple name>` when an `UnexpectedErrorException` is in the chain, else the root cause's simple class name plus SQLState or SMTP reply code (read from the exception's getter, never from its message), truncated to 450.
- [ ] **Step 2:** Register `failureKinds` in `IntegrationBeans` with a javadoc naming design 005 H4–H6.
- [ ] **Step 3:** Green: `./mvnw -q -DskipITs test -Dtest=FailureKindsTest`; record.
- [ ] **Step 4:** Verify: `! grep -q 'not implemented' src/main/java/com/trailheadgear/orderintegration/FailureKinds.java && grep -q '"failureKinds"' src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java && ./mvnw -q -DskipITs test && echo T2-GREEN-OK` prints `T2-GREEN-OK`.

**Review:**
- [ ] Spec compliance: classification table exactly §3.5/§3.6; describe without message text, ≤ 450.
- [ ] Code quality: no `getMessage()` call in the class; null-safe; plain bean (constitution rule 3).

---

### Task 19: ErrorContainment — Implementation and Bean (H1)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/java/com/trailheadgear/orderintegration/ErrorContainment.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java` (`@Bean("errorContainment")` with `Runtime.getRuntime()::halt`)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/advanced-patterns.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_eip_doc(eip="split", B)`, `camel_catalog_eip_doc(eip="onException", B)`, `camel_catalog_eip_doc(eip="routeConfiguration", B)`

**Design Spec Section:** §3.2 H1 (What, Rationale, Constraints); decision 1; A-5; P-8

- [ ] **Step 1:** `wrapProcessorInInterceptors` returns an `AsyncProcessor` around `AsyncProcessorConverterHelper.convert(target)`: `process(exchange, callback)` delegates and catches only a synchronously thrown `Error`. `VirtualMachineError` → in `try` log ERROR `Fatal error routeId=<id> nodeId=<id> error=<class> — the process stops`, in `finally` `halt.accept(70)`; return without completing the callback and without running any handler. Any other `Error` → `exchange.setException(new UnexpectedErrorException(e))`, log ERROR `Unexpected error routeId=<id> nodeId=<id> error=<class> orderNumber=${order-number property} correlationId=${correlation-id property}`, `callback.done(true)`, return true. `getOrder()` = `Ordered.HIGHEST` (P-8).
- [ ] **Step 2:** Register the bean in `IntegrationBeans` (javadoc: design 005 H1, decision 1).
- [ ] **Step 3:** Green: `./mvnw -q -DskipITs test -Dtest=ErrorContainmentTest`; record; record the confirmed interceptor order (P-8).
- [ ] **Step 4:** Verify: `! grep -q 'not implemented' src/main/java/com/trailheadgear/orderintegration/ErrorContainment.java && grep -q '"errorContainment"' src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java && grep -q 'halt(70)\|accept(70)' src/main/java/com/trailheadgear/orderintegration/ErrorContainment.java && ./mvnw -q -DskipITs test && echo T3-GREEN-OK` prints `T3-GREEN-OK`.

**Review:**
- [ ] Spec compliance: contain vs. halt split exactly as decision 1; halt in `finally`; log lines with class only.
- [ ] Code quality: async behaviour untouched; no `Exception` caught; no message text logged.

---

### Task 20: Flyway V3 — Failure Reasons

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/db/migration/V3__failure_reasons.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.6 (order_receipt columns and check); §3.7 (pick_list, notification columns); §7 Entity model delta

- [ ] **Step 1:** `ALTER TABLE order_receipt ADD COLUMN waiting_reason varchar(500), ADD COLUMN last_check_at timestamptz, ADD CONSTRAINT order_receipt_waiting_reason_ck CHECK (waiting_reason IS NULL OR waiting_for IS NOT NULL)`; `ALTER TABLE pick_list ADD COLUMN last_failure varchar(500)`; `ALTER TABLE notification ADD COLUMN last_failure varchar(500), ADD COLUMN last_attempt_at timestamptz`. All nullable; header comment naming design 005.
- [ ] **Step 2:** Verify: `f=src/main/resources/db/migration/V3__failure_reasons.sql; for c in waiting_reason last_check_at last_failure last_attempt_at order_receipt_waiting_reason_ck; do grep -q $c $f || exit 1; done && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=NotificationTextIT && echo V3-OK` prints `V3-OK` (Flyway runs V1–V3 in `SqlStatements`).

**Review:**
- [ ] Spec compliance: five columns, types and the check as §3.6/§3.7.
- [ ] Code quality: additive only; V1/V2 untouched.

---

### Task 21: Check SQL — waiting-select, order-wait, order-park, order-record, order-reject

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/sql/order-wait.sql`, `src/main/resources/sql/order-park.sql`
- Modify: `src/main/resources/sql/waiting-select.sql`, `src/main/resources/sql/order-record.sql`, `src/main/resources/sql/order-reject.sql`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/component-loading.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)` — `:#name` parameters, `CamelSqlUpdateCount`

**Design Spec Section:** §3.6 H5 (all bullets)

- [ ] **Step 1:** `order-wait.sql`: `UPDATE order_receipt SET last_check_at = now(), waiting_reason = :#waiting-reason WHERE id = :#receipt-id AND order_state = 'Received' AND waiting_for = 'Check'` (parameter names as the route sets them; Task 27 uses exactly these).
- [ ] **Step 2:** `order-park.sql`: `UPDATE order_receipt SET waiting_for = 'Recording', waiting_reason = :#waiting-reason, last_check_at = now() WHERE id = :#receipt-id AND order_state = 'Received' AND waiting_for = 'Check'`.
- [ ] **Step 3:** `waiting-select.sql`: same columns, `WHERE waiting_for = 'Check' ORDER BY last_check_at NULLS FIRST, received_at, id`; update its comment (`Recording` is now set by `order-park.sql` and never selected).
- [ ] **Step 4:** `order-record.sql` and `order-reject.sql`: where they clear `waiting_for`, also set `waiting_reason = NULL, last_check_at = NULL`. Nothing else changes.
- [ ] **Step 5:** Green: P-11 `-Dit.test=WaitingSelectIT` and `-Dit.test=NotificationTextIT`; record.
- [ ] **Step 6:** Verify: `grep -q 'NULLS FIRST' src/main/resources/sql/waiting-select.sql && grep -q "waiting_for = 'Recording'" src/main/resources/sql/order-park.sql && grep -q 'last_check_at = now()' src/main/resources/sql/order-wait.sql && grep -q 'waiting_reason' src/main/resources/sql/order-record.sql && grep -q 'waiting_reason' src/main/resources/sql/order-reject.sql && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=WaitingSelectIT && echo CHECK-SQL-OK` prints `CHECK-SQL-OK`.

**Review:**
- [ ] Spec compliance: one guarded statement each; poll order; both clears.
- [ ] Code quality: no reason text built in SQL; V3 check never violated (reason only while waiting).

---

### Task 22: Outbox SQL — pick-list-failed, notification-failed, pick-list-delivered

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/resources/sql/pick-list-failed.sql`, `src/main/resources/sql/notification-failed.sql`
- Modify: `src/main/resources/sql/pick-list-delivered.sql`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/component-loading.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`

**Design Spec Section:** §3.7 H6

- [ ] **Step 1:** `pick-list-failed.sql`: `UPDATE pick_list SET attempts = LEAST(attempts + 1, 100000), last_attempt_at = now(), last_failure = :#last-failure WHERE id = :#id AND delivery_state = 'Pending'` (the counting the current `onConsumeFailed` does, plus the reason).
- [ ] **Step 2:** `notification-failed.sql`: `UPDATE notification SET attempts = LEAST(attempts + 1, 1000), last_attempt_at = now(), last_failure = :#last-failure WHERE id = :#notification_id AND sent_at IS NULL`.
- [ ] **Step 3:** `pick-list-delivered.sql`: also `last_failure = NULL` where it records Delivered; nothing else changes.
- [ ] **Step 4:** Verify: `grep -q 'LEAST(attempts + 1, 100000)' src/main/resources/sql/pick-list-failed.sql && grep -q 'LEAST(attempts + 1, 1000)' src/main/resources/sql/notification-failed.sql && grep -q 'last_failure = NULL' src/main/resources/sql/pick-list-delivered.sql && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=NotificationTextIT && echo OUTBOX-SQL-OK` prints `OUTBOX-SQL-OK`.

**Review:**
- [ ] Spec compliance: counted once, reason and time written, success clears.
- [ ] Code quality: limits match the V1/V2 checks; parameters named as the routes set them.

---

### Task 23: Application Properties — Database Time Limits and Flyway's Own Connection (H3)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/properties-generation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.3 H3 (properties block and decision 3); P-9

- [ ] **Step 1:** New section `# --- Design 005 H3: Order Database time limits ---` with the §3.3 comment and the three Hikari properties exactly (`connection-timeout=2000`, `data-source-properties.connectTimeout=2`, `data-source-properties.socketTimeout=5`), then the three `spring.flyway.*` properties of P-9 with a comment that Flyway gets its own connection without the socket timeout (decision 3).
- [ ] **Step 2:** Green: P-11 `-Dit.test=ConnectionSettingsIT`; record, including the mechanism. Run `./mvnw -q -DskipITs test -Dtest=RequiredVariablesCheckTest` (X4 unchanged).
- [ ] **Step 3:** Verify: `f=src/main/resources/application.properties; grep -q '^spring.datasource.hikari.connection-timeout=2000$' $f && grep -q '^spring.datasource.hikari.data-source-properties.socketTimeout=5$' $f && grep -q '^spring.datasource.hikari.data-source-properties.connectTimeout=2$' $f && grep -q '^spring.flyway.url=${spring.datasource.url}$' $f && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=ConnectionSettingsIT && echo H3-OK` prints `H3-OK`.

**Review:**
- [ ] Spec compliance: values exact; Flyway has its own connection; per-profile URLs untouched.
- [ ] Code quality: Spring `${…}` interpolation (not `{{…}}`) in properties; comments explain why.

---

### Task 24: PartnerTimeoutAnswer — Last-Resort Answer (H2)

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/PartnerTimeoutAnswer.java`
- Modify: `src/main/resources/application.properties`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table
- `camel-implement/guides/properties-generation.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)` — Spring MVC async engine under the partner endpoint

**Design Spec Section:** §3.4 "Last-resort answer"

- [ ] **Step 1:** `@RestControllerAdvice` `PartnerTimeoutAnswer` with an `@ExceptionHandler(AsyncRequestTimeoutException.class)` that, only for request path `/orders`, answers status 503 with `orderAnswers.contentType(503)` and `orderAnswers.body(503, null, null)` (the `temporarily-unavailable` Problem; order number omitted). Other paths keep Spring's default. Log WARN `Partner request answered by the time limit correlationId=-` (no body, no key).
- [ ] **Step 2:** `application.properties` (partner section): `# Design 005 H2: last-resort contract answer before NFR-002's 10 s` and `spring.mvc.async.request-timeout=9s`.
- [ ] **Step 3:** Verify: `grep -q '^spring.mvc.async.request-timeout=9s$' src/main/resources/application.properties && grep -q 'AsyncRequestTimeoutException' src/main/java/com/trailheadgear/orderintegration/PartnerTimeoutAnswer.java && grep -q 'orderAnswers\|OrderAnswers' src/main/java/com/trailheadgear/orderintegration/PartnerTimeoutAnswer.java && ./mvnw -q -DskipITs test && echo H2-LAST-RESORT-OK` prints `H2-LAST-RESORT-OK`. Its runtime effect is part of T9's green run (Task 25).

**Review:**
- [ ] Spec compliance: 9 s, `/orders` only, contract body valid against `order-answer.schema.json`.
- [ ] Code quality: bean in the scanned package; no hand-built JSON.

---

### Task 25: Route YAML — partner-order-api Error Clauses (H2)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/partner-order-api.camel.yaml` (routeConfiguration `partner-order-error-handling` only)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_eip_doc(eip="routeConfiguration", B)`, `camel_catalog_eip_doc(eip="onException", B)` (`exception`, `onWhen`, `handled`, `redeliveryPolicy`), `camel_catalog_eip_doc(eip="doTry", B)`, `camel_catalog_eip_doc(eip="log", B)`
- `camel_catalog_language_doc(language="simple", B)` — `${exception}`, `${exchangeProperty.CamelFailureEndpoint}`, bean calls
- `camel_validate_yaml_dsl(…)` and `camel_validate_route(…)` on the full file, binding `B`

**Design Spec Section:** §3.4 H2; §3.1.1 P3, P4, P7; §3.8

- [ ] **Step 1:** Before `on-partner-order-failure`, add `on-partner-order-unexpected-error`: `exception: com.trailheadgear.orderintegration.UnexpectedErrorException`, handled, `maximumRedeliveries: 0`, no exhausted body/history logging; the same steps as `on-partner-order-failure` (best-effort release `doTry`, 503 through `direct:partner-order-answer`) but log ERROR `Partner order temporarily unavailable after an unexpected error orderNumber=… correlationId=… kind=${bean:failureKinds?method=describe(${exception})}`.
- [ ] **Step 2:** Add `on-partner-order-unstorable`: `exception: org.springframework.dao.NonTransientDataAccessException` with `onWhen` = `failureKinds.cannotBeRecorded(${exception})` and the failure endpoint containing `order-record.sql`; same steps, log ERROR `Partner order cannot be recorded kind=unstorable orderNumber=… correlationId=… cause=<describe>`. Still 503, no rejection (OQ-083).
- [ ] **Step 3:** `on-partner-order-failure` stays as is (WARN). No route step changes.
- [ ] **Step 4:** Run the `route-validation.md` loop until it passes.
- [ ] **Step 5:** Green: P-11 `-Dit.test=DatabaseFrozenIT`; record (needs Tasks 19, 23, 24).
- [ ] **Step 6:** Verify: `f=src/main/resources/camel/partner-order-api.camel.yaml; grep -q 'id: on-partner-order-unexpected-error' $f && grep -q 'UnexpectedErrorException' $f && grep -q 'id: on-partner-order-unstorable' $f && grep -q 'kind=unstorable' $f && grep -q 'id: on-partner-order-failure' $f && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=DatabaseFrozenIT && echo PARTNER-H2-OK` prints `PARTNER-H2-OK`.

**Review:**
- [ ] Spec compliance: clauses before the generic one; release + 503 in each; ERROR for unexpected and unstorable; WARN otherwise.
- [ ] Code quality: constitution rules 1–8; class/kind only in logs; Kaoto-compatible YAML; validation passed.

---

### Task 26: Route YAML — peakmart-order-intake Error Clauses (H4)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/peakmart-order-intake.camel.yaml` (routeConfiguration `peakmart-intake-error-handling`)
- Modify: `src/main/resources/application.properties` (flow peakmart-order-intake section)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/properties-generation.md`
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", B)` — `breakOnFirstError`, `allowManualCommit`, `CamelKafkaManualCommit`
- `camel_catalog_component_doc(component="direct", B)`
- `camel_catalog_eip_doc(eip="onException", B)`, `camel_catalog_eip_doc(eip="routeConfiguration", B)`, `camel_catalog_eip_doc(eip="script", B)`
- `camel_catalog_language_doc(language="simple", B)`
- `camel_validate_yaml_dsl(…)` and `camel_validate_route(…)` on the full file, binding `B`

**Design Spec Section:** §3.5 H4 (table, `<kind>`, property); §3.1.2 I2–I7, I9

- [ ] **Step 1:** Replace `on-intake-database-failure` with `on-intake-waits`: `exception: java.lang.Exception`, `onWhen` `failureKinds.waits(${exception})`, handled false, redelivery unchanged (3, 1 s, exponential ×2, no body/history logging).
- [ ] **Step 2:** Add `on-intake-cannot-take-in` after it: `exception: java.lang.Exception` (so `UnexpectedErrorException` and every other failure match), handled true, `maximumRedeliveries: "{{peakmart.set-aside-redeliveries}}"`, `redeliveryDelay: "1000"`, no body/history logging; steps: set `cannot-read-reason` = `cannot be taken in: ${bean:failureKinds?method=describe(${exception})}`; when the exception is an `UnexpectedErrorException`, log ERROR `Order event cannot be taken in after an unexpected error kafkaPosition=… correlationId=…`; `to direct:peakmart-event-set-aside` (it logs the unchanged WARN `Order event set aside`); then commit the offset with `${header.CamelKafkaManualCommit.commit()}` (node `commit-kafka-offset-after-set-aside`). A failure of the set-aside itself is not handled (offset stays).
- [ ] **Step 3:** Execution check: the commit must happen exactly once per record whether the failure occurred in `peakmart-order-intake` or in a `direct:` sub-route, and no step after the failure point may run (no receipt for a set-aside event). Confirm with T7's intake case (the next event recorded, the set-aside event has no receipt) and record how it was ensured.
- [ ] **Step 4:** `application.properties` (flow peakmart-order-intake): the §3.5 comment and `peakmart.set-aside-redeliveries=2`.
- [ ] **Step 5:** Run the `route-validation.md` loop until it passes; run the spec ITs that touch intake: P-11 with `-Dit.test='Tc002*IT,Tc004*IT'`.
- [ ] **Step 6:** Verify: `f=src/main/resources/camel/peakmart-order-intake.camel.yaml; grep -q 'id: on-intake-waits' $f && grep -q 'id: on-intake-cannot-take-in' $f && grep -q 'cannot be taken in: ' $f && grep -q 'peakmart.set-aside-redeliveries' $f && ! grep -q 'on-intake-database-failure' $f && grep -q '^peakmart.set-aside-redeliveries=2$' src/main/resources/application.properties && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test='Tc002*IT,Tc004*IT' && echo INTAKE-H4-OK` prints `INTAKE-H4-OK`.

**Review:**
- [ ] Spec compliance: three outcomes of the §3.5 table; Kafka failures never set aside; reason format; property.
- [ ] Code quality: constitution rules; one commit; no message text; validation passed.

---

### Task 27: Route YAML — peakmart-order-check Error Clauses (H5)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/peakmart-order-check.camel.yaml`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `shared/camel-security-checklist.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`, `camel_catalog_component_doc(component="json-validator", B)`
- `camel_catalog_eip_doc(eip="onException", B)`, `camel_catalog_eip_doc(eip="doTry", B)`, `camel_catalog_eip_doc(eip="choice", B)`
- `camel_catalog_language_doc(language="simple", B)`
- `camel_validate_yaml_dsl(…)` and `camel_validate_route(…)` on the full file, binding `B`

**Design Spec Section:** §3.6 H5; §3.1.3 C1–C5

- [ ] **Step 1:** In `when-catalog-outage` (`peakmart-order-check-poll`), after the WARN: set `waiting-reason` = `catalog outage` and call `sql:classpath:sql/order-wait.sql` (node `to-order-wait-outage`).
- [ ] **Step 2:** `order-record`: the `catch-order-invalid` doCatch (`JsonValidationException`) now sets `waiting-reason` = `cannot be recorded: <describe>`, calls `order-park.sql` (node `to-order-park-invalid`) and logs ERROR `Peakmart order cannot be recorded kind=unstorable orderNumber=… correlationId=… cause=<describe>`. Add a second doCatch `catch-order-unstorable` for `org.springframework.dao.NonTransientDataAccessException` with `onWhen` `failureKinds.cannotBeRecorded(${exception})`: same park and ERROR.
- [ ] **Step 3:** `order-check-error-handling`: add `on-check-unexpected-error` for `UnexpectedErrorException`: handled, no redelivery; set `waiting-reason` = describe (`unexpected error: <kind>`), `order-wait.sql` (its own failure ignored in a `doTry`, then WARN), ERROR `Peakmart order stays waiting after an unexpected error orderNumber=… correlationId=…`. `on-check-database-failure` stays unchanged.
- [ ] **Step 4:** Run the `route-validation.md` loop until it passes; run P-11 `-Dit.test='Tc002*IT,Tc003*IT'`.
- [ ] **Step 5:** Green: P-11 `-Dit.test=UnstorableOrderIT`; record (needs Tasks 18, 20, 21, 25).
- [ ] **Step 6:** Verify: `f=src/main/resources/camel/peakmart-order-check.camel.yaml; grep -q 'order-wait.sql' $f && grep -q 'order-park.sql' $f && grep -q 'id: on-check-unexpected-error' $f && grep -q 'catalog outage' $f && grep -q 'kind=unstorable' $f && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=UnstorableOrderIT && echo CHECK-H5-OK` prints `CHECK-H5-OK`.

**Review:**
- [ ] Spec compliance: outage → wait with reason; never-fits → park; unexpected → wait with reason; database-down unchanged.
- [ ] Code quality: constitution rules; no rejection invented; validation passed.

---

### Task 28: Route YAML — pick-list-delivery Failure Reason (H6)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/pick-list-delivery.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)` — `onConsumeFailed` removal, consumer continues
- `camel_catalog_eip_doc(eip="onException", B)`
- `camel_validate_yaml_dsl(…)` and `camel_validate_route(…)` on the full file, binding `B`

**Design Spec Section:** §3.7 H6; §3.1.4 D1, D2, D6

- [ ] **Step 1:** `on-pick-list-delivery-failure` stays not handled with 0 redeliveries; its steps first set `last-failure` = describe, call `sql:classpath:sql/pick-list-failed.sql` (node `to-pick-list-failed`; its own failure ignored in a `doTry`), then the WARN `Pick list not delivered … cause=<describe>`.
- [ ] **Step 2:** Remove `onConsumeFailed` from `from-pick-list-outbox` (counted once, by the clause). `UnexpectedErrorException` reaches the same clause (`java.lang.Exception`).
- [ ] **Step 3:** Run the `route-validation.md` loop; P-11 `-Dit.test='Tc001*IT,Tc005*IT'`.
- [ ] **Step 4:** Verify: `f=src/main/resources/camel/pick-list-delivery.camel.yaml; grep -q 'pick-list-failed.sql' $f && ! grep -q 'onConsumeFailed' $f && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test='Tc001*IT,Tc005*IT' && echo PICKLIST-H6-OK` prints `PICKLIST-H6-OK`.

**Review:**
- [ ] Spec compliance: row stays Pending; reason + attempt once; success clears (Task 22).
- [ ] Code quality: validation passed; WARN names the kind only.

---

### Task 29: Route YAML — order-desk-notification Failure Reason (H6)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/order-desk-notification.camel.yaml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`, `camel_catalog_component_doc(component="smtp", B)`
- `camel_catalog_eip_doc(eip="onException", B)`
- `camel_validate_yaml_dsl(…)` and `camel_validate_route(…)` on the full file, binding `B`

**Design Spec Section:** §3.7 H6; §3.1.5 M1–M3

- [ ] **Step 1:** `on-order-desk-notification-failure` stays not handled, 0 redeliveries; steps: `last-failure` = describe, `sql:classpath:sql/notification-failed.sql` (node `to-notification-failed`, own failure ignored in a `doTry`), WARN `… not sent … cause=<describe>`.
- [ ] **Step 2:** Remove `onConsumeFailed`; `onConsume` also sets `last_failure = NULL` (success clears).
- [ ] **Step 3:** Run the `route-validation.md` loop; P-11 `-Dit.test='Tc002*IT,Tc003*IT'`.
- [ ] **Step 4:** Verify: `f=src/main/resources/camel/order-desk-notification.camel.yaml; grep -q 'notification-failed.sql' $f && ! grep -q 'onConsumeFailed' $f && grep -q 'last_failure = NULL' $f && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test='Tc002*IT,Tc003*IT' && echo NOTIFY-H6-OK` prints `NOTIFY-H6-OK`.

**Review:**
- [ ] Spec compliance: retry unchanged (no back-off); reason, attempt and time written once.
- [ ] Code quality: no address in logs or columns; validation passed.

---

### Task 30: Route YAML — partner-rejection-email Failure Reason (H6)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/partner-rejection-email.camel.yaml`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`, `camel_catalog_component_doc(component="smtp", B)`
- `camel_catalog_eip_doc(eip="onException", B)`
- `camel_validate_yaml_dsl(…)` and `camel_validate_route(…)` on the full file, binding `B`

**Design Spec Section:** §3.7 H6; §3.1.5 M1–M3

- [ ] **Step 1:** `on-partner-rejection-email-failure` stays not handled, 0 redeliveries; steps: `last-failure` = describe, `sql:classpath:sql/notification-failed.sql` (node `to-rejection-email-failed`, own failure ignored in a `doTry`), WARN `… not sent … cause=<describe>`.
- [ ] **Step 2:** Remove `onConsumeFailed`; `onConsume` also sets `last_failure = NULL`.
- [ ] **Step 3:** Run the `route-validation.md` loop.
- [ ] **Step 4:** Green: P-11 `-Dit.test=UnexpectedErrorIT` and `-Dit.test=DeliveryFailureIT`; record both (every route change is now in).
- [ ] **Step 5:** Verify: `f=src/main/resources/camel/partner-rejection-email.camel.yaml; grep -q 'notification-failed.sql' $f && ! grep -q 'onConsumeFailed' $f && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test='UnexpectedErrorIT,DeliveryFailureIT' && echo REJECTION-H6-OK` prints `REJECTION-H6-OK`.

**Review:**
- [ ] Spec compliance: same as Task 29 for the rejection email; T7 and T11 green.
- [ ] Code quality: validation passed; no address in logs.

---

### Task 31: SpecEnvironment.createTopic — Wait for the Broker, Retry

**Agent:** implementation-engineer

**Files:**
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.10 row 1

- [ ] **Step 1:** Implement `createTopic(Admin, topic, startupTimeout)`: `describeCluster().nodes()` until a node answers within `spec-test.startup-timeout-ms`; then up to 3 `createTopics` tries, each with its own timeout; `TopicExistsException` counts as created; after 3 failures throw naming the topic. The existing caller uses it.
- [ ] **Step 2:** Green: `./mvnw -q -DskipITs test -Dtest=SpecEnvironmentTopicTest`; record.
- [ ] **Step 3:** Verify: `./mvnw -q -DskipITs test -Dtest=SpecEnvironmentTopicTest && grep -q 'describeCluster' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && grep -q 'TopicExistsException' src/test/java/com/trailheadgear/orderintegration/spec/support/SpecEnvironment.java && echo TOPIC-OK` prints `TOPIC-OK`.

**Review:**
- [ ] Spec compliance: broker wait, 3 tries, exists = created.
- [ ] Code quality: bounded total time; no busy loop.

---

### Task 32: SpecRun — Cleanup Message Choice

**Agent:** implementation-engineer

**Files:**
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/SpecRun.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java`, `src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java` (P-cleanup message only)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.10 row 2

- [ ] **Step 1:** Implement `SpecRun.cleanupMessage`; record in the run whether the partner was registered at `C-setup`; the P-cleanup of TC-001 and TC-005 uses `cleanupMessage(PARTNER_CODE, registeredAtSetup)` instead of the literal.
- [ ] **Step 2:** Green: `./mvnw -q -DskipITs test -Dtest=SpecRunCleanupMessageTest`; record.
- [ ] **Step 3:** Verify: `./mvnw -q -DskipITs test -Dtest=SpecRunCleanupMessageTest && ! grep -q '" was touched by the cleanup"' src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java src/test/java/com/trailheadgear/orderintegration/spec/Tc005PickListOnceAfterRestartIT.java && grep -q 'cleanupMessage' src/test/java/com/trailheadgear/orderintegration/spec/Tc001PartnerOrderToWarehouseIT.java && echo CLEANUP-MSG-OK` prints `CLEANUP-MSG-OK`.

**Review:**
- [ ] Spec compliance: both messages exact; decision in a static method.
- [ ] Code quality: no other IT behaviour changed.

---

### Task 33: SpecMatrix — Named Split Items Only

**Agent:** implementation-engineer

**Files:**
- Create: `src/test/resources/spec-test/split-items.properties`
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.10 row 3; P-14

- [ ] **Step 1:** Re-read every `ITEMS` id with a split suffix in the five `Tc00*IT` classes and compare with design 004 §3; write `split-items.properties` with one line per test case (planning list P-14: `TC-002=V3a,V3b`, `TC-004=S3-alert`, `TC-005=S4-lookup`); a difference from P-14 or from design 004 is recorded as a finding.
- [ ] **Step 2:** `SpecMatrix.covers(tcId, required, itemId)`: a split child is accepted only when listed for that test case; any other suffix is a violation. `SpecTraceabilityTest` and the matrix execution use it.
- [ ] **Step 3:** Green: `./mvnw -q -DskipITs test -Dtest=SpecTraceabilityTest`; record.
- [ ] **Step 4:** Verify: `grep -q '^TC-002=V3a,V3b$' src/test/resources/spec-test/split-items.properties && grep -q '^TC-004=S3-alert$' src/test/resources/spec-test/split-items.properties && grep -q '^TC-005=S4-lookup$' src/test/resources/spec-test/split-items.properties && ./mvnw -q -DskipITs test -Dtest=SpecTraceabilityTest && echo SPLIT-OK` prints `SPLIT-OK`.

**Review:**
- [ ] Spec compliance: only named split ids accepted; list checked against design 004.
- [ ] Code quality: properties file read from the test classpath; clear violation message.

---

### Task 34: SpecMatrix and TC-004 — C-alert Marker

**Agent:** implementation-engineer

**Files:**
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java` (`MARKERS`)
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java` (`ITEMS`)
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.10 row 4

- [ ] **Step 1:** `MARKERS` gains `C-alert` for the TC-004 precondition "The Integration Team mailbox … holds no Alert" (exact wording from the TC-004 document).
- [ ] **Step 2:** Red: `./mvnw -q -DskipITs test -Dtest=SpecTraceabilityTest`; expect TC-004 `C-alert` reported missing; record.
- [ ] **Step 3:** `Tc004…IT.ITEMS` gains `notYetTestable("C-alert", <name>, UC_006)`.
- [ ] **Step 4:** Green: the same command; record.
- [ ] **Step 5:** Verify: `grep -q 'C-alert' src/test/java/com/trailheadgear/orderintegration/spec/SpecMatrix.java && grep -q 'notYetTestable("C-alert"' src/test/java/com/trailheadgear/orderintegration/spec/Tc004SetAsideOrderEventReachesWarehouseIT.java && ./mvnw -q -DskipITs test -Dtest=SpecTraceabilityTest && echo C-ALERT-OK` prints `C-ALERT-OK`.

**Review:**
- [ ] Spec compliance: marker and not-yet-testable item; UC-006 not stubbed.
- [ ] Code quality: no executed step added to TC-004.

---

### Task 35: README — Finding and Moving Stuck Orders; Build and Kill Test

**Agent:** implementation-engineer

**Files:**
- Modify: `README.md`
- Modify: `docs/camel-kit/005-hardening/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.8 (both tables, `-- end-state:` blocks, decision 4); §6 README line; §3.9 (kill test in verify)

- [ ] **Step 1:** New section `## Finding and moving stuck orders` for the Integration Team: one subsection per §3.8 end state with the query and the step that gets it moving. Each statement in its own fenced `sql` block whose first line is `-- end-state: <name>` with the names Task 16 requires (`set-aside`, `waiting-for-check`, `waiting-for-recording`, `release-recording` (the exact §3.8 `UPDATE`, with `:id`), `pick-list-not-delivered`, `notification-not-sent`, `partner-unstorable`). Topic not read: the `kafka-consumer-groups.sh --describe` command as text (not a `sql` block); process stopped: restart is deployment's. The set-aside text says it waits for UC-008 (kept 90 days) and that the reason names what to fix first.
- [ ] **Step 2:** § Build: the kill test and the failure tests run in `./mvnw verify` (after the spec tests); `-DskipITs` runs unit tests incl. T1–T4. Remove `src/test/scripts/kill-test.sh` from § Run it in development and the kill-test hook from the folder table; update the unit-test count and `Flyway migrations V1 and V2` → `V1 to V3`.
- [ ] **Step 3:** Green: P-11 `-Dit.test=EndStateQueriesIT`; record.
- [ ] **Step 4:** Verify: `grep -q '^## Finding and moving stuck orders' README.md && [ "$(grep -c '^-- end-state:' README.md)" -ge 7 ] && ! grep -q 'kill-test.sh' README.md && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=EndStateQueriesIT && echo README-OK` prints `README-OK`.

**Review:**
- [ ] Spec compliance: every §3.8 end state with query and step; release statement exact; kill script gone from the docs.
- [ ] Code quality: other README sections unchanged; statements tested, not only written.

---

### Task 36: Spec Documents — Open Questions and Entity Model

**Agent:** integration-architect

**Files:**
- Modify: `docs/open_questions.md`
- Modify: `docs/entity_model.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §7 (OQ table, Entity model delta); §6

- [ ] **Step 1:** `docs/open_questions.md`: add OQ-079 to OQ-086 in the file's existing entry format, each with the question, the recommended answer taken (OQ-084 as changed by decision 1), why, and `Raised by: design 005`; on OQ-075 record the recommendation (reading b taken for Peakmart, reading a left open, OQ-083). Status stays the file's "open/recommended" value, not "answered".
- [ ] **Step 2:** `docs/entity_model.md`: add ORDER_RECEIPT.waiting_reason, ORDER_RECEIPT.last_check_at, PICK_LIST.last_failure, NOTIFICATION.last_failure, NOTIFICATION.last_attempt_at in the file's attribute format — nullable, no personal data, deleted with the row after 90 days (C-022), `Raised by: design 005`.
- [ ] **Step 3:** Verify: `for q in OQ-079 OQ-080 OQ-081 OQ-082 OQ-083 OQ-084 OQ-085 OQ-086; do grep -q "$q" docs/open_questions.md || exit 1; done && for a in waiting_reason last_check_at last_failure last_attempt_at; do grep -q "$a" docs/entity_model.md || exit 1; done && ./mvnw -q -DskipITs test && echo DOCS-OK` prints `DOCS-OK` (the unit tests that parse `docs/` still pass).

**Review:**
- [ ] Spec compliance: eight OQs and five attributes exactly as §7.
- [ ] Code quality: existing entries unchanged; file formats kept.

---

### Task 37: Proposed Test Cases TC-006 and TC-007

**Agent:** integration-architect

**Files:**
- Create: `docs/test_cases/TC-006-pick-list-retried-after-moved-to-processed.md`
- Create: `docs/test_cases/TC-007-partner-order-rejected-with-two-reasons.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 "Proposed test cases for the spec owner (decision 3), not approved, not run"

- [ ] **Step 1:** Write both in the format of the existing test cases (read TC-005 and TC-001), `Status: Proposed`: TC-006 "Pick list retried after the Warehouse moved it to processed/" (F5); TC-007 "Partner order rejected with two Rejection Reasons in check order" (F6). No IT is written for them.
- [ ] **Step 2:** Verify: `grep -q 'Status: Proposed' docs/test_cases/TC-006-pick-list-retried-after-moved-to-processed.md && grep -q 'Status: Proposed' docs/test_cases/TC-007-partner-order-rejected-with-two-reasons.md && ./mvnw -q -DskipITs test -Dtest='SpecTraceabilityTest,TestCaseDocumentTest' && echo PROPOSED-TC-OK` prints `PROPOSED-TC-OK` (SpecMatrix does not require Proposed documents).

**Review:**
- [ ] Spec compliance: two documents, Proposed, contents as §3.9 names.
- [ ] Code quality: parser accepts them; no Approved test case changed.

---

### Task 38: Build Times After

**Agent:** implementation-engineer

**Files:**
- Modify: `docs/camel-kit/005-hardening/evidence/build-times.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §4 Performance (decision 5); §7 "Required in the execution report"

- [ ] **Step 1:** With every task done: `/usr/bin/time -p ./mvnw -B verify -DskipITs` and `/usr/bin/time -p ./mvnw -B verify`; record exit codes, seconds and each IT's time from `target/failsafe-reports/TEST-*.xml` under "After".
- [ ] **Step 2:** If `./mvnw verify` takes more than 900 s, list the ITs that cost the most (descending). Do not move any IT out of `verify`.
- [ ] **Step 3:** Verify: `sed -n '/After/,$p' docs/camel-kit/005-hardening/evidence/build-times.md | grep -q 'verify -DskipITs' && sed -n '/After/,$p' docs/camel-kit/005-hardening/evidence/build-times.md | grep -Eq 'mvnw -B verify( |\||$)' && echo AFTER-OK` prints `AFTER-OK`.

**Review:**
- [ ] Spec compliance: before/after for both commands, per-IT times, the >15 min rule applied.
- [ ] Code quality: measured, not estimated; no build configuration changed to shorten it.

---

## Runtime Acceptance (performed by camel-execute after all tasks)

`./mvnw verify` with Docker running: every unit test (existing plus T1–T4, `SpecEnvironmentTopicTest`,
`SpecRunCleanupMessageTest`) passes; the five spec ITs pass and the matrix is written; the failure ITs T5–T13 pass in
the second failsafe execution. The execution report carries (design §7 "Required in the execution report"): every row
of `evidence/red-green-runs.md` (each §3.9/§3.10 test's red run with test, assertion, message, and its green run);
`evidence/build-times.md` before and after; the Flyway connection mechanism and T12's result (P-9); the confirmed
interceptor order (P-8); the intake commit-once check of Task 26; the split-item comparison of Task 33; any finding
under P-16.
