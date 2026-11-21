---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-14T08:44:55.111703Z"
  by: "camel-execute"
  from: "implementation-plan.md"
---
# Execution Report — 005-hardening

```
===============================================================
IMPLEMENTATION COMPLETE
===============================================================

Pipeline: 005-hardening (standalone mode; camel-validate not auto-invoked)
Plan: docs/camel-kit/005-hardening/implementation-plan.md
Design Spec: docs/camel-kit/005-hardening/design-spec.md

Tasks Completed: 38/38

Review Results:
  Adversarial (ACR): 38/38 passed after fixes (3 FAIL verdicts fixed: Tasks 22, 27, plus the Task 19 bean-step gap)
  Spec Compliance: 38/38 tasks passed
  Code Quality: 38/38 tasks passed (no open Critical; non-critical issues noted per task)

Cross-Cutting Review: PASS_WITH_ISSUES (1 Important fixed, 2 Important checked against logs, residual)

Verification: PASS
  ./mvnw -B verify: BUILD SUCCESS, 18:17 min (170 unit tests, 5 spec ITs, 23 failure tests, matrix written)
  ./mvnw -B verify -DskipITs: BUILD SUCCESS, 21.1 s
===============================================================
```

### Generated / changed files

- **Main code:**
  - New: `ErrorContainment.java`, `FailureKinds.java`, `UnexpectedErrorException.java`, `PartnerTimeoutAnswer.java`.
  - Changed: `IntegrationBeans.java` (beans `failureKinds`, `errorContainment`) and `WarehouseSftp.java` (`RemoteFiles`
    seam).
- **Routes:** all six `src/main/resources/camel/*.camel.yaml` (error clauses H2, H4, H5, H6). The check route also
  gets `stopOnException` on its split.
- **SQL and migration:**
  - New: `V3__failure_reasons.sql`, `order-wait.sql`, `order-park.sql`, `pick-list-failed.sql`,
    `notification-failed.sql`.
  - Changed: `waiting-select.sql`, `order-record.sql`, `order-reject.sql`, `pick-list-delivered.sql`.
- **Configuration:** `application.properties` (H3 time limits, Flyway's own connection, the 9 s async limit,
  `peakmart.set-aside-redeliveries`). `pom.xml` (fault-hook test-jar, failsafe `failure-tests` execution, surefire
  exclude, the summary-file clean).
- **Tests:**
  - Unit tests: T1 `JsonValidatorPinTest`, T2 `FailureKindsTest`, T3 `ErrorContainmentTest`, T4
    `WarehouseSftpLookupTest`, `SpecEnvironmentTopicTest`, `SpecRunCleanupMessageTest`.
  - Fault hook: `fault/FaultHook`.
  - Failure ITs in `failure/`: `SqlStatements`, T5–T13.
  - Harness: `RejectingSmtpServer`, plus changes to `IntegrationProcess`, `SpecEnvironment`, `SpecRun`,
    `WarehouseInbox`, `SpecMatrix`, `SpecTraceabilityTest`, `TestCaseDocumentTest`, Tc001/Tc004/Tc005.
  - `split-items.properties`.
- **Removed:** `KillTestHook.java`, `KillTestApplication.java`, `src/test/scripts/kill-test.sh`.
- **Docs:** `README.md` (section "Finding and moving stuck orders", build text), `docs/open_questions.md`
  (OQ-079…OQ-086, OQ-075), `docs/entity_model.md` (5 attributes), `docs/test_cases/TC-006…`, `TC-007…` (Proposed).
- **Evidence:** `evidence/red-green-runs.md` (every red and green run, decision 2) and `evidence/build-times.md`
  (decision 5).

### Required in the execution report (design §7)

- **Red before green (decision 2):** every run is in `evidence/red-green-runs.md`.
  - **Red recorded with the code or fault named:** T1 (pin removed), T2, T3, T4 (F5), T5 (F6), T6 (assertion-level, pre-H5
    `ORDER BY`), T7 (6 cases before H1–H6), T8 (F7, F5), T12 (pool 0/30000, and discriminating: Flyway 5000 without
    its own settings), T13 (README section missing), and the §3.10 harness items.
  - **Not obtained (honest gap): T9, T10, T11.** Their red runs fell in the Docker disk-full window, and the changes were
    made before Docker came back. Their rows say NOT RUN with the expected reason. The green runs are real.
  - **Partial:** T5's red stops at the Peakmart assertion.
