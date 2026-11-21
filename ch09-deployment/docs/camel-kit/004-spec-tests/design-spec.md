---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T21:56:29.071224Z"
  by: "camel-brainstorm"
---
# Trailhead Order Integration — Executable Spec Tests (TC-001 to TC-005) — Design Spec

**Date:** 2026-11-13
**Camel Version:** 4.22.2 (unchanged; this pipeline adds no route)
**Runtime:** Spring Boot 4.1.1 (Maven layout), Java 21
**Platform BOM Version:** 4.22.2 (`org.apache.camel.springboot:camel-spring-boot-bom:4.22.2`; catalog
`org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2`)
**Test framework:** Citrus 5.0.1 (`citrus.version` in `.camel-kit/config.properties`), JUnit Jupiter, Testcontainers
(version managed by the Spring Boot 4.1.1 BOM)
**Status:** Approved 2026-11-13 by the integration architect, with the changes of the design review
(`docs/camel-kit/004-spec-tests/design-review.md`, decisions 1 to 3, applied in §2, §3.1, §4, §5, §6 and §7). The
open decisions of the design session took the recommended option. They are listed in §7, and the review accepted them.

**Starting point:** the accepted project of pipeline 003 (`docs/camel-kit/003-partner-orders-pick-lists/`, including its
`validation-decisions.md` and `developer-checks.md`). Nothing under `src/main/` changes.

**Sources:** `docs/test_cases/TC-001` … `TC-005` (all `Approved`), `docs/use_cases/UC-001` … `UC-008`,
`docs/messages/examples/**`, `docs/api/**`, `docs/requirements.md` (C-004, NFR-001, NFR-014), the routes under
`src/main/resources/camel/`, `application*.properties`, `config/application-dev.properties`, `README.md`, and the
request for this pipeline.

## Not Doing (and Why)

- **Building UC-005 (order lookup), UC-006 (alerts), UC-008 (resubmission), or stubbing them:** they are not built yet.
  Faking them would make a test case pass on behaviour that does not exist. The steps, Validations and Postconditions
  that need them are reported as *not yet testable*, with the use case named (§3.2). The pipelines that build those use
  cases turn them into running steps (§8).
- **Hardening and production-profile tests (TLS to PostgreSQL, STARTTLS, partner endpoint over HTTPS, request-size
  limits, the 500-instead-of-503 warning of 003):** these belong to chapter 8. The tests run the integration with a
  test profile that has no TLS, like `dev`.
- **Load and timing tests (NFR-001 15 minutes as a measured SLO, NFR-002 response times, throughput):** out of scope by
  request. A timing a test case states ("within 15 minutes", "within 5 minutes") is asserted with a much shorter wait
  bound, which also proves the stated limit (§3.3, A-9). It is not a performance measurement.
- **Wall-clock waits of 15 minutes for negative checks** ("15 minutes after step 5 still one file"): one `verify`
  would take more than an hour. The tests use a quiet period of several poll cycles of the poller involved, with poll
  intervals shortened in the test profile (A-10).
- **A kill -9 restart in TC-005:** the test case says "stopped and started again". The graceful stop/start is tested.
  The SIGKILL case between receipt and Kafka commit already has its own kill test (`src/test/scripts/kill-test.sh`),
  which stays as it is.
- **Changing the five test cases or the use cases:** the tests implement the documents as approved. Where a document
  and the code disagree, the test fails and the disagreement is reported. Neither side is adjusted to make it pass.
- **Rewriting the 34 existing unit tests, or the shell scenario scripts, as Citrus tests:** they keep running in
  `./mvnw verify` as they are. The scenario scripts keep serving manual runs against `dev`.
- **Running the tests against the shared test environment of the organisation:** none exists yet (chapter 9). Every
  role is played by a container or an in-JVM server that the test run itself starts.
- **Code coverage reporting:** the integration runs as a separate process (§3.1). Coverage tooling for it is not
  requested.

---

## 1. Executive Summary

### Business Purpose
The five approved test cases describe the integration as its users see it: a partner, Peakmart, the Product Catalog,
the warehouse and the mailboxes. This pipeline makes each test case an automated test. The test plays those roles
against the packaged integration and checks the test case's Validation and Postconditions. Every test runs in
`./mvnw verify` with only Docker available, so the CI pipeline of chapter 9 can run it unchanged.

### Business Value
- The approved test cases become a regression suite. A change that breaks partner orders, Peakmart de-duplication,
  rejections, set-aside handling or exactly-once pick lists fails the build.
- Traceability is enforced by the build. An Approved test case without a test, or a test without its test case, fails
  `verify`. A generated matrix shows, for each use case, which test case steps run and which wait for an unbuilt use
  case.
- Steps that wait for UC-005, UC-006 and UC-008 stay visible instead of silently missing. They form a ready list for
  the pipelines that build those use cases.

### Stakeholders
Integration Team (owns the build and CI), Order Desk lead (product owner of the test cases), Warehouse Team and Partner
Manager (their contracts are what the tests check), and the chapter 9 CI pipeline (runs `./mvnw verify`).

---

## 2. Systems Landscape (test environment)

Every role is started by the test run and removed afterwards. Nothing runs before `./mvnw verify`, and no script starts
or stops a container. Testcontainers starts the containers, and Ryuk removes them. Host ports are random or picked free
at runtime. Ports 8080, 8081 and 5432 are never bound, and they are excluded explicitly from the free-port picker.

