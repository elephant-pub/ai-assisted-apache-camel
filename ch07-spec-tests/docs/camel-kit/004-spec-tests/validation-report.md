---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-14T01:21:13.401029Z"
  by: "camel-validate"
  from: "execution-report.md"
---
# Validation Report

**Date:** 2026-11-13 19:21
**Project:** trailhead-orders
**Runtime:** spring-boot
**Camel Version:** 4.22.2

Mode: standalone, pipeline-scoped (`/camel-validate 004-spec-tests`), non-interactive. This is a static validation of the
files. No build was run, the application was not started, and no container was touched.

**Inputs:**
- `design-spec.md`, `implementation-plan.md` and `execution-report.md`. `camel-kit doc check` reported each one as not
  stale. The lineage is plan ← design and execution report ← plan.
- `design-review.md` and `developer-checks.md` have no provenance frontmatter. `developer-checks.md` is untracked in git.
  Both are used as developer evidence and checked against the files (A-1).
- `003-partner-orders-pick-lists/validation-report.md` and `validation-decisions.md` are used for the routes, which this
  pipeline does not change (A-2).

**Catalog binding:** `camel_catalog_components(limit=0, runtime=spring-boot,
platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2)` returned `camelVersion=4.22.2`, which
matches. Every later catalog call used the same binding.

**Inventory** (single module, empty `MODULE_PREFIX`):
- `ROUTE_FILES`: the six files under `src/main/resources/camel/`. They are the same as in 003: `order-desk-notification`,
  `partner-order-api`, `partner-rejection-email`, `peakmart-order-check`, `peakmart-order-intake` and
  `pick-list-delivery`.
- `PROPS_FILE`: `src/main/resources/application.properties`, plus `application-default.properties`,
  `config/application-dev.properties`, and the new test profile
  `src/test/resources/spec-test/application-spec-test.properties`.

**What changed in this pipeline** (`git diff 3f73ce8 HEAD`):
- `pom.xml`, `README.md` § Build, and test-side files only.
- No change to `src/main/**`, `config/**`, `docs/test_cases/**`, `docs/use_cases/**` or `docs/messages/**`.
- The route and properties files under `src/main` have had no commit since `a874b0e` (003 execute). That is before the
  003 validation (`aca253a`).

**What was validated:**
- The routes, carried over from 003.
- The new build configuration.
- The test harness, the five ITs and the traceability tooling, against design §2–§3.10.
- The developer's fault-injection results, against the code.

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | 2 | 0 | 1 | 3 |
| Endpoint Verification | 4 | 0 | 1 | 5 |
| Quality Checks | 9 | 0 | 13 | 22 |
| Security Analysis | 7 | 4 | 5 | 16 |
| Anti-Patterns | 1 | 0 | 3 | 4 |
| Constitution Compliance | 4 | 0 | 4 | 8 |
| **Total** | **27** | **4** | **27** | **58** |

**Result: FAIL (4 items, all security, all carried over unchanged from 003).**
- The four are S-1 to S-4: committed dev passwords, the committed dev partner key, Kafka without TLS, and the Product
  Catalog over plain HTTP.
- They were accepted for 003 (003 decisions 2 and 3). None of them is in this pipeline's changes.
- The checklist does not allow a lower severity. They block 004 until they are fixed or accepted again for 004.

**The deliverables of this pipeline pass, with warnings.**
- Every design item is implemented with the stated status.
- The developer's build and classpath claims match the files.
- The main warning is the fault-injection result: three of eight planted faults (F5, F6, F7) pass every automated test.
  Each one can be guarded by a cheap test that needs no change to an Approved test case (see Quality Checks).

## Findings

### Schema Validation
- [PASS] `pick-list-delivery.camel.yaml`: the full file (comments removed) gave `camel_validate_yaml_dsl` valid, with 0
  errors. This was not a reduced payload, as it was in 003.
- [PASS] `order-desk-notification.camel.yaml`: the full file gave valid, with 0 errors.
- [WARN] The other four route files were not sent again in this run. Their git contents at `HEAD` are the same as those
  validated in 003 (A-2). That 003 check used reduced payloads (003 A-4) and found 0 errors. A full-file DSL validator
  (`camel-yaml-dsl-validator`, `camel run --check`) was not run, because this run starts no build.