- **Build time (decision 5):** before `verify` 189 s and `-DskipITs` 4.6 s; after 1097 s and 21.1 s. Over 15 minutes, so
  the costliest ITs are named in `evidence/build-times.md` (KillIT 514 s, UnexpectedErrorIT 202 s, …). None was moved out
  of `verify`.
- **Flyway's own connection (decision 3, P-9):** `spring.flyway.url/user/password` are set from `spring.datasource.*`.
  Spring Boot then builds a separate DataSource for Flyway without the Hikari data-source properties.
  - T12 green: pool network timeout 5000 ms, Hikari connection timeout 2000 ms, Flyway connection 0.
  - T12 red without the three lines: Flyway 5000.
- **Interceptor order (P-8):** ErrorContainment `Ordered.HIGHEST` (outermost), FaultHook `LOWEST`. Confirmed by T3's
  two-strategy cases and by T7 green.
- **Intake commit-once (Task 26):** a failure is handled in the sub-route where it happens. Camel stops the shared
  exchange's pipeline once it has been handled, so the parent's commit and receipt never run, and `stop` is a second guard.
  Confirmed at runtime by T7 TC-912: the set-aside event has no receipt, the next event is recorded, and the hook fired
  3 times.
- **Split-item comparison (Task 33):** the ITs declare exactly `TC-002=V3a,V3b`, `TC-004=S3-alert`, `TC-005=S4-lookup`.
  Design 004 §3 names only TC-002 V3 as split; its own step tables contain the other two.
- **Findings under P-16** (plan or design versus reality):
  1. Camel 4.22.2's `DelegateSyncProcessor` and the bean processor/producer catch a step's `Error` themselves. H1 handles
     both: sync steps are called directly, and a `CamelExecutionException(Error)` is checked on completion. This deviates
     from §3.2 Constraints ("only what the call throws synchronously").
  2. The plan's outbox parameters `:#id` and `:#notification_id` cannot resolve inside the error clause, so exchange
     properties are used instead.
  3. `CamelFailureEndpoint` is not yet set when `onWhen` runs, so the clause uses `CamelToEndpoint`.
  4. The partner unstorable clause names `DataIntegrityViolationException`, because Camel ranks by the deepest cause.
  5. A parallel-split line Error could invent a rejection, so `stopOnException` and a route guard were added.
  6. The P-11 per-IT runs and the full `verify` share `target/`. The failure-IT JVM deleted the spec result files, and
     failsafe merged stale summaries. Both are fixed.
  7. The existing `TestCaseDocumentTest` pinned five test cases, against Task 37.
  8. P7 at `to-validate-product` is answered by the check route's catalog-outage branch (503 at once) plus H1's ERROR,
     not by the H2 clause.

### Assumptions (non-interactive run)

- **Reviews:** each task (or group of 2–5 closely related tasks) had one implementer subagent and one review subagent.
  The reviewer ran ACR, then spec, then quality, in that order, in one context. Isolation between the three stages was
  therefore not available. Doc-only Task 1 was done and reviewed inline.
- **Task text:** implementers got the full task text in their prompts. They read the referenced design sections
  themselves.
- **Environment probe:** offline dependency resolution passed. The skeleton runtime start was skipped because port 8080
  is taken and the plan adds no new component. The catalog binding was verified once (spring-boot, BOM 4.22.2 →
  4.22.2 MATCHED).
- **Timing:** `/usr/bin/time` was not usable in this shell, so build times are Maven's `Total time`.
- **Docker:** nothing was pruned or touched outside Testcontainers. The disk freed up by itself later in the run.
- **Commits:** none were made. Everything is in the working tree.
- **Downstream:** no `validation-report.md` exists, so the staleness cascade is a no-op.

## Environment blocker (from Task 12 on, 2026-11-13 ~23:20 local; resolved ~01:30 when Docker had space again)