| Role in the test cases | Played by | Protocol | Lifetime |
|---|---|---|---|
| The integration (system under test) | The packaged jar `target/order-integration-0.1.0.jar`, started by the test as a child process with profile `spec-test` (§3.1) | — | One process per test class. TC-005 stops and starts it once |
| Partner Summit Outfitters | Citrus `http-client` in the test JVM, `POST /orders` with `X-API-Key` | HTTP (plain, as `dev`) | Per test |
| Peakmart | Citrus `kafka` endpoint (producer) on topic `peakmart.orders.v2` | Kafka | Per test |
| Peakmart order topic | Testcontainers Kafka, image `apache/kafka:3.9.1` with its digest (broker version not named, OQ-038) | Kafka | One per `verify` run (singleton) |
| Order Database | Testcontainers PostgreSQL, an exact PostgreSQL 16 image: patch and Alpine release in the tag (form `postgres:16.<minor>-alpine3.<n>`) plus its digest. C-004: the team's instance is PostgreSQL 16 (A-18). Flyway V1+V2 are run by the integration itself | JDBC | Singleton |
| Product Catalog | Citrus `http-server` in the test JVM, with an endpoint adapter that answers `GET /products/{sku}` from `docs/messages/examples/product/*.json` and records the SKUs asked | HTTP | Singleton (it answers throughout, including across the TC-005 restart) |
| Order Desk mailbox, Order Contact mailbox, Integration Team mailbox | One Citrus `mail-server` (SMTP) in the test JVM. Mailboxes are told apart by recipient address | SMTP (no STARTTLS, as `dev`) | Singleton |
| Warehouse SFTP Server | Testcontainers `atmoz/sftp` (the `README.md` dev image). It publishes no version tags, so it is pinned by digest only (`atmoz/sftp@sha256:…` of the Alpine variant); the floating `alpine` tag is not used. User `admin`, folders `inbound/picklists` and `inbound/picklists/processed`. Host bound to `127.0.0.1` only, as in `README.md` | SFTP | Singleton for TC-001 to TC-004. TC-005 has its own container (§3.8) |
| Warehouse Team (reads pick lists) | `WarehouseInbox` test helper: JSch over SFTP, lists both folders, reads a file, parses the CSV | SFTP | Per test |
| Secret Store | A directory under `target/spec-tests/secret-store/`. The API Key `partner-api-key-summit-outfitters` is generated randomly for each run. It is never committed and never printed | file | Per run |
| Partner Configuration | `docs/messages/examples/partner-configuration/summit-outfitters.json`, as released (UC-007) | file | Read-only |

**Pinned images (design review, decision 2).** The tests must start the same systems next month. Every container image
the test run starts is pinned to an exact version, never a floating tag (no `16-alpine`, `alpine`, `3`, or `latest`):

| Image | Pin | Where it is set |
|---|---|---|
| Kafka | `apache/kafka:3.9.1@sha256:<digest>` | `spec-test.image.kafka` |
| PostgreSQL | `postgres:16.<minor>-alpine3.<n>@sha256:<digest>` (exact patch and Alpine release) | `spec-test.image.postgres` |
| Warehouse SFTP | `atmoz/sftp@sha256:<digest>` (no version tags exist) | `spec-test.image.sftp` |
| Testcontainers Ryuk (the reaper Testcontainers starts itself) | `testcontainers/ryuk:<exact version>@sha256:<digest>` | `ryuk.container.image` in `src/test/resources/testcontainers.properties` |

The `<…>` parts are chosen once, during implementation, from the images current at that time, and committed. They are not
open in this design. `SpecEnvironment` refuses to start a container whose configured reference has no `@sha256:` digest,
so a floating reference fails the run instead of drifting. The execution report records every image with its tag and
digest (§7, "Required in the execution report").

---

## 3. Design

This pipeline adds no Camel route. The "flows" below are the test harness, the five tests and the traceability check.
The target module is the project root.

### 3.1 The integration under test: the packaged jar as a process

| Field | Value |
|---|---|
| Target Module | (project root) |

**Purpose:** run the integration exactly as it is shipped, and reach it only through its external interfaces.

- The tests run in Maven's `integration-test` phase (maven-failsafe-plugin, version from the Spring Boot parent),
  after `package`. The jar is fat (spring-boot-maven-plugin repackage) and contains the bundled partner contract made
  at `prepare-package`. The tests therefore check the artifact that chapter 9 deploys. Surefire's `test` phase would
  run before the bundle exists.
- `IntegrationProcess` (test helper) starts `java -jar <jar> --spring.profiles.active=spec-test
  --spring.config.additional-location=<run dir>/`. It uses the same `java` that runs the test JVM
  (`ProcessHandle.current().info().command()`) and sets the working directory to the run directory, so the project's
  `./config/` is not read.
- **Configuration:**
  - `src/test/resources/spec-test/application-spec-test.properties` holds the static test settings (below).
  - `IntegrationProcess` writes a generated `application-spec-test-run.properties` into
    `target/spec-tests/run/<TC-id>/`. It holds the dynamic values: the Kafka bootstrap servers, the JDBC URL, user and
    password, the catalog port, the SMTP port, the SFTP host, port and known-hosts file, `server.port`, the partner
    configuration path and the secret-store path.
  - No value goes on the command line or into the environment, so test credentials do not show up in `ps`.
- **Profile `spec-test`:** because a profile is active, `application-default.properties` (environment only) is not
  loaded, and `RequiredVariablesCheck` finds no `${NAME}` without a value. The profile works like `dev`: no TLS, no SMTP
  login.
- **Readiness:** stdout and stderr go to `target/spec-tests/logs/<TC-id>[-<n>].log`. Startup is complete when the log
  shows Spring Boot's `Started CamelApplication` line and `server.port` accepts a TCP connection, within
  `spec-test.startup-timeout-ms` (120 s). Otherwise the test fails and shows the last 50 log lines. The partner key and
  passwords never appear in the log (NFR-011 log masking is unchanged).
- **Stop:** `Process.destroy()` (SIGTERM, a graceful Spring Boot shutdown), with a wait of up to 30 s, then
  `destroyForcibly()`. The `finally` block of every test always stops the process.

**Static test settings** (`application-spec-test.properties`):

```properties
# Profile spec-test: the integration under the executable spec tests (design 004 §3.1). No TLS, no SMTP login, like dev.
server.ssl.enabled=false
smtp.starttls=false
orderdesk.mailbox=order-desk@trailhead.example
orderdesk.sender=order-integration@trailhead.example
partner.mail-sender=orders@trailhead.example
# Shortened poll intervals (A-10); all three pollers are sql consumers whose `delay` these set
order-check.poll-delay-ms=1000
notification.poll-delay-ms=1000
pick-list.poll-delay-ms=1000
# The atmoz/sftp container maps the folders relative to the login directory, as in dev
warehouse.sftp.inbound-directory=inbound/picklists
warehouse.sftp.processed-directory=inbound/picklists/processed
warehouse.sftp.username=admin
# A fresh topic per run; earliest is the shipped value and is kept so that no event published before the consumer joins is lost
peakmart.auto-offset-reset=earliest
```

The SFTP password is generated per run and written only into the generated run file.

**Test-side settings** (`src/test/resources/spec-test/spec-test.properties`, read by the harness, not the integration):

```properties
spec-test.startup-timeout-ms=120000
# Upper bound for an expected event (pick list, email); far below the test case's own limit (A-9)
spec-test.arrival-timeout-ms=60000
# How long "nothing more happens" is watched: at least 5 poll cycles of every poller (A-10)
spec-test.quiet-period-ms=10000
spec-test.excluded-ports=8080,8081,5432
# Container images, each pinned to an exact version plus digest (design review, decision 2; table in §2):
# spec-test.image.kafka, spec-test.image.postgres, spec-test.image.sftp
```

