---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-14T13:26:20.770027Z"
  by: "camel-validate"
  from: "execution-report.md"
---
# Validation Report

**Date:** 2026-11-14 07:40
**Project:** trailhead-orders
**Runtime:** spring-boot
**Camel Version:** 4.22.2

Mode: standalone, pipeline-scoped (`/camel-validate 005-hardening`), non-interactive. This is a static validation of
the files. No build was run, the application was not started, and no container was touched (user instruction).

**Inputs:**
- `design-spec.md`, `implementation-plan.md` and `execution-report.md`. `camel-kit doc check` reported each one as not
  stale. The lineage is plan ← design and execution report ← plan.
- `design-review.md` and `developer-checks.md` have no provenance frontmatter. Both are committed (`478d723`,
  `89dc743`). They are used as developer evidence and checked against the files (A-1).
- `evidence/red-green-runs.md`, `evidence/build-times.md`, `evidence/failure-lab-runs.md`.
- `004-spec-tests/validation-report.md` and `validation-decisions.md`, for the carried-over findings (A-2).

**Catalog binding:** `camel_catalog_components(limit=0, runtime=spring-boot,
platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2)` returned `camelVersion=4.22.2`
(MATCHED). Every later catalog call used the same binding.

**Inventory** (single module, empty `MODULE_PREFIX`):
- `ROUTE_FILES`: the six files under `src/main/resources/camel/`. All six changed in this pipeline
  (`git diff 478d723 HEAD`: +437 / −19 lines, error clauses only, plus `stopOnException` on `split-order-lines`).
- `PROPS_FILE`: `src/main/resources/application.properties` (+19 lines: H3, Flyway's own connection, H2's 9 s limit,
  `peakmart.set-aside-redeliveries`). `application-default.properties` and `config/application-dev.properties` did not
  change.

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | 1 | 0 | 2 | 3 |
| Endpoint Verification | 4 | 0 | 3 | 7 |
| Quality Checks | 5 | 1 | 9 | 15 |
| Security Analysis | 7 | 4 | 2 | 13 |
| Anti-Patterns | 1 | 0 | 3 | 4 |
| Constitution Compliance | 6 | 0 | 2 | 8 |
| **Total** | **24** | **5** | **21** | **50** |

**Result: FAIL (5 items).**
- **One new failure, in this pipeline's own code (Q-1).** An `Error` at the partner's first JSON validation step
  (`to-validate-partner-order`) gets the answer **400 "the request is not JSON"**. H1 turns the `Error` into an
  `UnexpectedErrorException`, and the existing `doCatch java.lang.Exception` of `try-read-partner-order` catches it.
  The design (P7, H2) requires a 503 contract answer. A 400 is final: the partner does not resend, so a valid order is
  turned away because of our own defect. This is exactly F8's step (the `NoSuchMethodError` on every `json-validator:`).
  No test faults this node.
- **Four security failures, carried unchanged from 003 and 004 (S-1 to S-4).** Accepted again for 004 (004 decision 1).
  This pipeline did not touch the files behind them. The checklist allows no lower severity, so they block 005 until
  they are fixed or accepted again.

**The rest of the pipeline holds, with warnings.**
- Every design item (H1–H6, V3, the README end states, T1–T13, §3.10, OQ-079…086, TC-006/007) is in the files.
- The developer's build and artifact claims agree with `pom.xml` and the evidence files.
- The main warnings come from the developer's fault-injection run. Four of the five hidden faults (F9–F12) pass every
  test. H3 is guarded only by reading its settings (R9). Each gap is named under Quality Checks with the test that
  would close it.

## Findings

### Schema Validation
- [PASS] `camel_validate_yaml_dsl` on the changed blocks of five files, comments removed, in one payload:
  `peakmart-intake-error-handling`, `order-check-error-handling`, the `order-check` route with
  `stopOnException: true`, `partner-order-error-handling` (the two new clauses), `pick-list-delivery-error-handling`,
  and `order-desk-notification-send` with its new `onConsume` and `set-notification-id`. One error, below.