Docker Desktop's VM disk is full. The Kafka container fails with `/tmp/kafka-logs: No space left on device`, and the
PostgreSQL container's `initdb` with `could not create directory "/var/lib/postgresql/data/pg_wal": No space left on
device`. The project's Testcontainers setup has Ryuk enabled, no tmpfs and no container reuse. Freeing the space needs
Docker to be pruned, which the run's instructions rule out ("don't touch containers other than through the tests'
Testcontainers"; other containers run on 8080, 8081 and 5432). From then on, every Testcontainers IT run (red or green)
is **NOT RUN (Docker disk full)**. The code is still checked by compilation, the unit tests (no Docker) and the Camel MCP
route validation. T7's red run (6 cases) completed before the disk filled.

## Task log

| Task | Implementer | ACR | Spec | Quality | Notes |
|---|---|---|---|---|---|
| 1 | inline (orchestrator) | n/a (doc-only, inline) | PASS (inline) | PASS (inline) | `verify -DskipITs` 4.6 s, `verify` 189 s; see evidence/build-times.md |
| 2 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | test-jar is attached (would be installed by `mvn install`; never in the production jar) — accepted trade-off |
| 3 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | fixed by orchestrator: `terminate` now resets `lastStopGraceful` before the already-dead early return (Important I-1). `summaryFiles` lists both summaries explicitly (spec counts may be double-counted; pass/fail unaffected). SpecMatrix ignores `failure/` ITs: `SpecTraceabilityTest.discover()` lists only the spec package (`SpecTraceabilityTest.java:180`, non-recursive), used at `SpecMatrix.java:90` |
| 4 | subagent | PASS | PASS | PASS_WITH_ISSUES | Low: the rejection case does not assert which schema rule failed |
| 5 | subagent | PASS | PASS | PASS_WITH_ISSUES | Low: the file-name check is duplicated in the public method and the seam; surefire red reports are overwritten by later runs (the red rows are the record) |
| 6 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | P-11 command works unchanged. Deviation: `order-reject.sql` uses `:#${exchangeProperty.x-y}` parameters, so `SqlStatements` also maps those to `:x_y` (parameter tokens only; quote-unaware, no literal `:#` exists in the SQL today). Low: the red run stops at the Peakmart assertion, so the Partner select's red is not shown separately |
| 7 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Harness additions: `IntegrationProcess.withoutFaults()`, `WarehouseInbox.moveToProcessed/inProcessed`. KillIT checks that the topic is drained before each Peakmart kill point (first F7 run showed a leftover record masking K1b). Fixed by orchestrator after review: the halted run's log is now checked for secrets (`stop()` before the restart; Medium) and K4 asserts the resend was answered from the receipt (log line). Trade-off: a halted JVM leaves the consumer group only after the session timeout (~40 s per Peakmart restart). Green re-run after the fixes: 7/7, 401.5 s |
| 8 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red 87/87 on `design 005: not implemented`. Trade-off carried to the route tasks: `UnexpectedErrorException`'s cause holds the Error's message, so every error clause that can see it must not log stack traces |
| 9 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red 9/9 at context start. Minor: the fatal case checks "no handler, callback not completed" once, not over a short interval. `error=` uses the full class name (plan wording); `describe()` uses the simple name (§3.5) |
| 10 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | First red is at the fixture (no `last_check_at` column). Orchestrator: fixture made stricter after review (C inserted before B, so only `received_at` orders them; A and D carry a reason as §3.6 requires). An assertion-level red is recorded after V3, before the SQL change (Task 20/21) |
| 11 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red: pool network timeout 0, connection timeout 30000; Flyway 0 already. The discriminating red (H3 values without `spring.flyway.*` → Flyway 5000) is recorded in Task 23. Minor: `pinnedImage()` duplicated from `SqlStatements` |
| 12 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | One process per concern (TC-911…916); intake fault count 3 (1 + 2 redeliveries) instead of 1000, so the next event passes. Red: 6/6 cases, 674 s (P7 no answer in 12 s; I6 topic not read; C5/D6/M3 pollers stopped; X6 no exit in 5 s; every stop forced after 30 s). Minor: only the topic is checked as a precondition, not leftover waiting/pending rows |
| 13 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red NOT RUN (Docker disk full). `SpecEnvironment`: `pausePostgres`, `unpausePostgres` (idempotent), `execInSftp`. Important (quality): `Cleanup`/`await`/drain helpers duplicated across the five failure ITs |
| 14 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red NOT RUN. Unstorable field: 150-char recipient name (no schema maxLength; `customer_order.recipient_name varchar(100)`). Carried to Task 25: `CamelFailureEndpoint` is likely not set yet when `onWhen` runs, so the clause matches on `CamelToEndpoint` |
| 15 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red NOT RUN. `RejectingSmtpServer` (plain sockets, 127.0.0.1); `IntegrationProcess.withProperty`. Minor: a fork killed mid-test can leave PostgreSQL paused or the inbound folder read-only for later classes |
| 16 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red ran for real (README parsed before any container): `section '## Finding and moving stuck orders' not found`. README contract in the class javadoc (seven names; selects return `id`; `:id`, `:order_number`). Fixed by orchestrator (Important Q1): a second Partner `Received` receipt, so a `partner-unstorable` query without the order-number filter fails. Medium: psql users must quote `:order_number` (README note, Task 35) |
| 17 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Red: SpecEnvironmentTopicTest 4/5 (the stub self-check passes on purpose), SpecRunCleanupMessageTest 2/2, SpecTraceabilityTest 1/11. Carried to Task 31: keep `describeTopics` in the caller. Carried to Task 33: all three `covers` call sites take `tcId` |
| 18 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | FailureKindsTest green 87/87. `describe(null)` = `unknown` |
| 19 | subagent (+1 fix round) | PASS_WITH_TRADEOFFS → re-review PASS | PASS | PASS (2 Low) | ErrorContainmentTest green 14/14. **Two Camel 4.22.2 findings (P-16):** (1) a `process` step reaches the strategy as `DelegateSyncProcessor`, which catches a plain `Error` itself, so ErrorContainment calls sync steps directly. (2) Review round 1 found that bean steps and bean producers also catch an `Error` and leave it as `CamelExecutionException(Error)`. Four new T3 cases went red (no containment, no halt on `OutOfMemoryError`). Fix: on completion, a `CamelExecutionException` whose direct cause is an `Error` gets the decision-1 split. **Deviation from §3.2 Constraints** ("catches only what the call throws synchronously"): the completion-time check is added; async behaviour unchanged. The design text is not edited by execute. Interceptor order P-8 confirmed by the two-strategy cases. Low: no test of a bean step inside a nested EIP |
| 20 | subagent | PASS | PASS | PASS | V3 adds 5 columns + `order_receipt_waiting_reason_ck` (`pick_list.last_attempt_at`/`attempts` already in V2). WaitingSelectIT assertion-level red with V3 and the old select: NOT RUN (Docker) |
| 21 | subagent | PASS | PASS | PASS | Parameters `:#${exchangeProperty.waiting-reason}`, `:#${exchangeProperty.receipt-id}` (existing statement style; a hyphenated `:#name` would not resolve). Greens NOT RUN (Docker) |
| 22 | subagent | **FAIL → fixed** | PASS | PASS | **Plan defect (P-16):** the plan's `:#id` / `:#notification_id` cannot resolve in the error clause (headers removed, body replaced), so a failure would not be counted once `onConsumeFailed` is gone. Orchestrator fix: `CAST(:#${exchangeProperty.pick-list-id} AS bigint)` (as `pick-list-delivered.sql`) and `CAST(:#${exchangeProperty.notification-id} AS bigint)` (Tasks 29/30 set `notification-id`). ACR re-check folded into the Task 28–30 review |
| 23 | subagent | PASS | PASS | PASS | Flyway's own connection: `spring.flyway.url/user/password` from `spring.datasource.*` (P-9). Boot then builds a separate DataSource without the Hikari data-source properties. RequiredVariablesCheck reports no new variable; RequiredVariablesCheckTest green. ConnectionSettingsIT red (H3 without Flyway props) and green: NOT RUN (Docker) |
| 24 | subagent | PASS | PASS | PASS_WITH_ISSUES → fixed | Orchestrator: inject the `orderAnswers` bean instead of `new OrderAnswers()`; dropped redundant `throws`. Rethrow for non-`/orders` paths gets Spring's default 503 (per review). Trade-off: after the 9 s answer the exchange goes on and may record the order; a resend gets the receipt's answer (OQ-086) |
| 25 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | `on-partner-order-unexpected-error`, `on-partner-order-unstorable` (onWhen: `CamelToEndpoint contains 'order-record.sql'` and `cannotBeRecorded`; `CamelFailureEndpoint` is set only after the clause is chosen). Orchestrator fix: the unstorable clause names `DataIntegrityViolationException` (design §3.4), not `NonTransientDataAccessException`. Camel ranks by the deepest cause, so the broader class could tie with `java.lang.Exception`. The routeConfiguration passes `camel_validate_yaml_dsl`. Deviation: P7 at `to-validate-product` (catalog-lookup thread) is answered by the check route's catalog-outage branch (503 at once) plus H1's ERROR line, not by the H2 clause. DatabaseFrozenIT green NOT RUN (Docker) |
| 26 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Three `java.lang.Exception` clauses in order: `on-intake-waits` (onWhen `waits`), `on-intake-cannot-take-in` (onWhen route ≠ set-aside; handled; reason; to set-aside; `commit-kafka-offset-after-set-aside`; `stop`), `on-intake-set-aside-failure` (design §3.5 row 3: not handled, no logging). **Commit-once check (Task 26 Step 3):** a failure is handled in the sub-route where it occurs. Camel's pipeline stops the shared exchange once the error handler has handled it (confirmed from 4.22.2 class strings), so the parent's `commit-kafka-offset` and the receipt insert never run. `stop` is a second guard. Runtime confirmation (TC-912) NOT RUN (Docker). Orchestrator fix: `logNewException: false` (Medium T1). Trade-offs: the Kafka consumer's own WARN on a waits failure carries the DB/Kafka exception message (pre-existing); `maximumRedeliveries` placeholder fails the MCP JSON schema (expects a number) but is a String field in the Camel model |
| 27 | subagent | **FAIL → fixed** (re-check folded into the cross-cutting review) | PASS | PASS_WITH_ISSUES | Outage → `order-wait.sql` (`catalog outage`); `catch-order-invalid` and new `catch-order-unstorable` (onWhen `cannotBeRecorded`, so a DB-down `CannotGetJdbcConnectionException` is not parked) → `order-park.sql` + ERROR `kind=unstorable`; `on-check-unexpected-error` guarded by `${fromRouteId} == 'peakmart-order-check-poll'`, because `order-check`/`product-lookup` are shared with the partner check. **ACR-1 (orchestrator fix):** an Error at a `product-lookup` step inside the parallel split was handled per line and left the line map as an "answer". The order could then be rejected ("not an Active Product"), against OQ-080. Fix: the clause onWhen also requires `CamelSplitIndex == null`, and `split-order-lines` gets `stopOnException: true`. The exception then reaches the order once (handled by the order's clause), and for a partner it reaches the partner's 503 clause. YAML validated (0 errors). No IT covers a fault inside the split (coverage gap). Spec ITs, UnstorableOrderIT: NOT RUN (Docker) |
| 28 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Clause: `last-failure` = describe → `pick-list-failed.sql` in a doTry → WARN with the kind. `onConsumeFailed` removed. Verified from camel-sql 4.22.2 class strings: the not-handled clause restores the caught exception, so the exchange stays failed, `onConsume` does not run, and the attempt is counted once. SqlConsumer logs nothing of its own for a failed exchange. Tc001/Tc005: NOT RUN (Docker) |
| 29 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | `set-notification-id` first step; `notification-failed.sql`; `onConsume` clears `last_failure`. Info: a successful send does not set `last_attempt_at` (not required). Tc002/Tc003: NOT RUN |
| 30 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | Same for the Rejection Email (`set-rejection-email-notification-id`). Task 22 SQL fix re-checked: bindings correct. UnexpectedErrorIT and DeliveryFailureIT greens: NOT RUN (Docker) |
| 31 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | SpecEnvironmentTopicTest green 5/5. Low: each create try may use the full startup budget (worst case about 4× the budget) |
| 32 | subagent | PASS_WITH_TRADEOFFS | PASS | PASS_WITH_ISSUES | SpecRunCleanupMessageTest green 2/2. Low: `registeredAtSetup` defaults to false, so if C-setup throws before the check, the cleanup message names "not registered" |
| 33 | subagent | PASS | PASS | PASS | SpecTraceabilityTest green 11/11. **Split-item comparison:** the ITs declare exactly P-14 (`TC-002=V3a,V3b`, `TC-004=S3-alert`, `TC-005=S4-lookup`). Design 004 §3 line 223 names only TC-002 V3 as split, but its step tables (S3, S4) describe the other two splits, so design 004 is inconsistent with itself, not with the ITs |
| 34 | subagent | PASS | PASS | PASS | Red appeared in the matrix execution (`-DskipITs verify`: "TC-004 item C-alert … is neither a run item nor a not-yet-testable item"), not in SpecTraceabilityTest, which does not build the matrix; then green. Whole unit suite: 170 tests, 0 failures |
| 35 | subagent | PASS | PASS | PASS_WITH_ISSUES | README section with seven `-- end-state:` blocks and a psql note for `:id`/`:order_number`; build text, `V1 to V3`, kill script gone. **EndStateQueriesIT GREEN** after the orchestrator fixed the test's fixture (7th INSERT parameter missing). Low: README line 13 still lists `camel-kit/001…003/` (outside the named lines) |
| 36 | subagent | PASS | PASS | PASS | OQ-079…OQ-086 (Open, `**Assumed:**` answer as §7; OQ-084 per decision 1); OQ-075 recommendation; five entity attributes. Diff: additions only |
| 37 | subagent | PASS | PASS | PASS | TC-006, TC-007 `Status: Proposed`; matrix accepts them. **Plan contradiction (P-16):** existing `TestCaseDocumentTest` pinned exactly TC-001…TC-005, so the orchestrator updated it to list TC-001…TC-007 (not weakened) |

## Fixes found by the runtime runs (after Docker came back)

1. **Task 27 guard regression (found by UnexpectedErrorIT TC-913).** In the orchestrator's ACR-1 fix the clause required `CamelSplitIndex == null`. The parallel split copies its properties back onto the order's exchange, so that guard was false for every error after the split. The Error then fell through to Camel's DefaultErrorHandler, which logged a stack trace with the Error's message, and `waiting_reason` was never written. Fix: `${routeId} != 'product-lookup'` (the route where the failure happened). Re-run: UnexpectedErrorIT 6/6 green, and TC-913's log shows `Peakmart order stays waiting after an unexpected error` twice and no DefaultErrorHandler line.
2. **Task 3 matrix defect (found by the full `./mvnw verify`).** The failure-IT JVM (`failure-tests` execution) ran `SpecEnvironment.deleteEarlierResults()` and deleted the spec ITs' result files before `SpecMatrix` (post-integration-test) read them, giving 5 violations. Fix: the `failure-tests` execution sets `spec-test.keep-results=true`, and `deleteEarlierResults` skips in that JVM.
3. **EndStateQueriesIT fixture (Task 16)** passed 6 of 7 INSERT parameters. Fixed, then green.
4. **TestCaseDocumentTest (pre-existing)** pinned exactly five test cases, which contradicted Task 37. It now lists seven.

## Cross-cutting review (Step 3): PASS_WITH_ISSUES

Isolated reviewer, read-only. No Critical findings.
- **Important 1:** `logNewException: false` was missing on the two new partner clauses. Fixed by the orchestrator.
- **Important 2 and 3 (plausible):** a partner-path `UnexpectedErrorException` inside `order-check`/`product-lookup` matches no clause of `order-check-error-handling`. Not-handled outbox and intake failures go to the consumers' own handlers. Either could log a stack trace carrying the Error's message or an address. **Checked against the process logs of all failure ITs:** no `fault-injection` message, no `SMTPAddressFailedException:` text, no address. The 550 case is logged as `cause=SMTPAddressFailedException 550`. The only stack traces are the sql pollers' pre-existing `Failed polling endpoint` traces while PostgreSQL is frozen (connection errors, no order data). Kept as a residual risk for untested paths.
- **Suggestions (not applied):**
  - `kind=` means the describe output in the partner unexpected-error line but the literal `unstorable` elsewhere.
  - The intake and check ERROR lines carry different fields.
  - Some "attempt not recorded" WARNs use the class name, not `describe()`.
  - Two route conditions depend on string formats (`describe()` prefix, `CamelToEndpoint contains 'order-record.sql'`).
  - `PartnerTimeoutAnswer` matches `/orders` exactly.
- **Constitution:** the 8 rules hold across the six routes.
- **Not Doing:** respected. No alert, dead-letter queue, circuit breaker, back-off, new Rejection Reason or production dependency (pom changes are build plugins and test configuration only).

## Fixes after validation

Session 07, 2026-11-14, for decisions 2, 3 and 5 of `validation-decisions.md`, and nothing else. Every test was run
against the code without its change first. The red and green runs, with assertion, message and duration, are in
`evidence/red-green-runs.md`, section "Session 07". Nothing is committed.

### What changed, and why

**1. H1 contains the `Error` of F8 (decision 2).** `ErrorContainment.java`.
- *Why.* How the `Error` travels was read from Camel 4.22.2's own classes (`javap` on the jars in the packaged
  jar) and confirmed by the stack trace of the red run.
  - A `to:` is a `SendProcessor`, which catches only `Exception`.
  - A producer that is not async is called through
    `AsyncProcessorConverterHelper$ProcessorToAsyncProcessorBridge`. That covers `json-validator` and every
    `ProcessorEndpoint` (`DefaultEndpoint.createAsyncProducer` → `convert(createProducer())`).
  - The bridge calls `callback.done(true)` in a `finally`, then rethrows the `Error`. H1 saw a completed step and
    rethrew the `Error` as "not this step's".
  - F8's own throw site is the endpoint's `uriSchemaLoader`: `DefaultJsonUriSchemaLoader.createSchema` →
    `SchemaRegistry.getSchema(SchemaLocation, JsonNode)`, the method named in the pin test's red.
