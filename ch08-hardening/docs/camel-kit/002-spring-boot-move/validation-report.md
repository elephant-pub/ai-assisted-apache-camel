---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T19:47:17.910921Z"
  by: "camel-validate"
  from: "execution-report.md"
---
# Validation Report

**Date:** 2026-11-13 13:45
**Project:** trailhead-orders
**Runtime:** spring-boot
**Camel Version:** 4.22.2

Mode: standalone, pipeline-scoped (`/camel-validate 002-spring-boot-move`), non-interactive. This was a static
validation of the files. The application was not started and no container was touched.

**Inputs:**
- `design-spec.md`, `implementation-plan.md` and `execution-report.md`. `camel-kit doc check` reported each one as not
  stale, and the lineage is plan ← design, execution report ← plan.
- `developer-checks.md`, which carries no provenance frontmatter. It is used as developer-supplied evidence and
  corroborated against files where they exist (see A-1).

**Catalog binding:** `camel_catalog_components(limit=0, runtime=spring-boot,
platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2)` returned `camelVersion=4.22.2`,
which matches.

**Inventory** (single module, empty `MODULE_PREFIX`):
- `ROUTE_FILES`:
  - `src/main/resources/camel/order-desk-notification.camel.yaml`
  - `src/main/resources/camel/peakmart-order-check.camel.yaml`
  - `src/main/resources/camel/peakmart-order-intake.camel.yaml`
- `PROPS_FILE`: `src/main/resources/application.properties`, plus the profile files `application-default.properties`
  and `application-dev.properties`.
- 3 route configurations and 10 routes.

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | 1 | 0 | 3 | 4 |
| Endpoint Verification | 7 | 0 | 2 | 9 |
| Quality Checks | 7 | 3 | 4 | 14 |
| Security Analysis | 7 | 3 | 0 | 10 |
| Anti-Patterns | 6 | 0 | 3 | 9 |
| Constitution Compliance | 6 | 0 | 2 | 8 |
| **Total** | **34** | **6** | **14** | **54** |

**Result: FAIL (6 items).**
- 3 security items. The design scope accepts all three, but they still fail checklist rules 1 and 2.
- 1 catalog-validator property result.
- 2 acceptance criteria are unproven or not met: scenario 9 and the refusal test's database variables.

## Findings

### Schema Validation

The canonical `camel-yaml-dsl-validator` Maven goal could not be run, because this session's permission policy denied
it. `camel_validate_yaml_dsl` (MCP) was used instead, on each full file and on each top-level item.

- [PASS] `order-desk-notification.camel.yaml`: full file valid, 0 errors.
- [WARN] `peakmart-order-check.camel.yaml`: the full file reports `required property 'from' not found` at
  `/4/route` (`order-reject`).
  - All 6 items, including `order-reject`, validate with 0 errors on their own, and `order-reject` does have a `from`.
  - The execution report recorded the same result (Tasks 5, 6), and the dev startup loaded all 10 routes.
  - Likely a validator artefact, but it is not resolved.
- [WARN] `peakmart-order-intake.camel.yaml`: the full file reports 2 errors, `null found, object expected`, at
  `/2/route/from/steps/1/choice/when/1/steps/0/setProperty/expression`. That is `peakmart-event-read` →
  `when-unstorable` → `set-reason-unstorable`, a multi-line Groovy block.
  - The routeConfiguration and 3 of the 4 routes validate alone.
  - `peakmart-event-read` passed only as a shortened copy, with the DataMapper Groovy replaced by a stub and the
    flagged block kept verbatim. The original route on its own was not tested.
  - This route is unchanged in logic from the accepted prototype, and the dev startup loaded it.
  - The disagreement is not resolved.
- [WARN] The Maven validator, the guide's authoritative schema check, was not executed (session limitation). Until it
  has run, the two disagreements above are unresolved and not proven false.

### Endpoint Verification

`camel_validate_route` used spring-boot and the BOM above, with the full file as `route`. A URI counts as passing when
the top-level `uri` echoes it and there are no top-level `errors`; the aggregate `valid` field was ignored.

- [PASS] `kafka:{{peakmart.topic}}?brokers=…&groupId=…&autoOffsetReset=…&breakOnFirstError=true&autoCommitEnable=false&allowManualCommit=true`
  is valid, with no unknown options.
