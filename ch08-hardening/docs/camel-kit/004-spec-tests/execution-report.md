---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-14T00:26:02.957816Z"
  by: "camel-execute"
  from: "implementation-plan.md"
---
# Execution Report — 004-spec-tests (Executable Spec Tests TC-001 to TC-005)

```
===============================================================
IMPLEMENTATION COMPLETE
===============================================================

Pipeline: 004-spec-tests
Plan: docs/camel-kit/004-spec-tests/implementation-plan.md
Design Spec: docs/camel-kit/004-spec-tests/design-spec.md

Tasks Completed: [19/19]

Review Results:
  Adversarial (ACR): 19/19 passed (after fixes; trade-offs below)
  Spec Compliance:   19/19 tasks passed
  Code Quality:      19/19 tasks passed (no open Critical/Important; Suggestions noted)

Cross-Cutting Review: PASS

Verification: PASS
===============================================================
```

**Mode:** standalone (`/camel-execute 004-spec-tests` in a new session; the run was interrupted once during Wave 5 and
resumed from the files on disk). `camel-validate` is not auto-invoked. Both inputs passed `camel-kit doc check` (not
stale; plan `generated.by camel-plan`, `from design-spec.md`; design `generated.by camel-brainstorm`).
`camel-kit plan analyze`: 19 tasks in 13 waves, dependencies consistent with the plan metadata.

## Generated / changed files

Changed:
- `pom.xml` — Citrus BOM 5.0.1 (after the Boot and Camel BOMs), the §3.10 test dependencies, failsafe, surefire
  exclusion, `json-schema-validator` held at 2.0.1 (see Defects), `skipITs`/`skipTests` properties, exec execution
  `spec-traceability-matrix` at `post-integration-test`.
- `README.md` — § Build only.

Created (all test-side):
- `src/test/resources/spec-test/application-spec-test.properties`, `src/test/resources/spec-test/spec-test.properties`,
  `src/test/resources/testcontainers.properties`
- `src/test/java/com/trailheadgear/orderintegration/spec/`: `SpecTestCase`, `TestCaseDocument`, `TestCaseDocumentTest`,
  `SpecTraceabilityTest`, `SpecMatrix`, `Tc001PartnerOrderToWarehouseIT`, `Tc002PeakmartRedeliveryShippedOnceIT`,
  `Tc003PeakmartOrderRejectedIT`, `Tc004SetAsideOrderEventReachesWarehouseIT`, `Tc005PickListOnceAfterRestartIT`
- `.../spec/support/`: `SpecRun`, `FreePorts`, `WarehouseHostKeys`, `ProductCatalogStub`, `Mailboxes`, `SpecEnvironment`,
  `IntegrationProcess`, `Partner`, `Peakmart`, `WarehouseInbox`, `OrderDatabase`

Not changed (checked with git by the cross-cutting review): `src/main/**`, `config/**`, `docs/**` outside this
pipeline's folder, `prototype/**`, the existing unit tests, `src/test/scripts/**`, `.camel-kit/**`.

Commit state: the session wrapper committed the work up to Wave 5 as `b6a8084 Session 04-execute`. The later changes
(the files listed by `git status`: the five ITs, `SpecMatrix`, `SpecTraceabilityTest`, `OrderDatabase`, `Peakmart`,
`WarehouseInbox`, and changes to `pom.xml`, `README.md`, `IntegrationProcess`, `SpecEnvironment`) are **not committed**.
They must be committed together: the uncommitted `pom.xml` refers to `SpecMatrix`.

## Verification (Step 3.5)

Final `./mvnw verify` (Docker through Testcontainers only), exit code 0, BUILD SUCCESS:
- surefire: `Tests run: 54, Failures: 0, Errors: 0, Skipped: 0` (34 existing + `TestCaseDocumentTest` 10 +
  `SpecTraceabilityTest` 10)