- *Change.* A synchronous completion made on the calling thread during the step's call is held. It is passed on
  only once the call returns. If the call throws instead, the `Error` is the step's: contained, or a halt for a
  `VirtualMachineError`, with no completion. An `Error` thrown after the call returned, or after an asynchronous
  completion, is still rethrown ("not this step's"). The sync-step path and the completion-time check are
  unchanged.

**2. Tests where the `Error` comes from inside a real producer reached through `to:` (decision 2).**
- `ErrorContainmentTest`: 5 new cases.
  - A `ProcessorEndpoint` of the test's own, alone and in a parallel split.
  - A real `json-validator` step whose `uriSchemaLoader` (an endpoint option in the 4.22.2 catalog) throws.
  - The `OutOfMemoryError` variant (halt with 70, no callback).
  - A guard for the "not this step's" case.
- Packaged jar: `FaultHook` has a new kind, `<node>:producer-error:<n>`. It wraps nothing. It replaces the
  json-validator endpoint's `uriSchemaLoader` with one that throws `NoSuchMethodError` with F8's message, so the
  `Error` is thrown in `JsonValidatorEndpoint.onExchange`, as in F8.
- `UnexpectedErrorIT` TC-917 posts a partner order with this fault at `to-validate-partner-order`. It expects the
  503 contract answer and the `Unexpected error` line, with neither F8's message nor Spring's servlet line in the
  log.

