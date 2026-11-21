---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T17:27:28.689021Z"
  by: "camel-execute"
  from: "implementation-plan.md"
---
===============================================================
IMPLEMENTATION COMPLETE
===============================================================

Pipeline: 001-peakmart-intake
Plan: docs/camel-kit/001-peakmart-intake/implementation-plan.md
Design Spec: docs/camel-kit/001-peakmart-intake/design-spec.md
Mode: standalone (non-interactive run; recommended options taken and listed under Assumptions)

Tasks Completed: [13/13]

Generated Files:
  prototype/peakmart-order-intake.camel.yaml        (Task 5; routes peakmart-order-intake, peakmart-event-read,
                                                     peakmart-event-set-aside, peakmart-order-receive; DataMapper
                                                     kaoto-datamapper-5c1e9a27)
  prototype/peakmart-order-check.camel.yaml         (Task 6; peakmart-order-check-poll, order-check, product-lookup,
                                                     order-reject, order-record)
  prototype/order-desk-notification.camel.yaml      (Task 7; order-desk-notification-send)
  prototype/db/order-db-schema-init.camel.yaml      (Task 2)
  prototype/sql/schema.sql                          (Task 2)
  prototype/sql/receipt-insert.sql, set-aside-insert.sql                        (Task 5)
  prototype/sql/waiting-select.sql, order-reject.sql, order-record.sql          (Task 6)
  prototype/sql/notification-select.sql                                         (Task 7)
  prototype/CannotReadReason.java                   (Task 3)
  prototype/OrderChecks.java                        (Task 4)
  prototype/application.properties                  (Task 8)
  prototype/run.sh                                  (Task 12)
  prototype/scenarios.sh                            (Task 13)
  prototype/stubs/product-catalog/product-catalog-stub.camel.yaml   (Task 9)
  prototype/stubs/product-catalog/application.properties            (Task 10)
  prototype/stubs/product-catalog/run.sh                            (Task 11)

Review Results:
  Adversarial Code Review: PASS_WITH_TRADEOFFS (2 cycles; cycle 1 FAIL -> fixes -> cycle 2)
  Spec Compliance: [13/13] tasks passed
  Code Quality: [13/13] tasks passed (11 non-critical issues noted; 3 Critical found and fixed, re-review PASS)

Cross-Cutting Review: PASS (after the C1-C3 fixes)

Verification: PARTIAL
  (Build/startup smoke and every acceptance scenario PASS; there is no Citrus suite, by design P-1; the
   kill-and-restart test was run in an equivalent form, see below)

===============================================================

## How the run was executed

- **Waves** (`camel-kit plan analyze`): W1 [1], W2 [2,3,4,9], W3 [5,7,10], W4 [6,11], W5 [8], W6 [12], W7 [13].
  The tasks were run in wave order.
- **Isolation.** Implementation ran inline in the orchestrator session, not in per-task implementer subagents, because the
  tasks share one runtime and needed live iteration against Kafka and PostgreSQL. The review stages ran in fresh-context
  subagents, strictly in the order ACR → spec compliance → code quality/cross-cutting. Each stage covered all 13 tasks in
  one pass instead of one pass per task. The ACR moderator applied its four critic lenses sequentially in one context,
  not as isolated critics.
- **Catalog research** (Iron Law 1). The binding `camel_catalog_components` with runtime=main and
  platformBom=org.apache.camel:camel-catalog:4.22.2 returned camelVersion 4.22.2, so the binding is MATCHED. The
  following were re-verified:
  - components: kafka (options and headers), sql (options and headers), http, json-validator, file, platform-http
    (Maven coordinates)
  - EIPs: throttle, split, script, openApi
  - languages: jsonpath, bean (method), simple (uuid function)

  The endpoint URIs were checked with `camel_validate_route`, the YAML DSL with `camel_validate_yaml_dsl`, and both
  properties files with `camel_configuration_validate` (all lines valid). The full CamelContext loaded at runtime.