### Endpoint Verification
- [PASS] Catalog binding `MATCHED` for 4.22.2 spring-boot.
- [PASS] No endpoint was added or changed, so the 11 endpoint PASS findings of 003 stand on identical files. Those cover
  sql, smtp, kafka, http, sftp, json-validator, direct, csv and placeholder resolution.
- [PASS] `camel_configuration_validate` on `application-spec-test.properties` gave 12 of 12 lines valid, with no
  unknown Camel key.
  - Every key it sets overrides a key the routes already use from `application.properties`: the three
    `*.poll-delay-ms`, the SFTP folders and username, `peakmart.auto-offset-reset` and the mail addresses.
  - The dynamic keys (hosts, ports, credentials) are written per run by `IntegrationProcess`, as design §3.1 says.
- [PASS] The spec-test profile lowers the poll delays only through configuration: 1000 ms, against shipped values of
  5000, 5000 and 30000 (`application.properties:29,39,58`). No route was changed to make the tests faster (A-10).
- [WARN] Carried over: the Product Catalog URI uses plain `http://` (`peakmart-order-check.camel.yaml:179`). This is
  security finding S-4.

### Quality Checks
- [PASS] **Design coverage.** The `ITEMS` tables of all five ITs match design §3.4–§3.8 item by item, with the same
  status (run, or not yet testable with its use case). The five matrix summary lines match the execution report.
- [PASS] **Design assertions are present:**
  - elapsed time below the stated limit: `Tc001…IT:168-170`, `Tc002…IT:194-196`, `Tc003…IT:185-192`, `Tc005…IT:265-267`;
  - the catalog SKUs requested: `Tc001…IT:140-142`, `Tc002…IT:171-173`, `Tc003…IT:172-173`, `Tc005…IT:177-179`;
  - topic offset growth: `Tc002…IT:289-293`, `Tc004…IT:206-211`;
  - `notification.sent_at`: `Tc003…IT:253-254`;
  - no `.tmp` file: `Tc002…IT:240`, `Tc003…IT:222`, `Tc005…IT:272,280`;
  - TC-003's mail is plain text and carries no buyer personal data: `Tc003…IT:197-213`.
- [PASS] **No quiet-period check can pass vacuously on timing alone.** Each quiet period starts after the integration
  has acted, either after the file arrived or after the consumer group committed the end offset (`Peakmart.java:98-103`).
  The negative mail checks are also backed by a `notification` table check (`Tc001…IT:189`, `Tc002…IT:259`,
  `Tc003…IT:232-233`).
- [PASS] **Traceability.** `SpecTraceabilityTest` implements §3.9 rules 1–5 (`:82-163`), and each rule has a negative
  test. `SpecMatrix` implements completeness, missing results, and result-against-`ITEMS` agreement (`:132-233`).
- [PASS] **Pinned images.** Kafka, PostgreSQL 16.16, atmoz/sftp and Ryuk all carry an `@sha256:` digest
  (`spec-test.properties`, `testcontainers.properties`). `SpecEnvironment:213-228` rejects any reference without one.
  `FreePorts:28,94` always excludes 8080, 8081 and 5432.
- [PASS] **Developer check 1 (commit).** `ebbc9b3 Session 05-execute-resume` holds the files the execution report listed
  as uncommitted: the five ITs, `SpecMatrix`, `SpecTraceabilityTest`, `OrderDatabase`, `Peakmart`, `WarehouseInbox`,
  `pom.xml`, `README.md`, `IntegrationProcess` and `SpecEnvironment`. The tree is otherwise clean, except for the
  untracked `developer-checks.md`.
- [PASS] **Developer check 2 (counts).** The `@Test` methods in the files add up to the reported surefire 54: 34 existing
  (13+11+2+3+4+1), plus `TestCaseDocumentTest` 10, plus `SpecTraceabilityTest` 10. There is one `@Test` per IT, so 5 for
  failsafe. The 189 s run time and the exit code were not rerun (A-1).
- [PASS] **Developer check 3 (classpath).** `pom.xml:50-54` holds `com.networknt:json-schema-validator` at 2.0.1 in
  `dependencyManagement`, as the execution report says. The comment at `:46-49` names the reason.