**3. Q-1 and Q-2: the catch-alls let an `UnexpectedErrorException` pass (decision 2).**
- *First attempt.* `onWhen: ${exception} !is '…UnexpectedErrorException'` on `catch-partner-order-unreadable`,
  `catch-not-json` and `catch-product-invalid`. It was not enough, and the runs showed why.
  - Inside `doTry`, `onException` does not trigger: "doTry is in fact its own error handler" (Camel 4.22 doTry
    EIP and user manual).
  - So the partner got a 500 and the intake event stayed unhandled.
  - The validation report's recommended rethrow therefore can't work within one route.
- *Change.* `try-read-partner-order` and `try-read-as-json` moved, unchanged apart from the `onWhen`, into two
  new `direct:` routes:
  - `partner-order-read`, called by `to-partner-order-read`;
  - `peakmart-event-read-json`, called by `to-peakmart-event-read-json`.
  
  The caller's existing clause then handles the exception, as it already did for `product-lookup`:
  - partner: `on-partner-order-unexpected-error`, 503;
  - intake: `on-intake-cannot-take-in`, the read redelivered twice, then set aside as `cannot be taken in:
    unexpected error: NoSuchMethodError`.
- *Peakmart check.* The order waits with `waiting_reason = unexpected error: NoSuchMethodError`.
- *Regression found and fixed.* With `catch-product-invalid` letting the exception pass on a *partner* check, TC-911
  went red: 500, and `DefaultErrorHandler` logged a stack trace with the `Error`'s message. This is the residual of
  Q-9 / Important 2: no clause of `order-check-error-handling` takes a partner's exception. The `onWhen` now lets it
  pass in a Peakmart check only (`|| ${fromRouteId} != 'peakmart-order-check-poll'`). On a partner check it stays the
  OUTAGE answer, i.e. the 503 contract answer, as at HEAD. Q-2 asked for the Peakmart reason (`waiting_reason`); the
  partner has no stored reason.