- [PASS] Intake producers are valid:
  - `direct:peakmart-event-read`
  - `direct:peakmart-event-set-aside`
  - `direct:peakmart-order-receive`
  - `json-validator:classpath:schemas/peakmart-order-placed.schema.json`
  - `sql:classpath:sql/set-aside-insert.sql?dataSource=#dataSource`
  - `sql:classpath:sql/receipt-insert.sql?dataSource=#dataSource`
- [PASS] Notification endpoints are valid:
  - `sql:classpath:sql/notification-select.sql`, with `delay`, and `onConsume`/`onConsumeFailed` as `RAW(...)`
  - `smtp://{{smtp.host}}:{{smtp.port}}`, with `from`, `useHeaderRecipients`, `useHeaderSubject`, `contentType`,
    `connectionTimeout` and the `mail.*` additional properties
- [PASS] Order-check endpoints submitted: `sql:classpath:sql/waiting-select.sql?…maxMessagesPerPoll=…`,
  `http://{{catalog.host}}:{{catalog.port}}?httpMethod=GET&throwExceptionOnFailure=false` and `direct:order-check`.
  - All are valid.
  - The supplementary `uriValidations` also lists as true: `direct:product-lookup`, `direct:order-reject`,
    `direct:order-record` and `json-validator:classpath:schemas/product.schema.json`.
- [WARN] Three order-check URIs were not checked by the tool in this run:
  - `json-validator:classpath:schemas/order.schema.json`
  - `sql:classpath:sql/order-reject.sql?dataSource=#dataSource`
  - `sql:classpath:sql/order-record.sql?dataSource=#dataSource`

  The same components and options passed elsewhere in this run, and the routes started at runtime. Their individual
  URI result is unverified.
- [PASS] Placeholder completeness: every `{{…}}` in the three routes is defined in `application.properties` plus each
  profile file (`default` and `dev`). The full list is `kafka.brokers`, `peakmart.*`, `order-check.*`, `catalog.*`,
  `notification.poll-delay-ms`, `smtp.host`, `smtp.port`, `smtp.timeout-ms`, `smtp.starttls`, `orderdesk.mailbox` and
  `orderdesk.sender`. `${…}` inside the properties files is correct for spring-boot.
- [PASS] Bean references resolve:
  - `#dataSource` is Spring Boot's auto-configured DataSource (`spring.datasource.*`).
  - `orderChecks` and `cannotReadReason` are `@Bean`s in `IntegrationBeans.java`.
  - No `camel.beans.*` entries remain.
- [PASS] Dependencies: `pom.xml` declares a starter for every component and language used: kafka, sql, http,
  json-validator, mail (smtp), direct, bean, jsonpath, groovy and yaml-dsl. `postgresql` and Flyway are also present.
- [WARN] `camel-file-starter` and `camel-timer-starter` still arrive transitively through `camel-spring-boot-starter`,
  so the E10 check in plan Task 1 is not met as written. No route uses `file:` or `timer:`. The ACR accepted this as a
  trade-off (execution report X-2).

### Quality Checks

**Completeness and correctness**
- [PASS] All 10 routes have an `id`, a `from`, a sink or `direct:` hand-off, a `description` of at least 10
  characters, and error handling through `routeConfigurationId`.
- [FAIL] `application.properties:12` `camel.main.run-controller=true`: `camel_configuration_validate` (spring-boot,
  4.22.2) types it as `unknown` ("Unknown option"), with no suggestion.
  - The execution report presents counter-evidence: the key is listed in the `camel-spring-boot` jar's Spring metadata,
    and the `inRunController` thread starts.
  - That evidence could not be corroborated in this run, because reading the jar was not permitted.
  - Under Stage 7.1 a typed `unknown` is a FAIL until the tool, or a recorded decision, says otherwise.
- [PASS] The other 31 submitted lines are valid or are application keys the tool does not own. `camel.component.http.*`
  timeouts and `camel.component.smtp.username`/`password` are accepted. `application-dev.properties` holds no `camel.*`
  keys. No file has a duplicate key.
- [PASS] Expressions:
  - every JSONPath starts with `$`;
  - SQL uses `:#` prepared bindings in all six SQL files (checked);
  - Simple references only exchange properties that earlier steps set.

