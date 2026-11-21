---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T19:33:07.013754Z"
  by: "camel-execute"
  from: "implementation-plan.md"
---
# Execution Report — 002-spring-boot-move

```
===============================================================
IMPLEMENTATION COMPLETE
===============================================================

Pipeline: 002-spring-boot-move
Plan: docs/camel-kit/002-spring-boot-move/implementation-plan.md
Design Spec: docs/camel-kit/002-spring-boot-move/design-spec.md

Tasks Completed: 14/14

Review Results:
  Spec Compliance: 14/14 tasks passed
  Code Quality: 14/14 tasks passed (non-critical issues noted below)

Cross-Cutting Review: PASS (0 Critical, 2 Important, 6 Suggestions)

Verification: PARTIAL
===============================================================
```

Mode: standalone (`/camel-execute 002-spring-boot-move`). The run was non-interactive. Where the workflow asks the user,
the recommended option was taken; those choices are listed under *Assumptions*. Provenance: `doc check` on both inputs
reported them as not stale. The plan is `generated.by camel-plan`, `from design-spec.md`.

## Generated / changed files

| Task | File | Change |
|---|---|---|
| 1 | `pom.xml` | E10: removed `camel-file-starter`, `camel-timer-starter`; added `camel-mail-starter`, `spring-boot-starter-flyway`, `flyway-database-postgresql`; `postgresql` without version. E6: `maven-resources-plugin` execution `copy-message-schemas` |
| 2 | `src/main/resources/sql/{receipt-insert,set-aside-insert,waiting-select,order-reject,order-record,notification-select}.sql` | byte-identical copies (`cmp`) |
| 3 | `src/main/resources/db/migration/V1__order_database.sql` | Flyway V1. No `DO $$` and no `IF NOT EXISTS`; otherwise the same as `prototype/sql/schema.sql` |
| 4 | `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java` | `@Bean("orderChecks")`, `@Bean("cannotReadReason")` |
| 5 | `src/main/resources/camel/peakmart-order-intake.camel.yaml` | D5 (delay removed; `commit-kafka-offset` kept, still the last step), classpath endpoints, `#dataSource` |
| 6 | `src/main/resources/camel/peakmart-order-check.camel.yaml` | classpath endpoints, `#dataSource`, `http://{{catalog.host}}:{{catalog.port}}` |
| 7 | `src/main/resources/camel/order-desk-notification.camel.yaml` | SMTP sink, D4 `LEAST(attempts + 1, 1000)`, new `order-desk-notification-error-handling` (not handled) |
| 8 | `src/main/resources/application.properties`, `application-default.properties` (13 keys), `application-dev.properties` (11 keys) | profiles; E1/E2/E7/E8/E9 |
| 9 | `src/test/java/com/trailheadgear/orderintegration/EmailPatternTest.java` | E7 regression test |
| 10 | `src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java`, `KillTestHook.java` | kill-test hook (test classpath, profile `kill-test`) |
| 11 | `src/test/scripts/kill-test.sh` | kill-test script |
| 12 | `src/test/scripts/scenarios.sh` | scenarios 1–6 against the Spring Boot app, scenario 3 checked in Mailpit |
| 13 | `prototype/run.sh` | one guard line (line 2) |
| 14 | `docs/open_questions.md` | OQ-076 appended |

## Recorded facts (plan Task 1, design §3)

- Flyway (Boot 4.1.1 BOM): `org.springframework.boot:spring-boot-starter-flyway:4.1.1`,
  `org.flywaydb:flyway-database-postgresql:12.4.0`, which brings in `flyway-core:12.4.0`.
- `org.postgresql:postgresql` resolves to **42.7.13**. The Boot 4.1.1 BOM manages 42.7.13 itself, so the version is
  unchanged.
- Other resolved versions: HikariCP 7.0.2; Jackson 2 `com.fasterxml.jackson.core:jackson-databind` **2.21.5**;
  `com.networknt:json-schema-validator` **2.0.1**; angus-mail 2.0.5. `CannotReadReason` compiles against them, and
  scenarios 5/6 prove the accessors at run time.