The image values are written in at implementation, in the form given in §2. Ryuk's image is pinned in
`src/test/resources/testcontainers.properties` (`ryuk.container.image`), because Testcontainers starts it before any
harness code runs.

### 3.2 Test structure and the "not yet testable" report

- **One test class per test case**, in package `com.trailheadgear.orderintegration.spec`. Each class has a single
  `@Test`:

  | Test case | Class | Display name |
  |---|---|---|
  | TC-001 | `Tc001PartnerOrderToWarehouseIT` | `TC-001 Partner Order to Warehouse` |
  | TC-002 | `Tc002PeakmartRedeliveryShippedOnceIT` | `TC-002 Peakmart Redelivery Shipped Once` |
  | TC-003 | `Tc003PeakmartOrderRejectedIT` | `TC-003 Peakmart Order Rejected` |
  | TC-004 | `Tc004SetAsideOrderEventReachesWarehouseIT` | `TC-004 Set-Aside Order Event Reaches Warehouse` |
  | TC-005 | `Tc005PickListOnceAfterRestartIT` | `TC-005 Pick List Once After Restart` |

  Every class carries `@SpecTestCase("TC-00n")` (a new runtime-retained annotation). The display name is the ID and
  the title of the test case document.
- **Citrus Java DSL** with the JUnit Jupiter extension (`@CitrusSupport`, injected `TestCaseRunner`). Citrus endpoints
  play the roles: `http-client` (partner), `kafka` (Peakmart), `http-server` (Product Catalog), `mail-server`
  (mailboxes). Citrus actions do the checks: `send`/`receive` with JSON validation against the example messages,
  `sql` for the Order Database Postconditions and cleanup, `expectTimeout` for "no email", and `waitFor` / repeat-on-
  error for "file appears". Plain Java helpers are used only where Citrus adds nothing (§3.3, A-4).
- **Items.** Each test case is split into items in document order:
  - `S<n>` for the Flow steps;
  - `V<n>` for the Validation items;
  - `P<n>` for the Postcondition bullets, numbered in document order. The "Cleanup order" bullet is `P-cleanup`.

  A Validation that has a testable part and an unbuilt part is split into `V<n>a`/`V<n>b`; this happens only for
  TC-002 V3. Every item is one of:
  - **run:** executed and asserted;
  - **not yet testable:** declared with the use case it needs, for example
    `notYetTestable("S6", "Look up order", UC_005)`. It is never executed and never faked. Items that only depend on an
    earlier not-yet-testable step name that step's use case (TC-004 S5 to S8 name UC-008).
- **Recording.** A small `SpecRun` helper executes items in order and records each outcome: `PASSED`, `FAILED`,
  `NOT_YET_TESTABLE(UC-xxx)`, or `NOT_RUN` (after an earlier failure). It writes
  `target/spec-tests/results/TC-00n.json` and reports every not-yet-testable item through JUnit's `TestReporter` and
  the log, as `TC-001 S6 Look up order: not yet testable, needs UC-005 Look Up Order (not built)`.
- **Outcome.** The `@Test` passes when every *run* item passed, and fails on the first failing item; the items after
  it are `NOT_RUN`. Not-yet-testable items never make the test fail or skip. They are reported, and they show in the
  matrix (§3.9). A test case is "fully covered" only when it has no not-yet-testable item.
- **Preconditions** are established by the test, not assumed:
  - cleanup of the order number at the start (the same statements as `P-cleanup`);
  - absence checks on the server folders;
  - the partner configuration and key in place;
  - the catalog examples served.

  Precondition checks that need an unbuilt use case are recorded as such. These are "the Helpdesk Tool … (OQ-066)",
  which needs UC-005, and "authorised to resubmit … (OQ-067)", which needs UC-008. They count as not-yet-testable items
  `C-helpdesk` and `C-resubmit`.

### 3.3 Shared harness (`com.trailheadgear.orderintegration.spec.support`)

| Helper | Role | Built on | Notes |
|---|---|---|---|
| `SpecEnvironment` | Starts the singleton containers and in-JVM servers once per JVM | Testcontainers Java API, Citrus endpoint builders | Images from the `spec-test.image.*` properties; a reference without digest fails the start (§2). Kafka topic `peakmart.orders.v2` created before the first integration start (precondition "the integration reads the Peakmart order topic"). Containers are reused by all five classes, so one run starts them once |
| `IntegrationProcess` | The system under test | `ProcessBuilder` | §3.1 |
| `Partner` | Partner Summit Outfitters | Citrus `http-client` | Body `partner-order-sample.json` as is (`orderId` `TG-1060`). Header `X-API-Key` from the generated secret file. The answer is validated against `order-answer/accepted.json`, with run-dependent fields ignored by Citrus `@ignore@` |
| `Peakmart` | Publishes Order Events | Citrus `kafka` endpoint | Value = the example file, byte for byte. No key (OQ-038: the key is not named). Topic-offset snapshot before and after for "the integration published nothing" (Kafka `AdminClient` end offsets) |
| `ProductCatalogStub` | Product Catalog | Citrus `http-server` + endpoint adapter | Serves `product/<sku-lowercase>.json` for TENT-2P, LAMP-HD200, STAKE-AL8, TENT-1P (inactive) and 404 for anything else, as `docs/api/external/product-catalog.openapi.yaml`. Records each requested SKU for the S2 assertions |
| `Mailboxes` | Order Desk, Order Contact, Integration Team mailboxes | Citrus `mail-server` (`autoAccept`, `splitMultipart`) | `receive` with a selector on recipient and subject, so other tests' mail is never consumed. "Exactly one" = one `receive` plus `expectTimeout` for the quiet period. The Integration Team address is not configured anywhere yet (UC-006), so alert checks are not-yet-testable rather than "no mail arrived" |
| `WarehouseInbox` | Warehouse Team | JSch (already on the classpath through `camel-ftp-starter`) | Counts `PL-x.csv` across both folders, checks that no `PL-x.csv.tmp` exists, reads and parses the CSV (commons-csv from `camel-csv-starter`, RFC 4180), and compares the rows field by field with `docs/messages/examples/pick-list/pl-*.json`. Citrus `sftp-client` is not used, because it models FTP commands as messages and adds no assertion over a two-folder count (A-4) |
| `OrderDatabase` | Order Database Postconditions and cleanup | Citrus `sql` action on a `DataSource` to the container | Read-only queries plus the cleanup deletes, in the test case's cleanup order: `order_state_change`, `rejection_reason`, `notification`, `pick_list`, `order_line` before `customer_order`, before `order_receipt`; `set_aside_order_event` for TC-004 |
| `WarehouseHostKeys` | The SFTP server identity | JSch `KeyPair` | Ed25519 and RSA host keys generated per run into `target/spec-tests/sftp/`, copied into the container before it starts. `known_hosts` is written as `[127.0.0.1]:<port>` with both keys, before the integration starts. The integration keeps `strictHostKeyChecking=yes` |
| `SpecRun` | Items and results | JUnit `TestReporter`, Jackson | §3.2 |
| `FreePorts` | Ports for the integration, catalog, mail and the TC-005 SFTP server | `ServerSocket(0)` on 127.0.0.1 | Skips 8080, 8081, 5432 |

