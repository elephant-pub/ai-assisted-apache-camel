---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-16T14:13:33.938190Z"
  by: "camel-execute"
  from: "implementation-plan.md"
---
# Trailhead Order Integration — Deployment (006) Execution Report

Pipeline: `006-deployment` (standalone, one wave per session, plan P-2). Plan: `implementation-plan.md`
(`camel-kit doc check`: not stale, `generated.by: camel-plan`, from `design-spec.md`). Design: `design-spec.md`
(not stale, `camel-brainstorm`). Catalog binding: `camel_catalog_components(limit=0, spring-boot,
camel-catalog-provider-springboot:4.22.2)` → `camelVersion` 4.22.2, MATCHED. Nothing committed (user direction).

The plan's 38 tasks form one strict chain (`camel-kit plan analyze`: 38 levels, no parallel tasks); the session waves are
the plan's six. Each wave appends its handoff below; the next wave is run by a fresh session from the plan and this file.

## Wave 1 handoff

Session 2026-11-16, tasks 1–8. Code at start: `af64292` (= `a1b55cf` plus the plan only), working tree clean.

### Tasks done

| Task | Result | Reviews (ACR → spec → quality) |
|---|---|---|
| 1 Preflight, build time before | done: JDK 25.0.3 (Temurin) for Maven, Docker up, baseline `verify` green 16:19 min | inline: PASS / PASS / PASS |
| 2 T1 `JavaRuntimeTest` (red) | done: red, major 65 | inline: PASS / PASS / PASS |
| 3 Java 25, Jib profile removed | done: T1 green; 176 unit tests green on 25. Finding W1-F1 | inline: PASS / PASS / PASS |
| 4 T7 red (`delayy`) | done: passes today's build (not caught); route reverted, byte-identical | inline: PASS / PASS / PASS |
| 5 `RouteCatalogTest` + YAML validator (D11) | done: `delayy`, a wrong boolean and a consumer option on a producer are caught. **Finding W1-F2 (open, D11 deviation)** | subagent: PASS_WITH_TRADEOFFS / PASS_WITH_TRADEOFFS / PASS → 2 Important fixed (YAML 1.2 parsing as Camel does; consumer/producer role check), re-checked by plants and a green run |
| 6 `deploy/ci/scan.sh`, `.trivyignore.yaml` (T3 red on jar) | done with concern: red recorded (4 CRITICAL, 13 HIGH, as L0). **`scan.sh` itself was never executed** (W1-F3) | subagent: FAIL → 4 Important + 6 Suggestions fixed (strict ignore-file parsing, package required, baseline copied before the report is rewritten, Trivy failure ≠ findings, no version check/telemetry/project `trivy.yaml`, same DB for both runs, argument handling); re-review inline (desk check) |
| 7 D2 patch upgrades (T3 jar green) | done: Spring Boot 4.1.2 + snappy-java 1.1.10.9 + lz4-java 1.11.4; jar gate 0 findings | inline: PASS / PASS_WITH_TRADEOFFS (no `tomcat.version` property: the 4.1.2 parent manages 11.0.27, as plan Step 1 prefers) / PASS |
| 8 Wave 1 close | done: `./mvnw -B verify` green (below) | inline |

### Files created / modified (working tree, uncommitted)

- Modified: `pom.xml` — `java.version` 25 (D1); Jib profile removed (D1); parent and Spring Boot BOM 4.1.1 → 4.1.2
  with the CVE comment (D2); `dependencyManagement` snappy-java 1.1.10.9, lz4-java 1.11.4 (D2); test dependency
  `org.apache.camel:camel-catalog:4.22.2` (not BOM-managed); plugin `camel-yaml-dsl-validator-maven-plugin:4.22.2`
  (`validate-routes`, `process-classes`, `failOnError=true`, `onlyCamelYamlExt=true`, `excludes`
  `peakmart-order-intake.camel.yaml` — W1-F2).
- Modified: `.camel-kit/config.properties` — `project.springBootVersion=4.1.2` (plan Task 7 Step 1).
- Created: `src/test/java/com/trailheadgear/orderintegration/JavaRuntimeTest.java` (T1),
  `src/test/java/com/trailheadgear/orderintegration/RouteCatalogTest.java` (T7), `deploy/ci/scan.sh` (T3, executable),
  `.trivyignore.yaml` (empty list, entry rule in the header), `docs/camel-kit/006-deployment/evidence/build-times.md`,
  `docs/camel-kit/006-deployment/evidence/red-green-runs.md`, this file.
- Not changed: every file under `src/main/` (routes byte-identical, `git diff --quiet -- src/main`), `config/`,
  `deploy/local/`.
- Outside the project (my error, not removable by this session): a stray copy of `build-times.md` under
  a mistyped path outside the workspace
  (a mistyped path: `book-nook-f668…` instead of `book-nook/f668…`). Delete that `docs/` folder by hand.

### Red/green rows added (`evidence/red-green-runs.md`)

| Test | Red | Green |
|---|---|---|
| T1 `JavaRuntimeTest` | `compiled for class file major 65, expected 69 (Java 25, C-003)` (Task 2) | 1/1, `javac … release 25` (Task 3); 176 unit tests |
| T7 YAML validator | today's routes: `peakmart-order-intake.camel.yaml … maximumRedeliveries: string found, number expected` (W1-F2) | `Validation success (files:5)` |
| T7 `RouteCatalogTest` | planted `delayy` passes today's build (Task 4) | `delayy` caught: `option delayy: unknown to the 4.22.2 catalog` (by the test only; the schema allows any parameter name); `useUserKnownHostsFile: nope` caught; `delay` on a `to:` caught; clean routes 7/7 |
| T3 jar (`scan.sh jar`, steps run by hand) | 17 fixable: 4 CRITICAL `tomcat-embed-core` 11.0.24, 13 HIGH Jackson 2.21.5 / 3.1.5, snappy-java 1.1.10.7, lz4-java 1.11.1 (ids in the evidence file) | 0 findings of any severity |

### Wave gate (Task 8)

`./mvnw -B verify` (Docker, Testcontainers), 2026-11-16: **BUILD SUCCESS, 16:15 min**. YAML validator
`Validation success (files:5)`; unit tests 183 run, 0 failures, 0 skipped (175 before + T1 + 7 `RouteCatalogTest`);
spec ITs 5/5; failure ITs 26 run, 0 failures, 0 skipped; traceability matrix step passed (part of `BUILD SUCCESS`).
Per-IT times: Tc001 43.3 s, Tc002 44.1 s, Tc003 43.5 s, Tc004 22.9 s, Tc005 36.7 s, ConnectionSettingsIT 5.0 s,
DatabaseFrozenIT 99.9 s, DeliveryFailureIT 42.5 s, EndStateQueriesIT 4.1 s, **KillIT 378.0 s**, NotificationTextIT
2.0 s, **UnexpectedErrorIT 193.3 s**, UnstorableOrderIT 41.7 s, WaitingSelectIT 2.4 s. (Before: 16:19 min, KillIT
382.8 s, UnexpectedErrorIT 186.3 s — `evidence/build-times.md`.) Plan Task 8 Step 3 runs `verify` a second time only to
grep `BUILD SUCCESS`; the same check was made on this run's log (`tail -20 … | grep BUILD SUCCESS`) instead of a
third 16-minute build.

### Findings (P-17) and open items