**Acceptance coverage (design §4), including `developer-checks.md`**
- [PASS] Kill test with a real `kill -9`, run 133407, corroborated:
  - `src/test/scripts/kill-test.sh:67` is `kill -9 "$pid"`.
  - `target/kill-test/133407/result.txt` matches the developer's output: gap 1, and all four `ok` lines.
  - The hook line is in `1.log` (count 1) and not in `2.log` (count 0).
  - `2.log` contains `Duplicate Peakmart order skipped orderNumber=PM-1334070010`.

  This closes the caveat on run 133000 in the execution report.
- [PASS] Prototype guard: the developer reports a REFUSED exit. Static check: line 2 of `prototype/run.sh` exits 1
  unless `PROTOTYPE_ANYWAY=1`, before `set -euo pipefail` and before any `jbang` call. Since the execution,
  `git diff --stat` under `prototype/` shows only that line.
- [PASS] Scenario 7 (catalog outage, `dev` profile): developer report. PM-67007 stayed `Received`/`Check` with a
  "waits for Product Catalog" WARN on each poll, then became `Recorded`. The log text matches `log-waits-for-catalog`.
  No log file for this run is in the repo, so this rests on the developer's statement.
- [FAIL] Scenario 9 was not run. Design §4 sets a new expectation for 002: "the rejection email appears in Mailpit only
  after the stub starts again."
  - The prototype wrote a file, not an email, so this expectation cannot have passed there.
  - The developer's reason for skipping ("routes and SQL are unchanged from the prototype") does not cover it, because
    the SMTP delivery path is new in this pipeline.
- [WARN] Scenario 8 (`invalid`/`slow` stub) was not run. The developer says the routes are unchanged; the diff against
  the prototype shows endpoint wiring only. But the timeout configuration did change:
  - the prototype resolved `camel.component.http.*-timeout={{catalog.timeout-ms}}` through camel-main;
  - here `${catalog.timeout-ms}` is bound through Spring Boot's binder.

  Scenario 7 (connection refused) does not exercise `response-timeout`. Only the `slow` variant proves that the 2000 ms
  timeout applies under Spring Boot.
- [WARN] Scenario 10 (restart while waiting) was not run. The kill test's second start covers a restart on a populated
  database: Flyway skips V1, and the intake resumes. A waiting order being checked again after a restart was not
  observed on Spring Boot.
- [FAIL] Refusal test: design §3/§4 requires that "the output names that variable". This holds for the 7 route
  variables and the 2 SMTP credentials. It holds only indirectly for `DB_HOST`, `DB_PORT` and `DB_USERNAME`. It does
  not hold for `DB_PASSWORD`, `DB_NAME` or `DB_SSL_ROOT_CERT` (execution report, check 3). The developer defers this
  to the next pipeline, so it stays open against this design.
- [WARN] Check 4a (no SMTP `AUTH` against Mailpit in `dev`) is still NOT OBSERVED. `developer-checks.md` does not
  address it.
- [WARN] `src/test/scripts/scenarios.sh` has never been executed as a script; only `bash -n` was run, and scenarios
  1–6 were run step by step. Its rerun guard now refuses on the development database (exit 2).

### Security Analysis

- [PASS] Rule 1, routes and the default profile: no credential is in a route URI or YAML. The default profile takes
  `spring.datasource.password`, `camel.component.smtp.username` and `camel.component.smtp.password` from the
  environment, with no default value.
- [FAIL] Rule 1, `application-dev.properties:7-8` `spring.datasource.username=test` / `spring.datasource.password=test`
  is a literal password in a properties value. It is accepted by design B-4 (local `camel infra` PostgreSQL only), and
  this file is packaged into the jar. The checklist allows no exception.
- [PASS] Rule 2, Order Database without a profile:
  `jdbc:postgresql://…?sslmode=verify-full&sslrootcert=${DB_SSL_ROOT_CERT}` (C-024). The execution report's baseline
  run showed `verify-full` in effect.
- [PASS] Rule 2, SMTP without a profile: `smtp.starttls=true` drives `mail.smtp.starttls.enable` and
  `mail.smtp.starttls.required`. `dev` disables STARTTLS only toward `localhost:1025`.
