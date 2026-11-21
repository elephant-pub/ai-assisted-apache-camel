---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T18:01:52Z"
  by: "camel-validate"
  from: "execution-report.md"
---
# Validation Report

**Date:** 2026-11-13 12:01
**Project:** trailhead-orders
**Runtime:** main
**Camel Version:** 4.22.2
**Pipeline:** 001-peakmart-intake (standalone, pipeline-scoped; non-interactive). **Second validation.** It replaces the
report of 2026-11-13 11:45.
**Catalog binding:** runtime=main, platformBom=org.apache.camel:camel-catalog:4.22.2. Every MCP response for this run
reported camelVersion 4.22.2 (MATCHED).

**Inputs (provenance checked with `camel-kit doc check`, all `stale: false`):** design-spec.md, implementation-plan.md
(from design-spec.md), execution-report.md (from implementation-plan.md). `validation-decisions.md` has no generated
frontmatter. It is read as the integration architect's record of decisions on the first report, not as a pipeline
artifact.

**Scope.**
- Route files (main runtime, module prefix `prototype/`): peakmart-order-intake, peakmart-order-check,
  order-desk-notification, db/order-db-schema-init, and the stub stubs/product-catalog/product-catalog-stub.
  `.camel.yaml` is the extension for all five.
- Properties files: prototype/application.properties, and prototype/stubs/product-catalog/application.properties for
  the stub.
- **Changed since the first report** (commit 2b60fb4):
  - `peakmart-order-intake.camel.yaml`: new step `delay-before-commit`
  - `application.properties`: new key `peakmart.test.commit-delay-ms`
  - stub `application.properties`: `camel.server.host=127.0.0.1`
  - `kill-test.sh` and `kill-test-results.md`: rewritten
- The other route, SQL and Java files are unchanged. Their findings from the first report are carried over and marked
  *(carried over)*.
- Kill-test evidence read: `prototype/target/kill-test/{114703,115014,115232,115441}/` and `prototype/target/stub.log`.
- Nothing was started, stopped or changed. No integration was run, no container was touched, and no route or
  configuration file was modified. Stage 3 (`camel run --check`) was not run, as instructed.

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | 2 | 0 | 3 | 5 |
| Endpoint Verification | 6 | 0 | 3 | 9 |
| Quality Checks (incl. kill test) | 9 | 0 | 8 | 17 |
| Security Analysis | 5 | 1 | 0 | 6 |
| Anti-Patterns | 3 | 0 | 3 | 6 |
| Constitution Compliance | 7 | 0 | 1 | 8 |
| **Total** | **32** | **1** | **18** | **51** |

**Result: FAIL (1 blocking item), down from 3. The architect has accepted that item for the local prototype only.**

| First-report FAIL | Now | Evidence |
|---|---|---|
| Kill-and-restart test not achieved | **PASS** | 2 runs killed in the gap, and the uncommitted order was skipped as a Duplicate after the restart (logs below) |
| Rule 5: stub unauthenticated on 0.0.0.0 | **PASS** | `Vert.x HttpServer started on 127.0.0.1:8091` (target/stub.log:82). The stub is no longer externally exposed. |
| Rule 2: Kafka and PostgreSQL in plain text | **FAIL (accepted)** | Configuration unchanged. Decision 2 accepts this for the prototype. The checklist has no waiver, so this report keeps it as FAIL. |

## Findings

### Kill test (prototype/kill-test.sh, prototype/kill-test-results.md)

How it works now: the integration's first start gets `COMMIT_DELAY_MS=500`, which holds each commit 500 ms after its
receipt insert. The script kills once `KILL_AT` receipts exist. It compares the receipts at the kill with the
committed-offset delta (`gap`), restarts with a 0 ms delay, waits until all N are Recorded, and then asserts four
checks. It exits 1 on a failed check and 2 when the gap was 0.

- [PASS] **The kill landed between the receipt insert and the commit, and the order was skipped as a Duplicate after
  the restart (design §3).** The logs corroborate both passing runs:
  - Run 115014:
    - 1.log: received PM-1150140000 to PM-1150140010 (11 orders), then `Killed: 9` (line 262).
    - 2.log:246: `Duplicate Peakmart order skipped orderNumber=PM-1150140010 eventId=5443b40c-…`.
  - Run 115441:
    - 1.log: 26 orders received, the last PM-1154410025, then `Killed: 9` (line 293).
    - 2.log:245: `Duplicate … orderNumber=PM-1154410025 eventId=063a1e10-…`.
  - Both event IDs appear in that run's `events.txt`. The skipped record is the same Kafka record consumed again, not
    a new event.
  - The spacing between receipts in each run's 1.log (~535 ms against ~50–300 ms in 2.log) corroborates that the
    500 ms hold was active on the first start only.