- *New T7 cases.* TC-917 (Q-1 at `to-validate-partner-order`), TC-918 (Q-2 intake, `set-json-object:error:3`) and
  TC-919 (Q-2 check, `to-validate-product:error:2`).
- *Validation.* The YAML shape was checked against the Camel YAML DSL schema (MCP `camel_validate_yaml_dsl`).
  `doCatch.onWhen` and `json-validator`'s `uriSchemaLoader` were checked in the 4.22.2 catalog.

**4. Q-4: the freeze outlasts the intake's retries (decision 3).** `DatabaseFrozenIT`.
- `MINIMUM_FREEZE` went from 15 s to 60 s.
- New constant `INTAKE_RETRY_SPAN` = 4 attempts × 7 s (H3) + 1 + 2 + 4 s back-off = 35 s.
- The test asserts that the measured freeze is longer than that span.
- Red with F9 (`on-intake-waits` `handled: "true"`): `PM-91201 … not within PT2M (receipts 0 …)`.
- Green without it: frozen 60,044 ms, partner answer 5,608 ms.

**5. Design §3.2 amended (decision 5).** It now says:
- the three ways H1 sees a step's `Error` on 4.22.2, including decision 2's case;
- what "not this step's" means now;
- why a catch-all inside `doTry` needs its own route, with a table of the three catches and their end states;
- the residual stack-trace paths that stay a risk.