- [PASS] **Developer check 4, the caught faults match the code.**
  - F1: the email pattern is at `application.properties:26`. `java.util.Properties` reads `\s` as `s`, so the pattern
    would reject `orders@summit-outfitters…` and `jordan.reyes@…`. It is caught at `EmailPatternTest:53` and
    `Tc001…IT:129`.
  - F2: caught at `Tc002…IT:206`.
  - F3: the rule is at `OrderChecks.java:221`. It is caught at `OrderChecksPartnerTest:135,154`.
  - F4: the formula is at `order-record.sql:47`. It is caught at `Tc002…IT:207` and `Tc005…IT:271`.
  - F8: caught by the json-validator steps in TC-001 to TC-005.
- [WARN] **F5 not caught: delivery's `processed/` check has no automated test.**
  - The check is `WarehouseSftp.java:68-70`, called from `pick-list-delivery.camel.yaml:79-85`. No test moves a file to
    `processed/` before a delivery attempt.
  - TC-002 V1 (`Tc002…IT:243-252`) counts one file and one `Delivered` row. It cannot see a second write, because
    `fileExist: Fail` protects only `inbound/`.
  - The only exercise is the manual `pick-list-scenarios.sh:108,124`.
  - `WarehouseSftp.connect()` creates its own `JSch` (`:90-113`), so the check cannot be mocked without a change to
    `src/main`.
- [WARN] **F6 not caught: Rejection Reason order is tested only for structural reasons.**
  - `OrderChecksPartnerTest:100-106` asserts check order for five structural reasons. The SKU reasons appended in
    `OrderChecks.evaluate` (`:186-203`) are not covered.
  - Neither is the stored position: `order-reject.sql:39-40` (`WITH ORDINALITY`) and the `ORDER BY rr.position` reads
    in the three selects.
  - TC-003 has exactly one reason. The file the developer changed for F6 is not named, so where the gap is was not
    verified (A-3).
- [WARN] **F7 not caught: commit-after-receipt is guarded only by the manual kill test.**
  - `commit-kafka-offset` is the last step of the intake route (`peakmart-order-intake.camel.yaml:153-157`). The receipt
    is inserted in a sub-route (`:369-373`).
  - `kill-test.sh` is not wired into `pom.xml` and there is no CI file. `README.md:57` lists it as manual.
  - The ITs' commit gate (`Peakmart.java:98-103`) assumes this order and does not check it.
- [WARN] **F8 is caught only by the slow spec tests.**
  - There is no enforcer rule and no unit test for the pin. The execution report's suggested guard test was not
    applied.
  - Without Docker (`-DskipITs`), removing the pin passes the build and ships `json-schema-validator` 3.0.4.
  - The F8 run takes 387 s, against a usual 117–203 s.
- [WARN] **A misleading P-cleanup failure after a failed setup.** `Tc001…IT:242-243` and `Tc005…IT:348-349` assert that
  one `partner` row exists and report "Partner summit-outfitters was touched by the cleanup". The cleanup never touches
  that table (`OrderDatabase:54-61`).
  - When registration fails in C-setup, this assertion fails too and blames the cleanup. That is what happened in F1.
  - The first failure is still the one reported: `SpecRun.executeAlways` (`:162-180`) adds the later failure as
    suppressed.
  - The wrong reason still shows up in the log, in the suppressed exception, and in the P-cleanup `failure` field of
    `results/TC-001.json`.
- [WARN] **Flaky environment start: Kafka topic creation.**
  - `SpecEnvironment.createTopic` (`:499-512`) makes one attempt, with each call bounded by the 120 s startup timeout.
    There is no `describeCluster` readiness probe and no retry.
  - The failure is cached (`:171-184`), so every IT in the JVM fails. This matches the 2 of 11 F7 runs that stopped
    before any step.
- [WARN] **Matrix completeness accepts any split child.** `SpecMatrix.covers` (`:173-176`) treats any `<id>[a-z]` or
  `<id>-<text>` as covering `<id>`.
  - `V3a` alone satisfies V3, as the execution report says.
  - The reverse check (`:187`) also accepts a misspelt child such as `V3z` or `S4-typo` as a valid item.