**Waiting rule (A-9, A-10).** An expected arrival is polled (Citrus `waitFor` / repeat until true) up to
`spec-test.arrival-timeout-ms` (60 s). The test also asserts that the elapsed time is below the test case's own limit
(15 min for a pick list, 5 min for an Order Desk Notification). A "nothing more happens" check waits
`spec-test.quiet-period-ms` (10 s, at least 5 cycles of the 1 s pollers) before it looks.

### 3.4 TC-001 Partner Order to Warehouse — `Tc001PartnerOrderToWarehouseIT`

| Item | How | Assertion | Status |
|---|---|---|---|
| Preconditions | Cleanup of `TG-1060`. Partner configuration from the example, generated key. Catalog serves TENT-2P, LAMP-HD200. No `PL-TG-1060.csv` in either folder | As stated in the test case | run, except `C-helpdesk` (OQ-066): not yet testable, UC-005 |
| S1 Submit partner order | `Partner` POSTs `partner-order-sample.json` with the key | HTTP exchange completes | run |
| S2 Check order | — | Catalog stub was asked for exactly TENT-2P and LAMP-HD200. The order lines are completed: `order_line` holds the product names, the weights and the catalog prices 249.00 / 39.95 (part of P1) | run |
| S3 Verify order accepted | — | Status 200. Body matches `order-answer/accepted.json` for `TG-1060`, with no `rejectionReasons` | run |
| S4 Deliver pick list | — | (outcome checked in S5) | run |
| S5 Verify pick list | `WarehouseInbox` | Within 60 s (< 15 min of S1): `PL-TG-1060.csv` is in `inbound/picklists` or `…/processed`, with the header and two rows equal to `pl-tg-1060.json`, `total_weight_g` 2340 on both rows, and no `.tmp` file | run |
| S6 Look up order | — | — | **not yet testable — UC-005** |
| S7 Verify order history | — | — | **not yet testable — UC-005** |
| V1 One Pick List | After the quiet period | Exactly one `PL-TG-1060.csv` across both folders | run |
| V2 Final Order State (lookup) | — | — | **not yet testable — UC-005** |
| V3 No rejection | `Mailboxes` | `expectTimeout` over the quiet period: no mail to `orders@summit-outfitters.example.com` whose subject names `TG-1060` | run |
| V4 No Alert | — | — | **not yet testable — UC-006** |
| P1 Accepted Order recorded | `OrderDatabase` | One `customer_order` `TG-1060` of partner `summit-outfitters`, with lines TENT-2P × 1 @ 249.00 and LAMP-HD200 × 2 @ 39.95 (USD), and the address Lee Martin, 18 Canyon Road, Moab 84532, US | run |
| P2 Order History | `OrderDatabase` | `order_state_change` for the receipt holds Received, Recorded, Sent to Warehouse, each once, in this order | run |
| P3 Pick list on server | `WarehouseInbox` | `PL-TG-1060.csv` in either folder | run |
| P-cleanup | `OrderDatabase`, `WarehouseInbox` | Order History deleted with or before the Order; file removed; partner configuration and catalog data untouched | run (in `finally`) |

### 3.5 TC-002 Peakmart Redelivery Shipped Once — `Tc002PeakmartRedeliveryShippedOnceIT`

| Item | How | Assertion | Status |
|---|---|---|---|
| Preconditions | Cleanup of `PM-50117`. Catalog serves TENT-2P, STAKE-AL8. No file | — | run, except `C-helpdesk`: not yet testable, UC-005 |
| S1 Publish peakmart order | `Peakmart` sends `peakmart-order-placed-sample.json` | Sent | run |
| S2 Check order | — | Catalog asked for TENT-2P and STAKE-AL8. `order_line` keeps the Peakmart prices 229.00 and 12.50 | run |
| S3 Deliver pick list / S4 Verify pick list delivered | `WarehouseInbox` | Within 60 s (< 15 min): `PL-PM-50117.csv` equals `pl-pm-50117.json`, two rows, street line `2240 Pearl Street, Apt 4`, total weight 2390 | run |
| S5 Redeliver peakmart order | `Peakmart` sends `pm-50117-redelivered.json` (new event id) | Sent | run |
| S6 Verify no second pick list | After the quiet period | Exactly one `PL-PM-50117.csv` across both folders, and no `.tmp` | run |
| S7, S8 Look up / single order | — | — | **not yet testable — UC-005** |
| V1 Shipped once | — | Exactly one file was delivered during the journey: one file on the server, and the single `pick_list` row is `Delivered` | run |
| V2 Recorded once (lookup) | — | — | **not yet testable — UC-005** |
| V3a Redelivery silent: Order Desk | `Mailboxes` | `expectTimeout`: no Order Desk Notification for `PM-50117` | run |
| V3b Redelivery silent: no Alert | — | — | **not yet testable — UC-006** |
| P1 Accepted Order recorded | `OrderDatabase` | One `customer_order` `PM-50117`, channel Peakmart, TENT-2P × 1 @ 229.00 and STAKE-AL8 × 2 @ 12.50, address as stated. One `order_receipt` for `PM-50117` | run |
| P2 Order History | `OrderDatabase` | Received, Recorded, Sent to Warehouse, each once | run |
| P3 Pick list on server | `WarehouseInbox` | — | run |
| P4 Events stay; nothing to Peakmart | `Peakmart` offset snapshot | The topic grew by exactly the 2 records the test sent | run |
| P-cleanup | — | — | run |

### 3.6 TC-003 Peakmart Order Rejected — `Tc003PeakmartOrderRejectedIT`