### Changed files

- Main:
  - `ErrorContainment.java`;
  - `partner-order-api.camel.yaml` (new route `partner-order-read`);
  - `peakmart-order-intake.camel.yaml` (new route `peakmart-event-read-json`);
  - `peakmart-order-check.camel.yaml` (`onWhen` on `catch-product-invalid`).
- Test:
  - `ErrorContainmentTest.java`;
  - `fault/FaultHook.java` (`producer-error`);
  - `failure/UnexpectedErrorIT.java` (TC-917, TC-918, TC-919; set-aside rows found and cleaned up by content);
  - `failure/DatabaseFrozenIT.java`.
- Docs:
  - `design-spec.md` §3.2;
  - `evidence/red-green-runs.md`;
  - this section.

### Runs

| run | result |
|---|---|
| ErrorContainmentTest, HEAD | RED, 4 errors of 19 (three `TimeoutException`: the exchange never completes; the OOM case never halts); the guard green |
| ErrorContainmentTest, H1 fix | GREEN, 19 (8.6 s) |
| UnexpectedErrorIT TC-917/918/919, HEAD | RED 3 of 3: partner **200 echoing its own request**, Spring's `Servlet.service() … threw exception` with F8's message and stack trace; intake `not JSON`; check `catalog outage` |
| TC-917, H1 fix only | RED: 400 "cannot be read" (Q-1 exactly as predicted) |
| TC-917/918/919, `onWhen` only | RED 2 of 3: partner 500, intake not set aside (doTry disables onException) |
| TC-917/918/919, own routes | GREEN, after a fix to the intake case's cleanup (a `kafka-0-0` key) |
| UnexpectedErrorIT, all 9 | RED 1 of 9: TC-911 500 (the partner-check regression above) |
| TC-911 + TC-919, narrowed `onWhen` | GREEN; no stack trace or `Error` message in their logs |
| DatabaseFrozenIT, F9 / without | RED / GREEN (215.5 s / 95.7 s) |
| `./mvnw -B verify`, first | BUILD FAILURE in 14:58 min, environmental only. Unit tests (175) and all 26 failure ITs were green. `Tc003PeakmartOrderRejectedIT` failed because the integration could not start: `Web server failed to start. Port 59323 was already in use` (a free port chosen by the harness and taken before Tomcat bound it). None of this session's processes were left running. Re-run unchanged |
| `./mvnw -B verify`, final | **BUILD SUCCESS, measured `Total time: 15:27 min`** (finished 2026-11-14T08:34:47-06:00): 175 unit tests, 5 spec ITs, 26 failure ITs (23 + TC-917/918/919), traceability matrix written, no violation |

### Assumptions and open points (non-interactive run)

- **Packaged-jar case.** The decision allows "a `json-validator` step, or a `ProcessorEndpoint` of the test's own".
  The packaged-jar case uses the real `json-validator` step with a throwing `uriSchemaLoader`, planted by the fault
  hook. Assumed acceptable: the hook throws nothing itself, and the stack trace shows the producer path.
- **F8 and F9 by the developer.** The F8 re-run of decision 2 (pin removed, full build) is the developer's and was
  not run here. TC-917 reproduces its partner path at the same throw site. The F9 red in this section is the one
  decision 3 asks for.
- **Not fixed (out of scope).**
  - In TC-919 two split lines fail in parallel, and `Peakmart order stays waiting after an unexpected error` is
    logged twice for one order. The end state is right (one `order-wait.sql` reason).
  - The partner-path residual in `order-check` / `product-lookup` (Q-9) stays a risk. It is now avoided for
    `to-validate-product` by the narrowed `onWhen`, not by a clause.
- **Build time.** The 60 s freeze adds about 45 s to `verify` (decision 6 leaves build time to chapter 9).