- [FAIL] Rule 2, Kafka: no `securityProtocol=SSL` or `SASL_SSL` is set in any profile. `KAFKA_BROKERS` is an external
  broker in production. Design *Not Doing* defers this until OQ-038 is answered.
- [FAIL] Rule 2, Product Catalog: `http://{{catalog.host}}:{{catalog.port}}`
  (`peakmart-order-check.camel.yaml:175`) is plain HTTP. In the default profile `CATALOG_HOST` is not `localhost`.
  Design *Not Doing* keeps `http://` (only SKUs travel, and the contract has `security: []`). `camel_route_harden_context`
  raised the same concern, rated High.
- [PASS] Rule 3:
  - Log lines carry only order numbers, event ids, Kafka positions, counts, reasons and correlation ids.
  - No log has `${body}` or headers.
  - `logExhaustedMessageBody` and `logExhaustedMessageHistory` are false in all 3 route configurations.
  - `camel.main.log-mask=true`, plus `additional-sensitive-keywords=email,phone,ssn,creditCard`.
- [PASS] Rule 4: inputs are validated at every untrusted boundary.
  - At the Kafka ingress, a size limit (`peakmart.max-record-chars`) applies before the first JSONPath parse, followed
    by `json-validator` against `peakmart-order-placed.schema.json`.
  - A catalog 200 answer is validated against `product.schema.json`.
  - The completed Order is validated against `order.schema.json` before it is recorded.
  - SQL uses prepared `:#` bindings.
  - The SKU in `CamelHttpPath` is URL-encoded (`OrderChecks.productPath`).
  - Groovy only reads exchange properties and never evaluates input as code.
  - `removeHeaders: "*"` runs before the SMTP producer.
- [PASS] Rule 5: there is no inbound HTTP endpoint. No web server starter is present, and no port is opened.
- [PASS] Non-checklist MCP concern not confirmed: `camel_route_harden_context` flagged "SQL query may not use
  parameterized queries" for order-check. All six SQL files use `:#` named parameters. It returned no CVE advisories
  for any route.

### Anti-Patterns

- [PASS] No god route: the largest route has 8 steps (`order-desk-notification-send`), below the default of 10.
- [PASS] No hardcoded connections in the routes.
- [PASS] Every route has error handling through a routeConfiguration.
- [PASS] Idempotency: `receipt-insert.sql` is conflict-safe (`CamelSqlUpdateCount == 0` → Duplicate), the Kafka
  offset is committed manually as the last step, and the kill test proves exactly-once recording.
- [PASS] Correlation id: it is generated at intake and on every order log line, including the new WARN.
- [PASS] Connection pooling: HikariCP is used through Spring Boot's DataSource.
- [WARN] No circuit breaker around the Product Catalog call. The design handles outages with timeouts, a throttle
  (20 calls per second) and wait-and-recheck (BR-013/BR-014). This is a suggestion only.
- [WARN] An exception type that no routeConfiguration's `onException` lists falls back to Camel's default error
  handler. This was the cross-cutting review's Important 2, inherited from the prototype.
- [WARN] Business mapping is inline Groovy (`kaoto-datamapper-5c1e9a27` in `peakmart-event-read`, plus the
  `when-unstorable` and set-aside key expressions). This was carried unchanged from the accepted prototype.

### Constitution Compliance

- Rule 1 (Route Structure): PASS. The pollers end in `sql`, `smtp` or `direct:`, and the `direct:` sub-routes are
  exempt.
- Rule 2 (Single Responsibility): WARN. `order-desk-notification-send` has 8 processing steps, more than the default
  of 7. It has a single purpose, and every step is required by the design (ACR B-6).
- Rule 3 (Separation of Concerns): WARN. `order-desk-notification-send` (more than 5 steps) is not decomposed, and
  intake keeps the mapping as inline Groovy. Check logic is in `OrderChecks`, and SQL is in files.
- Rule 4 (Naming Conventions): PASS. All 10 route ids and 3 routeConfiguration ids match
  `^[a-z][a-z0-9]*(-[a-z][a-z0-9]*)+$`. No `routeIdPattern` customization is active.
- Rule 5 (Observability): PASS. Every route has an id and a description.
- Rule 6 (External Configuration): PASS. Every host, port, topic, address, interval and limit in the routes is a
  `{{placeholder}}`.