| Item | How | Assertion | Status |
|---|---|---|---|
| Preconditions | Cleanup of `PM-50118`. Catalog serves `tent-1p-inactive.json`. No Order Desk Notification for `PM-50118` is pending in the mail server's queue (selector receive with a zero-wait check). No file | — | run, except `C-helpdesk`: not yet testable, UC-005 |
| S1 Publish peakmart order | `Peakmart` sends `pm-50118-inactive-sku.json` | — | run |
| S2 Check order | — | Catalog asked for TENT-1P. One `rejection_reason` (`sku`, line 1) | run |
| S3 Verify order desk notification | `Mailboxes` | Within 60 s (< 5 min): one plain-text mail to `order-desk@trailhead.example`, subject `Peakmart order PM-50118 rejected`. The body contains `PM-50118` and the reason text of `order-desk-notification/pm-50118.json` with Order Line 1. It contains none of the buyer's name, email address or shipping address fields taken from the event | run |
| S4, S5 Look up / rejection reason shown | — | — | **not yet testable — UC-005** |
| V1 No Pick List | After the quiet period | No `PL-PM-50118.csv` or `.tmp` in either folder | run |
| V2 Same reasons everywhere (notification vs lookup) | — | — | **not yet testable — UC-005** |
| V3 One notification | `Mailboxes` | After the S3 receive, `expectTimeout` for a second one | run |
| V4 Never recorded (lookup) | — | — | **not yet testable — UC-005** (the data side is covered by P1) |
| P1 Rejected, not recorded | `OrderDatabase` | `order_receipt` `PM-50118` in state Rejected. Order History Received, Rejected only. One reason (`sku`, line 1). No `customer_order` | run |
| P2 Notification kept | `OrderDatabase`, `Mailboxes` | `notification.sent_at` is set. The mail was received (S3) | run |
| P-cleanup | `OrderDatabase` | `notification` and `rejection_reason` deleted before the Order History and the receipt. The mail was consumed by the receive, so the mailbox is empty for this order | run |

### 3.7 TC-004 Set-Aside Order Event Reaches Warehouse — `Tc004SetAsideOrderEventReachesWarehouseIT`

| Item | How | Assertion | Status |
|---|---|---|---|
| Preconditions | Cleanup of `PM-50119` and of the set-aside event `1f4e9a7c-…`. Catalog serves TENT-2P. No file. OQ-062 assumption as stated | — | run, except `C-resubmit` (OQ-067): not yet testable, UC-008 |
| S1 Publish unreadable event | `Peakmart` sends `invalid/pm-50119-buyer-email-not-an-email.json` | The System sets it aside: one `set_aside_order_event` with event id `1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f`, order number `PM-50119`, reason `$.buyer.email: format`, state `Set Aside`. No `order_receipt` for `PM-50119` | run |
| S2 Alert integration team | — | — | **not yet testable — UC-006** |
| S3 Verify set-aside alert | Split: the "No Pick List `PL-PM-50119.csv` exists" sentence is run after the quiet period. The Alert part is not | No file in either folder | run (file part); Alert part **not yet testable — UC-006** |
| S4 Resubmit corrected event | — | — | **not yet testable — UC-008** |
| S5 to S8 (taken in, check, deliver, verify) | — | — | **not yet testable — UC-008** (they depend on S4) |
| V1 One Pick List, V2 No longer set aside | — | — | **not yet testable — UC-008** |
| V3 One Alert | — | — | **not yet testable — UC-006** |
| V4 Nothing to Peakmart | `Peakmart` offset snapshot | The topic grew by exactly the 1 record sent | run |
| P1, P2, P4 (accepted order, no longer set aside, pick list) | — | — | **not yet testable — UC-008** |
| P3 Alert kept | — | — | **not yet testable — UC-006** |
| P-cleanup | `OrderDatabase` | Set-aside record removed (there are no resubmission or alert records yet) | run |

### 3.8 TC-005 Pick List Once After Restart — `Tc005PickListOnceAfterRestartIT`

The Warehouse SFTP Server is "unavailable" at the start. The test picks a free port P on 127.0.0.1 and configures the
integration with it, and nothing listens there yet, so a connection is refused. After the restart, the test starts this
test's own `atmoz/sftp` container bound to `127.0.0.1:P`, with the run's host keys. That makes the same configured server
available, without faking it and without a port change.

| Item | How | Assertion | Status |
|---|---|---|---|
| Preconditions | Cleanup of `TG-1060` (TC-001 cleans up too, so the run order does not matter). Port P with nothing listening. The rest as in TC-001 | The maintenance window is not involved: the test does not depend on the clock (A-11) | run, except `C-helpdesk`: not yet testable, UC-005 |
| S1 to S3 | As in TC-001 | Accepted | run |
| S4 Verify order recorded | Split: the lookup part is not testable. The data side is checked: `order_receipt` Recorded, history Received, Recorded, and the `pick_list` row `Pending` with `attempts ≥ 1` (proving that delivery was tried and the Pick List is held, UC-004 A1). "Holds no `PL-TG-1060.csv`" is true because the server is unreachable, and V1 settles the count | — | run (data part); lookup part **not yet testable — UC-005** |
| S5 Restart integration | `IntegrationProcess.stop()` (SIGTERM, graceful), then `start()` with the same run configuration. Then the SFTP container is started on P | The new process is ready. The `pick_list` row is still `Pending` | run |
| S6, S7 Deliver / verify pick list | `WarehouseInbox` | Within 60 s of the server becoming available, and < 15 min of S1: `PL-TG-1060.csv` equals `pl-tg-1060.json`, complete, total weight 2340, no `.tmp` | run |
| S8, S9 Look up / order history | — | — | **not yet testable — UC-005** |
| V1 Delivered exactly once | After the quiet period | Exactly one file across both folders, and no `.tmp` | run |
| V2 Recorded once (lookup) | — | — | **not yet testable — UC-005** |
| V3 Not lost by the restart | `Partner`, `OrderDatabase` | The test sent exactly one request, and there is one `order_receipt` for `TG-1060` | run |
| P1 to P3 | As in TC-001 | — | run |
| P4 Server left available | — | The TC-005 SFTP container is running when the assertions end. Testcontainers removes it after the class | run |
| P-cleanup | — | — | run |

### 3.9 Traceability check and matrix

**Static check: `SpecTraceabilityTest`** (a surefire unit test, `test` phase, no Docker). It fails `verify` early when:
1. an `Approved` test case in `docs/test_cases/` has no test class carrying its `@SpecTestCase` ID;
2. a test case has more than one test class;
3. a class in package `…spec` named `*IT` has no `@SpecTestCase`, or names an ID that has no document, or whose
   document is not `Approved`;
4. the class name prefix (`Tc001…`) or the display name (`TC-001 <title>`) does not match the document;
5. a use case named in a not-yet-testable declaration does not exist in `docs/use_cases/` or is not `Approved`. The
   declarations are read from a static `ITEMS` table in each test class, which the test executes from (A-7).