- [WARN] **TC-004 precondition gap.** The design and plan name no `C-alert` item for "the Integration Team mailbox holds
  no Alert". `developer-checks.md` item 5 says the recommended amendment is accepted. The design and plan are not
  amended yet, and `Tc004…IT.ITEMS` lacks the item.
- [WARN] **One consumer group for every IT.** All ITs use one topic and one consumer group (`Peakmart.java:43`).
  - If an earlier IT fails before its events are committed, the next IT's integration reads them again.
  - `clearRequests()` runs after `integration.start()` (`Tc003…IT:133,148`), so a later catalog SKU assertion can see
    another test's SKUs. This happens only after an earlier failure, but it adds a second, misleading failure.
- [WARN] **TC-003 S2 compares the SKUs as a set** (`Tc003…IT:172`), so a repeated lookup would pass. The other tests
  check that each SKU is asked for once.
- [WARN] **Waits not taken from `spec-test.properties`:**
  - `waitFor` intervals of 1 s and 2 s in the five ITs;
  - `REFUSED_CHECK_TIMEOUT_MS` (`Tc005…IT:80`);
  - the SFTP session timeout (`WarehouseInbox:64`);
  - the 100 ms zero-wait mail check (`Mailboxes:138`).

  The stop grace of 30 s matches §3.1 but is hard-coded (`IntegrationProcess:77-82`).
- [WARN] **README** Layout row still says "34 unit tests" (`README.md:11`). This is known from the execution report.
- [WARN] With `-DskipITs`, `SpecMatrix` still fails on a completeness violation (`:44-45,105-107`). Design §3.9 says it
  "does not fail". This is stricter than the design, not weaker, but it is an undocumented deviation.

### Security Analysis
- [FAIL] **S-1 (rule 1): dev passwords committed** in `config/application-dev.properties`. Carried over from 003 and
  accepted there (decision 2). Not changed by 004.
- [FAIL] **S-2 (rule 1): dev partner API key committed** in `config/dev/secrets/partner-api-key-summit-outfitters`.
  Carried over from 003 (decision 2).
- [FAIL] **S-3 (rule 2): Kafka without TLS** in the default profile. Re-accepted for 003 (decision 3, OQ-038).
- [FAIL] **S-4 (rule 2): Product Catalog over plain HTTP** (`peakmart-order-check.camel.yaml:179`). Re-accepted for 003
  (decision 3, OQ-039).
- [PASS] **The harness keeps per-run secrets out of view.** The partner key and the SFTP and DB passwords:
  - never reach the command line (`IntegrationProcess:242-258`);
  - never reach the child's environment, which uses an allowlist (`:87-89,352-354`).

  Other protections:
  - The run file is owner-only (`:289-298`).
  - The 50-line log dump is redacted (`:402`), and the secret scan prints no values (`:426-427`).
  - The Citrus logging interceptor is replaced, so `X-API-Key` is not logged (`Partner:102`).
  - The SFTP password comes from a 0600 file that is deleted afterwards (`SpecEnvironment:399-405,570-581`).
  - The root log level is forced to INFO before any Docker call (`:80-87`).
- [PASS] SFTP keeps `strictHostKeyChecking=yes`, with per-run host keys and a per-run `known_hosts`.
- [PASS] The SFTP containers, the catalog stub and the mail server bind to 127.0.0.1 (`ProductCatalogStub:73`,
  `Mailboxes:74`, `Tc005…IT:241-243`).
- [PASS] The spec-test profile files are under `src/test/resources`, so they are not packaged into the jar. They hold no
  credential.
- [PASS] No secret is committed by this pipeline. Generated keys and passwords live under `target/spec-tests/`, and
  `target/` is ignored.
- [PASS] The shipped classpath is unchanged from 003. `json-schema-validator` stays at 2.0.1 (`pom.xml:50-54`), and every
  Citrus and Testcontainers artifact is test-scoped (`pom.xml` dependencies). Developer check 3 agrees.