- Rule 7 (Component Support): PASS.
  - kafka, sql, http, smtp, json-validator and direct resolved under the matched binding in this run.
  - Bean, the EIPs and the languages rest on the design's §5 catalog evidence.
  - Deprecation status was not re-queried in this run.
- Rule 8 (Infrastructure via Forage): PASS.
  - Forage is unavailable (no `forage.version`), so the ladder checks were skipped.
  - The DataSource is Spring Boot's `spring.datasource.*` (rung 2).
  - There are no `camel.beans.*` and no YAML `beans`.
  - Required-bean check: `dataSource` is provided for every `sql` endpoint.

Project Customizations in `docs/constitution.md` are all commented out, so no custom rules were applied.
`.camel-kit/project-graph.json` is absent, so the graph guides were not loaded and the default thresholds were used.

## Recommendations

### Priority fixes (FAIL)

Route changes belong to implementation; this report does not modify any file.

1. **Scenario 9:** run it by hand with the stub stopped. Confirm that the rejection email reaches Mailpit only after
   the stub starts again, and record the result.
2. **Refusal test, `DB_*` variables:** this needs a design amendment, which the developer has deferred to the next
   pipeline. One option, from the execution report, is an early `EnvironmentPostProcessor` that calls
   `resolveRequiredPlaceholders` on the 15 names. Until then, the design §4 criterion stays unmet for `DB_PASSWORD`,
   `DB_NAME` and `DB_SSL_ROOT_CERT`.
3. **`camel.main.run-controller`:** either confirm the binding in a way the validator accepts, or record an explicit
   decision accepting the catalog validator's `unknown` as a false positive. The execution report has evidence (jar
   metadata and the `inRunController` thread), but this run could not corroborate it.
4. **Security rule 2, Kafka TLS/SASL and HTTPS to the catalog:** these are out of scope by design (*Not Doing*, OQ-038).
   Record an accept-risk decision, as `validation-decisions.md` of pipeline 001 did. For Kafka, add `securityProtocol`
   and truststore settings to `application-default.properties` once OQ-038 is answered.
5. **Security rule 1, `application-dev.properties` `test`/`test`:** either accept it explicitly (design B-4, local
   container only), or move the values out of the packaged jar, for example by taking them from the environment with
   the local values as defaults outside `src/main/resources`.

### Suggested improvements (WARN)

1. Run the canonical `camel-yaml-dsl-validator` Maven goal on all three route files, to settle the two MCP
   full-file schema disagreements.
2. Run scenario 8 (`slow` and `invalid` stub), to prove that the Spring-bound `camel.component.http.response-timeout`
   applies. Run scenario 10, a restart while an order is waiting.
3. Observe check 4a (no SMTP `AUTH` in `dev`), and run `scenarios.sh` once as a script on a fresh database.
4. Optionally add exclusions for the transitive `camel-file-starter` and `camel-timer-starter`, if E10 is meant
   literally.
5. Consider a catch-all handler, or narrower exception lists, so that unexpected exception types do not fall to the
   default error handler.
6. Longer term: move the inline Groovy mapping to a bean or a DataMapper artifact, and split
   `order-desk-notification-send` if it grows.

## Assumptions (non-interactive run)

| # | Decision taken |
|---|---|
| A-1 | `developer-checks.md` has no provenance frontmatter. It was used as developer evidence; where files exist (kill-test result and logs, `run.sh`, route and SQL diffs), its claims were corroborated against them. Scenario 7 rests on the statement alone |
| A-2 | The Maven schema validator and the reading of jar contents were denied by the session permission policy. MCP `camel_validate_yaml_dsl` and `camel_configuration_validate` were used instead, and the gap is reported as a WARN |
| A-3 | Endpoint checks ran in three parallel read-only subagents, one per route file. 3 order-check URIs were not submitted individually (WARN) |
| A-4 | Security rule violations that the design scope accepts (Kafka TLS, catalog HTTP, dev credentials) are still reported as FAIL. Per the checklist, the design does not change their severity; accepting them is a decision for the owner, as in pipeline 001 |
| A-5 | Scenario 9 is counted as FAIL rather than WARN, because its expectation is new in this pipeline and was never observed |