- [PASS] **No loss and no double recording.** Across the three reported runs, the logs have 150 distinct
  `Peakmart order recorded` order numbers and 0 recorded twice:

  | Run | Recorded in 1.log | Recorded in 2.log |
  |---|---|---|
  | 115014 | 8 | 42 |
  | 115232 | 4 | 46 |
  | 115441 | 24 | 26 |

  The received counts also add up to 50 per run. Run 115232 (gap 0) is correctly reported as inconclusive.
- [PASS] **The script asserts its own result.** `check` sets `failed`, and the script exits 1 on a mismatch and 2 when
  the kill missed the gap. This fixes the first report's "no assertions" finding.
- [PASS] **Isolation and robustness fixes from the first report:**
  - Order numbers are unique per run (`PM-<HHMMSS><4 digits>`) and matched with an anchored regex
    (`~ '^PM-…[0-9]{4}$'`).
  - Every wait has a timeout (120/120/120/300 s) and polls with `sleep 1`.
  - A `trap cleanup EXIT` stops the integration.
  - Logs and events are kept per run in `target/kill-test/<run>/`.
- [WARN] **`started()` still matches the schema-init context, not the integration.** Both `run.sh` steps load
  `application.properties`, so both contexts are named `peakmart-intake-prototype`.
  - The new pattern `(peakmart-intake-prototype) started` therefore matches the one-shot schema init first. In run
    115014, 1.log:32 is pid 22700 (schema init) at 11:50:27, while the integration (pid 23132) started at 11:50:38.
  - Effect: the events are published before the integration is up, and the restart wait returns early. Both are
    harmless here: Kafka keeps the records, and `all_recorded` does the real wait.
  - The first report's finding is therefore not fixed. Match `Started peakmart-order-intake (kafka://` instead.
- [WARN] **One run is missing from the results file.** In run 114703 (11:47) the integration received and recorded all
  50 orders and was never killed.
  - It was stopped gracefully at 11:49:27 (Kafka consumer stopping, routes stopped). That fits the 120 s
    `wait_for … receipts` timeout followed by the `trap` cleanup.
  - That run has no 2.log. The likely cause is a script defect that was fixed afterwards.
  - kill-test-results.md lists only the three later runs. List every run, including failed ones.
- [WARN] **The committed script is not the version that ran.** kill-test.sh was modified at 11:56:57, which is after
  the last run's 2.log (115441, 11:56:46), and was committed at 11:57:11. No run is recorded with the committed version.
  Re-run it once and record the run ID.
- [WARN] **The script's stdout is not kept.** The `ok`/`FAIL` lines, the committed offset and the database checks
  (orders recorded, history entries = 2N) go to the terminal only.
  - The results table's "Committed" and "In the gap" columns, and the 100 history entries per run, cannot be
    reproduced from files.
  - The logs corroborate the received, recorded and Duplicate counts, but not the offsets or the history rows.
  - Fix: `exec > >(tee "$LOGS/result.txt") 2>&1`.
- [WARN] **`committed()` parsing is fragile.**
  - It assumes a single partition. With more than one, it prints one line per partition and the arithmetic breaks.
  - Empty output (for example while the group rebalances after the kill) becomes 0 in `$((offset - before))` and
    yields a wrong `gap`.
  - Fix: guard against empty or multi-line output, and fail the run when it happens.

### Schema Validation

Tool: `camel_validate_yaml_dsl`.

- [PASS] prototype/db/order-db-schema-init.camel.yaml: full file valid *(carried over)*.
- [PASS] prototype/order-desk-notification.camel.yaml: full file valid *(carried over)*.
- [WARN] prototype/peakmart-order-intake.camel.yaml was not submitted as a full file.
  - A structural excerpt was submitted: the route's `from` with all Kafka parameters, the `choice`, the new
    `delay-before-commit` step and `commit-kafka-offset`. It is valid with 0 errors.
  - `delay` options match the 4.22.2 EIP doc: `expression` is required, and `asyncDelayed` is a boolean that defaults
    to `true`, so the explicit `false` is needed for the commit to stay on the poll thread.
  - Runtime evidence for the edited file (modified at 11:45:36): all 4 intake routes started in every
    `kill-test/*/2.log`, for example 115014/2.log:87–90.