It parses the documents: `**ID:**`, `**Status:**`, the Flow table (step number, name, use case link), the numbered
Validation list and the Postcondition bullets. Its parsing is itself unit-tested against the five real documents (item
counts: TC-001 7 steps, 4 validations, 4 postcondition bullets, and so on).

**Completeness check and matrix: `SpecMatrix`** (a test-classpath main, run by `exec-maven-plugin` at
`post-integration-test`, after failsafe and before `failsafe:verify`). It reads the documents, the `ITEMS` tables and
`target/spec-tests/results/*.json`, and:
- fails the build when an item of a test case (step, validation, postcondition, precondition marker) is neither run
  nor declared not yet testable, or when a result file is missing for an Approved test case while the ITs ran;
- writes `target/spec-tests/traceability-matrix.md` (and `.json` for CI), with:
  - **Use case → test case → test:** for each UC-001 … UC-008, each test case step that names it, the test class, and
    the outcome (`passed`, `failed`, `not run`, or `not yet testable`). Use cases exercised only as a precondition
    (UC-007 through the Partner Configuration) are listed as such. Use cases with no test case step are listed as "no
    test case".
  - **Per test case:** every item with its status, and a summary line: `TC-001: 12 run (12 passed), 5 not yet testable
    (UC-005 ×4, UC-006 ×1)`.
  - **Not yet testable, by use case:** the ready list for the pipelines that build UC-005, UC-006 and UC-008.
- With `-DskipITs` it writes the matrix with every run item as `not run` and does not fail.

The matrix is a build output under `target/`. CI (chapter 9) archives it. It is not committed, because a committed copy
would go stale (A-8).

### 3.10 Build changes (`pom.xml`)

- `dependencyManagement`: import `org.citrusframework:citrus-bom:5.0.1`, after the Spring Boot and Camel BOMs so that
  their versions win (Spring, Jackson, Kafka clients, Jetty).
- Test-scoped dependencies:
  - `citrus-junit5` (the JUnit Jupiter integration; implementation confirms the 5.0.1 artifact name), `citrus-http`,
    `citrus-kafka`, `citrus-mail`, `citrus-sql`, `citrus-validation-json`, `citrus-validation-text`;
  - `org.testcontainers` `testcontainers-junit-jupiter`, `testcontainers-kafka`, `testcontainers-postgresql`
    (Testcontainers 2.x names under the Boot 4.1.1 BOM; implementation confirms them).
- `maven-failsafe-plugin`: `integration-test` and `verify` goals, includes `**/spec/*IT.java`, `runOrder=alphabetical`
  (the order is cosmetic, because every test cleans up its own order numbers), `forkCount=1`, and no parallel runs (the
  tests share containers and a mail queue). It sets the system properties `spec-test.jar` (the jar path),
  `spec-test.docs-dir` (`${project.basedir}/docs`) and `spec-test.work-dir` (`${project.build.directory}/spec-tests`).
- `exec-maven-plugin`: a second execution, `spec-traceability-matrix`, at `post-integration-test`, running
  `SpecMatrix`. It uses the test classpath, as the existing `bundle-partner-contract` execution does.