- Catalog binding: `camel_catalog_components(runtime=spring-boot, platformBom=…camel-catalog-provider-springboot:4.22.2)`
  returned `camelVersion=4.22.2`.
  - `smtp` → `org.apache.camel.springboot:camel-mail-starter:4.22.2`.
  - kafka/sql/http/json-validator/direct/bean starters confirmed.
  - `camel_dependency_check`: healthy, 0 issues.
- **Trade-off (Task 1, accepted by ACR and the spec review):** the Step 9 check "dependency:tree does not contain
  `camel-file-starter`/`camel-timer-starter`" is not met as written. Both still arrive **transitively** through
  `camel-spring-boot-starter` 4.22.2, whose pom declares them. The direct declarations E10 names are gone. No exclusions
  were added, because the design says everything else in the pom stays as exported. No route uses `file:` or `timer:`.
- `camel.main.run-controller`: `camel_configuration_validate` (spring-boot) flags it as unknown. However,
  `camel_properties_translate` keeps it unchanged, `META-INF/spring-configuration-metadata.json` of
  `camel-spring-boot-4.22.2.jar` lists `camel.main.run-controller`, and at startup the `inRunController` thread starts
  (`Apache Camel (Main) 4.22.2 is starting`). The key binds. The validator result is a false positive.
- `camel.main.name` takes effect: `Apache Camel 4.22.2 (trailhead-order-integration) is starting`.
- InterceptStrategy registration: Camel Spring Boot picks up the `InterceptStrategy` bean from the context. The
  `CamelContextConfiguration` fallback of Task 10 Step 3 was not needed.
- Route-started marker in Spring Boot: `    Started peakmart-order-intake (kafka://peakmart.orders.v2)`, which matches
  `Started peakmart-order-intake`.
- `spring-boot:test-run` (plugin 4.1.1) user properties, from `help:describe`: `spring-boot.run.main-class`,
  `spring-boot.run.profiles`, `spring-boot.run.arguments`.

## Review summary per task

ACR ran before spec review, and spec review before quality review, for every task. The Moderator and the critic
lenses ran **one after another inside a single reviewer subagent per wave**, not in separate fresh contexts, so
isolation was reduced.

| Task | ACR | Spec | Quality | Notes |
|---|---|---|---|---|
| 1 | PASS_WITH_TRADEOFFS | PASS | PASS | transitive file/timer starters (above) |
| 2, 3, 13, 14 | PASS | PASS | PASS | Task 13 reviewed statically (see Verification) |
| 4 | PASS | PASS | PASS | |
| 7 | PASS_WITH_TRADEOFFS (B-6) | PASS | PASS | Suggestions not applied, because they are outside the design (Iron Law 6): `logStackTrace: false`, `maxMessagesPerPoll`; the route has 8 steps (constitution rule 2 WARNING, every step required by the design) |
| 5, 6 | PASS | PASS | PASS | `camel_validate_yaml_dsl` reported "required property 'from' not found at /4/route" on the full check file, although every route validates on its own. Runtime startup loads all 10 routes, so this was treated as a validator artefact |
| 8 | PASS (Important escalation, see refusal test) | PASS | PASS | |
| 9, 10 | PASS | PASS | PASS | |
| 12 | FAIL → fixed → PASS (2 cycles) | PASS | PASS | Fixed a Critical: the scenario 4 query collided with the example files' shared event id and PM-50117. Also fixed: a rerun guard and the Mailpit transient-failure handling |
| 11 | FAIL → fixed → PASS (2 cycles) | PASS | PASS | Fixed two Criticals: the pgrep pattern also matched Maven's JVM, and the `sh -c` wait matched itself |

Cross-cutting review (code-quality-reviewer, all files): **PASS**.
- Important 1: answered (run-controller binds, see above).
- Important 2: an exception type outside each routeConfiguration's `onException` falls back to the default error
  handler, which logs message history. This behaviour comes unchanged from the prototype routes and is outside this
  pipeline's scope, so it was noted, not changed.