- [WARN] prototype/peakmart-order-check.camel.yaml: the full file was not re-submitted in one piece *(carried over;
  file unchanged)*. Runtime evidence: all its routes start in the kill-test logs.
- [WARN] prototype/stubs/product-catalog/product-catalog-stub.camel.yaml: only an excerpt was submitted *(carried
  over; file unchanged)*. Runtime evidence: target/stub.log:91.

### Endpoint Verification

No endpoint URI changed. The new step is an EIP, not an endpoint. *(All carried over.)*

- [PASS] `sql:file:sql/notification-select.sql?…`
- [PASS] `file:target/outbox/order-desk?…`
- [PASS] `timer:order-db-schema-init?repeatCount=1`
- [PASS] `sql:file:sql/schema.sql?dataSource=#orderDb`
- [PASS] `direct:getProduct` (stub)
- [PASS] Catalog availability: all 10 components are available, Stable and not deprecated in 4.22.2.
- [WARN] `kafka:peakmart.orders.v2?…` was validated against an abridged route.
- [WARN] `http://localhost:8091?…` was validated against an abridged route.
- [WARN] 15 `direct:`/`sql:file:`/`json-validator:file:` URIs were not individually submitted. They have runtime
  evidence only.

### Quality Checks

- [PASS] Completeness: all routes have an `id`, a `from` and a description of 10 or more characters. The new step has
  id `delay-before-commit`.
- [PASS] Expressions: the new `constant` expression resolves `{{peakmart.test.commit-delay-ms}}`, and the key is
  defined in application.properties.
- [PASS] 7.1 Property keys (`camel_configuration_validate`, 4.22.2):
  - The stub's 4 server/main keys are all valid with 0 errors.
  - `camel.server.host` is reported `accepted: false` like the other `camel.server.*` keys (the same result as the
    first report), but the runtime proves it is honoured: `Vert.x HttpServer started on 127.0.0.1:8091`.
  - `peakmart.test.commit-delay-ms` is an application key, not a catalog key.
- [PASS] 7.2 Placeholders:
  - The new value is `{{env:COMMIT_DELAY_MS:0}}`, a Camel placeholder with a default. There is no `${…}`.
  - The logs show the key listed unresolved at startup (`peakmart.test.commit-delay-ms = {{env:COMMIT_DELAY_MS:0}}`),
    which is how camel-main prints the raw value. The timing evidence shows that it resolved.
- [PASS] 7.3 Beans: unchanged *(carried over)*.
- [WARN] **A test-only hook is in the production route, and the design does not include it.**
  - Design §3 and the execution report do not list `delay-before-commit`. The route was hand-edited after execution
    (execution report 17:27Z; route edited 11:45 local, 17:45Z).
  - With the default of 0 it has no effect. But any environment that sets `COMMIT_DELAY_MS` slows every commit.
  - A non-numeric value would make the delay fail on every record, so no offset would be committed and, with
    `breakOnFirstError=true`, the same record would be retried indefinitely.
  - Record the hook in the design (§3, Offset commit) or keep it out of the Spring Boot project. If it stays, give the
    variable a test-specific name and validate it as a non-negative integer.
- [WARN] Error handling: `order-desk-notification-send`, `order-db-schema-init` and the stub route use the default
  error handler *(carried over)*.
- [WARN] 7.4 Forage: there is no Forage cache, so the ladder checks were skipped. The required-bean check passes
  *(carried over)*.
- Kill test: the 9 findings above (4 PASS, 5 WARN) are counted in this category.

### Security Analysis

- [PASS] Rule 1, no hardcoded credentials: `db.password={{env:DB_PASSWORD}}` and no secret in the routes. The new key
  holds no secret.
- [FAIL] Rule 2, TLS everywhere. The configuration is unchanged:
  - `kafka.brokers=localhost:9092` has no SSL/SASL_SSL security protocol.
  - `db.url=jdbc:postgresql://localhost:15432/postgres` has no `ssl=true&sslmode=verify-full`.

  The checklist's localhost exemption covers HTTP only, and the checklist maps every confirmed violation to FAIL and
  provides no waiver.

  **Architect decision 2 (validation-decisions.md):** accepted for the prototype only, because the local `camel infra`
  services have no TLS. The cited references check out:
  - C-024 (docs/requirements.md:108, status Open) requires encrypted connections to the order database, among others.
  - OQ-038 (docs/open_questions.md:548) leaves Peakmart's broker settings open.

  Note that C-024 does not name Kafka. The TLS requirement for the broker therefore rests on this checklist and OQ-038,
  not on C-024. This report records the decision and does not change the result.