- Surefire excludes `**/spec/*IT.java` (failsafe's default naming already does).
- `README.md` § Build: `./mvnw verify` now needs Docker, takes several minutes, and writes the matrix. Developers
  without Docker run `./mvnw verify -DskipITs`.

---

## 4. Cross-Cutting Concerns

### Performance
Not measured (Not Doing). A typical run starts Kafka, PostgreSQL and SFTP once, plus one more SFTP container for
TC-005, and the integration six times (once per class, twice for TC-005). Expected duration: 3 to 5 minutes. The
quiet periods (10 s) dominate after container start.

### Security
- No real secrets anywhere. The partner API Key and the SFTP password are random per run and written only under
  `target/spec-tests/` (not committed; `target/` is ignored). They never go on a command line or into a log.
- The SFTP host key is checked strictly against per-run keys. The integration's `strictHostKeyChecking=yes` is not
  relaxed for the tests.
- The SFTP container is bound to `127.0.0.1` only (`README.md` warning). Kafka and PostgreSQL use Testcontainers'
  random port mapping. They hold only synthetic data from `docs/messages/examples/` and live only for the run (A-6).
- In-JVM Citrus servers (catalog, mail) bind to `127.0.0.1` where the endpoint allows it. Implementation verifies this.
- The examples are synthetic people and addresses, so the test logs may print them. The integration's own logs keep
  their masking.

### Monitoring
Each integration process log is kept in `target/spec-tests/logs/`. Each test logs every item's outcome with the test
case ID and item ID. The matrix is the summary.

### Constraints
- `./mvnw verify` with only Docker running. Testcontainers is the only thing that starts or stops containers.
- Ports 8080, 8081 and 5432 are never bound.
- Citrus 5.0.1 exactly. Camel 4.22.2 and Spring Boot 4.1.1 are unchanged.
- Every container image the tests start is pinned to an exact version plus digest (§2). No floating tag.
- No change under `src/main/`. If a test exposes a defect, the defect is reported in the execution report and goes to
  its own pipeline. The test is not weakened.

---

## 5. Constitution Compliance

This pipeline adds no route, so the route rules apply to the routes it exercises. Those are unchanged and were accepted
in 001 to 003.

- [x] Route Structure: no new or changed routes.
- [x] Single Responsibility: one test class per test case; one helper per role.
- [x] Separation of Concerns: roles (helpers), test-case logic (IT classes) and traceability (check, matrix) are
  separate.
- [x] Naming Conventions: no new route IDs. Test classes are named `Tc00n<Title>IT` after their test case.
- [x] Observability: unchanged routes. The tests log the test case and item IDs.
- [x] External Configuration: every endpoint the integration uses comes from `application-spec-test.properties` or the
  generated run file. Waits and timeouts are `spec-test.*` properties.
- [x] Component Support: the components the tests drive or configure are verified below. Citrus test vocabulary was
  checked against the same-version cache at design time. Planning and execution verify it against the Citrus MCP
  server, as Camel components are (design review, decision 1).
- [x] Infrastructure via Forage: not applicable (no `forage.version`). No new beans in the application.

### Catalog Verification Evidence

Catalog binding: camel_catalog_components(limit=0); runtime=spring-boot; platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2; returned camelVersion=4.22.2; binding=MATCHED

| Artifact identity | Result | Verification provenance |
|---|---|---|
| component:kafka | VERIFIED | camel_catalog_component_doc (autoOffsetReset, values earliest/latest/none); requested component:kafka; catalog binding=MATCHED |
| component:sql | VERIFIED | camel_catalog_component_doc (scheduler `delay` default 500, `initialDelay` 1000; the shortened poll intervals set `delay`); requested component:sql; catalog binding=MATCHED |
| component:sftp | VERIFIED | camel_catalog_component_doc (knownHostsFile, useUserKnownHostsFile, autoCreateKnownHostsFile default false; per-run known_hosts written before start); requested component:sftp; catalog binding=MATCHED |
| component:smtp | VERIFIED | camel_catalog_component_doc (connectionTimeout); requested component:smtp; catalog binding=MATCHED |
| component:platform-http | VERIFIED | camel_catalog_component_doc (consumer path; the partner endpoint is reached on `server.port`); requested component:platform-http; catalog binding=MATCHED |
| component:http | VERIFIED | camel_catalog_component_doc (throwExceptionOnFailure; the catalog stub's 404 for unknown SKUs is read as "not found", not an outage); requested component:http; catalog binding=MATCHED |

**Citrus test vocabulary from its catalog (design review, decision 1).** The design session could not reach the Citrus
MCP server (a session setting, not a design gap) and checked the vocabulary below against the same-version cache
`.camel-kit/.cache/citrus/5.0.1/`. That cache check is design-time evidence only. Planning and execution have the
server, and they verify there, under the Citrus version binding of `shared/mcp-setup.md` (server coordinate
`org.citrusframework:citrus-mcp-server:5.0.1:runner` equal to `citrus.version` 5.0.1):

- **camel-plan:** every Citrus endpoint, action and option a task uses is checked with `citrus_catalog_endpoint` /
  `citrus_catalog_endpoint_schema` and `citrus_catalog_action` / `citrus_catalog_action_schema`, and the plan records
  the evidence (artifact identity, result, tool, binding) as it does for Camel components.
- **camel-execute:** each task re-checks the endpoints, actions and options it actually writes against the server
  before the code is accepted. This includes options only settled in code, such as the mail message header names for
  the selectors (§7) and the bind address of `http-server` and `mail-server`.
- An artifact or option the server does not return, or a disagreement between the server and the cache, stops that
  task as an open question. The cache does not overrule the server, and neither is bypassed by a fallback to another
  Citrus version.

Design-time cache check (to be superseded by the server evidence in the plan):

| Citrus artifact | Result | Provenance |
|---|---|---|
| endpoint:http-client, endpoint:http-server (`endpointAdapter`, `autoStart`, `defaultStatus`) | VERIFIED (cache) | `citrus-catalog-aggregate-endpoints.json`, module citrus-http, version 5.0.1 |
| endpoint:mail-server (`autoAccept`, `port`, `splitMultipart`, `knownUsers`) | VERIFIED (cache) | same, module citrus-mail |
| endpoint:kafka-asynchronous / kafka-synchronous | VERIFIED (cache) | same, module citrus-kafka |
| action:send, receive, sql, expectTimeout, waitFor, sleep | VERIFIED (cache) | `citrus-catalog-aggregate-test-actions.json`, 5.0.1 |
| endpoint:sftp-client, action:testcontainers-start-* | Present, deliberately not used (A-4, A-5) | same files |

---

## 6. Project Structure

```
trailhead-orders/
├── .camel-kit/pipeline.json                         (activePipeline → 004-spec-tests)
├── docs/camel-kit/004-spec-tests/
│   ├── design-spec.md                               ← this file
│   ├── implementation-plan.md                       [created by camel-plan]
│   ├── execution-report.md                          [created by camel-execute]
│   └── validation-report.md                         [created by camel-validate]
├── pom.xml                                          (citrus-bom, test deps, failsafe, exec post-integration-test)
├── README.md                                        (§ Build: Docker needed, matrix location, -DskipITs)
├── src/main/…                                       (unchanged)
└── src/test/
    ├── resources/testcontainers.properties          (ryuk.container.image, pinned; §2)
    ├── java/com/trailheadgear/orderintegration/
    │   ├── (34 existing unit tests, unchanged)
    │   └── spec/
    │       ├── SpecTestCase.java                    (annotation)
    │       ├── SpecTraceabilityTest.java            (static check, surefire)
    │       ├── SpecMatrix.java                      (completeness check + matrix, post-integration-test)
    │       ├── TestCaseDocument.java                (parser of docs/test_cases/*.md and docs/use_cases/*.md)
    │       ├── TestCaseDocumentTest.java            (parser against the five real documents)
    │       ├── Tc001PartnerOrderToWarehouseIT.java
    │       ├── Tc002PeakmartRedeliveryShippedOnceIT.java
    │       ├── Tc003PeakmartOrderRejectedIT.java
    │       ├── Tc004SetAsideOrderEventReachesWarehouseIT.java
    │       ├── Tc005PickListOnceAfterRestartIT.java
    │       └── support/
    │           ├── SpecEnvironment.java, IntegrationProcess.java, FreePorts.java, SpecRun.java
    │           ├── Partner.java, Peakmart.java, ProductCatalogStub.java, Mailboxes.java
    │           └── WarehouseInbox.java, WarehouseHostKeys.java, OrderDatabase.java
    └── resources/spec-test/
        ├── application-spec-test.properties         (integration side, §3.1)
        └── spec-test.properties                     (harness side and pinned images, §3.1)
```

Run-time output (not committed): `target/spec-tests/{run,logs,results,secret-store,sftp}/` and
`target/spec-tests/traceability-matrix.{md,json}`.

---

## 7. Assumptions Taken (non-interactive run — recommended option chosen)

| ID | Open decision | Taken |
|---|---|---|
| A-1 | How the integration runs in the test | The packaged jar as a child process (§3.1). It is the shipped artifact, it is reached only through its interfaces, it contains the `prepare-package` contract bundle, and it allows a real process stop/start for TC-005. Rejected: `@SpringBootTest` in the test JVM (it shares the classpath and statics with the test, and the bundle is missing in surefire), and a Docker image (chapter 9 builds that) |
| A-2 | Citrus style | Citrus Java DSL with the JUnit Jupiter extension, run by failsafe. The tests need a process restart, per-run keys, two-folder SFTP counts and the not-yet-testable report, which plain Citrus YAML can't express. The `.camel.it.yaml` / JBang style of `camel-test` targets single routes on Main |
| A-3 | Granularity of "one test per test case" | One class with one `@Test` per test case. Items are recorded inside it (§3.2), not as separate JUnit tests, so one test case stays one test and its steps run in order |
| A-4 | Citrus where it fits | Citrus for every role that is a message exchange: partner HTTP, Peakmart Kafka, catalog HTTP server, mail server, plus SQL checks and timeouts. Plain Java (JSch, commons-csv) only for the warehouse folder view and host keys, where the Citrus `sftp-client` would turn a file count into FTP command messages |
| A-5 | Who starts containers | The Testcontainers Java API in a JVM-wide singleton, not Citrus `testcontainers-start-*` actions. Those are scoped to one Citrus test, and the containers must exist before the integration process starts and be shared by five classes |
| A-6 | Port binding | SFTP bound to 127.0.0.1 (as `README.md` insists). Kafka and PostgreSQL use the Testcontainers default mapping on random ports: binding Kafka to a fixed loopback port fights its advertised listeners, and both hold only synthetic data for minutes |
| A-7 | Where not-yet-testable declarations live | A static `ITEMS` table per test class (item ID, name, `run` or `notYetTestable(UC)`). The test executes from it, and both checks read it, so the declaration and the behaviour cannot drift |
| A-8 | Matrix location | Generated at every `verify` into `target/spec-tests/`, archived by CI. Not committed |
| A-9 | Stated time limits (15 min, 5 min) | Asserted with a 60 s arrival bound plus an elapsed-time assertion against the stated limit. A pass proves the limit holds. A failure at 60 s is reported as a test failure, not as an NFR breach |
| A-10 | "15 minutes later still one file" and other negative waits | A 10 s quiet period with all pollers at 1 s, which is at least 5 poll cycles (the exactly-once guarantees are per poll, not per minute). The shortened poll intervals are configuration (`*.poll-delay-ms`), not a fake |
| A-11 | TC-005 "outside the Maintenance Window" | The integration has no maintenance-window logic yet, so the test does not depend on the clock. If one is added later, the test sets the time zone or skips the window |
| A-12 | How an "unavailable" SFTP server is made | Nothing listens on the configured port until the test starts that test's container on it (§3.8). There is no proxy and no wrong address |
| A-13 | Restart kind in TC-005 | Graceful (SIGTERM). SIGKILL is covered by the existing kill test |
| A-14 | TC-004 S1 evidence "the System sets it aside" | Read from `set_aside_order_event`, because no outside view of set-aside events exists until UC-006/UC-008. This checks the step's stated outcome. It does not stand in for the Alert or the resubmission |
| A-15 | Data-side checks next to unbuilt lookups | Postconditions about the Order Database are run with SQL. Validations that say "in the order lookup" are not yet testable even when the database holds the same fact, so the lookup is never claimed as tested |
| A-16 | Rejection reason text | The example text is asserted verbatim. It matches `OrderChecks` today; 003's C-12 makes examples non-binding for wording, but here they agree |
| A-17 | No Docker | The tests fail rather than skip, so CI can't pass without running them. Local opt-out: `-DskipITs` |
| A-18 | PostgreSQL version | PostgreSQL 16 per C-004 (the team's instance is 16), as an exact pinned image (§2). Confirmed by the design review (decision 3). The review also found that `dev` has run on PostgreSQL 17 since chapter 5. That is recorded in the execution report, and it is not changed here: the dev setup changes in chapter 9 |
| A-19 | Kafka record key | None (OQ-038: not named). The integration ignores the key |
| A-20 | Mail server | One Citrus `mail-server` for all mailboxes, told apart by recipient. Rejected: a Mailpit container, which works but puts the mailbox checks outside Citrus |
| A-21 | Form of an image pin (amendment for review decision 2) | Exact version tag plus `@sha256:` digest for every image, digest only for `atmoz/sftp` (it has no version tags), and Ryuk included because Testcontainers starts it too. The digest guards against a re-pushed tag. `SpecEnvironment` rejects a reference without digest. The values are chosen and committed during implementation |

### Design review decisions (`design-review.md`)

| # | Decision | Where applied |
|---|---|---|
| 1 | Citrus from its catalog: every Citrus endpoint, action and option the tests use is verified on the Citrus MCP server in planning and execution, as Camel components are | §5 (Citrus test vocabulary), constitution item Component Support |
| 2 | Pinned images: every container image the tests start is pinned to an exact version and recorded in the execution report | §2 (pin table), §3.1, §3.3 `SpecEnvironment`, §4 Constraints, §6, A-21 |
| 3 | PostgreSQL 16 (A-18) is right. `dev` has run on 17 since chapter 5: noted in the execution report, the dev setup changes in chapter 9 | A-18, "Required in the execution report" |

Everything else, A-1 to A-20 included, was accepted as written.

### Required in the execution report
- Every container image the tests start (Kafka, PostgreSQL, SFTP, Ryuk), with its exact tag and digest.
- The Citrus MCP verification evidence for every Citrus endpoint, action and option the committed tests use.
- The finding that `dev` runs PostgreSQL 17 while C-004 and the tests use 16, left to chapter 9 (the dev setup is not
  changed in this pipeline).
- Any defect a test exposes, for its own pipeline (§4 Constraints).

### For implementation to confirm (not catalog artifacts)
- The Citrus 5.0.1 JUnit Jupiter artifact name, and Citrus 5.0.1's compatibility with Spring Framework 7 / Boot 4.1.1
  dependency versions (Jetty for `http-server`, `kafka-clients`). Check with `dependency:tree`, keeping Boot/Camel
  versions.
- Testcontainers 2.x artifact names under the Boot 4.1.1 BOM, and that Testcontainers 2.x reads `ryuk.container.image`
  from the classpath `testcontainers.properties`.
- The exact image tags and digests of §2.
- That `atmoz/sftp` uses supplied host keys and creates nested folders from its user spec (otherwise a startup script
  in `/etc/sftp.d/`). Also that JSch generates Ed25519 host keys on Java 21 (otherwise RSA only).
- The Citrus mail message header names for the recipient/subject selectors (verified on the Citrus MCP server, §5).
- That the Citrus `http-server` and `mail-server` can bind to 127.0.0.1 (option verified on the Citrus MCP server, §5).
- That the integration logs `Started CamelApplication` (the readiness marker).

---

## 8. Handoff to Later Pipelines

When UC-005, UC-006 or UC-008 is built, its pipeline:
- replaces the matching `notYetTestable` rows in the `ITEMS` tables with running items;
- adds the role helper (Helpdesk Tool client for UC-005, an Integration Team mailbox address for UC-006, an Integration
  Team client with a bearer token for UC-008);
- checks that the matrix shows those items as passed.

The matrix's "Not yet testable, by use case" section is that list.