- failsafe: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`
- `target/spec-tests/traceability-matrix.md` / `.json` written, no completeness violation:

```
TC-001: 12 run (12 passed), 5 not yet testable (UC-005 ×4, UC-006 ×1)
TC-002: 14 run (14 passed), 5 not yet testable (UC-005 ×4, UC-006 ×1)
TC-003: 9 run (9 passed), 5 not yet testable (UC-005 ×5)
TC-004: 5 run (5 passed), 15 not yet testable (UC-006 ×4, UC-008 ×11)
TC-005: 15 run (15 passed), 5 not yet testable (UC-005 ×5)
```

Also run by Task 18: `./mvnw verify -DskipITs` and `./mvnw verify -DskipTests`, both BUILD SUCCESS, matrix written with
every run item `not run`. A full run takes about 4–5 minutes on this machine.

No fully covered test case yet: all five keep not-yet-testable items, which are the ready list for the UC-005, UC-006 and
UC-008 pipelines (matrix section "Not yet testable, by use case").

## Required by the design (§7 "Required in the execution report")

### Container images (pinned; resolved 2026-11-13 from Docker Hub, index digests)

| Image | Reference |
|---|---|
| Kafka | `apache/kafka:3.9.1@sha256:4ceccc577f03f51f6af8dbfda55194d0d892f4fa7913ffbded567ce3895622ed` |
| PostgreSQL | `postgres:16.16-alpine3.24@sha256:0adbf25a9e96d38823bb813f3ac0ed41fadb665c88f1df7fe6dbfb060cbdc106` (newest 16.x Alpine tag; server reports `160016`) |
| Warehouse SFTP | `atmoz/sftp@sha256:c5fb9e4beb8b7054a4c0b65c5e11df47cc20da95ad6002e88aecff62bab06372` (digest of the `alpine` variant; a single amd64 image, emulated on arm64 hosts) |
| Ryuk | `testcontainers/ryuk:0.14.0@sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0` (the Testcontainers 2.0.5 default) |

The digests were read from the Docker Hub tag API (`hub.docker.com/v2/repositories/...`) because the Docker CLI needed
an approval nobody could give in this non-interactive run. Plan P-8 named `docker buildx imagetools inspect`; same
value (the index digest). `SpecEnvironment` rejects any reference without a 64-hex `@sha256:` digest, and pulls and
creates containers as `<repo>@sha256:<digest>`.

### Citrus MCP evidence (Citrus 5.0.1; server `citrus-mcp-server:5.0.1` = `citrus.version`, binding MATCHED)

Re-checked on the server during execution:
- `endpoint:http-server` (module citrus-http): `port`, `autoStart`, `endpointAdapter`, `connector`/`connectors`, … — no
  host option; the catalog stub binds 127.0.0.1 through a Jetty `ServerConnector` passed as `connector`.
- `endpoint:http-client`: `requestUrl`, `contentType`, `timeout`, `interceptors` (used to replace the default logging
  interceptor so the `X-API-Key` header never reaches a log).
- `endpoint:mail-server` (module citrus-mail): `port`, `autoAccept`, `autoStart`, `splitMultipart`, `authRequired`,
  `smtp`, `timeout`, … — no bind-address option.
- `endpoint:kafka-asynchronous` / `kafka-synchronous` (module citrus-kafka): `server`/`bootstrapServers`, `topic`,
  `keySerializer`, `valueSerializer`, … (no plain `kafka` endpoint).
- Mail header names (docs page `endpoint-mail.adoc`): `citrus_mail_to`, `citrus_mail_subject` (also `_from`, `_cc`,
  `_bcc`, `_date`; the docs say `citrus_mail_replyTo`, the source constant is `citrus_mail_reply_to`; not used).
- `action:waitFor` (`action`, `interval`, `timeout`, …), `action:expectTimeout` (`endpoint`, `selector`/`select`, `wait`).
- Verified at planning and not contradicted at execution: `send`, `receive`, `http`, `sql`, `repeatOnError`, `sleep`.
- Artifact: the Citrus 5.0.1 JUnit Jupiter module is **`citrus-junit-jupiter`** (the design expected `citrus-junit5`;
  confirmed in the 5.0.1 BOM).
- Not used: `endpoint:sftp-client`, `action:testcontainers-start-*` (A-4, A-5).

### PostgreSQL version finding (design review decision 3)

`dev` runs PostgreSQL 17 (`camel infra run postgres`, since chapter 5), while C-004 and these tests use PostgreSQL 16.
Not changed here; the dev setup changes in chapter 9.

### Defects exposed by the tests

- **Integration (`src/main`): none.** All five tests pass against the Approved documents.
- **Build defect introduced by this pipeline (Task 1), found by TC-001 and fixed:** `citrus-validation-json` (test
  scope) brings `com.networknt:json-schema-validator:3.0.4`. By Maven's nearest-wins rule it replaced Camel's 2.0.1 at
  **compile** scope, so it would have gone into the shipped jar. `camel-json-validator` 4.22.2 then fails every
  partner order with `NoSuchMethodError: SchemaRegistry.getSchema(...)`. Because that is an `Error`, the route's
  `doCatch Exception` misses it and the exchange stays in flight. Fix: `dependencyManagement` holds
  `json-schema-validator` at 2.0.1, the version `camel-json-validator` 4.22.2 declares. The runtime classpath is now
  identical to pipeline 003's (202 entries, checked with `dependency:list -DincludeScope=runtime` before and after;
  the jar contains only `json-schema-validator-2.0.1.jar`). Citrus JSON (non-schema) validation works with 2.0.1. Do
  not use Citrus JSON *schema* validation while the pin stands, and revisit the pin on a Camel upgrade.

## §7 confirmations

- Testcontainers 2.0.5 artifacts under the Boot 4.1.1 BOM: `testcontainers-junit-jupiter`, `testcontainers-kafka`,
  `testcontainers-postgresql`. Classes `org.testcontainers.kafka.KafkaContainer`,
  `org.testcontainers.postgresql.PostgreSQLContainer`.
- Testcontainers 2.0.5 reads `ryuk.container.image` from the classpath `testcontainers.properties`. This was confirmed
  in `TestcontainersConfiguration` and at runtime ("substituted by configuration"). Testcontainers logs that this
  substitution approach is deprecated.
- Compatibility (Step 7): Spring 7.0.9, Camel 4.22.2, kafka-clients 4.2.1, Jetty 12.1.12, jackson-databind 2.21.5 / 3.1.5
  stay Boot/Camel-managed. Every `org.citrusframework` artifact is 5.0.1 at test scope.
- atmoz/sftp uses the supplied host keys: strict known_hosts connections from the test and the integration succeed. Its
  user spec creates the nested folders, and delivery into `inbound/picklists` works. The password is set by an
  `/etc/sftp.d` script from a 0600 file, because atmoz prints the user spec to its log. The negotiated host-key
  algorithm is not logged; Ed25519 is likely.
- JSch (mwiede 2.28.6) generates Ed25519 host keys on this JVM. Note that the JVM is **Java 25.0.3**, not 21; the build
  targets 21.
- Readiness marker: `Started CamelApplication in <n> seconds`. Both config locations take effect: the generated
  `server.port` is used, the 1 s pick-list poll is visible (delivery about 2 s after recording), and the folders are
  the relative spec-test values.
- Bind addresses: the catalog stub and the mail server both bind 127.0.0.1 (see deviations), and so does the SFTP
  container. Kafka and PostgreSQL use the random default mapping (A-6).

## Decisions and deviations (non-interactive run: recommended option taken)

1. **Review execution.** Each wave had one fresh reviewer that ran the stages in order: adversarial (Route
   Architecture + Security, plus Boundary Compliance in Wave 5), then spec compliance, then quality. The critic lanes
   did not run in separate fresh contexts; this is recorded as reduced isolation. Tasks 1, 2, 4 and 19 were implemented
   inline by the orchestrator (implementation-engineer persona), because it held the research. The Step 3
   cross-cutting review was run by the same reviewer as Task 19's review, after that review.
2. **Environment probe.** Dependency resolution ran in a temp pom: PASS. The Docker CLI check was not possible (approval
   needed), so Docker reachability was proven by Testcontainers in TC-001. The skeleton `spring-boot:run` was skipped
   because it would bind the taken port 8080.
3. **Mail server bound to 127.0.0.1** through its catalog-listed `smtp` option (a GreenMail `ServerSetup`). Plan P-10
   expected "binds all interfaces"; design §4 asks for loopback "where the endpoint allows it", and it does.
4. **C-setup order (all ITs):** the integration starts first, then the cleanup runs, because Flyway creates the tables.
5. **Logging guard:** `SpecEnvironment` raises the Logback root level to INFO before the first Docker call. The failsafe
   JVM has no logging config, and at DEBUG docker-java, Citrus and the HTTP transport would log the PostgreSQL password,
   the SFTP password file and the key header.
6. **Additions found by review and kept:**
   - `IntegrationProcess` checks the log for secrets after stop as well as after start, and exposes `lastStopGraceful()`.
     TC-005 S5 asserts it (A-13).
   - `Peakmart.committedOffsets()`: negative checks wait until the integration has committed the events before the quiet
     period starts, so "nothing more happens" cannot pass vacuously.
   - `SpecRun.bodyCompleted()`/`executeAlways()`, so that cleanup runs and never masks the first failure.
   - `SpecMatrix` also honours `-DskipTests` and `-Dmaven.test.skip`, rejects result files that disagree with `ITEMS`,
     fails when no Approved test case exists, and `SpecEnvironment` deletes earlier result files once per run.
7. **TC-001 S2 and the other catalog checks** compare the requested SKUs as a set: each SKU exactly once, any order. The
   integration looks them up concurrently, and the documents state no order.

## Trade-offs accepted (for follow-up, not defects)

- **The integration's HTTP port listens on all interfaces** during the tests. `server.address` is not in the P-7
  run-file key list. The exposure is limited: the port is random and short-lived, a random key is required, and the
  data is synthetic. To close it, add `server.address=127.0.0.1` to the run file; that needs a plan amendment.
- **Kafka and PostgreSQL** publish on all host interfaces (A-6). The PostgreSQL password is visible in the container
  environment (`docker inspect`); it is random per run.
- **Mail checks use exact subjects** (the routes' subjects, e.g. `Peakmart order PM-50118 rejected`), backed by a
  `notification` table check. Validation-matcher support in selectors could not be confirmed.
- **Order Database Postconditions** are asserted with JDBC through `OrderDatabase` plus JUnit. The cleanup and some
  steps use the Citrus `sql` action. Design §3.2 names Citrus `sql` for the Postconditions; the assertions are equally
  strong.
- **Design gap, TC-004:** the precondition "the Integration Team mailbox holds no Alert for event id …" has no
  `C-alert` not-yet-testable item, because design §3.2/§3.7 and plan Task 18 name only `C-helpdesk` and `C-resubmit`.
  It is not checked and not faked. Recommended amendment: add
  `notYetTestable("C-alert", …, UC_006)` in design, plan and `Tc004…IT.ITEMS`.
- **One split child covers its parent** in `SpecMatrix` (e.g. `V3a` alone would cover TC-002 V3).
- **Stale README line:** the Layout table still says "34 unit tests", outside § Build, so it was left unchanged.
- **One transient environment failure:** a TC-005 run once failed before any item with `Could not connect to Ryuk`.
  The immediate retry passed, and no later run showed it again.

Non-critical suggestions noted by reviewers (not applied): shared helpers for code copied in the five ITs (`cleanUp`,
`keep`, `check`, `docsFile`); a uniform `waitFor` poll interval (1 s vs 2 s); consistent split-item naming
(`V3a`/`V3b` vs `S3-alert`/`S4-lookup`); a shared `ObjectMapper` in `SpecMatrix`; removing the unused helper in
`SpecTraceabilityTest`; a guard test for the json-schema-validator pin.

## Processes

Every process this run started has stopped. Each integration child process stopped in `finally` (SIGTERM, exit 143).
Maven ran in the foreground or as a completed background command. Containers were started and removed only by
Testcontainers/Ryuk. No `docker`, `docker compose` or `camel infra` command was run. Ports 8080, 8081 and 5432 were
never bound.