- [WARN] `peakmart-order-intake.camel.yaml:48` `maximumRedeliveries: "{{peakmart.set-aside-redeliveries}}"`: "string
  found, number expected" (`RedeliveryPolicyDefinition.maximumRedeliveries`). The execution report knows this (Task 26):
  the Camel model field is a String, and T7 TC-912 shows 3 fault fires (1 + 2 redeliveries), so the placeholder
  resolves at runtime. The JSON schema and tools built on it (Kaoto, the validator plugin) will still flag the file.
  Recommended: keep it and record it as accepted, or write `2` and drop the property.
- [WARN] Not validated in full: the unchanged parts of the six files, and `partner-rejection-email-error-handling`
  (the same shape as the Order Desk clause, which passed). The full-file validators (`camel-yaml-dsl-validator`,
  `camel run --check`) were not run, because this run starts no build. The developer's clean `./mvnw -B clean verify`
  (exit 0, all six routes started by the ITs) is the runtime evidence (A-1).

### Endpoint Verification
- [PASS] Catalog binding MATCHED for 4.22.2 spring-boot.
- [PASS] The pipeline adds no component and no new endpoint kind. The new endpoints are
  `sql:classpath:sql/{order-wait,order-park,pick-list-failed,notification-failed}.sql`, each with
  `dataSource: "#dataSource"`, as the existing sql endpoints have. The design's catalog evidence (§5) covers sql and the
  EIP options it uses (`stopOnException`, `onWhen`, `redeliveryPolicy`).
- [PASS] Every `{{...}}` placeholder in the six routes is defined. The one new placeholder,
  `peakmart.set-aside-redeliveries`, is at `application.properties:28`.
- [PASS] `camel_configuration_validate` on the Camel keys of `application.properties`: the three
  `camel.component.http.*-timeout` keys, `camel.main.name`, `camel.main.log-mask`,
  `camel.main.additional-sensitive-keywords`, and (from the default profile) `camel.component.smtp.username/password`
  are valid.
- [WARN] `camel.main.run-controller` (`application.properties:12`) is reported unknown. This is the false positive of
  002 decision 4 (the key is in `camel-spring-boot-4.22.2.jar` and binds at startup). The line did not change.
- [WARN] The new non-Camel keys are not in the Camel catalog, so they were checked against the design only (§3.3,
  §3.4): `spring.datasource.hikari.connection-timeout=2000`,
  `spring.datasource.hikari.data-source-properties.connectTimeout=2` / `socketTimeout=5` (pgjdbc, seconds),
  `spring.flyway.url/user/password=${spring.datasource.*}` and `spring.mvc.async.request-timeout=9s`. They match.
  T12 reads the resulting settings (green, plus a discriminating red at `red-green-runs.md:80`).
- [WARN] Carried over: the Product Catalog URI uses plain `http://` (`peakmart-order-check.camel.yaml:246`, was `:179`).
  This is S-4.

### Quality Checks
- [PASS] **Design coverage.** Every item of design §6 is in the tree:
  - Main code: `ErrorContainment` (Ordered.HIGHEST), `UnexpectedErrorException` (class name in the message, no
    `Error` message), `FailureKinds`, `PartnerTimeoutAnswer`, and the beans `errorContainment` and `failureKinds`.
  - Data: `V3__failure_reasons.sql` (5 columns, `order_receipt_waiting_reason_ck`), and the four new statements.
    `order-record.sql:11-12` and `order-reject.sql:13-14` clear `waiting_reason` and `last_check_at` together with
    `waiting_for`, as the V3 check requires. `waiting-select.sql` orders by `last_check_at NULLS FIRST, received_at, id`
    and selects only `Check`.
  - Routes: the clauses of §3.4–§3.7, in the design's order.
  - Tests and harness: T1–T13 and the §3.10 tests.
  - Docs: the README has seven `-- end-state:` blocks (`README.md:92-170`). OQ-079 to OQ-086 are in
    `open_questions.md`. TC-006 and TC-007 have `Status: Proposed`.
  - Removed: `KillTestHook`, `KillTestApplication` and `kill-test.sh`.