- **Environment probe.** The result was PROCEED:
  - Dependency resolution: SKIPPED (main runtime).
  - Docker services: PASS. The existing `camel infra` Kafka 9092 and PostgreSQL 17.10 on 15432 were reachable and were
    not started, stopped or changed.
  - Runtime startup: PASS. One mechanical fix was needed: the guide's skeleton put `steps` at route level, and Camel
    4.22.2 requires them under `from`.

## Facts verified at implementation (plan "no guessing" list)

| Fact | Result |
|---|---|
| `PGSimpleDataSource` setters `url`, `user`, `password` | Work: the datasource connected on pgjdbc 42.7.13, the version managed by `camel-parent-4.22.2` and pinned |
| `GroupedBodyAggregationStrategy` in camel-core 4.22.2 | Exists: the split aggregated the per-line answers |
| `KafkaManualCommit#commit()` | Works: the committed group offset advanced to the log end (8/8 and 12/12) only after each record was handled |
| `JsonValidationException` error accessors | The validator is networknt json-schema-validator **2.0.1** (managed by Camel 4.22.2). `com.networknt.schema.Error#getInstanceLocation()` and `#getKeyword()` compiled under `camel run`. The instance location is a JSON Pointer (`/items/0/price_usd`, empty for the root), so `CannotReadReason` turns it into the design's `$.items[0].price_usd` / `$` form |
| jsonpath `suppressExceptions` hiding parse errors | A separate `$` jsonpath (`suppressExceptions=false`, resultType Map) is the readability test. A literal text record and a single-quoted pseudo-JSON record both landed in the catch and were set aside as `not JSON` (scenario 6) |
| Contract-first stub (`rest` + `openApi`) | Works with the spec used in place, so the fallback was not needed. The spec's relative response `$ref` cannot be resolved; this is logged at startup and is not fatal |

## Acceptance scenarios (run live against Kafka 9092 and PostgreSQL 15432; stub on 8091)

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | sample PM-50117 | **PASS** | `customer_order` PM-50117 with street line `2240 Pearl Street, Apt 4`. Lines are TENT-2P 229.00 "Ridgeline 2-Person Tent" 2150 g and STAKE-AL8 12.50 "Aluminium Tent Stakes 8-Pack" 120 g. History is Received, Recorded |
| 2 | PM-50117 redelivered with a new event id | **PASS** | No new rows. INFO `Duplicate Peakmart order skipped orderNumber=PM-50117 eventId=c3a8f1d2-…` |
| 3 | PM-50118 inactive SKU | **PASS** | Receipt Rejected with 1 reason (SKU, line 1, the text of `pm-50118.json`). The notification was sent (attempts=1). File `peakmart-order-PM-50118-rejected.txt` has subject `Peakmart order PM-50118 rejected` and contains no name, email or address |
| 4 | order.cancelled | **PASS** | Skipped: no receipt and no set-aside row |
| 5 | PM-50119 email is not an email | **PASS** | Set aside with reason `$.buyer.email: format` and no receipt. The WARN log has no email |
| 6 | price-as-number / event-id-missing / non-JSON text | **PASS** | `$.items[0].price_usd: type` (under event id 8d2f6c1e-…); `$: required` (under `kafka-0-6`); `not JSON` (under `kafka-0-7`). The validator message was never used |
| 7 | Catalog outage (stub stopped) | **PASS** | PM-50120 stayed Received/waiting_for=Check with no reasons. It was recorded after the stub started |
| 8 | Stub `invalid` and `slow` modes | **PASS** | The order kept waiting in both modes. In slow mode each lookup gave up after about 2 s (the timeout) |
| 9 | Structural failure (qty 21, country DE) with the stub stopped | **PASS** | PM-50121 was not rejected while the stub was down (BR-012). After the stub came back it was Rejected with reasons in check order: 1 Quantity (line 1), 2 Destination Country |
| 10 | Integration restart between 7 and the stub restart | **PASS** | After the restart the waiting orders were checked again from the database, no topic record was consumed again, and both orders reached their outcome |
| — | Intake kill-and-restart | **PASS (equivalent form)** | See below |
| — | Regression after the final fixes | **PASS** | Scenarios 1-6 were run again on a clean database and topic after the C1-C3 fixes, with the same results |