- [PASS] Rule 3, no sensitive data in logs: unchanged *(carried over)*. The kill-test logs show order numbers,
  event IDs and correlation IDs only.
- [PASS] Rule 4, input validation at ingress: unchanged *(carried over)*. The delay step comes after validation and
  reads only configuration.
- [PASS] Rule 5, authentication on external endpoints:
  - The stub binds to 127.0.0.1 only (stub properties line 6; target/stub.log:11 and :82), so it is no longer
    externally exposed. The integration exposes no HTTP endpoint.
  - Decision 3's reference checks out: `docs/api/external/product-catalog.openapi.yaml:21` is `security: []`.
- [PASS] Supplemental MCP evidence: the first report's `camel_route_harden_context` items were not corroborated
  *(carried over)*. MCP was not re-run for the one-step change.

### Anti-Patterns

*(All carried over; the files involved are unchanged.)*

- [PASS] Idempotency: receipts use `ON CONFLICT … DO NOTHING`. The kill test now shows a redelivery deduplicated.
- [PASS] Correlation IDs are carried through.
- [PASS] There are no hardcoded endpoints.
- [WARN] There is no circuit breaker on the Product Catalog (ACR F5).
- [WARN] Poison-in-waiting (ACR F2).
- [WARN] `PGSimpleDataSource` has no pooling.

### Constitution Compliance

- Rule 1 (Route Structure): PASS. `commit-kafka-offset` is still the last step on every path.
- Rule 2 (Single Responsibility): PASS. `peakmart-order-intake` has 7 top-level steps after the addition, which is
  within the limit.
- Rule 3 (Separation of Concerns): WARN *(carried over)*. There is inline Groovy for the DataMapper, guards and keys.
- Rule 4 (Naming Conventions): PASS. `delay-before-commit` and `peakmart.test.commit-delay-ms` follow the conventions.
- Rule 5 (Observability): PASS.
- Rule 6 (External Configuration): PASS. The delay value comes from a property.
- Rule 7 (Component Verification): PASS. The `delay` EIP was verified in the 4.22.2 catalog.
- Rule 8 (Infrastructure via Forage): PASS *(carried over)*.

## Recommendations

Validation does not change any file. These are for implementation handling or the developer.

**Priority (FAIL):**
1. **Rule 2 (TLS):** accepted by the architect for the local prototype. Before any non-local use, and in the Spring
   Boot project:
   - Set Kafka `securityProtocol=SSL` or `SASL_SSL`, with the truststore from `{{env:…}}`, once OQ-038 is answered.
   - Add `?ssl=true&sslmode=verify-full` to the database URL (C-024).

   The checklist has no waiver, so this item stays FAIL on every validation of this prototype.

**Suggested (WARN):**
- kill-test.sh:
  - Match `Started peakmart-order-intake (kafka://` in `started()`.
  - Tee the stdout to `$LOGS/result.txt`.
  - Guard `committed()` against empty or multi-partition output.
  - Re-run the committed version once and add every run to kill-test-results.md, including the failed run 114703.
- Record the commit-delay hook in design §3, or keep it out of the Spring Boot project. If it stays, give it a
  test-specific variable name and validate it as an integer.
- Carried over:
  - Submit the full intake, check and stub files to the schema validator.
  - Add explicit error handling to `order-desk-notification-send`.
  - Decide ACR F2 and ACR F5.
  - Move the repeated Groovy guard into `OrderChecks`.
  - Use a pooled DataSource before load tests.

## Assumptions (non-interactive)

- **Pipeline:** the ID came from the invocation and matches `activePipeline`. All inputs were current, so no
  stale-revision decision was needed.
- **Architect decisions:** read as recorded decisions. They do not change checklist severities. Rule 2 stays FAIL, with
  the acceptance noted, because the checklist says confirmed violations are FAIL and provides no waiver. This is the
  recommended option: apply the checklist without narrowing it.
- **Rule 5:** taken to apply only to externally exposed endpoints, as worded. A loopback-only test double is not
  externally exposed, so it passes.
- **Kill test:** classified as PASS because two runs show the design's exact sequence in retained logs (kill in the
  gap, then a Duplicate skip of the same event ID after the restart). The values that come only from stdout
  (committed offsets, history rows) are reported as unverified, not as failures.
- **Unchanged files:** their findings are carried over from the first report rather than re-derived. Their content is
  unchanged since that report (commit 2b60fb4 touched only the files listed under Scope).
- **Stage 3:** not run (instruction: do not start the integration). Runtime-load evidence comes from the existing
  logs.