- Suggestions (not applied, outside scope):
  - correlationId on two DEBUG lines;
  - routeConfiguration id naming;
  - a redundant catch type;
  - `logServerErrorDetail=false` on the JDBC URLs;
  - an explicit Jackson 2 dependency.

## Verification (Step 3.5)

The services were `camel infra` Kafka 9092, PostgreSQL 15432 (empty at start), Mailpit 1025/8025, and the stub on
8091. No container was started, stopped or otherwise touched.

**Session limitation:** this session's permission policy does not allow running shell scripts (`bash <script>`),
`kill`, `lsof`/`netstat`, or commands that set environment variables. So `scenarios.sh` and `kill-test.sh` were
syntax-checked (`bash -n`) but **not executed**. Their steps were run by hand as individual commands, as described
below. The refusal test passed values as `--NAME=value` command-line arguments instead of environment variables.
Spring resolves `${NAME}` the same way from either source.

| # | Acceptance check | Result | Evidence |
|---|---|---|---|
| 0 | Environment probe (skeleton with the planned dependency set; no DB/Flyway) | PASS | Dependencies resolve; Spring Boot + Camel start (`Started ProbeApp in 2.789 s`). One mechanical fix: the probe template's `steps` belongs under `from` |
| 1 | `./mvnw clean verify`, with `EmailPatternTest` run | **PASS** | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in …EmailPatternTest`; `BUILD SUCCESS` |
| 2 | `java -jar target/order-integration-0.1.0.jar --spring.profiles.active=dev` | **PASS** | See the four sub-checks below |
| 3 | Refusal test (no profile; 15 runs) | **PARTIAL — see below** | |
| 4 | Scenarios 1–6 (manual execution of the `scenarios.sh` steps) | **PASS** | See the per-scenario rows below |
| 4a | No SMTP `AUTH` against Mailpit in `dev` | NOT OBSERVED | dev sets no `camel.component.smtp.username`, and Mailpit accepted the messages. The SMTP dialogue was not inspected, because Mailpit's container logs were not read (containers were not to be touched) |
| 5 | Scenarios 7–10 (catalog outage, stub variants, restart while waiting) | **NOT RUN** | They need the Product Catalog stub stopped or replaced, and the stub had to stay running |
| 6 | Scenario 11 (mail server down) | **PASS** | See the sub-checks below |
| 7 | Scenario 12 (D4) | **PASS** | A synthetic Rejected receipt PM-59012 with a notification row `attempts=1000`, `sent_at NULL`: sent once, `sent_at` set, `attempts` stays 1000, and Mailpit still has 1 message after further polls |
| 8 | Kill test | **PASS (manual, see below)** | run 133000 |
| 9 | No `kill-test`/`commit-delay` in `src/main/` or the jar | **PASS** | `grep -rlE … src/main/` returns nothing. `unzip -p target/order-integration-0.1.0.jar 'BOOT-INF/classes/*' \| grep -acE 'kill-test\|commit-delay'` returns `0`. The jar has no test classes; schemas, SQL and V1 are in `BOOT-INF/classes/` |
| 10 | `prototype/run.sh` guard and OQ-076 | PARTIAL | `git diff --numstat -- prototype/` gives `1 0` (only `run.sh`). OQ-076 is present once and append-only (`12 0`). The guard **could not be executed** (session limitation). It was reviewed statically: exit 1 before `set -euo pipefail` and before any `jbang` call, and the message names `PROTOTYPE_ANYWAY=1` and `trailhead-order-integration` |

Check 2 detail (dev startup):
- Flyway: `Migrating schema "public" to version "1 - order database"` and `Successfully applied 1 migration` (13:20:18.5).
- Camel starts afterwards: `Apache Camel 4.22.2 (trailhead-order-integration) is starting` (13:20:20.1), so Flyway ran
  first.
- All 10 routes started.
- No web server: no Tomcat, Netty, Jetty or Undertow line and no "started on port". (`lsof` was not available.)

Check 4 detail (scenarios 1–6):
- **1:** PM-50117 recorded with street line `2240 Pearl Street, Apt 4`, Boulder CO 80302 US, and an email. Lines
  TENT-2P 1 × 229.00, *Ridgeline 2-Person Tent*, 2150 g, and STAKE-AL8 2 × 12.50, *Aluminium Tent Stakes 8-Pack*,
  120 g. History `Received, Recorded`.
- **2:** still one receipt, with the first event id `8d2f6c1e-…`. Log line `Duplicate Peakmart order skipped
  orderNumber=PM-50117`.
- **3:** PM-50118 Rejected, with one SKU reason on line 1 that matches `order-desk-notification/pm-50118.json`.
  Notification sent, attempts 1. Mailpit went from 0 to exactly 1 message:
  - Subject `Peakmart order PM-50118 rejected`;
  - From `order-integration@trailhead.example`, To `order-desk@trailhead.example`;
  - `Content-Type: text/plain; charset=UTF-8`;
  - body exactly `Order number: PM-50118` followed by `- Order Line 1: the SKU TENT-1P is not an Active Product in the
    Product Catalog. Replace it with an active SKU.`;
  - no name, email or address, and no Camel/SQL headers leaked;
  - there is no `target/outbox`.
- **4:** no receipt beyond PM-50117/PM-50118, and order.cancelled was not set aside.
- **5:** PM-50119 set aside with `$.buyer.email: format`; no receipt.
- **6:** set aside with `$.items[0].price_usd: type` (key `8d2f6c1e-…`), `$: required` (key `kafka-0-6`) and
  `not JSON` (key `kafka-0-7`). No validator message.

Check 6 detail (scenario 11):
- The app ran with `--smtp.port=1` and rejected PM-59011.
- The row stayed `sent_at NULL`, and `attempts` went 1 → 3 over successive polls.
- A WARN line appeared on every poll: `Order Desk notification not sent, tried again on the next poll
  orderNumber=PM-59011 correlationId=…`.
- The exhausted-error line shows only `MailConnectException … timeout 10000`, so `smtp.timeout-ms` is applied. No body
  and no message history appear in the log.
- After a restart with `smtp.port=1025`: exactly 1 email, and `sent_at` was set.
- The earlier emails (PM-50118, PM-59012) were not sent again.

### Refusal test (check 3)

The baseline run, with all 15 values and no profile, got past placeholder resolution: there was no
`PlaceholderResolutionException`. It then failed with `The server does not support SSL.`, which proves that
`sslmode=verify-full` is in effect. Per P-9 this counts as a pass.

Then 15 runs were made, each leaving one value out. **All 15 exited non-zero before any route consumed.** Whether the
output names the missing variable:

| Variable | Run A: as designed (local DB has no TLS) | Run B: only `spring.datasource.url` overridden to the local DB |
|---|---|---|
| KAFKA_BROKERS, CATALOG_HOST, CATALOG_PORT, SMTP_HOST, SMTP_PORT, ORDERDESK_MAILBOX, ORDERDESK_SENDER | not named. Flyway's TLS failure comes first | **named**: `Could not resolve placeholder '<VAR>'`, and no route started |
| SMTP_USERNAME, SMTP_PASSWORD | **named**: `Could not resolve placeholder` during context refresh | — |
| DB_HOST | named indirectly: `UnknownHostException: ${DB_HOST}` | — |
| DB_PORT | named indirectly: the driver rejects the URL `…:${DB_PORT}/…` | — |
| DB_USERNAME | not named (TLS failure first) | named indirectly: `password authentication failed for user "${DB_USERNAME}"` |
| DB_NAME, DB_SSL_ROOT_CERT | not named (TLS failure first) | not tested. The URL override removes them, and no TLS-enabled database was available |
| DB_PASSWORD | not named (TLS failure first) | **NOT named**: `password authentication failed for user "test"` |

**Finding (escalated, not fixed; needs a design amendment):** Spring Boot's binder passes an unresolvable `${…}` in
`spring.datasource.*` through as literal text. So for the database variables the application does refuse to start,
but it names the variable only through whatever the driver reports, and for `DB_PASSWORD` it never names it. The
design's statement "the output names that variable" holds for the 7 Camel-route variables and the 2 SMTP credentials.
For the database variables it holds only partly. One possible amendment: resolve the 15 names with
`Environment.resolveRequiredPlaceholders` before the datasource is created (for example in an early
`EnvironmentPostProcessor`). No such change was made, because it is not in the approved design.

### Kill test (check 8)

`src/test/scripts/kill-test.sh` was not executed (session limitation). Its steps were run by hand as run `133000`.
Result in `target/kill-test/133000/result.txt`:

```
First start: ./mvnw -q spring-boot:test-run -Dspring-boot.run.main-class=com.trailheadgear.orderintegration.KillTestApplication -Dspring-boot.run.profiles=dev,kill-test -Dspring-boot.run.arguments=--kill-test.commit-delay-ms=500
  hook line: "Kill-test hook active commitDelayMs=500"; marker "Started peakmart-order-intake (kafka://peakmart.orders.v2)"
  forked JVM found by the script's pattern: exactly one PID (73275), Maven PIDs not matched