- [PASS] `camel_security_advisories(camelVersion=4.22.2)` lists 0 matching advisories (76 published).
- [WARN] **The spec-test profile runs without TLS** (`server.ssl.enabled=false`, `smtp.starttls=false`, and plain
  Kafka/JDBC). This is approved in design "Not Doing" and §4. Every peer is loopback or a short-lived container holding
  synthetic data. Not a rule-2 FAIL, because it is a test-only profile that is not packaged (A-4).
- [WARN] **Known from the execution report:** the integration's HTTP port listens on all interfaces during the tests,
  because `server.address` is not in the run file.
- [WARN] **Known from the execution report:** Kafka and PostgreSQL publish on all host interfaces (A-6), and the random
  PostgreSQL password shows in `docker inspect`.
- [WARN] Not verified: whether a Citrus failure report or message trace could print the `X-API-Key` header that Citrus
  keeps on the request message in its test context.
- [WARN] The five security WARNs of 003 are unchanged on identical files:
  - TLS protocols on the SSL bundle;
  - `checkserveridentity`;
  - the request-size limit;
  - the dev endpoint on all interfaces;
  - the SFTP password as a `RAW()` URI parameter.

### Anti-Patterns
- [PASS] The anti-pattern PASS findings of 003 stand on identical files: no body logging, headers removed at ingress and
  egress, a schema at every ingress, and a record-size check.
- [WARN] **New evidence from F8: the error handlers catch only `Exception`.**
  - `partner-order-error-handling` and the read `doCatch` list `java.lang.Exception` (`partner-order-api.camel.yaml:17-20,
    168-171`). The same holds for intake, order-record and product lookup.
  - A `LinkageError` such as F8's `NoSuchMethodError` gets no designed 503. The exchange stays in flight, and shutdown
    waits for it (developer check 4).
  - Whether the partner gets no answer or a default 500 was not verified at runtime.
  - Any fix is under `src/main`, so it belongs to its own pipeline (design §4).
- [WARN] The Pick List total weight is computed in two places, `order-record.sql:47` and
  `V2__partners_and_pick_lists.sql:51` (backfill). A later fix to one can drift from the other. F4 changed only one.
- [WARN] The 11 anti-pattern WARNs of 003 are unchanged:
  - no poison-message cap on the pollers;
  - the narrow `order-check-error-handling`;
  - a Kafka poison record blocking the partition;
  - no split timeout;
  - no circuit breaker;
  - the single-instance assumption of pick-list delivery;
  - and the others listed there.

### Constitution Compliance
- Rule 1 (Route Structure): **PASS.** No route was added or changed. The 003 result stands.
- Rule 2 (Single Responsibility): **WARN.** Carried over: five routes have more than 7 steps. On the test side there is
  one class per test case and one helper per role.
- Rule 3 (Separation of Concerns): **WARN.** Carried over: Groovy business logic in the routes. The tests keep roles,
  test-case logic and traceability apart.
- Rule 4 (Naming Conventions): **WARN.** Carried over: `direct:submitPartnerOrder`. The test classes follow
  `Tc00n<Title>IT`, and `SpecTraceabilityTest` enforces it.
- Rule 5 (Observability): **WARN.** Carried over: the generated `partner-orders-rest-route` has no `description`.
- Rule 6 (External Configuration): **PASS.** Every endpoint the integration uses under test comes from
  `application-spec-test.properties` or the generated run file. The hard-coded harness waits are a test-side quality
  WARN, not integration configuration.
- Rule 7 (Component Verification): **PASS.** No new Camel component, under the matched 4.22.2 binding. The Citrus
  vocabulary was verified on the Citrus 5.0.1 MCP server according to the execution report (binding MATCHED). It was
  not re-queried here (A-5).
- Rule 8 (Infrastructure via Forage): **PASS.** There is no `camel.beans.*`, no YAML `beans` and no `forage.*` key, and
  the application gains no new bean.

## Assumptions (non-interactive run; recommended option taken)

- **A-1:** `developer-checks.md` (untracked, no frontmatter) and `design-review.md` are developer evidence.
  - Checked against the files: the commit contents, the `@Test` counts, the pom pin, and the code sites of F1–F5, F7
    and F8.
  - Not rerun: the build times, exit codes, the `dependency:list` output, and the eleven fault-injection runs. They are
    taken as reported.