**Intake kill-and-restart (equivalent form).** `kill` was not allowed in this sandbox, and a graceful stop always
finishes the in-flight commit. So the crash window "receipt written, offset not committed" was reproduced
deterministically:
1. The integration was stopped.
2. The consumer group was rewound to offset 0 with `kafka-consumer-groups --reset-offsets --to-offset 0`.
3. The integration was restarted.

Every event was consumed again. Each received order was skipped as a **Duplicate**, the set-aside inserts were no-ops,
and the row counts were unchanged (receipts 4, history 8, orders 2, lines 3, reasons 3, notifications 2). The only new
row was one new set-aside record. The group was committed at 12/12. A real SIGKILL between the insert and the commit
has **not** been run; it is still open for the developer.

## Defects found and fixed during execution (all corroborated live before fixing)

1. **Duplicate node ids across files** (startup failure): renamed with per-flow prefixes.
2. **The `+` in `onConsume`/`onConsumeFailed` was URI-decoded to a space**, so `sent_at` was never set and the
   notification file was rewritten on every poll. Both queries are now wrapped in `RAW(...)`.
3. **networknt 2.0.1 reports JSON-Pointer paths, and an empty path for the root.** A missing `event_id` came out as
   `unreadable`; the path is now normalised to `$`-form.
4. **ACR F1 (Critical): poison record.** An `order.placed` event whose `order_ref` contains U+0000 made the receipt
   insert fail. Because the onException is not handled and `breakOnFirstError` is on, the consumer was sought back
   forever and the topic was blocked (confirmed live). Fix: an `order_ref` or `event_id` longer than its column or
   holding control characters is now set aside (`$.order_ref: maxLength|pattern`, `$.event_id: maxLength|pattern`).
   U+0000 in the stored raw content is escaped as `\u0000`. This also closes ACR F4 (CR/LF injection into logs and the
   notification Subject line).
5. **ACR F3: notification file names.** An unsafe Order Number now uses `peakmart-order-unsafe-<notification_id>-rejected.txt`.
   **ACR F6:** `.` in a SKU is encoded as `%2E`.
6. **Quality C1 (Critical, checklist rule 1):** `db.password` had a literal fallback. It is now `{{env:DB_PASSWORD}}`
   only, and `run.sh` fails fast when it is unset.
7. **Quality C2 (Critical, rule 3):** `camel.main.logMask=true` and `additionalSensitiveKeywords=email,phone,ssn,creditCard`.
8. **Quality C3 (Critical, rule 4):** a per-record size limit before the first parser (`peakmart.max-record-chars=1048576`).
   A larger record is set aside as `too large`, keyed `kafka-<p>-<o>`, with its content truncated to the limit. Verified
   live with the limit lowered to 2000 for that run only.

## Deviations from the plan text (all reviewed by spec compliance and judged justified)

- **HTTP timeouts.** In camel-http 4.22.2, `connectTimeout`, `responseTimeout` and `connectionRequestTimeout` are
  component options only (catalog), so they are set as `camel.component.http.*={{catalog.timeout-ms}}`. The endpoint uri
  is `{{catalog.base-url}}`: the plan's literal `http:{{catalog.base-url}}` would resolve to `http:http://…`.
- **Route-scoped `onException`** is expressed with YAML `routeConfiguration` plus `routeConfigurationId`.
- **The DataMapper** is a `transform` with id `kaoto-datamapper-5c1e9a27`, per the shipped datamapper-groovy guide, where
  the plan said `setBody`.
- **`run.sh`** also passes `OrderChecks.java` and `CannotReadReason.java` to the schema-init run, because
  `application.properties` declares their `#class` beans.
- **SQL bindings** use camel-sql `:#${exchangeProperty…}` and `:#${body}` simple bindings. `:#reasons` stays a plain
  named parameter. `order_check` kebab-case values are mapped to the entity-model names. Positions come from
  `ROWS FROM (jsonb_to_recordset(…)) WITH ORDINALITY`.
- **`OrderChecks`** has two extra helpers, `productPath` (URL-encoding) and `answer` (the per-line catalog answer).
- **New set-aside reasons** not listed in the design: `$.order_ref: maxLength|pattern`, `$.event_id: maxLength|pattern`
  and `too large`. Raw U+0000 is escaped in `event_content`. **The design should be amended** to record these.