Committed offset before: 9; 50 events published (PM-1330000000..PM-1330000049)
Kill: the process tree was terminated by the session's task stop (kill -9 was not permitted in this session);
  the JVM began a graceful shutdown and was terminated while an exchange was in flight
killed with 40 of 50 received; committed offset moved by 39
gap: 1
Second start: profiles dev only, no hook (0 hook lines)
ok   orders recorded: 50
ok   orders recorded twice: 0
ok   history entries: 100
ok   Duplicates skipped after the restart: 1 (= gap; PM-1330000039)
```

- The pass criterion (a gap ≥ 1 and every check ok) is met.
- **Caveat:** the termination was not a literal `kill -9`, because the session does not allow `kill`. The process
  received a shutdown signal and died while an exchange was in flight. The script's own `kill -9` path, including its
  PID patterns (which were verified to match only the forked JVM), should be run once by a person:
  `src/test/scripts/kill-test.sh`.

### Data left in the development database

- Scenario orders PM-50117, PM-50118 and PM-50119 (set aside).
- PM-59011 (scenario 11) and PM-59012 (scenario 12, a synthetic receipt inserted for D4).
- 50 kill-test orders PM-1330000000…049.

Because these exist, `scenarios.sh` refuses to run again on this database (its guard exits 2). Mailpit holds 3 new
messages (PM-50118, PM-59011, PM-59012); none was deleted.

## Assumptions (non-interactive run)

| # | Decision taken |
|---|---|
| X-1 | Reviews were run as one subagent per wave, applying ACR → spec → quality in order, rather than one fresh context per critic lane (reduced isolation, recorded) |
| X-2 | Task 1's transitive file/timer starters were accepted as a trade-off. No exclusions were added (R1: pom otherwise as exported) |
| X-3 | ACR/quality suggestions outside the design were not applied (Iron Law 6): Task 7 logging and per-poll cap, the cross-cutting suggestions |
| X-4 | Refusal test values were passed as command-line arguments, not environment variables. A second set of runs overrode only `spring.datasource.url`, to get past the local database's missing TLS |
| X-5 | The environment probe was built under `target/camel-kit-probe` (removed by `mvn clean`) instead of `/tmp`, because the session could not write to `/tmp` with the needed commands. It used no database and no Flyway (P-8) |
| X-6 | Scenario 12 used a synthetic Rejected receipt (PM-59012) inserted with `psql` DML. No DDL was run by hand (P-8) |
| X-7 | Scenarios 7–10 were not run, because the stub had to stay running |

## Downstream

No `validation-report.md` exists yet, so no staleness had to be cascaded. Standalone mode: `/camel-validate` was not
started automatically.