- [PASS] **Developer checks 2–4 agree with the files.**
  - The fault hook is a `@Profile("fault-injection")` test class (`FaultHook.java:34`), packaged only by the
    `fault-hook-jar` execution with classifier `fault-hook` (`pom.xml:230-237`).
  - Failsafe runs `**/failure/*IT.java` in its own `failure-tests` execution, with `spec-test.keep-results=true`
    (`pom.xml:321-333`). Surefire excludes it (`:277`).
  - The 2.0.1 pin is unchanged (`pom.xml:52-53`).
  - Build times and run counts were not rerun (A-1).
- [PASS] **Decision 5 (build time).** The build takes 1,075 s (developer, clean) or 1,097 s (execute), against 189 s
  before. That is over the 15 minutes. `evidence/build-times.md` names the costliest ITs (KillIT 514 s,
  UnexpectedErrorIT 202 s), and none left `verify`, as decided. The cost driver (the 40 s Kafka session timeout after
  every halt) goes to the CI pipeline.
- [PASS] **The four misses of 004 (F5–F8) are now caught.** F8 is caught in the fast build (`JsonValidatorPinTest`),
  which closes 004 decision 2.
- [PASS] **Decision 2 (red before green), honest record.** `red-green-runs.md` has a red and a green row for every test
  except T9–T11. Those rows say NOT RUN, with the reason. The developer filled two of the three gaps: T10 goes red with
  F15 and T11 with R11. T9 is the remaining gap (next item but one).
- [FAIL] **Q-1: an `Error` at `to-validate-partner-order` is answered 400, not 503** (`partner-order-api.camel.yaml:237-290`).
  - `try-read-partner-order` catches `java.lang.Exception` ("any failure here is a 400, never a 503", `:236`).
  - Before H1, an `Error` was not an `Exception`, and this catch never saw one. Now H1 sets an
    `UnexpectedErrorException` (a `RuntimeException`) on the exchange, so the `doCatch` takes it.
  - `PartnerOrders.cannotReadReason` (`PartnerOrders.java:52-56`) finds no `JsonValidationException` in the chain and
    returns "the request is not JSON." The partner gets a 400 Unreadable answer, and the H2 clause never runs.
  - This contradicts design §3.1 P7 and §3.4 (an `Error` in the request thread → 503 contract answer) and FR-030 (no
    order turned away because of an outage or a defect of ours). A 400 tells the partner not to resend.
  - F8 is exactly this case: every `json-validator:` step throws `NoSuchMethodError`. T7 faults only
    `to-validate-product` and `to-validate-partner-completed-order` (`UnexpectedErrorIT.java:167`), so no test sees it.
  - Recommended correction (implementation, not applied): in `catch-partner-order-unreadable`, rethrow when the chain
    holds an `UnexpectedErrorException`, or put a `doCatch` for `UnexpectedErrorException` first that rethrows. Then
    the H2 clause answers 503. Add a T7 case for `to-validate-partner-order:error:1` that expects the 503 contract
    answer.
- [WARN] **Q-2: two more existing catch-alls give an `Error` the wrong reason.** The end state is right; the reason a
  person reads is not.
  - Intake: `try-read-as-json` catches `java.lang.Exception` (`peakmart-order-intake.camel.yaml:157-167`). An `Error`
    at `set-event-type` or `set-json-object` sets the event aside as `not JSON`, not as
    `cannot be taken in: unexpected error: …` (§3.5).
  - Check: `catch-product-invalid` catches `java.lang.Exception` (`peakmart-order-check.camel.yaml:275-285`). An `Error`
    at `to-validate-product` or `set-body-found` becomes an OUTAGE answer. A Peakmart order then waits with
    `waiting_reason = catalog outage`, not `unexpected error` (§3.1 C5).
  - The execution report names the partner side of the second case (finding 8). Neither case has a Peakmart test.
  - H1's ERROR `Unexpected error` line still appears in both cases.
  - Recommended: the same rethrow of `UnexpectedErrorException` in both catches.