## Trade-offs and open points for the user

- **ACR F2 (accepted as a trade-off; needs a business decision).** Some values pass every Order Check but do not fit
  their Order Database column, so they fail only at record time: a name over 100 characters, a price of 100000000.00 or
  more, a catalog name over 200, an escaped U+0000. Such an order is handled as the design says (WARN, retry on the next
  poll), so it waits forever and the Order Desk is never told. Once 50 such orders exist (`order-check.batch-size`),
  oldest-first polling stops any newer order from being checked. Options: add data-limit Rejection Reasons (the texts
  are needed), or move such receipts to `waiting_for='Recording'` and make the poll fair.
- **ACR F5:** catalog lookups are not cut short after an OUTAGE answer or beyond 30 lines.
- **Design inconsistency.** §3 route 1 says the skip is logged at DEBUG with `kafka-position` only, but §4 Monitoring
  lists "skip" among the INFO/WARN decision points with a correlation id. The implementation follows §3. Please decide.
- **Accepted trade-offs:**
  - single instance, with no `FOR UPDATE SKIP LOCKED`
  - a file stands in for SMTP
  - the HTTP timeouts apply to every `http` endpoint, and there is only one
  - `${uuid}` uses Camel's default generator (a random prefix per JVM plus a counter); the 4.22.2 `uuid(type)` function
    offers no Java random UUID
  - the WARN "set aside" line is also logged when a redelivered event is already set aside, because `ON CONFLICT`
    makes the insert a no-op
- **Non-critical quality notes (not changed):**
  - I1: the catch lists are broad (the intake one is as the design specifies)
  - I2: see the design inconsistency above
  - I3: the notification route uses the default error handler
  - I4: the control-character/length check is repeated in Groovy and could become a bean method
  - S1: the DataMapper id sits on a Groovy transform
  - S2: "rejected"/"recorded" are logged even when the guard updated 0 rows
  - S3: `canConvertToInt` (moot, because the schema requires an integer)
  - S4: notification `attempts` could exceed its CHECK after 1000 failures; there is no `maxMessagesPerPoll`
  - S5: in the stub, the host is not limited to localhost and the 3 s delay is hardcoded
  - S6: in `scenarios.sh`, the outbox path and `-U test` are hardcoded
  - S7: `length()` counts UTF-16 units, not code points

## Assumptions taken (non-interactive)

- The ACR F2 trade-off was accepted rather than escalated, because fixing it needs Rejection Reason texts or a
  state-model change that only the business can approve.
- `DB_PASSWORD` is now required. For the local `camel infra` PostgreSQL, run `DB_PASSWORD=test ./run.sh`. In this
  sandbox, setting environment variables needed approval, so the final regression ran run.sh's two `jbang` commands with
  `--property=db.password=test`. The script's fail-fast check itself was verified.
- The test data written to the `camel infra` Kafka topic `peakmart.orders.v2` and the PostgreSQL tables was left in place
  for inspection. No container was started, stopped or changed. Ports 8080, 8081 and 5432 were not used.

## Verification report (Step 3.5)

```text
VERIFICATION
Phase 1 - Camel Main startup smoke:  PASS (schema init + all 10 integration routes; stub on 8091)
Phase 2 - Citrus tests:              NOT_RUN (no Citrus suite by approved design, plan P-1)
Phase 3 - Runtime acceptance:        PASS (scenarios 1-10, crash-window replay, size guard, regression)
Result: PARTIAL - the Citrus phase is intentionally empty; the real SIGKILL test is still open
```

All integrations and stubs started in this session were stopped at the end (`camel ps` is empty, and 8091 no longer
answers). Run logs, the outbox and the `.camel-jbang` state were removed from `prototype/`. Not removed, because
deleting them needed approval: `.claude/skills/.camel-jbang/camel-jbang-run.properties` (JBang run state from the
probe) and `/tmp/camel-kit-probe-001/` (the probe skeleton).

Standalone mode: `/camel-validate` was not auto-invoked. Run it next for Phase 4.