- **A-2:** The routes and `application.properties` have identical git contents to the files validated in 003, so the
  route-level findings of 003 are carried over. Only two route files were sent to the schema validator again, in full.
- **A-3:** The exact change for F6 is not described, so it was not verified where the reverse order was introduced. The
  candidates are listed under Quality Checks.
- **A-4:** The no-TLS spec-test profile is treated as an approved test configuration (design "Not Doing", §4), not as a
  checklist rule-2 violation. It is not packaged and talks only to loopback or ephemeral containers.
- **A-5:** The Citrus MCP evidence in the execution report was not re-queried. The design puts that check in planning
  and execution.
- **A-6:** `target/` was not used as evidence. It is local build output, not the developer's clean-copy run.

## Recommendations

**Priority fixes (FAIL items):**
1. **S-1 to S-4.** These are unchanged since 003. Either have the integration architect re-accept them for 004, as 003
   decisions 2 and 3 did, or close them:
   - S-1/S-2: take the dev secrets out of git and commit a template instead;
   - S-3/S-4: close OQ-038 and OQ-039.

**Suggested improvements (WARN items):**
2. **Close the fault-injection gaps with fast guards.** None of these needs a change to an Approved test case:
   - **F7:** a surefire test that parses `peakmart-order-intake.camel.yaml` and asserts three things:
     `commit-kafka-offset` is the route's last step, `CamelKafkaManualCommit` appears nowhere else, and
     `autoCommitEnable: false` is set. There is a precedent: `PickListCsvTest:106-112` already checks a route file as
     text. This is very cheap and needs no Docker.
   - **F8:** a surefire test that runs a `json-validator:` endpoint on the Peakmart sample in a `DefaultCamelContext`.
     It fails in seconds on `NoSuchMethodError` if the pin goes. Or use a maven-enforcer `bannedDependencies` rule
     against `com.networknt:json-schema-validator:[3,)`.
   - **F6:** a JUnit test of `OrderChecks.evaluate` with two reasons, for example a structural reason plus an inactive
     SKU, asserting their order. If the SQL position is also in scope, add a Testcontainers PostgreSQL test of
     `order-reject.sql` and the three selects, under failsafe so that `-DskipITs` stays Docker-free.
   - **F5:** let `WarehouseSftp.deliveredFolder` take a `ChannelSftp` so that a mocked channel can be unit tested.
     That is a `src/main` change, so it goes to its own pipeline. Otherwise, add a failsafe test against an SFTP
     container with a file in `processed/`.
   - Pass the two proposed test cases (a Pick List already in `processed/` on retry, and TC-003 with two reasons) to
     the spec owner, as `developer-checks.md` item 5 says. Optionally, run `kill-test.sh` in the chapter 9 CI.
3. **Harness robustness:**
   - In P-cleanup (TC-001, TC-005), snapshot the `partner` row before the cleanup and compare afterwards. Skip the
     comparison when C-setup did not register the partner.
   - Before `createTopics`, probe `describeCluster`, and retry with a short per-attempt timeout inside the startup
     limit.
   - Give each IT its own consumer group, or call `clearRequests()` after the previous events are drained.
4. **Make `SpecMatrix.covers` exact:** accept only the split children that the design defines (`V3a`/`V3b` and the
   named `S3-alert`/`S4-lookup`), and reject unknown suffixes. Also document the `-DskipITs` behaviour on completeness
   violations, or align it with §3.9.
5. **Amend design and plan for `C-alert`** (TC-004, UC-006) and add the item to `Tc004…IT.ITEMS`. Fold in the other
   documented deviations at the same time: the `citrus-junit-jupiter` name, the json-schema-validator pin, and the JDBC
   Postconditions.
6. **For a later `src/main` pipeline:**
   - give `partner-order-error-handling` (and intake) a `java.lang.Throwable` or `LinkageError` fallback that answers
     the designed 503 and ends the exchange;
   - compute the total weight in one place.
7. **Minor:**
   - move the hard-coded harness waits to `spec-test.properties`;
   - check that each SKU is asked for once in TC-003 S2;
   - add `server.address=127.0.0.1` to the run file;
   - fix the README "34 unit tests" row.