- [WARN] **Q-3: T9 does not show that H3, or the 9 s answer, is needed.** The developer's 7a.
  - R9 (H3 removed) leaves `DatabaseFrozenIT` green, because `spring.mvc.async.request-timeout=9s` answers the 503
    first. By the same reasoning, removing `PartnerTimeoutAnswer` with H3 in place would also stay green: H3 alone
    gives the route's own 503 within about 7 s. This is inferred, not run. So T9 guards only the pair (NFR-002), not
    either change.
  - H3 is guarded only by `ConnectionSettingsIT`, which reads the settings.
  - The effect it misses is real: without H3, the request goes on after its 503 and holds a connection for 30 s.
  - Design decision 2 says "a test that passes against the code without its change is a finding: the test is fixed
    before the change is made". T9's red was never obtained.
  - Recommended: have T9 also measure the request's own end (its log line, or the partner receipt's release) within
    about 8 s, and add a red run with only the advice removed.
- [WARN] **Q-4: nothing guards "the intake waits for the database, not handled" (F9).**
  - `DatabaseFrozenIT` freezes for 15 s (`DatabaseFrozenIT.java:85`). The intake retries for about 35 s (3
    redeliveries with 1, 2 and 4 s back-off, each attempt bounded at about 7 s by H3). So the event is written whether
    the clause is handled or not.
  - With F9 and a 60 s freeze, the developer saw the event skipped with no log line and its offset left uncommitted. A
    later record's commit would have lost it for good (NFR-007).
  - This is the most serious test gap. Recommended: a freeze longer than the retries (the developer's 60 s run: 96 s),
    as its own case or as T9's.
- [WARN] **Q-5: nothing measures BR-014 / C-010, the 20 calls/s throttle (F12).** Recommended: a 25-line order against a
  counting catalog stub, asserting at most 20 calls in any 1 s window.
- [WARN] **Q-6: nothing guards the 2 s catalog timeout (F11).** `catalog.timeout-ms` (`application.properties:33-38`)
  predates this pipeline. With 60 s, partner answers still come within the 9 s limit, while every Peakmart check is held
  for a minute per line. Recommended: a slow-catalog case that measures the Peakmart check poll (or the request's own
  duration), not the partner answer.
- [WARN] **Q-7: `fileExist: Fail` is a second guard with no test (F10)** (`pick-list-delivery.camel.yaml:153`). It
  matters only when the stat check is wrong or loses a race. Low priority. A test would need a fixture that skips the
  first guard.
- [WARN] **Q-8: failure IT reports don't say why a test failed** (developer 7e). A failing Citrus test reports
  `CitrusRuntimeException: Test failed`. The assertion message is only in the logs. Recommended: put the failed item
  into the exception, as the spec ITs do.
- [WARN] **Q-9: open deviations and residual risks from execute.**
  - H1 also checks for an `Error` when a step completes (`ErrorContainment.java:62-69,142-149`), against §3.2
    Constraints ("only what the call throws synchronously"). This is needed on 4.22.2 and recorded (P-16 finding 1),
    but the design text was not amended.
  - The cross-cutting review's Important 2 and 3 stay residual: a partner-path `UnexpectedErrorException` inside
    `order-check` or `product-lookup` matches no clause of `order-check-error-handling`. The not-handled outbox and
    intake failures go to the consumers' own handlers. Either could log a stack trace that carries the `Error`'s
    message. Checked only against the ITs' logs.
  - Two route conditions depend on string formats: the `describe()` prefix at
    `peakmart-order-intake.camel.yaml:67` and `CamelToEndpoint contains 'order-record.sql'` at
    `partner-order-api.camel.yaml:80`.
- [WARN] **Q-10: `PartnerTimeoutAnswer` matches `/orders` exactly** (`PartnerTimeoutAnswer.java:36`). A context path, a
  trailing slash or a proxy prefix gets Spring's generic 503 body. This is low risk today (no context path).

*(Quality has 15 items: 5 PASS, 1 FAIL and 9 WARN, Q-2 to Q-10.)*

### Security Analysis
- [FAIL] **S-1 (rule 1): dev passwords committed** in `config/application-dev.properties` (`test/test`,
  `admin/admin`). Carried over from 003 and 004 and accepted there (004 decision 1). Not changed in 005.
- [FAIL] **S-2 (rule 1): dev partner API key committed** in `config/dev/secrets/partner-api-key-summit-outfitters`.
  Carried over and not changed.
- [FAIL] **S-3 (rule 2): Kafka without TLS** in the default profile (`kafka.brokers=${KAFKA_BROKERS}`, no
  `securityProtocol`). Waits for OQ-038. The design's Not Doing repeats this.
- [FAIL] **S-4 (rule 2): Product Catalog over plain HTTP** (`peakmart-order-check.camel.yaml:246`). Waits for OQ-039.
- [PASS] Rule 1: no new secret. Flyway's credentials are `${spring.datasource.username/password}`
  (`application.properties:76-78`), which resolve from the environment in the default profile.
- [PASS] Rule 2: Flyway's own connection reuses `spring.datasource.url`, so it keeps `sslmode=verify-full` and the
  root certificate in the default profile (`application-default.properties:8`).
- [PASS] Rule 3, columns and log lines: `FailureKinds.describe` reads only the class name, the SQLState and the SMTP
  return code, never a message (`FailureKinds.java:76-131`). It is capped at 450 characters, so a prefixed reason fits
  `varchar(500)`. Every new log line carries ids, the class and the kind only.
- [PASS] Rule 3, stack traces: every new clause sets `logStackTrace: false`, `logNewException: false` and
  `logExhausted*: false` (the Error's message can quote data). The partner `java.lang.Exception` clause logs the class
  name only (`partner-order-api.camel.yaml:145`).
- [PASS] Rule 3, H1's lines: `Fatal error` and `Unexpected error` carry the `Error` class only, with no message and no
  trace (`ErrorContainment.java:156-169`). `UnexpectedErrorException`'s message is the class name.
- [PASS] Test-only code stays out of production: the fault hook is in the classifier jar, and the profile does nothing
  without it (developer check 4: the main jar holds no hook, kill-test or test class).
- [PASS] Rule 4 and rule 5: the ingress validation and authentication are unchanged, with one exception. Q-1 weakens
  the partner answer, not authentication: the API key check and `removeHeaders: "*"` still run first
  (`partner-order-api.camel.yaml:201-212`).
- [WARN] The residual stack-trace paths of Q-9 (cross-cutting Important 2 and 3) could put an `Error`'s message into a
  log line (rule 3). They are unconfirmed. The ITs' logs show no such line, and untested paths remain.
- [WARN] Supplemental `camel_route_harden_context` was not run in this pass. The canonical checklist analysis above is
  complete without it.

### Anti-Patterns
- [PASS] **Missing error handling: closed for `Error`.** Before 005, an `Error` left the exchange in flight with
  nothing logged (004 decision 5). Every route now gives a contained `Error` an end state, or halts on a
  `VirtualMachineError`. The exceptions are Q-1 and Q-2, where an existing catch-all takes it first.
- [WARN] No circuit breaker on the Product Catalog. This is deliberate (design Not Doing, review accepted): every
  failure is already a Catalog Outage, and the throttle bounds the load. That makes Q-5 (the throttle is untested) more
  important.
- [WARN] The mail and SFTP retries have no back-off and no end, by the spec ("until it is sent", OQ-082, OQ-085). A
  550 is retried on every poll. `last_failure` now shows why.
- [WARN] Carried over: long routes (`partner-order-submit`, `peakmart-event-read`, `product-lookup` above 10 steps) and
  inline Groovy mappings. Unchanged by 005.

### Constitution Compliance
- Rule 1 (Route Structure): PASS. No route added. Every changed route keeps its source and sink. The error clauses end
  at sql, direct or a log step.
- Rule 2 (Single Responsibility): WARN, carried over. Several routes have more than 7 steps. The new statements are
  steps of existing error clauses (design §5).
- Rule 3 (Separation of Concerns): WARN, carried over (inline Groovy mappings). The new logic is in beans:
  `failureKinds` classifies, `errorContainment` contains. Routes choose the end state.
- Rule 4 (Naming Conventions): PASS. The new node ids are kebab-case in the routes' style (`on-intake-waits`,
  `on-check-unexpected-error`, `to-order-park-unstorable`, …).
- Rule 5 (Observability): PASS. Every route and routeConfiguration has an `id` and a `description`. Every new log line
  carries the order number (or the Kafka position) and the correlation id.
- Rule 6 (External Configuration): PASS. The new values are properties: H3, the 9 s limit and
  `peakmart.set-aside-redeliveries`. No host, port or secret is in a route.
- Rule 7 (Component Verification): PASS. No new component. Binding MATCHED, and the design §5 evidence is
  version-bound.
- Rule 8 (Infrastructure via Forage): PASS. No infrastructure bean added. `errorContainment` and `failureKinds` are
  plain Java beans. The datasource is Spring Boot's own, as before.

## Recommendations

**Priority fixes (FAIL):**
1. **Q-1.** Make an `UnexpectedErrorException` pass through `catch-partner-order-unreadable`, so the H2 clause answers
   503. Add the T7 case `to-validate-partner-order:error:1` and record its red against today's code (400) before the
   fix. This is an implementation change for a follow-up execute; validation does not apply it.
2. **S-1 to S-4.** They are unchanged since 003. Re-accept them for 005, as 004 decision 1 did, or fix them:
   - S-1, S-2: take the dev secrets out of git and commit a template.
   - S-3, S-4: close OQ-038 and OQ-039.

**Suggested improvements (WARN), in order of value:**
1. **Q-4 (F9).** A database freeze longer than the intake's retries. Without it, a regression that silently loses a
   Peakmart event passes the build.
2. **Q-3 (R9 and the 9 s answer).** A T9 assertion on the request's own duration, plus a red run per change.
3. **Q-2.** The same rethrow in `try-read-as-json` and `catch-product-invalid`, so the reasons a person reads are true.
4. **Q-5 and Q-6 (F12, F11).** A counting catalog stub (throttle) and a slow-catalog case measured on the Peakmart
   poll.
5. **Q-8.** Failure-IT messages that name the failed item.
6. **Schema.** Decide on the string `maximumRedeliveries`: accept and record it, or use a literal.
7. **Q-9.** Amend design §3.2 Constraints with the completion-time check, so design and code agree. Add a test for a
   partner-path `Error` inside `order-check` or `product-lookup` that asserts no stack trace.
8. **Q-7, Q-10.** Low priority.

## Assumptions (non-interactive run)

- A-1: the developer's build, timing, artifact and fault-injection results are taken as reported. They were checked
  against `pom.xml`, the tests and the evidence files, but not rerun (user instruction: no build, no application, no
  containers).
- A-2: S-1 to S-4 and the carried-over WARNs are taken from 003/004. Their files did not change in this pipeline.
- A-3: `camel.main.run-controller` is kept as the false positive of 002 decision 4 (recommended option) and not
  counted as a new FAIL.
- A-4: The Q-3 claim that T9 also passes without `PartnerTimeoutAnswer` is inferred from the code (H3 alone answers
  within about 7 s). It was not run.
- A-5: The schema check sent the changed blocks of the six files, not every full file (see Schema Validation).