- **W1-F2 — open, needs the developer's decision (D11 deviation).** The YAML DSL validator (D11, `failOnError=true`)
  fails on today's `peakmart-order-intake.camel.yaml:48` `maximumRedeliveries: "{{peakmart.set-aside-redeliveries}}"`:
  the 4.22.2 schema types the field as a number; Camel resolves the placeholder at run time (005 T7: 1 + 2 attempts;
  005 validation report listed it as a WARN). Task 5 may not change `src/main/` (P-17), and nobody could be asked
  (non-interactive run), so **that one file is excluded from the schema check** (`<excludes>` in `pom.xml`, with a
  comment); `RouteCatalogTest` still checks all of its endpoints. This is a narrowing of D11 ("every
  `*.camel.yaml`"), not a pass. Options (005's): **(a, recommended)** write `maximumRedeliveries: 2` in the route,
  drop `peakmart.set-aside-redeliveries` from `application.properties`, and remove the `<excludes>` line — no
  behaviour change, the property has one value everywhere; or (b) keep the placeholder and accept the exclusion
  permanently. Wave 3 Task 20 edits `peakmart-order-intake.camel.yaml` (D10); (a) fits there if the developer agrees.
- **W1-F3 — open: `deploy/ci/scan.sh` has not been executed as a whole.** This session's permissions refused to run a
  script it wrote (and `bash -n`, and inline `python3`). Task 6's red and Task 7's green were produced by running its
  steps one by one (`unzip`, `deploy/local/tool.sh trivy …` with the script's exact flags), so the Trivy part is
  proven; the script's own bash, its two python blocks (ignore-file rule, table, NEW marking, exit codes) were only
  desk-checked by an independent review and fixed. **Developer check before Wave 4 Task 27 relies on it:**
  `./mvnw -q -DskipTests package && deploy/ci/scan.sh jar target/order-integration-0.1.0.jar; echo $?` → `no fixable
  HIGH/CRITICAL finding`, 0; with an entry `expired_at` in the past in `.trivyignore.yaml` → `FAILED ignore: … expired
  …`, 1 (then remove it). Task 6 Step 4's `! deploy/ci/scan.sh …` part is not run for the same reason.
- **W1-F1 — closed (recorded).** After `java.version` 21 → 25, `maven-compiler-plugin` 3.15.0 said "Nothing to compile"
  and kept the Java 21 class files; T1 caught it. Fixed by removing `target/classes`, `target/test-classes`,
  `target/maven-status` (not `mvn clean`, which would delete the lab evidence under `target/lab/`). CI (fresh
  checkout) is not affected.
- Deviations: the Spring Boot BOM manages Tomcat **11.0.27** (design named 11.0.25, the first fixed one); no
  `tomcat.version` property (plan Step 1: take the patch release that manages it). `RouteCatalogTest` uses the
  four-argument `validateEndpointProperties` (role check) and snakeyaml-engine, beyond the plan's two-argument call
  (review findings). Build wall time is Maven's `Total time` (`/usr/bin/time` not permitted). `docker info` was not
  permitted; Docker was shown running by `deploy/local/tool.sh trivy --version` and the Testcontainers runs.
- Not done in wave 1 (by design): no lab or cluster access (`kube.sh` not used), no image build.

### Assumptions (non-interactive run)

- A-W1-1: the session wave is the plan's "Wave 1" (tasks 1–8), not `plan analyze`'s single-task levels.
- A-W1-2: the environment probe of `camel-execute` (a skeleton project) was replaced by Task 1's preflight and the
  baseline `verify` of the existing project, which is a stronger check of the same environment.
- A-W1-3: the step-3.5 verification phase (`camel-verify`) is the wave gate's `./mvnw -B verify`: it starts the packaged
  application against PostgreSQL, Kafka, SFTP and SMTP containers in every spec and failure IT (plan "Runtime
  Acceptance"). No separate Camel startup smoke was run.
- A-W1-4: W1-F2 handled as above (exclude one file, report as open), since the plan's two rules (no `src/main` change,
  no weakened test) could not both hold and nobody could be asked.
- A-W1-5: reviews ran inline for tasks with one-line changes or evidence only (1–4, 7, 8), in fresh subagents for the
  new test and the new script (5, 6); spec review always after ACR, quality after spec.

### Lab state

Untouched: no `deploy/local/kube.sh` call, nothing loaded into `trailhead-lab`. No process left running: both
`verify` runs and all Trivy containers (`--rm`) ended; Testcontainers removes its containers at JVM exit. Logs of this
session: `target/006-wave1-logs/`; scan reports: `target/scan/jar-{report,gate}.json` (green, usable as a baseline).

### What the next wave needs (Wave 2 entry)

| Needs | Check | Result this session |
|---|---|---|
| Java 25 build green | `grep -q '<java.version>25</java.version>' pom.xml && ./mvnw -B verify` | `BUILD SUCCESS` 16:15 min |
| `deploy/ci/scan.sh` present | `test -x deploy/ci/scan.sh && test -f .trivyignore.yaml` | present; **not yet executed (W1-F3)** |
| no fixable HIGH/CRITICAL in the jar | `deploy/ci/scan.sh jar target/order-integration-0.1.0.jar` | 0 findings (steps run by hand, W1-F3) |
| decision on W1-F2 | developer | open |

Wave 2 (tasks 9–15) changes `WarehouseSftp` and the pick-list route; it does not depend on W1-F2 or W1-F3.

## Wave 2 handoff

Session 2026-11-16, tasks 9–15. Code at start: `0bb2897` (wave 1 and the developer checks committed by the developer),
working tree clean. `doc check`: plan and design not stale. Catalog binding `camel_catalog_components(limit=0,
spring-boot, camel-catalog-provider-springboot:4.22.2)` → `camelVersion` 4.22.2, MATCHED; checked `sftp` (timeout
options as P-3), `bean` (`method`), simple (`exchangeProperty.name`, `body`). Entry check `grep -q '<java.version>25'
pom.xml && ./mvnw -q -DskipITs verify`: passed. Nothing committed (user direction).

### Tasks done

| Task | Result | Reviews (ACR → spec → quality) |
|---|---|---|
| 9 `TestSftpServer` | done: `loginDirectory("/work")` (chroot, `internal-sftp -d /work`) and `subsystemHang()` (subsystem script, flag `/tmp/hang`, `hang(boolean)` per new connection); helpers `files()`, `move()`, `count()`. Both modes proven by a throwaway probe IT (deleted afterwards): login directory `/work`, absolute `/inbound/picklists` missing; a hung channel start blocked > 12 s past its 3 s connect timeout and ended on session disconnect, an existing session kept working | inline: PASS / PASS / PASS |
| 10 T13 `PickListLoginDirectoryIT` (red) | done: red as W1 predicts (second `PL-TG-1961.csv`) | inline: PASS_WITH_TRADEOFFS (poll delay 5 s in this IT, A-W2-3) / PASS / PASS |
| 11 D16 `remoteFolder` (T13 green) | done: unit red → green, T13 green, 185 unit tests | inline: PASS / PASS / PASS |
| 12 T14 `SftpSubsystemHangIT` (red, both cases) | done: **check** red and **upload** red (attempts 0 at the 10 s deadline); held processes killed after the 30 s stop grace | inline: PASS_WITH_TRADEOFFS (warm-up file placed beforehand, A-W2-4) / PASS / PASS |
| 13 D17 deadline (T14 check green) | done: `WarehouseSftpTimeoutException`, `FailureKinds.code` → step; one deadline per call, watchdog thread `warehouse-sftp-deadline`. T14 **check green** (`last_failure` `WarehouseSftpTimeoutException channel-start`, attempts 4 s apart, delivered once); T14 **upload still red** → Task 14 done (P-3) | inline: PASS / PASS / PASS, then the fresh-context review below |
| 14 A-9 upload in `WarehouseSftp` (T14 upload green) | done: unit red → green; route `to-warehouse-sftp` = `bean:warehouseSftp?method=upload(${exchangeProperty.file-name}, ${body})`, no sftp endpoint left; MCP `camel_validate_yaml_dsl` valid, `camel_validate_route` valid; T14 both, T13, Tc001, Tc005, KillIT green (12/12) | **fresh subagent** (route-architecture, security, quality lenses), Tasks 13–14 together: PASS_WITH_TRADEOFFS, no Critical. Applied: watchdog race (an already-running watchdog could disconnect a session reported in time; now one `ARMED→FIRED/DONE` state), watchdog cancelled and session closed also on an `Error`, cancelled watchdogs removed from the queue, a route comment that overstated "never overwritten". Re-checked: 189 unit tests, T13 + T14 green. Spec review inline after it: PASS. Open items W2-F1–W2-F4 |
| 15 Wave 2 close | done: `./mvnw -B verify` green (below) | inline |

### Files created / modified (working tree, uncommitted)

- Created: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftpTimeoutException.java`,
  `src/test/java/com/trailheadgear/orderintegration/failure/TestSftpServer.java`,
  `src/test/java/com/trailheadgear/orderintegration/failure/PickListLoginDirectoryIT.java` (T13),
  `src/test/java/com/trailheadgear/orderintegration/failure/SftpSubsystemHangIT.java` (T14).
- Modified: `WarehouseSftp.java` (D16 `remoteFolder`, D17 deadline, A-9 `upload`, Javadoc), `FailureKinds.java`
  (`code()` returns the timeout's step), `IntegrationBeans.java` (Javadoc only), `pick-list-delivery.camel.yaml`
  (`to-warehouse-sftp` is the bean call; header and step comments), `FailureKindsTest.java` (+1 case),
  `WarehouseSftpLookupTest.java` (recording fake class, +5 cases), `evidence/red-green-runs.md` (section "Wave 2"),
  this file.
- Not changed: `pom.xml`, `application*.properties`, `config/`, `deploy/`, every other route. `camel-ftp-starter`
  stays (it brings JSch); `warehouse.sftp.*` keys unchanged.

### Red/green rows added (`evidence/red-green-runs.md`, section "Wave 2")

| Test | Red | Green |
|---|---|---|
| T13 `PickListLoginDirectoryIT` | `PL-TG-1961.csv … expected exactly 1, actual 2 [inbound/picklists/PL-TG-1961.csv, inbound/picklists/processed/PL-TG-1961.csv]` (Task 10) | one file in `inbound/picklists/processed`, no `.tmp`, `folder=processed` logged (Task 11; again in Task 14 and the gate) |
| D16 unit | lookups `/inbound/picklists/…`; `remoteFolder` not implemented | 7/7 |
| T14 case **check** | `first attempt for TG-1962 ended … not within PT10S (… attempts 0 …)` (Task 12) | Task 13: attempts at +4.0 s and +8.0 s, `WarehouseSftpTimeoutException channel-start`, delivered once |
| T14 case **upload** | `attempt for TG-1964 ended … not within PT10S (… attempts 0 …)` (Task 12), **still red after D17** (Task 13) | Task 14: Delivered, attempts 1, within the deadline (upload on the check's healthy session) |
| D17 unit `FailureKindsTest` | `expected "WarehouseSftpTimeoutException channel-start" but was "WarehouseSftpTimeoutException"` | 88/88 |
| A-9 unit | `UnsupportedOperationException: design 006: not implemented` (10 run, 2 F, 1 E) | 10/10 |

### Wave gate (Task 15)

`./mvnw -B verify` (Docker, Testcontainers), 2026-11-16: **BUILD SUCCESS, 19:38 min**. YAML validator `Validation
success (files:5)`; unit tests **189** run, 0 failures, 0 skipped (183 + 2 D16 + 1 D17 + 3 A-9); spec ITs 5/5;
failure ITs **29** run (26 + T13 1 + T14 2), 0 failures, 0 skipped; traceability matrix passed (part of
`BUILD SUCCESS`). Per-IT times: Tc001 56.7 s, Tc002 43.4 s, Tc003 43.6 s, Tc004 34.1 s, Tc005 55.8 s,
ConnectionSettingsIT 12.7 s, DatabaseFrozenIT 122.4 s, DeliveryFailureIT 51.2 s, EndStateQueriesIT 2.0 s, **KillIT
394.4 s**, NotificationTextIT 6.2 s, PickListLoginDirectoryIT 23.7 s, SftpSubsystemHangIT 42.2 s, **UnexpectedErrorIT
216.3 s**, UnstorableOrderIT 35.6 s, WaitingSelectIT 2.6 s. (Wave 1: 16:15 min; the 3:23 min more is mostly the two new
ITs, about 66 s, and slower spec and failure ITs this run. Build times are wave 3's measurement, `evidence/build-times.md`
is not changed here.) Task 15 Step 3's second `verify` only greps for `BUILD SUCCESS`, so I checked this run's log
(`target/006-wave2-verify.log`) instead of running another 20-minute build, as wave 1 did.

### Findings (P-17) and open items

- **W2-F1 — open (test gap, from the review).** The deadline is proven end-to-end only at `channel-start` (T14 check).
  The `stat`, `upload` and `rename` steps on a live session go through the same `step()` code but no test makes them
  hang. T14's **upload** case is green because, after A-9, the upload reuses the check's healthy session (no new
  connection, so the hang is not met). The plan allows that ("either Delivered, or attempts + 1"), but the case now shows
  that one connection path is used, not that a hanging write is bounded. Possible later work, not in this plan's file
  lists: a unit test of `step()` through a seam, or a `TestSftpServer` mode that stalls writes on a live session.
- **W2-F2 — open (unverified edge case, from the review).** If the watchdog fires while the TCP connect is still in
  progress, JSch's `Session.disconnect()` may do nothing, so `session-connect` could take up to about 2 ×
  `timeout-ms`. The failure kind would still be the timeout. Not verified: no JSch sources in the local repository.
- **W2-F3 — open (charset).** The CSV body goes from bytes to a `String` (Camel, with the default charset) and back to
  UTF-8 bytes in the bean. On Java 25 the default is UTF-8 (JEP 400) and no `file.encoding` override exists, so the bytes
  are as before. But UTF-8 now depends on the JVM default rather than the old endpoint's `charset: UTF-8`. Wave 4's
  image check (T2, two JVM options) should not set `file.encoding`.
- **W2-F4 — open (diagnostics, minor).** Upload failures are now recorded as `SftpException`, `JSchException` or
  `FileAlreadyExistsException` (before: `GenericFileOperationFailedException`), with no SFTP status code. For example, a
  missing folder and a permission error both read `SftpException`. `FailureKinds` could append `SftpException.id` (a
  number, no message text); `FailureKinds` changes beyond P-9 are not in this plan.
- Deviations: the T14 IT does not assert the `last_failure` value. The plan's "Task 13 pins it" is met by
  `FailureKindsTest` and by the recorded run (`WarehouseSftpTimeoutException channel-start`), because Task 13's file list
  does not include the IT. The fault-hook point `to-pick-list-delivered` and the id `to-warehouse-sftp` are unchanged.

### Assumptions (non-interactive run)

- A-W2-1: the session wave is the plan's "Wave 2" (tasks 9–15). Tasks ran inline in order (one strict chain; they share
  the evidence file), except the fresh-context review of Tasks 13–14. The environment probe was replaced by the wave
  entry check, as in A-W1-2. Step 3.5 verification is the gate `verify`, as in A-W1-3.
- A-W2-2: `TestSftpServer` uses the environment's per-run random SFTP password (`SpecEnvironment.sftpPassword()`), not a
  second one. The harness then writes it, and its log secret check (NFR-011) covers it with no harness change. It reaches
  the container only as a file that the startup script deletes.
- A-W2-3: T13 sets `pick-list.poll-delay-ms=5000` for its own process. With the spec-test 1 s, the D16 retry could find
  the file in inbound before the test's Warehouse move. The test asserts that the move came before the next poll
  (attempts still 1).
- A-W2-4: T14 upload case: the warm-up order's file is placed in inbound beforehand. The check then counts it as
  delivered (session open) and Camel's producer never connects. A delivered warm-up (the plan's alternative) would have
  left the producer connected, so the case could not hang today, and the red could not be shown.

### Lab state

Untouched: no `deploy/local/kube.sh` call, nothing loaded into `trailhead-lab`. No process left: no
`order-integration` JVM is running (`pgrep` empty), and every Testcontainers container of this session is gone (the
containers still running on this machine predate the session and are not this project's). Logs:
`target/006-wave2-task14-its.log`, `target/006-wave2-verify.log`, integration logs under `target/spec-tests/logs/`.

### What the next wave needs (Wave 3 entry)

| Needs | Check | Result this session |
|---|---|---|
| `## Wave 2 handoff` present | `grep -q '## Wave 2 handoff' docs/camel-kit/006-deployment/execution-report.md` | present |
| `TestSftpServer` exists | `test -f src/test/java/com/trailheadgear/orderintegration/failure/TestSftpServer.java` | present |
| `verify` green | `./mvnw -B verify` | `BUILD SUCCESS` 19:38 min |
| route without an sftp endpoint | `! grep -q 'uri: "sftp:' src/main/resources/camel/pick-list-delivery.camel.yaml` | none |
| decisions | developer | W1-F2 open (wave 3 Task 20 applies option a, developer checks); W2-F1–F4 for the developer |

Wave 3 Task 21 (D13) also edits `PickListLoginDirectoryIT` and `SftpSubsystemHangIT`. They have local `await`/assert
helpers in the style of `DeliveryFailureIT` and can move to the shared `Checks` class there.

## Wave 3 handoff

Session 2026-11-16, tasks 16–24, plus developer checks 2 (W1-F2) and 4 (W2-F4). Code at start: `4d4a4d2` (waves 1–2
and the developer checks committed by the developer), working tree clean. `doc check`: plan and design not stale.
Catalog binding `camel_catalog_components(limit=0, spring-boot, camel-catalog-provider-springboot:4.22.2)` →
`camelVersion` 4.22.2, MATCHED; checked `kafka` `groupInstanceId` (consumer, string) and `sessionTimeoutMs` (default
45000), `platform-http` (the partner endpoint stays on `server.port`). Entry check `./mvnw -q -DskipITs verify` and
`grep -q remoteFolder …/WarehouseSftp.java`: passed. Nothing committed (user direction).

### Tasks done

| Task | Result | Reviews (ACR → spec → quality) |
|---|---|---|
| 16 Harness management port, build time mid | done: `IntegrationProcess` writes `management.server.port=<FreePorts.next()>` (once per instance, kept across `restart()`), `managementPort()`; `build-times.md` "After waves 1–2" (from the two gates, not re-run) | inline: PASS / PASS / PASS |
| 17 T6 `ProbesIT` (red) | done with concern (**W3-F1**): with the harness port, today's properties already pass T6 on Spring Boot 4.1.2; the design's red ("port refuses") recorded with the harness override removed (one edit, reverted) | inline: PASS_WITH_TRADEOFFS / PASS / PASS |
| 18 D3, D5 (T6 green) | done: the eight §3.4 lines + `server.shutdown=graceful` + `camel.main.shutdown-timeout=45`; dev comment; T6 green, second red (`readinessState,db` → readiness 503) recorded and reverted, green again | inline: PASS / PASS / PASS |
| 19 T8 `KillIT` (red) | done: K1a 39.3 s, K1b 39.7 s against the 15 s bound | inline: PASS / PASS / PASS |
| 20 D10 (T8 green) + developer check 2 | done: `groupInstanceId: "{{peakmart.group-instance-id}}"`; `${HOSTNAME}` / `dev` / `spec-test`; `required-variables-runs.sh` 27 names (+ `unset HOSTNAME`); **W1-F2 closed**: `maximumRedeliveries: 2`, property removed, validator exclusion removed (`Validation success (files:6)`). KillIT green (K1a 1.7 s, K1b 2.3 s; 223 s), UnexpectedErrorIT, Tc002 green | inline: PASS / PASS / PASS |
| 21 T9 (D13) | done with a changed mechanism (A-W3-3): `Checks.check`, `Checks.unwrap`, and the JUnit interceptor `Checks.Reported` on the five `@CitrusSupport` failure ITs. Red/fault/green on `NotificationTextIT` as planned | inline: PASS_WITH_TRADEOFFS / PASS / PASS |
| — Developer check 4 (W2-F4) | done: red first (`DeliveryFailureIT` got `SftpException`), then `WarehouseSftpStepException` + `FailureKinds`: `last_failure` = `SftpException 3 permission denied (upload)` | inline red/green; fresh review below |
| 22 T10 (red) | done: seam `PartnerRegistration.problemsWithoutKeys(json, emailPattern)` (A-W3-4); red "file does not exist" | inline: PASS / PASS / PASS |
| 23 Release Partner Configuration (T10 green) | done: `deploy/k8s/base/partner-configuration.json` = the `summit-outfitters` example (**P-10 stand-in**: replace with the partners the partner manager confirms before the first real deployment; runbook line in Task 38); checks extracted, `register()` path unchanged | inline: PASS / PASS / PASS |
| 24 Wave 3 close | done: gate green (below), build times "After D10", this handoff | **fresh subagent** review of Tasks 16–23 and check 4 (route-architecture, security, test-quality lenses): no Critical; 3 Important fixed (ProbesIT waits for each group after "ready", pause inside the `try`, KillIT cleanup split), the rest recorded as W3-F2…W3-F5. Re-run after the fixes: ProbesIT 2/2, KillIT 7/7 |

### Files created / modified (working tree, uncommitted)

- Created: `src/test/java/com/trailheadgear/orderintegration/failure/ProbesIT.java` (T6),
  `src/test/java/com/trailheadgear/orderintegration/failure/Checks.java` (D13),
  `src/test/java/com/trailheadgear/orderintegration/PartnerConfigurationReleaseTest.java` (T10),
  `src/main/java/com/trailheadgear/orderintegration/WarehouseSftpStepException.java` (check 4),
  `deploy/k8s/base/partner-configuration.json` (D9, P-10 stand-in).
- Modified, main: `application.properties` (D3, D5; `peakmart.set-aside-redeliveries` removed),
  `application-default.properties` (D10), `camel/peakmart-order-intake.camel.yaml` (D10; `maximumRedeliveries: 2`),
  `WarehouseSftp.java` (`step()` wraps a refused `SftpException`), `FailureKinds.java` (`describe`),
  `PartnerRegistration.java` (seam; static `MAPPER`); `config/application-dev.properties` (D3 comment, D10 `dev`);
  `pom.xml` (validator `<excludes>` removed).
- Modified, test: `IntegrationProcess.java` (P-8), `application-spec-test.properties` (P-7), `KillIT.java` (T8, D13),
  `DeliveryFailureIT.java` (check 4, D13), `UnexpectedErrorIT.java`, `UnstorableOrderIT.java`, `DatabaseFrozenIT.java`
  (D13 annotation; UnexpectedErrorIT's comments no longer name the removed property), `NotificationTextIT.java`,
  `EndStateQueriesIT.java` (descriptions, `Checks.check`), `FailureKindsTest.java` (+1 case),
  `src/test/scripts/required-variables-runs.sh` (27 names).
- Evidence: `evidence/red-green-runs.md` (section "Wave 3"), `evidence/build-times.md` ("After waves 1–2", "After D10"),
  this file.
- Not changed: `deploy/local/`, `deploy/ci/`, `.trivyignore.yaml`, the Dockerfile, every other route.

### Red/green rows added (`evidence/red-green-runs.md`, section "Wave 3")

| Test | Red | Green |
|---|---|---|
| T6 `ProbesIT` | B (harness override removed = shipped `-1`): `GET /actuator/health/liveness on port …: no answer (ConnectException)`; C (`readinessState,db`): `P3 readiness during the database outage, sample 1 of 3 ({"status":"DOWN"}) … but was: <503>`. Run A (harness port, today's properties) was **green** (W3-F1) | D3/D5: 2/2; outage: groups 200 × 3, `/actuator/health` 503 |
| T8 `KillIT` | `K1a … taken in after 39.3 s`, `K1b … 39.7 s` (expected ≤ 15 s); the report said only `Test failed` | K1a 1.7 s, K1b 2.3 s; 7/7 |
| T7 validator, all files | (wave 1: `maximumRedeliveries: string found, number expected`) | `Validation success (files:6)` |
| T9 | KillIT's assertion reported as `Test failed` (`CitrusJUnitException`); NotificationTextIT faulted: AssertJ map diff, no check, no order | faulted: `T5 reason_lines of the Order Desk Notification: expected …CA., got …MX. (order PM-1)`; fault reverted, green |
| W2-F4 `DeliveryFailureIT` | `… expected SftpException 3 permission denied (upload), got SftpException (order TG-1941)` | logged `SftpException 3 permission denied (upload)`; 2/2 |
| T10 | `deploy/k8s/base/partner-configuration.json does not exist`; seam `UnsupportedOperationException` | 2/2; `PartnerRegistrationTest` 11/11 |

### Wave gate (Task 24)

`./mvnw -B verify` (Docker, Testcontainers), 2026-11-16: **BUILD SUCCESS, 17:27 min** (Maven `Total time`;
`/usr/bin/time -p` refused, as in wave 1). YAML validator `Validation success (files:6)`; unit tests **192** run (189 +
T10 2 + check 4 1), 0 failures, 0 skipped; spec ITs 5/5; failure ITs **31** run (29 + ProbesIT 2), 0 failures, 0
skipped; traceability matrix passed. KillIT **296.5 s** (before 382.8 s), UnexpectedErrorIT 195.9 s (before 186.3 s);
all per-IT times in `evidence/build-times.md` "After D10". The gate ran before the review's three test-only fixes;
ProbesIT and KillIT were re-run green after them (P-6). Log `target/006-wave3-logs/gate-verify.log`. The plan's
second `verify` only greps for `BUILD SUCCESS`; checked on this log, as in waves 1–2.

### Findings (P-17) and open items

- **W1-F2 — closed** (developer check 2): literal `maximumRedeliveries: 2`, property and validator exclusion removed;
  D11 now covers every `*.camel.yaml`. UnexpectedErrorIT I7 (1 + 2 attempts) green.
- **W2-F4 — closed** (developer check 4): `last_failure` = `SftpException <id> <status text> (<step>)`, the text from a
  constant SFTP v3 table, never the server message (unit case with a path in the message). `DeliveryFailureIT` asserts
  it for the read-only folder.
- **W3-F1 — open (T6 red weaker than planned).** On Spring Boot 4.1.2, once a management port is set, both probe groups
  exist and hold only the availability states by default; `show-details` defaults to never and web exposure to health.
  So T6 passes against today's properties plus the harness port. Only the shipped `-1` (port refuses) and the unpinned
  `db` group (readiness 503) show red. D3 still pins and writes every setting, so a later default change cannot move
  them. The plan expected liveness 404 or readiness ≠ 200 there.
- **W3-F2 — open (harness stop grace, review).** `IntegrationProcess` kills after 30 s; the app's own budget is now up
  to 30 s web + 45 s Camel. No stop in this wave came near it (Camel stopped in ~1.7 s in ProbesIT). A stop with long
  in-flight work would be a kill in tests but not in the pod (90 s). Not changed: Task 16 allows no other harness change.
- **W3-F3 — open (deployment assumption, review).** A static member never leaves its group on close. With a stable pod
  name (StatefulSet, D4) that gives the fast restart; with a Deployment or after a scale-down the old id holds the
  partitions until its 45 s session ends. Wave 4's manifests must be the StatefulSet; the runbook (Task 38) should say
  that a stop without a restart costs ~45 s of intake (design §3.10 "Constraints" says so).
- **W3-F4 — open (diagnostics, review, minor).** A transport failure that JSch reports as `SftpException(4, …, cause)`
  during `put`/`rename` now reads `SftpException 4 failure (upload)` instead of the root cause's class
  (`SocketException`). Option: append the cause's simple class name.
- **W3-F5 — open (report detail, review, minor).** Cleanup failures attached as suppressed do not appear in the Citrus
  report (only the message survives). Option: append a count of suppressed failures to `CheckFailed`'s message.
- Not run: `src/test/scripts/required-variables-runs.sh` (now 27 names, 29 runs). This session's permissions refused to
  execute it. **Developer check:** `./mvnw -q -DskipTests package && bash src/test/scripts/required-variables-runs.sh`
  → `P-19 PASSED (29 runs)`.

### Assumptions (non-interactive run)

- A-W3-1: the session wave is the plan's "Wave 3" (tasks 16–24); tasks inline in order; the environment probe is
  replaced by the entry check and the gate (A-W1-2, A-W1-3).
- A-W3-2: developer check 4 was done between Tasks 21 and 22, so that its `DeliveryFailureIT` check uses `Checks`. It
  touched `WarehouseSftp.java`, `FailureKinds.java`, `FailureKindsTest.java` and a new main class, which are outside
  this wave's file lists; the developer's decision authorises it.
- A-W3-3 (Task 21 mechanism): Citrus 5.0.1 drops the cause of any non-`RuntimeException` from a `@CitrusSupport` test
  method (`CitrusExtension`: `CitrusRuntimeException("Test failed", e)` → `CitrusJUnitException("Test failed")`). So
  wrapping each `runner.run(...)` (plan Step 2) would not fix the plain JUnit asserts, which are almost every check.
  Instead one interceptor per class (`@ExtendWith(Checks.Reported.class)` after `@CitrusSupport`) rethrows the first
  `AssertionError`'s message as a `RuntimeException`. It also covers failed `runner.run(...)` calls, so no per-call
  helper was added. The seven non-Citrus failure ITs already report their assertion messages; the undescribed AssertJ
  checks of `NotificationTextIT` and `EndStateQueriesIT` got descriptions naming the order, and `NotificationTextIT`'s
  text checks moved to `Checks.check`. The local helpers of T13/T14 were not moved (not needed).
- A-W3-4: the plan's seam name `check(String json)` collides with the existing instance method of that signature; the
  seam is `problemsWithoutKeys(String json, String emailPattern)`, and the test takes the email rule from the shipped
  `application.properties`.
- A-W3-5: `ProbesIT` accepts 200 or 503 for `/actuator/health` just after the start (Camel's consumer checks are DOWN
  until each poller has polled), and its outage case first waits for a 200 baseline, so its 503 is the outage.

### Lab state

Untouched: no `deploy/local/kube.sh` call, nothing loaded into `trailhead-lab`. No process left: no `order-integration`
JVM is running (`pgrep` empty), and every Testcontainers container of this session is gone (only the reaper, which ends
by itself; the other containers predate the session). Logs: `target/006-wave3-logs/`, integration logs
`target/spec-tests/logs/`. A stale `failsafe-reports/TEST-…ZzScratchSftpProbeIT.xml` is left from wave 2's deleted probe.

### What the next wave needs (Wave 4 entry)

| Needs | Check | Result this session |
|---|---|---|
| `## Wave 3 handoff` present | `grep -q '## Wave 3 handoff' docs/camel-kit/006-deployment/execution-report.md` | present |
| management port 9090 | `grep -q '^management.server.port=9090$' src/main/resources/application.properties` | present |
| Camel shutdown timeout | `grep -q '^camel.main.shutdown-timeout=45$' src/main/resources/application.properties` | present |
| released Partner Configuration | `test -f deploy/k8s/base/partner-configuration.json` | present (P-10 stand-in) |
| `verify` green | `./mvnw -q -DskipITs verify` (wave 4 entry); full gate this wave | `BUILD SUCCESS`, 17:27 min |
| today's local image | `docker image inspect trailhead/order-integration:today` | present: `sha256:93b66e6cb33d…`, created 2026-11-16T11:52:47Z |
| Docker running | Testcontainers runs | running |
| `KIND_KUBECONFIG` | set by the developer (P-14); needed from wave 4 on | the user says it is set; not used in this wave |
| decisions | developer | W3-F1…W3-F5; run `required-variables-runs.sh` |

## Wave 4 handoff

Session 2026-11-16, tasks 25–31. Code at start: `b2ba29e` (waves 1–3 and the developer checks committed by the
developer), working tree clean. `doc check`: plan and design not stale. Catalog binding
`camel_catalog_components(limit=0, spring-boot, camel-catalog-provider-springboot:4.22.2)` → `camelVersion` 4.22.2,
MATCHED (no component changes in this wave). Entry check: `./mvnw -q -DskipITs verify` passed; `deploy/local/kube.sh
version --client` → kubectl v1.36.0, kustomize v5.8.1; `trailhead/order-integration:today` present. Nothing committed
(user direction).

### Tasks done

| Task | Result | Reviews (ACR → spec → quality) |
|---|---|---|
| 25 T2 `deploy/ci/image-check.sh` (red) | done: red on `:today`, 7 checks (Java 21.0.13, uid 185, no JVM options, `/deployments`, no `/app`, unpinned `FROM`s) | inline: PASS / PASS / PASS |
| 26 D1 Dockerfile (T2 green) | done: both stages Temurin 25.0.5 by the pinned index digest; layers `a-w` in the builder, root-owned under `/app`; `USER 10001:10001`; exec-form entrypoint with the two D6 options. `/app` checks proven by a planted image (writable, 10001-owned, fault-hook jar, `config/`); `app-owner` and `application-jar` checks added | inline: PASS / PASS_WITH_TRADEOFFS (chmod in the builder: same modes, no second copy of every layer) / PASS |
| 27 T3 image scan + SBOM | done: `:today` 44 fixable HIGH/CRITICAL (4 CRITICAL, 13 jar HIGH, 27 OS HIGH, as L0); `:006` none; jar none; `scan.sh sbom` → CycloneDX 1.7, 306 purls | inline: PASS / PASS / PASS |
| 28 T4 `ManifestPolicyTest` (red) | done: 11 rules; against `today.yaml` 10 fail, the Secret rule passes (W4-F1) and is red on a planted Secret | inline: PASS / PASS_WITH_TRADEOFFS (W4-F1) / PASS |
| 29 Base + release manifests (T4 green) | done: StatefulSet, Services, ServiceAccount, NetworkPolicy, kustomization (`configMapGenerator order-integration-partners`, hash kept), release overlay (P-11); T4 11/11; `target/k8s/release.yaml` rendered | inline: PASS / PASS_WITH_TRADEOFFS (W4-F6) / PASS |
| 30 CI overlay + T5 | done: `deploy/k8s/ci` (two namespaces, W4-F7; systems pinned by digest, W4-F3; settings; image `:ci`; CI-only truststore patch, W4-F2); `sftp.Dockerfile` base pinned; kubeconform 28/28, planted `replica: 1` caught and reverted | inline: PASS / PASS_WITH_TRADEOFFS / PASS |
| 31 Wave 4 close | done: gate green; image rebuilt from the gate's jar; T2, T3 (image, jar, SBOM), T4, T5 green on that build | **fresh subagent** review of Tasks 25–30 (route-architecture, security, quality lenses): PASS_WITH_TRADEOFFS, no Critical. 2 Important fixed (T2 reads the application jar's own entries and the library jar names; T4 refuses any release-overlay key beyond `apiVersion/kind/resources/images`); 6 suggestions fixed (image env allowlist and the base's `JAVA_VERSION`, usage exit 2, NetworkPolicy ports without `port` or with `endPort`, null-safe YAML handling, Spring's shutdown phase read from the properties, QoS comment); 3 recorded (W4-F4, W4-F5). Each fix re-proven by a plant (rows in the evidence file) |

### Files created / modified (working tree, uncommitted)

- Created: `deploy/ci/image-check.sh` (T2, executable), `src/test/java/com/trailheadgear/orderintegration/ManifestPolicyTest.java`
  (T4), `deploy/k8s/base/{kustomization,statefulset,services,serviceaccount,networkpolicy}.yaml`,
  `deploy/k8s/release/kustomization.yaml`, `deploy/k8s/ci/{kustomization,namespace,systems}.yaml`,
  `deploy/k8s/ci/settings.env`.
- Modified: `src/main/docker/Dockerfile` (D1, replaced), `deploy/ci/scan.sh` (`sbom <tar>` mode),
  `deploy/local/lab/sftp.Dockerfile` (`alpine:3.22@sha256:bfe7…b034`), `evidence/red-green-runs.md` (section "Wave 4"),
  this file.
- Not changed: `pom.xml`, everything under `src/main/` except the Dockerfile, `config/`,
  `deploy/k8s/base/partner-configuration.json`, `deploy/local/lab/k8s/*` (`today.yaml` read only).

### Red/green rows added (`evidence/red-green-runs.md`, section "Wave 4")

| Test | Red | Green |
|---|---|---|
| T2 `image-check.sh` | `:today`: `FAILED user: expected 10001:10001, got 185`, `FAILED java-version: expected 25.0.5, got 21.0.13`, entrypoint without the JVM options, no `/app`, unpinned bases (7). Plant 1: writable, 10001-owned fault-hook jar, `/app/config` (4). Plant 2 (after the review): `SPRING_PROFILES_ACTIVE` in the image, `lib/spring-boot-test-4.1.2.jar`, `FaultHook*.class` and `config/application-dev.properties` inside `application.jar` (4) | `:006`: all 15 checks |
| T3 `scan.sh image` | `:today`: 44 fixable HIGH/CRITICAL (Tomcat 11.0.24 CRITICAL ×4; Jackson, snappy-java, lz4-java; expat, krb5-libs, util-linux libs, libxml2, python3) | `:006`: none (MEDIUM fixable 18 / no fix 20, LOW no fix 4 reported); jar: no finding at all; SBOM written |
| T4 `ManifestPolicyTest` | `today.yaml`: 10 of 11 rules, e.g. `kind: expected StatefulSet, got Deployment`, `terminationGracePeriodSeconds: expected >= 75 …, got (absent: 30 by default)`. Secret rule on a planted Secret: `stringData: … got keys [DB_PASSWORD]`. Release overlay with a planted `replicas:` override: `replicas: expected only [...] (the base plus the image), got present` | base + release: 11/11 (before and after the review fixes) |
| T5 kubeconform | planted `replica: 1`: `at '/spec': additional properties 'replica' not allowed` | `ci.yaml` + `release.yaml`: 28 resources, 28 valid |

### Wave gate (Task 31)

`./mvnw -B verify` (Docker, Testcontainers), 2026-11-16: **BUILD SUCCESS, 17:11 min**. YAML validator `Validation
success (files:6)`; unit tests **203** run (192 + T4 11), 0 failures, 0 skipped; spec ITs 5/5; failure ITs 31, 0
failures, 0 skipped; traceability matrix passed. Per-IT: Tc001 70.6 s, Tc002 45.6, Tc003 44.0, Tc004 27.4, Tc005 49.8,
ConnectionSettingsIT 6.2, DatabaseFrozenIT 109.9, DeliveryFailureIT 59.9, EndStateQueriesIT 4.4, **KillIT 272.5**,
NotificationTextIT 3.1, PickListLoginDirectoryIT 25.0, ProbesIT 26.5, SftpSubsystemHangIT 42.1, **UnexpectedErrorIT
184.5**, UnstorableOrderIT 31.6, WaitingSelectIT 1.7. Log `target/006-wave4-logs/gate-verify.log`.

The review's fixes touched only `ManifestPolicyTest` (after the gate: 11/11 green, and 10/11 red again against
`today.yaml`) and `image-check.sh` (re-run on the rebuilt image). Neither is run by the gate's ITs. Then, on the
gate's jar, `docker build … -t trailhead/order-integration:006` gave **image ID
`sha256:7346a64c840e15581c28653ab6fc80eee9b78452c32cda480bb30e500e0ec6d4`** (arm64, 412 MB). That is the same ID as the
Task 26 build, because the jar is reproducible. On that image: `image-check.sh` all passed; `docker save -o
target/image.tar`; `scan.sh image` and `scan.sh jar` found no fixable HIGH/CRITICAL; SBOM `target/scan/sbom.cdx.json`;
T5 28/28 valid. Plan Step 3 (`WAVE-4-OK`) ran on this build.

### Findings (P-17) and open items

- **W4-F1 — recorded (T4 red weaker than planned for one rule).** "No Secret with data" cannot fail against
  `today.yaml`, which holds no Secret (the lab's come from `secrets.sh`). The rule is proven red by a planted Secret;
  the test was not changed to fail artificially.
- **W4-F2 — open, needs the developer's look (CI only).** STARTTLS to the stand-in mail server needs the per-run CI CA
  in the JVM's truststore. The jar has no other way to trust it, and the release forbids `JAVA_TOOL_OPTIONS` (D9, T4).
  So the CI overlay adds `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/trailhead/files/truststore.p12 …` to the
  StatefulSet **in `deploy/k8s/ci` only**, as the lab's `today.yaml` did. The CI smoke therefore runs the release plus
  one JVM option. Task 32's `smoke.sh` must put `truststore.p12` (holding only the CI CA) into `order-integration-files`.
  The real mail server's trust is OQ-076's. Alternative, not built: a `mail.smtp.ssl.trust` setting, which is a
  `src/main` change.
- **W4-F3 — open (developer check).** `docker buildx imagetools inspect` was refused by this session's permissions.
  The system images' digests are therefore the ones Docker recorded when each was pulled by tag (`docker image inspect
  … RepoDigests`, classic image store, i.e. the registry's digest for the tag): python 3.12-alpine `e064…0fc7`,
  postgres 16-alpine `7218…80ea`, kafka 4.3.1 `77e3…2837`, mailpit v1.21 `8137…50f1a`, curl 8.12.1 `94e9…44e6`, alpine
  3.22 `bfe7…b034`. **Check:** `docker buildx imagetools inspect <image>@<digest>` shows a multi-arch index with
  `linux/amd64` for each (the CI runner is amd64).
- **W4-F4 — open (design text).** Design §3.7 says request = limit gives the Guaranteed QoS class. With a CPU request and
  no CPU limit (also §3.7), the pod is **Burstable**. Memory request = limit still means it is not evicted for its own
  memory use. The StatefulSet comment says so. Options: (a, recommended) accept Burstable and correct §3.7 with Task 38's
  docs; (b) a CPU limit equal to the request (Guaranteed, but with the throttling §3.7 rejects).
- **W4-F5 — open (from the review; Wave 5 A4, Task 38 runbook).** With `podManagementPolicy: OrderedReady`, a pod that
  never becomes ready is not replaced when the previous release is applied (a known StatefulSet behaviour): the person
  must also delete `order-integration-0`. A4 (bad secret, rollback by the runbook) will show it, and the runbook's
  rollback step needs that line. Also not covered by T4: `configMapGenerator.envs` files (the base has none).
- **W4-F6 — recorded (NetworkPolicy shape).** 9090 is admitted from `podSelector: {}` (the policy's own namespace)
  rather than a `kubernetes.io/metadata.name` namespaceSelector: the base has no namespace name, and kustomize does not
  rewrite selector labels. Kubelet probes are not subject to NetworkPolicy in kind (kindnet). A CNI that filters them
  needs an ipBlock for the node CIDR (comment in the file). A5 in the lab will show whether the probes pass.
- **W4-F7 — recorded (CI namespaces, plan Task 30 Step 2's second option).** The native SFTP server (sshd with a
  chroot) cannot run restricted. The systems therefore run in `trailhead-ci-systems` (Pod Security "baseline"), and the
  integration and the client pod in `trailhead-ci` (restricted). Hosts in `settings.env` are
  `<name>.trailhead-ci-systems.svc`, and Kafka advertises `kafka.trailhead-ci-systems.svc:9092`. The CI overlay uses
  kustomize's NamespaceTransformer with `unsetOnly`; a plain `namespace:` would move the systems too. **Task 32
  needs:** certificates for `postgres.trailhead-ci-systems.svc`, `mail.trailhead-ci-systems.svc` and
  `order-integration.trailhead-ci.svc`; the systems' Secrets and the `product-examples` ConfigMap in
  `trailhead-ci-systems`; the integration's Secrets in `trailhead-ci`; the Kafka topic.
- Deviations: T2 checks more than the plan lists (owner, jar present, image environment, the base's `JAVA_VERSION`,
  the application jar's entries). The `FROM` check reads the repository Dockerfile only and is named
  `dockerfile-base-digest`. `image-check.sh` streams `/app` out with `docker run … tar` into `target/image-check/` and
  removes it afterwards; no host folder is mounted.

### Assumptions (non-interactive run)

- A-W4-1: the session wave is the plan's "Wave 4" (tasks 25–31), with tasks inline in order. The environment probe is
  replaced by the entry check and the gate, and step 3.5 verification is the gate (A-W1-2, A-W1-3).
- A-W4-2: W4-F2 was handled with the recommended option for CI (a CI-only overlay patch), because Task 32's file list
  cannot edit `deploy/k8s/ci` and nobody could be asked.
- A-W4-3: the planted faults for red runs live in separate images and files (`target/006-wave4-logs/*`, tags
  `:t2-plant`, `:t2-plant2`). No release file was edited for a fault except the two one-line plants of T4 (release
  `replicas:`) and T5 (`replica: 1`), both reverted and checked.

### Lab state

The lab cluster was not changed. Only `kube.sh version --client`, `kube.sh kustomize` (no API call) and `kube.sh get
nodes` (`trailhead-lab-control-plane Ready`, v1.34.0) ran; nothing was loaded or applied.

Local images built this wave: `trailhead/order-integration:006` (`sha256:7346a64c840e…`), `trailhead/lab-sftp:ci`
(`sha256:1e1451e313a5…`), and the plant images `trailhead/order-integration:t2-plant` and `:t2-plant2`. This session
may not remove them (`docker rmi` is denied): **delete the two plant images by hand**. The tars `target/image-pre.tar`
and `target/image.tar` are gitignored.

No process is left: no `order-integration` or surefire JVM (`pgrep` empty), and every Trivy, kubeconform and
image-check container ran with `--rm`. A Testcontainers PostgreSQL and its reaper appeared at 17:00:25Z, just after the
gate, during the T4 re-runs; no surefire JVM was left when I looked. The reaper removed both within a minute, and none
remain.

### What the next wave needs (Wave 5 entry)

| Needs | Check | Result this session |
|---|---|---|
| `## Wave 4 handoff` present | `grep -q '## Wave 4 handoff' docs/camel-kit/006-deployment/execution-report.md` | present |
| the release image | `docker image inspect trailhead/order-integration:006` | `sha256:7346a64c840e15581c28653ab6fc80eee9b78452c32cda480bb30e500e0ec6d4` |
| `KIND_KUBECONFIG` | set by the developer (P-14) | set; `kube.sh` works |
| lab cluster reachable | `deploy/local/kube.sh get nodes` | `trailhead-lab-control-plane Ready` |
| `deploy/local/lab/lab.sh up` | done by Wave 5 (or the developer) | **not done** in this wave |
| manifests | `deploy/local/kube.sh kustomize deploy/k8s/ci` and `deploy/k8s/release` render; T4 green | rendered to `target/k8s/`; T4 11/11; kubeconform 28/28 |
| decisions | developer | W4-F2 (CI truststore), W4-F3 (digest check), W4-F4 (QoS text), W4-F5 (rollback line) |

## Wave 5 start (Task 32 Step 3b)

Session 2026-11-16, code at `9bb0b41` (waves 1–4 and developer checks 8–12 committed by the developer), working tree
clean. `git diff --stat -- src pom.xml` at the start of wave 5: **empty** (no line). Task 35 compares against this.

## Wave 5 handoff

Session 2026-11-16, tasks 32–35 (the plan's "Wave 5", no Java change). Code at start: `9bb0b41`, working tree clean.
`doc check`: plan and design not stale. Catalog binding `camel_catalog_components(limit=0, spring-boot)` →
`camelVersion` 4.22.2, MATCHED (no component in this wave; `platform-http` doc read for the partner endpoint). Entry:
`## Wave 4 handoff` present; `trailhead/order-integration:006` = `sha256:7346a64c840e…`; `KIND_KUBECONFIG` set;
`kube.sh get nodes` → `trailhead-lab-control-plane Ready` v1.34.0; namespace `trailhead-lab` present with its systems,
so `lab.sh up` was not needed.

**A commit appeared during the session:** `7ccf545 Session 08-execute-wave5` (author `dev`, 18:14:47Z, right after the
last smoke run) holds this wave's files up to that moment. This session did not make it and committed nothing. The
edits after it (the TLS wording and the post-review row in `red-green-runs.md`, this handoff) are uncommitted.

### Tasks done

| Task | Result | Reviews (ACR → spec → quality) |
|---|---|---|
| 32 Fault image + T12 smoke | `deploy/ci/fault.Dockerfile` (FROM the release image; the hook jar root-owned and read-only under `/fault`; never published); `deploy/ci/smoke.sh` (every §3.15 job-4 check, `FAILED <check>: expected …, got …` + 50 log lines, mode `secrets`); lab overlay `deploy/local/lab/k8s/release`; `lab.sh app-sts`, `lab.sh smoke [red\|secrets\|keep]`. Red: `FAILED ready` (no pod). Green in the lab in 246 s | inline: PASS_WITH_TRADEOFFS (W5-F1, W5-F2; the Secrets stay at cleanup because the client pod mounts them) / PASS / PASS |
| 33 A1, A2, A9, A10 | `evidence/deploy-after-runs.md`, `runs-after/`; `runs.sh` on the StatefulSet (rollout with a pod watch; `replicas` with no scale command) | inline: PASS_WITH_TRADEOFFS (W5-F3) / PASS / PASS |
| 34 A3, A4, A5, A6, A6b | `runs.sh` cases `badconfig` (the bad release through `apply -k`; rollback by the runbook, incl. developer check 10), `outages` (pod watch, restart count), `burst`, `patched`, `release`; lab patches `k8s/patch-heap-96m.yaml`, `-64m.yaml` | inline: PASS_WITH_TRADEOFFS (W5-F4, W5-F5) / PASS / PASS |
| 35 A7, A8, close | `k8s/patch-fault-006.yaml`; `partner-change.sh` for the StatefulSet and the generated ConfigMap (`behavior: replace`, `--restore`); this handoff | **fresh subagent** review of Tasks 32–35 (security, correctness, quality lenses): **FAIL**, 1 Critical, 3 Important, 6 suggestions. Fixed: the Critical (CI `apply -k` with `-n`), all three Important (the TLS 1.1 claim and check; rollout checks that passed with no traffic; background jobs left running on a failure) and suggestions 5, 6, 7, 9. Recorded, not done: 8 (KUBE context guard) and 10 (events printed twice in run files). Smoke re-run green after the fixes, 241 s |

### Files created / modified (working tree; up to 18:14:47Z also in commit `7ccf545`)

- Created: `deploy/ci/smoke.sh` (executable), `deploy/ci/fault.Dockerfile`, `deploy/local/lab/k8s/release/kustomization.yaml`,
  `deploy/local/lab/k8s/{patch-heap-96m,patch-heap-64m,patch-fault-006}.yaml`, `evidence/deploy-after-runs.md`,
  `evidence/runs-after/*` (23 files).
- Modified: `deploy/local/lab/{lab,runs,partner-change}.sh`, `evidence/red-green-runs.md` (section "Wave 5"), this file.
- Not changed: `src/`, `pom.xml` (`git diff --stat 9bb0b41 -- src pom.xml` is empty, as recorded at the start),
  `deploy/k8s/*`, `deploy/local/lab/k8s/{today,systems,config-pods}.yaml`, `secrets.sh`, `inspect-image.sh`.

### Red/green rows and after-runs

| Test | Red | Green |
|---|---|---|
| T12 `smoke.sh` | no release: `FAILED ready: expected order-integration-0 Ready within 20 s, got … NotFound` | lab, `:006` + `:006-fault`: every check (246 s). Green again after the review fixes (241 s): partner order accepted, its resend the same answer, a 422; Peakmart order recorded; both pick lists once; TLS 1.2/1.3; exit 70 → restart → 20 of 20 once (first receipt 5.1 s); rollout ≤ 1 pod, 0 × 404 |

**A1–A10: 10 pass, 1 not met (A6b, W5-F5); A2 passes with a measured-value finding (W5-F3).**

- A1: the image as designed.
- A9: starts and serves under `restricted` (enforcement shown by a Forbidden dry run).
- A2: 0 × 404, never more than 1 pod.
- A10: never more than 1 pod; 200 Order Desk emails, each sent once.
- A3: 200 of 200 recorded once; the stop took 3 s.
- A4: CrashLoopBackOff on Flyway's password failure; rolled back, the stuck pod deleted; ready 39 s after the rollback.
- A5: probes 200 in all three outages, no restart, the contract's 503s.
- A6: no OOMKill under 1 GiB; peak 448 MiB (cgroup; the lab has no metrics-server).
- A6b: no OutOfMemoryError at a 96 or 64 MiB heap.
- A7: the restarted process took its first event 4.8 s after container start (L6: ~42 s); 20 of 20 once.
- A8: a new ConfigMap name, the pod rolled, the changed partner registered.

### Findings (P-17) and open items

- **W5-F1 — open (TLS 1.1; T12 and design §3.15 "TLS 1.2/1.3 only").** The client pod's curl (OpenSSL 3) does not offer
  TLS 1.1 (`curl: (35) … no protocols available`), so no run reached the server with TLS 1.1. As in L4, the server's
  refusal rests on `server.ssl.enabled-protocols=TLSv1.3,TLSv1.2`. The smoke now requires a handshake error and says
  which side refused. Option (recommended, wave 6 or the developer): a client that can offer TLS 1.1 (`openssl s_client
  -tls1_1 -cipher 'DEFAULT:@SECLEVEL=0'`) and a check for the server's `protocol version` alert.
- **W5-F2 — recorded (plan deviation from P-12).** The release image's `/app/application.jar` is the extracted thin jar
  (manifest `Class-Path: lib/…`; the `spring-boot-loader` layer is empty), so it has no `PropertiesLauncher` and no
  `loader.path`. `fault.Dockerfile` therefore puts the hook jar on `-cp` next to it and starts `CamelApplication` with
  `--spring.profiles.active=default,fault-injection`. `FaultHook` was active (`Fault hook active …`; exit 70 seen).
- **W5-F3 — recorded (A2; OQ-088's number).** Each rollout leaves ~16 s without a ready pod, plus up to the client's own
  timeout for a request sent just as the old pod's network goes (one request waited 15 s). The design estimated 10–15 s.
- **W5-F4 — recorded (A4; developer check 10 confirmed and extended).** The bad release's pod never became ready and had
  to be deleted after "apply the previous objects". The same happened after every switch back from the crash-looping
  fault image (smoke step 7, A7): a pod that was ready once and then crash-loops is not replaced either. Task 38's
  runbook line should cover both cases: "if `order-integration-0` is still on the old revision after the rollback, delete
  it". The cause of the crash is in the `Caused by` lines (`FlywaySqlUnableToConnectToDbException … password
  authentication failed`); the very last log line is a stack-frame count, not the cause.
- **W5-F5 — open (A6b, expectation not met).** With `-Xmx96m` and `-Xmx64m` the L7 burst did not exhaust the heap, so
  exit 3 from `-XX:+ExitOnOutOfMemoryError` was not seen; nothing was lost. Options: (a, recommended) accept it: the
  option is in the entrypoint (T2, A1) and what it does is the JVM's behaviour; (b) in wave 6, a smaller heap or a fault
  point that allocates memory.
- **W5-F6 — open (the CI path of `smoke.sh` has not run).** The lab has one namespace; the CI overlay has two
  (`trailhead-ci`, `trailhead-ci-systems`). The review found the Critical `apply -k -n` bug by reading the code; it is
  fixed. The two-namespace path (namespaces created before `namespace.yaml` sets their labels, Secrets in both, the
  topic, cross-namespace host names) is first exercised by the developer's CI run (§7). Also not done (review
  suggestion 8): with `KUBE=kubectl` the script acts on the current context; in CI that is the runner's kind cluster.
- **W5-F7 — recorded (lab procedure).** A5 restarts the system pods, which cannot run `restricted` (as W4-F7). A9's
  `restricted` label was removed before A5, and `trailhead-lab` has no Pod Security label now.
- Plan text: the harness refused the shell loops in the verify steps of Tasks 33–35 (`for a in …; do grep …; done`).
  Each check ran instead as one `grep -oE '^\| (A…) '` and found every row.

### Assumptions (non-interactive run)

- A-W5-1: the tasks ran inline in plan order (one strict chain, one lab). The environment probe is the wave's entry
  check. Step 3.5 verification is the wave's own checks (smoke green, after-runs recorded) plus `./mvnw -B -DskipITs
  verify` (`BUILD SUCCESS`, 203 unit tests, 0 failures, 0 skipped, 15 s). There was no full `verify` because no Java
  changed (P-2).
- A-W5-2: the design-phase "today" Deployment, its ConfigMap and its Service were deleted from `trailhead-lab` (through
  `kube.sh`) before the red run, so that the release never ran next to a second instance.
- A-W5-3: in the lab `smoke.sh` reuses `target/lab/secrets` (`SECRETS_DIR`), the files the running systems were started
  with: new random values would not match the existing PostgreSQL. In CI `SECRETS_DIR` defaults to a new
  `target/smoke/<NS>` per run, with random values.
- A-W5-4: the harness refused env-prefixed commands, so the lab runs the smoke through `lab.sh smoke` (`lab.sh` is in
  Task 32's file list).
- A-W5-5: the bad release (A4) and the lab-only patches (A6b, A7) went in through `apply -k` of a temporary kustomization
  under `target/lab/runs/`, so that "apply the previous objects" undoes them as it would undo a real release.
- A-W5-6: A6b's extra 64 MiB run used a different parameter; it was not a re-run of the same run until green. Its result
  is recorded as not met too.

### Lab state

- `trailhead-lab` holds the systems (postgres, kafka, sftp, mail, catalog), the client pod, and the ConfigMaps
  `catalog-stub`, `product-examples` and `partner-configuration` (from the design phase).
- Secrets: the systems' four; `order-integration-secrets`, `-files` and `-partner-api-keys` (from `smoke.sh`);
  `order-integration-env` and `-secret-store` (from the design phase).
- **No integration object:** the last smoke run removed the StatefulSet, Services, NetworkPolicy, ServiceAccount and
  ConfigMaps. The namespace has no Pod Security label.
- The Order Database holds this wave's orders: TG-1000..1003, TG-1100..1102, and PM numbers from 549686 to 650019.
- Images, local and loaded into the kind node:
  - `trailhead/order-integration:006` = `sha256:7346a64c840e15581c28653ab6fc80eee9b78452c32cda480bb30e500e0ec6d4`
  - `trailhead/order-integration:006-fault` = `sha256:c2a3b09bb54061068c74ab9c9304e9f87ebfdaeedf0ee30a8d436ab25b19988b`
    (lab and CI only; can be deleted by hand after wave 6)
- No process is left: no smoke, runs or lab script, and no `kubectl exec`. Two waiters of this session that matched
  their own command line were stopped with TaskStop.
- Logs: `target/006-wave5-logs/`; run files: `target/lab/runs/`.

### What the next wave needs (Wave 6 entry)

| Needs | Check | Result this session |
|---|---|---|
| `## Wave 5 handoff` present | `grep -q '## Wave 5 handoff' docs/camel-kit/006-deployment/execution-report.md` | present |
| `deploy/ci/{scan,image-check,smoke,fault}` | `ls deploy/ci/scan.sh deploy/ci/image-check.sh deploy/ci/smoke.sh deploy/ci/fault.Dockerfile` | present; `smoke.sh` executable |
| smoke and fault image green in the lab | `deploy/local/lab/lab.sh smoke` | green twice (246 s; 241 s after the review fixes) |
| `verify` unchanged since wave 4 | `git diff --stat 9bb0b41 -- src pom.xml` empty; `./mvnw -q -DskipITs verify` | empty; `BUILD SUCCESS`, 203 unit tests |
| `KIND_KUBECONFIG` | needed for T5 | set; `kube.sh` works |
| decisions | developer | W5-F1 (TLS 1.1 client), W5-F5 (A6b), W5-F6 (first CI smoke run); W5-F4 goes into Task 38's runbook rollback step |

## Wave 6 handoff

Session 2026-11-16, tasks 36–38 (the plan's "Wave 6", the last). Code at start: `5c12365` (waves 1–5 and developer
checks 13–16 committed by the developer), working tree clean. `doc check`: plan and design not stale. Catalog binding
`camel_catalog_components(limit=0, spring-boot, camel-catalog-provider-springboot:4.22.2)` → `camelVersion` 4.22.2,
MATCHED (no component in this wave). Entry: `## Wave 5 handoff` present; `deploy/ci/{scan,image-check,smoke}.sh` and
`fault.Dockerfile` present; `./mvnw -B -DskipITs verify` `BUILD SUCCESS` (203 unit tests, 13.99 s); `KIND_KUBECONFIG`
set (`kube.sh kustomize` worked). Developer decisions applied: checks 9 (design §3.7: Burstable), 11 (runbook: a mail
server with a private CA) and 16 (runbook: delete `order-integration-0` after a rollback, both cases); the CI repository
is private on a free plan (review decision 4). Nothing committed, nothing pushed (user direction).

### Tasks done

| Task | Result | Reviews (ACR → spec → quality) |
|---|---|---|
| 36 T11 `test-inventory.sh`, `test-summary.sh` | done: red (a deleted report named; planted failure/skip/error named; after `-DskipITs` all 17 ITs named), green after a full `verify` (35 of 35 classes), `T11-OK` | inline: PASS_WITH_TRADEOFFS (W6-F3, W6-F4, W6-F8) / PASS / PASS |
| 37 D14 `.github/workflows/gate.yml` | done: five jobs, `needs` release → kind → image, manifests → build; least privilege (top `contents: read`, `actions: read` in image, `packages: write` in release); SHA pins; actionlint + shellcheck clean; `GATE-OK` (with `-no-color`, W6-F5) | inline: PASS_WITH_TRADEOFFS (W6-F1, W6-F2, W6-F7) / PASS / PASS |
| 38 Runbook, open questions, close | done: `deploy/README.md` (every §3.12 bullet plus developer checks 11 and 16, W3-F3, W4-F6, the pull secret and the node architecture, W6-F6); `docs/open_questions.md` OQ-087 … OQ-095 added, OQ-078 and OQ-060 "proposed by design 006"; `README.md` "Deployment"; design §3.7 corrected (check 9); final `verify`, T11, T5, actionlint; this handoff | **fresh subagent** review of Tasks 36–38 (correctness, security, design-conformance lenses): no Critical, 2 Important, 6 suggestions. **All fixed**, each corroborated in the files first: (1) no baseline marked nothing NEW (`scan.sh` adds the column only with a baseline): the workflow now writes `{}`, proven on today's image: 44 of 44 `NEW`; (2) the pull secret came after the first apply, so the first pod could never pull: now the ServiceAccount and the secret come before it; (3) the image is amd64 only: node check added; (4) the rollback revision check ran too early: after `rollout status --timeout=2m` now; (5) rotate commands for the ConfigMap and the files Secret; (6) the token no longer on a command line; (7) baseline query `event=push`; (8) stale-report note in `test-inventory.sh`. GATE-OK re-run after the fixes |

### Files created / modified (working tree, uncommitted)

- Created: `deploy/ci/test-inventory.sh`, `deploy/ci/test-summary.sh` (both executable), `.github/workflows/gate.yml`,
  `deploy/README.md`.
- Modified: `docs/open_questions.md`, `README.md` (section "Deployment"), `docs/camel-kit/006-deployment/design-spec.md`
  (§3.7 rationale only, developer check 9), `evidence/red-green-runs.md` (section "Wave 6"), `evidence/build-times.md`
  ("Final"), this file.
- Not changed: `src/`, `pom.xml` (`git diff --stat -- src pom.xml` empty), `deploy/k8s/*`, `deploy/ci/{scan,image-check,smoke}.sh`,
  `deploy/ci/fault.Dockerfile`, `deploy/local/*`, `.trivyignore.yaml`, `.claude/`.

### Red/green rows added (`evidence/red-green-runs.md`, section "Wave 6")

| Test | Red | Green |
|---|---|---|
| T11 `test-inventory.sh` | `FAILED inventory: …failure.ProbesIT no report (…)` (report deleted); `…EmailPatternTest failures 1`, `…WaitingSelectIT skipped 1, errors 1` (planted); after `-DskipITs`: all 17 ITs `no report` | full `verify`: `all 35 test classes have a report with 0 skipped, 0 errors, 0 failures`; `test-summary.sh` table, `T11-OK` |
| T3 baseline (review) | no baseline → no NEW column (by reading `scan.sh`) | `{}` baseline: 44 of 44 findings `NEW` on today's image |
| actionlint | — (first run: the plan's `-color=never` is not a flag) | 0 errors (shellcheck included); `GATE-OK` |
| T5 | — | 28 of 28 valid |

### Wave gate (Task 38)

`./mvnw -B verify` (Docker, Testcontainers), final run after every fix: **BUILD SUCCESS, 19:20 min** (the T11 green run
on the same code an hour earlier: 14:41 min; the spread is mostly `UnexpectedErrorIT`, 158 → 346 s, see
`evidence/build-times.md` "Final"). `Validation success (files:6)`; unit tests 203, spec ITs 5, failure ITs 31, 0
failures, 0 skipped; traceability matrix passed. Then `deploy/ci/test-inventory.sh` → 35 of 35 clean; T5 28/28;
actionlint 0 errors. Plan Step 6 (`WAVE-6-OK`) ran after this build. Logs `target/006-wave6-logs/`.

### Findings (P-17) and open items

- **W6-F1 — open, developer check before the first push (action SHAs).** This session's network calls (`git ls-remote`,
  web fetch) were refused, so the four action SHAs in `gate.yml` were **not resolved by a tool**. They are the commits I
  know for `actions/checkout` v4.2.2 `11bd7190…f683`, `actions/setup-java` v4.7.1 `c5195efe…1c00`,
  `actions/upload-artifact` v4.6.2 `ea165f8d…fa02`, `actions/download-artifact` v4.3.0 `d3f86a10…8093`. **Check each:**
  `git ls-remote https://github.com/actions/<name> refs/tags/<tag>` must print the SHA in the file. A wrong SHA fails the
  run at "Set up job"; never replace one with a tag.
- **W6-F2 — recorded (plan deviation, kind).** The plan asked for kind's release binary with a pinned SHA-256 in the
  workflow. Its checksum could not be read here (no network). Taken instead: `go install sigs.k8s.io/kind@v0.30.0` on the
  runner. The pinned version is verified by the Go checksum database. kind v0.30.0's default node image is Kubernetes
  v1.34.0, pinned by digest inside kind. Option for the developer: switch to the binary with its `.sha256sum` value
  written into the workflow.
- **W6-F3 — recorded.** The `grep` fallback of both scripts (no `xmllint`) was not run: the session refused a PATH
  without `xmllint`. Job 1 installs `libxml2-utils` when `xmllint` is missing, so CI uses the path that was run.
- **W6-F4 — recorded (local only).** T11 does not check report age. A `-DskipITs` build after an earlier full `verify`,
  without `clean`, passes on the old failsafe reports (the header says so; CI checks out fresh). Locally,
  `test-summary.sh` also lists wave 2's stale `ZzScratchSftpProbeIT` report: delete
  `target/failsafe-reports/TEST-com.trailheadgear.orderintegration.failure.ZzScratchSftpProbeIT.xml` by hand.
- **W6-F5 — recorded (plan text).** The verify command of Task 37 Step 8 uses `actionlint -color=never`, which actionlint
  1.7.12 rejects (`invalid boolean value "never" for -color`). `-no-color` was used, in the workflow too.
- **W6-F6 — open, matters for the developer's `trailhead-prod` deployment.**
  - *Image pull:* the release manifests carry no pull secret, and the GHCR package of a private repository is private.
    The runbook's Prepare step 6 creates the ServiceAccount with the secret before the first apply.
  - *Architecture:* the release is `linux/amd64` only (design "Not Doing"). A kind cluster on this Apple silicon machine
    is arm64, so the released image would run there under emulation at best. Prepare step 7 says how to check, and what
    a local arm64 build of the same commit means: it is not the released image.
  - *Decision:* the design names neither case.
- **W6-F7 — recorded (runner assumption).** Job 5 uses `kustomize edit` from the ubuntu-24.04 image. The step fails with
  `FAILED render: expected kustomize on the runner, got none` if the image has no kustomize. Not verifiable here.
- **W6-F8 — recorded (beyond the plan).** T11 also fails a report with `tests="0"` (`no tests ran`) and an unreadable
  attribute. The summary also has a "Flakes" column (§7 item 6).
- **W6-F9 — recorded.** In CI, `smoke.sh` gets `KUBE="kubectl --context kind-trailhead-ci"`. That closes wave 5 review
  suggestion 8 for CI without a change to `smoke.sh`. W5-F6 (the two-namespace path) stays open until the first CI run.
- Developer check 9: design §3.7's rationale corrected (Burstable). This is the approved design's text and not a change
  of a decision, so the plan was not marked stale.

### Assumptions (non-interactive run)

- A-W6-1: the tasks ran inline in plan order (one strict chain). The environment probe is the wave entry check. Step 3.5
  verification is the final `verify` plus T11, T5 and actionlint. The cross-cutting review is the fresh-subagent review
  of Task 38's row.
- A-W6-2: Task 36's red copies were made file by file (the harness refused `cp -R`). The `-DskipITs` red used a fresh
  copy of that build's surefire reports without failsafe reports: the state of a fresh CI checkout. The local
  `target/failsafe-reports` still held the previous run's reports (W6-F4).
- A-W6-3: W6-F1 and W6-F2 took the recommended options that keep the gate runnable, and flagged them. The alternative
  was placeholders that fail the first run.
- A-W6-4: production's namespace in the runbook is a variable (`NS=trailhead` as the example). The design names the
  cluster (`trailhead-prod`), not the namespace.

### Lab state

The lab cluster was not changed: only `kube.sh kustomize` ran (no API call). No image was built, loaded or removed. The
local images of wave 5 remain: `trailhead/order-integration:006` (`sha256:7346a64c840e…`) and `:006-fault`
(`sha256:c2a3b09bb540…`, can be deleted by hand). `target/scan/image-report.json` was rewritten during the review proof
and then restored from the `:006` tar (`no fixable HIGH/CRITICAL finding`). No process of this session is left: no JVM
started from this project (`pgrep -f scratchpad/ws9` empty), every Trivy, kubeconform and actionlint container ran with
`--rm`, and this build's Testcontainers session is gone. The Testcontainers containers still running belong to another
project's build (their sessions include `registry:3.0.0`, which this project never uses).

## For the developer's first CI run

The agent does not push, open a pull request or start a run (§3.16). **The developer:** check W6-F1 (the four SHAs),
then push the branch, open a pull request (jobs 1–4 run), then merge to `main` (jobs 1–5 run, the release). Report from
these runs (design §7):

- [ ] 1. Wall-clock time of each job and of each step; the runner's CPU count and memory.
- [ ] 2. Job 1: `Tests run`/`Skipped` per execution, and the `Time elapsed` of every IT (from the summary). If job 1
      takes more than 25 minutes after D10, failsafe is split across two parallel jobs (§3.15).
- [ ] 3. Job 2: the image digest, the T2 output, and the T3 tables (image and jar), including findings without a fix.
      The first run has no baseline, so every fixable finding is marked `NEW`.
- [ ] 4. Job 4: the smoke output, the restart check (exit code, restart count, time from restart to the first receipt),
      and the rollout check (pods seen, statuses). This is the first run of the two-namespace CI path (W5-F6).
- [ ] 5. Job 5 (on `main`): the pushed digest, that `release.yaml` names it, and that job 5 ran only after jobs 1–4
      passed in the same run (review decision 4).
- [ ] 6. Any test that failed and then passed on a re-run, with its log.

Then deploy the `release` artifact to `trailhead-prod` with `deploy/README.md` (W6-F6: pull secret, node architecture).

## Fixes after the first CI run

Scope: developer checks 17 (CI-1) and 18 (CI-2) only. Nothing is committed; the developer pushes and runs CI again.
Rows are in `evidence/red-green-runs.md` under "Fixes after the first CI run"; logs in `target/006-ci-fix-logs/`.

### CI-1: `known_hosts` names the SFTP host as `WAREHOUSE_SFTP_HOST` does

- **Cause** (check 17, run 43964902986): `smoke.sh` wrote `known_hosts` for `sftp`, the CI settings set
  `WAREHOUSE_SFTP_HOST=sftp.trailhead-ci-systems.svc`, so JSch refused the host key (`JSchUnknownHostKeyException`) and
  no pick list reached the server. The lab could not show it: both names are `sftp` there (W5-F6).
- **Fix in `deploy/ci/smoke.sh`:**
  - `setting <KEY>` reads ConfigMap `order-integration-settings` from what `APPLY_DIR` renders (`$KUBE kustomize`).
    Without `APPLY_DIR` it reads the ConfigMap the cluster holds in `NS`.
  - The `known_hosts` entry is `WAREHOUSE_SFTP_HOST`, or `[host]:port` when `WAREHOUSE_SFTP_PORT` is not 22
    (`deploy/README.md`). Without settings (the red run) it stays `sftp`.
  - A newly generated `known_hosts` uses that entry. The new check `known-hosts` runs first, after the files are
    generated or reused and before any Secret is created or anything is applied. It fails with
    `FAILED known-hosts: expected <host>, got <host>` when no name on any line is the setting's. In the red run (no
    settings in use) it logs that nothing was compared.
  - The check catches a reused secrets directory whose `known_hosts` no longer matches (the lab's `target/lab/secrets`)
    instead of rewriting it.
- **Runs:**
  - Red: the developer's CI run (check 17).
  - The new check red on a planted mismatch: the lab overlay set to `sftp.trailhead-lab.svc`, then reverted.
    `FAILED known-hosts: expected sftp.trailhead-lab.svc, got sftp`, exit 1, nothing applied.
  - Lab smoke green: every check, 269 s, first receipt 7.7 s after the restart, 0 × 404.
- Not changed: `deploy/local/lab/secrets.sh` still writes `sftp`, which matches the lab overlay.

### CI-2: the four actions on Node.js 24

| Action | Before | After (SHA, tag comment kept) |
|---|---|---|
| `actions/checkout` (5 uses) | v4.2.2 | `3d3c42e5aac5ba805825da76410c181273ba90b1` # v7.0.1 |
| `actions/setup-java` (2) | v4.7.1 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` # v6.1.0 |
| `actions/upload-artifact` (6) | v4.6.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` # v7.0.2 |
| `actions/download-artifact` (6) | v4.3.0 | `9000827ccba6bdab643e8b6fd33ac0654aef8333` # v8.0.2 |

Each SHA is the developer's (`git ls-remote`) and matches the commit on the action's GitHub release page (WebFetch).
The release notes and changelogs from v5 up to each new version, and the `action.yml` at the two artifact SHAs, show
**no change the workflow has to follow**:

- **checkout:**
  - v5 moved to Node.js 24.
  - v6 persists the credentials in a separate file (`includeIf`). No step here uses git credentials after checkout; the
    release job logs in to GHCR with `docker login`.
  - v7.0.0 blocks checking out fork PRs for `pull_request_target` and `workflow_run`, and v7.0.1 skips that check when
    `allow-unsafe-pr-checkout` is at its default. The gate has only `pull_request` and `push` triggers.
- **setup-java:**
  - v6.0.0 renamed `jdkFile` to `jdk-file` (old name kept as an alias), removed the legacy Adopt distributions, rejects
    invalid boolean inputs and adds `.mvn/extensions.xml` to the Maven cache key.
  - The gate uses `distribution: temurin`, `java-version: '25'` and `cache: maven`, none of them changed. The first
    run after the bump misses the Maven cache once.
  - v6.3.0 (not taken) makes the problem matcher opt-in.
- **upload-artifact:**
  - v5 and v6 moved to Node.js 24; v6 requires runner ≥ 2.327.1, which GitHub-hosted `ubuntu-24.04` has.
  - v7 adds `archive` (default `true`, so every upload is still a zip under its `name`).
  - `name`, `path`, `if-no-files-found` and `retention-days` are unchanged.
- **download-artifact:**
  - v5 changed the path only for downloads by ID (`artifact-ids`); the gate downloads by `name`.
  - v7 moved to Node.js 24 (runner ≥ 2.327.1).
  - v8 errors on a digest mismatch by default (`digest-mismatch: error`, wanted) and skips extracting files that are
    not zips (every artifact here is a zip).
  - `run-id` and `github-token` (the baseline download) are unchanged. The baseline from run 43964902986 was uploaded
    by v4, still a zip.

The header comment that told the developer to check the SHAs before the first push (W6-F1) now records that they
were resolved. actionlint (`deploy/local/tool.sh actionlint -no-color`): exit 0, no finding.

### Findings and status

- **W5-F6 — fixed in the script, not yet proven in CI.** The two-namespace path failed at CI-1 and is now fixed in
  `smoke.sh`. The next CI run shows whether the rest of job 4 passes in two namespaces.
- **W6-F1 — closed** by developer check 18 and the release pages above.
- **CI-F1 — recorded.** The `setting` function was not run on the CI overlay, only checked against its rendered
  ConfigMap by reading. The session's permissions refused a scratch script and any command with an environment
  prefix, so only `lab.sh` could start `smoke.sh`. Expected in the next CI run:
  `ok known-hosts: names sftp.trailhead-ci-systems.svc, …` as the first line of job 4's smoke.
- **CI-F2 — recorded.** A `known-hosts` failure also prints the "last 50 log lines" header and a `NotFound` for the
  pod, which does not exist yet at that point. This is the shared `fail` output and was left as it is.

### Assumptions (non-interactive run)

- A-CI-1: "the settings in use" means the ConfigMap `order-integration-settings` that `APPLY_DIR` renders, which is
  what the release reads. Without `APPLY_DIR` it is the one in the cluster.
- A-CI-2: the planted mismatch went into the lab overlay as one reverted edit (this file's plant rule). The direct
  `smoke.sh` invocation with a scratch overlay was refused by the session's permissions.
- A-CI-3: the developer's versions were taken as given, even where a newer release exists (setup-java v6.3.0 of
  2026-11-14 is less than two weeks old).

### Lab state

The lab is as it was before: only `trailhead-lab`'s systems and Secrets, no StatefulSet. The smoke removed the
integration's objects. The Secrets were re-applied from the same `target/lab/secrets` files, so their values did not
change. No image was built, loaded or removed. No process of this session is left (`pgrep -fl 'smoke.sh|kube.sh|kubectl'`
empty).

### For the developer's second CI run

Push the working tree (`deploy/ci/smoke.sh`, `.github/workflows/gate.yml`, this report, `evidence/red-green-runs.md`).
Then check:

- No Node.js 20 deprecation warning in any job.
- Job 4's first smoke line is `ok known-hosts: names sftp.trailhead-ci-systems.svc`.
- Every job-4 check passes and job 5 runs on `main`.

The remaining items of "For the developer's first CI run" (times, T3 tables, job 5) apply to that run.

## Fixes after validation

Scope: `validation-decisions.md` decisions 2 and 3 (Q-1, Q-2), and decision 6's first point (this report's closing
summary). Nothing else in the decisions was changed here: 1, 4 and 5 and the rest of 6 are the developer's and the
architect's. Nothing is committed; the developer pushes, runs the gate and plants D10 through it again. Rows are in
`evidence/red-green-runs.md` under "Fixes after validation"; logs in `target/006-validation-fix-logs/`. Order (design
review decision 2): today's T4 run against both faults first (not caught), then the new rules red on each fault, then
green on `main`'s files.

### Decision 2: no secret value in a manifest (D10)

- **Rule** `noSecretValueInTheManifests` in `ManifestPolicyTest` (T4). A name matching `PASSWORD`, `SECRET`, `TOKEN`,
  `KEY` or `CREDENTIAL` (case-insensitive):
  - as an `env` entry of any container or init container of any workload, may not have a literal `value`. The value
    comes from `valueFrom`/`envFrom`. The one allowance is the path of a file in a volume mounted from a Secret
    (optionally `file:`): the base's `SECRET_STORE_DIR` and `PARTNER_TLS_PRIVATE_KEY=file:/etc/trailhead/files/…`
    name the file, and the secret is the file;
  - as a key of a ConfigMap (`data`, `binaryData`) may not have a value. On the files as written (job 1), the same
    holds for a `configMapGenerator`'s `literals`, its `envs` files (the CI overlay's `settings.env`) and its `files`.
- The value is never printed, only its length.
- `noSecretWithDataInTheManifests` (a `Secret` with data) runs on the renders too. It is unchanged.
- **Red:** today's T4 passed with D10 planted (`DB_PASSWORD` with a 15-character password-like literal). The new rule
  then caught it in the release and CI renders and on the raw files. It also caught a second plant,
  `SMTP_PASSWORD=…` in `settings.env`, in the rendered CI ConfigMap and in the `.env` file itself.
- **Green** on `main`'s files, rendered and raw. 12 rules.

### Decision 3: the T4 rules on what kustomize renders

- **Rendered mode** of `ManifestPolicyTest`:
  - `-Dmanifest-policy.rendered=<file>` takes `kubectl kustomize deploy/k8s/release` output as the input, and every
    T4 rule runs on it: kind, one replica, `OrderedReady`, the probes, grace period, resources, security context, no
    `SPRING_PROFILES_ACTIVE`/`JAVA_TOOL_OPTIONS`, mounts, Services and NetworkPolicy, image by digest.
  - The generator rule checks the rendered ConfigMap's name, `order-integration-partners-<10-character hash>`, with
    the `partner-configuration.json` key. The overlay-keys rule stays a raw-file rule.
  - `-Dmanifest-policy.rendered-also=target/k8s/ci.yaml` adds the CI render to the two secret rules only (A-V-2).
  - A file that still holds a Kustomization is refused. `manifest-policy.dir` and `manifest-policy.rendered` exclude
    each other.
- **Red:** today's T4 passed with `replicas: 2` set by a `patches:` entry in `deploy/k8s/base/kustomization.yaml`.
  On the render, D4 failed: `target/k8s/release.yaml StatefulSet/order-integration spec.replicas: expected 1, got 2`.
- **Green** on `main`'s render.
- **Gate:** job 3 `manifests` gains `setup-java` (Temurin 25, Maven cache; the same pinned SHA as jobs 1 and 4). After
  its `kubectl kustomize` step it runs
  `./mvnw -B -ntp test -Dtest=ManifestPolicyTest -Dmanifest-policy.rendered=target/k8s/release.yaml
  -Dmanifest-policy.rendered-also=target/k8s/ci.yaml`. Job 1 keeps T4 on the raw files, so T11 still finds its
  report there. actionlint is clean.
- **Locally:** the same command after `deploy/local/kube.sh kustomize deploy/k8s/release > target/k8s/release.yaml`
  (and `deploy/k8s/ci > target/k8s/ci.yaml`), as the class Javadoc says.

### Full build

`./mvnw -B verify` (Docker, Testcontainers): `BUILD SUCCESS` in 14:16 min. `Validation success (files:6)`. 204 unit
tests (203 + the new rule), 5 spec ITs and 31 failure tests ran, with 0 failures and 0 skipped. Afterwards
`deploy/ci/test-inventory.sh` reported `all 35 test classes have a report with 0 skipped, 0 errors, 0 failures`
(`target/006-validation-fix-logs/verify.log`).

### Findings and status

- **Q-1 / D10: fixed in the files, and red then green locally.** Still open: the developer's gate run and D10
  through it again (decision 2).
- **Q-2: fixed for the release.** All T4 rules now run on the release render, as decision 3 lists them. Not added
  (outside decision 3): Q-2's further items, such as init containers in the non-secret rules, `JDK_JAVA_OPTIONS`,
  `command`/`args` and egress.
- **VF-1 — recorded.** The CI render is not held to the release rules, only to the secret rules. It deploys the
  release with a tag image, `JAVA_TOOL_OPTIONS` (S-5) and the lab's systems. Its `trustStorePassword=changeit` sits
  inside `JAVA_TOOL_OPTIONS`, which is not a secret-named entry, so the rule does not see it (S-5 stays as accepted
  in W4-F2/check 11).
- **VF-2 — recorded.** The `KEY` pattern is wide on purpose (decision 2). A future non-secret setting with `KEY` in
  its name, given as a literal, fails T4, and the fix is to rename it or to take it from a Secret.
- **VF-3 — recorded.** Job 3 now compiles the project (about 1–2 min with the Maven cache) before T4. The job
  timeout stays at 15 minutes.

### Assumptions (non-interactive run)

- A-V-1: "the same check as decision 2" was read as the same test class, `ManifestPolicyTest`, in one run per
  input, not a new class. A separate class would have no report in job 1, which runs no render, and T11 would fail.
- A-V-2: the release-relevant rules apply to the release render only. The CI render is held to the secret rules, as
  decision 2 asks for "`deploy/k8s/release` and `deploy/k8s/ci`".
- A-V-3: the D10 value was made up to look like a real password (15 characters, mixed case, digits). No lab or CI
  secret was used.

### Lab state

No cluster object was touched. `kube.sh` was used for `kustomize` only, with no API call. No image was built. The
Testcontainers containers of `verify` are gone (`docker ps` shows only the containers that were there before), and
no process of this session is left.

## Summary of pipeline 006: every red/green row and every P-17 finding

Status after developer checks 17–20 and the fixes after validation (session 12).

**Red → green** (full rows in `evidence/red-green-runs.md`; after-runs in `evidence/deploy-after-runs.md`):

| Test | Red (without the change) | Green |
|---|---|---|
| T1 `JavaRuntimeTest` | major 65 (Java 21) | 69 (Java 25) |
| T2 `image-check.sh` | today's image: Java 21.0.13, uid 185, no JVM options, no `/app`, unpinned bases; two planted images | `:006`: all checks |
| T3 `scan.sh` | jar 17 and image 44 fixable HIGH/CRITICAL | jar 0, `:006` 0; SBOM; baseline marks NEW (wave 6) |
| T4 `ManifestPolicyTest` | `today.yaml`: 10 of 11 rules (the Secret rule on a plant, W4-F1). Session 12: D10 and a `replicas: 2` patch both passed today's T4 | base + release 11/11. Session 12: 12/12 on the raw files and on the release render (with the CI render for the secret rules); D10, an `.env` secret key and the patch each red first |
| T5 kubeconform | planted `replica: 1` | 28/28 valid |
| T6 `ProbesIT` | port refuses (`-1`); readiness 503 with `db` (A green against today's properties, W3-F1) | 2/2 |
| T7 `RouteCatalogTest` + validator | planted `delayy` passes today's build | caught; every file schema-valid (W1-F2 closed) |
| T8 `KillIT` | intake 39.3 / 39.7 s after a kill | 1.7 / 2.3 s |
| T9 failure ITs | `Test failed` | assertion message with check, values and order |
| T10 `PartnerConfigurationReleaseTest` | the file does not exist | 2/2 |
| T11 `test-inventory.sh` | a deleted report; `-DskipITs` (17 ITs) | 35/35 |
| T12 `smoke.sh` | no release: `FAILED ready`; CI run 43964902986: `FAILED pick-list` (CI-1, check 17); a planted `known-hosts` mismatch | lab green three times (246 s, 241 s, 269 s); CI green in PR #1 and in `main` run 43975492281 (check 19) |
| T13 `PickListLoginDirectoryIT` | second pick list | once, in `processed` |
| T14 `SftpSubsystemHangIT` | check and upload held past 10 s | both bounded, delivered once |
| A1–A10 (lab) | L0–L7 | 10 pass; A6b not met (W5-F5, accepted); A2 16 s (W5-F3, accepted) |
| actionlint | the four actions on Node.js 20 (CI-2, check 18) | clean after CI-2 and after session 12 |
| Release (check 19) | — | `main` run 43975492281: `release` green; `ghcr.io/<owner>/order-integration@sha256:780d25d2…6550c2`, named by `release.yaml`, with the SBOM and both scan reports |
| Deployment faults D1–D12 (check 20) | — | 12 of 12 failed the gate, 11 for the reason planted; D10 only by accident (Q-1). Session 12: D10 caught by T4 locally; the run through the gate is the developer's |

**P-17 findings** (status after the developer checks):

| Finding | Status |
|---|---|
| W1-F1 compiler kept Java 21 classes | closed |
| W1-F2 validator vs placeholder | closed (check 2) |
| W1-F3 `scan.sh` not run whole | closed (check 1) |
| W2-F1 deadline proven at channel start only | accepted (check 5) |
| W2-F2 connect edge case ≤ 2 × limit | accepted (check 5) |
| W2-F3 UTF-8 from the JVM default | accepted (check 5) |
| W2-F4 `last_failure` without SFTP status | closed (check 4) |
| W3-F1 T6 red weaker than planned | recorded |
| W3-F2 harness 30 s kill | accepted (check 7) |
| W3-F3 static member, stop without restart | accepted (check 7); runbook line (wave 6) |
| W3-F4 `SftpException 4 failure` | accepted (check 7) |
| W3-F5 suppressed cleanup failures | accepted (check 7) |
| W4-F1 T4 Secret rule red by plant | recorded |
| W4-F2 CI truststore | accepted (check 11); runbook line (wave 6) |
| W4-F3 digests multi-arch | checked (check 8) |
| W4-F4 Burstable | decided (check 9); design §3.7 corrected (wave 6) |
| W4-F5 rollback deletes the stuck pod | decided (checks 10, 16); runbook (wave 6) |
| W4-F6 NetworkPolicy and probes | recorded; runbook line (wave 6) |
| W4-F7 two CI namespaces | recorded |
| W5-F1 TLS 1.1 refusal | **open**: no record shows the server's refusal (validation Q-5). To be run against the release on `trailhead-prod` with a TLS 1.1 client and recorded in the deployment check (validation decision 5) |
| W5-F2 fault image `-cp` | recorded |
| W5-F3 A2 16 s | accepted (check 15) |
| W5-F4 stuck pod after a crash loop | runbook (check 16, wave 6) |
| W5-F5 A6b no exit 3 | accepted (check 15) |
| W5-F6 CI two-namespace path not run | closed: failed in CI (CI-1, check 17), fixed, green in CI (check 19) |
| W5-F7 lab Pod Security label | recorded |
| W6-F1 action SHAs not resolved by a tool | closed (check 18; matched on the release pages) |
| W6-F2 kind via `go install` | recorded (deviation) |
| W6-F3 `grep` fallback not run | recorded |
| W6-F4 stale local reports | recorded |
| W6-F5 plan's actionlint flag | recorded |
| W6-F6 pull secret, amd64-only image | decided (validation decision 4): the `linux/amd64` release is deployed by digest as released, and becomes a multi-architecture image in a later change if it does not run. **Open**: the developer's `trailhead-prod` deployment |
| W6-F7 kustomize on the runner | recorded |
| W6-F8 T11 extra rules | recorded |
| W6-F9 CI `KUBE` with `--context` | recorded |
| CI-1 `known_hosts` named `sftp` | closed (checks 17, 19) |
| CI-2 actions on Node.js 20 | closed (checks 18, 19) |
| CI-F1 `setting` not run on the CI overlay | closed: the known-hosts check ran in CI (check 19) |
| CI-F2 `known-hosts` failure output | recorded |
| D8 the gate's definition guarded by review only (check 20, validation Q-3) | recorded (review decision 4) |
| D10 / Q-1 a secret value in a manifest passes the gate (check 20) | fixed in session 12 (validation decision 2), red then green locally. **Open**: the developer's gate run with D10 again |
| Q-2 T4 reads the YAML as written | fixed in session 12 for the release render (validation decision 3) |
| VF-1 CI render held to the secret rules only | recorded |
| VF-2 wide `KEY` pattern | recorded |
| VF-3 job 3 compiles before T4 | recorded |
