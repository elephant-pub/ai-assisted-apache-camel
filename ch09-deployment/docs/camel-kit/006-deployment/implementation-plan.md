---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-16T13:15:25Z"
  by: camel-plan
  from: design-spec.md
---
# Trailhead Order Integration — Deployment (006) Implementation Plan

> **For agentic workers:** Use camel-execute to implement this plan task-by-task, **one wave per session** (design
> §3.19, review decision 7). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The integration is released as one image by digest plus plain Kubernetes manifests, produced by a CI gate
that runs `./mvnw verify`, scans, validates, deploys to kind and smoke-tests; the deployment keeps NFR-006/NFR-007
(one instance, restart on every exit) and FR-030 (probes that never take the pod away during an outside outage), and
the Warehouse SFTP delivery is fixed for an unconfirmed login directory (D16) and a server that never starts its SFTP
subsystem (D17). Each change has a test that was seen failing without it.

**Architecture:** The Spring Boot jar is unchanged in shape. A new Dockerfile wraps it on Temurin 25 (pinned digest,
root-owned files, uid 10001, two JVM options). `application.properties` gains a health-only management port 9090,
pinned probe groups and Camel's shutdown timeout; the intake gets Kafka static membership. `WarehouseSftp` resolves
folders like Camel's sftp producer, runs every SFTP call under one deadline and — because no 4.22.2 sftp option
bounds the channel start (P-3) — also does the upload, keeping C-014. Manifests are a kustomize base (StatefulSet of
1) with a `release` overlay (image by digest) and a `ci` overlay (kind with the lab's systems). `.github/workflows/gate.yml`
runs five jobs; only the last publishes, and only after the four gate jobs pass.

**Tech Stack:** Apache Camel 4.22.2 (unchanged), Spring Boot 4.1.1, **Java 25 (Eclipse Temurin)**, Camel Spring Boot
BOM 4.22.2, JSch 2.28.6 (via `camel-ftp-starter`), Kafka client (static membership), Spring Boot Actuator (health
only), JUnit Jupiter, Testcontainers 2.x, Citrus 5.0.1 (existing harness), `camel-catalog` 4.22.2 (test),
`camel-yaml-dsl-validator-maven-plugin` 4.22.2, Docker, kind (Kubernetes 1.34), kustomize (`kubectl kustomize`),
Trivy 0.76.1 / kubeconform 0.8.0 / actionlint 1.7.12 (digests of `deploy/local/tool.sh`), GitHub Actions.

**Design Spec:** `docs/camel-kit/006-deployment/design-spec.md` (approved 2026-11-16 with the eight changes of
`design-review.md`; `camel-kit doc check`: not stale, `generated.by: camel-brainstorm`)

---

## Planning Decisions and Assumptions (non-interactive run — recommended option taken)

| # | Decision | Reason |
|---|---|---|
| P-1 | **Standalone mode.** The plan is written and the run stops; `camel-execute` is not auto-invoked. | `/camel-plan 006-deployment` was invoked directly; `design-spec.md` exists, is not stale and has `camel-brainstorm` provenance. |
| P-2 | **Six waves, one execute session each** (user direction; design §3.19). Every wave opens with a *Wave entry* block (what it needs from earlier waves, how to check it) and ends with a *Wave close* task that runs the wave's gate and appends a `## Wave N handoff` section to `docs/camel-kit/006-deployment/execution-report.md` (files changed, red/green rows added, open findings, lab state, what the next wave needs). Waves 1–4 and 6 end with `./mvnw -B verify` green; wave 5 changes no Java and ends with its own checks (smoke in the lab, after-runs recorded). | Review decision 7. A fresh session reads only this plan and the execution report. |
| P-3 | **A-9 resolved at planning time: no sftp option bounds the channel start, so the upload moves into `WarehouseSftp` (Task 14), keeping C-014.** Checked against Camel 4.22.2: the catalog (`camel_catalog_component_doc(component="sftp", includeOptions="all")`) lists `connectTimeout` (default 10000, "connect timeout … used by both FTPClient and JSCH"), `soTimeout` ("set as timeout on the JSCH Session"), `timeout` ("used only by FTPClient"), `serverAliveInterval`/`serverAliveCountMax`. The component's code (`camel-ftp-4.22.2.jar`, `SftpOperations.tryConnect`, read with `javap`) calls `Session.connect(connectTimeout)`, `openChannel("sftp")`, then `ChannelSftp.connect(connectTimeout)`; `soTimeout` becomes `Session.setTimeout` (skipped when `serverAliveInterval` is set). In JSch 2.28.6 `ChannelSftp.start()` sends the subsystem request, then `SSH_FXP_INIT`, then reads the reply through `header()` → `fill()` → `PipedInputStream.read` with **no deadline**; `connectTimeout` is not consulted there. `WarehouseSftp.connect` already calls the same `newChannel.connect(timeoutMs)` with `setTimeout(timeoutMs)`, and the lab saw it wait >136 s exactly there (F-1 thread dump). Keep-alives only end a transport that stops answering, not a live transport whose subsystem never starts, and `connectTimeout × (count+1)` would not be "within `warehouse.sftp.timeout-ms`" anyway. So no option covers it. Task 12 still records the upload case red and Task 13 re-runs it before the move. If it is unexpectedly green there, Task 14 is not done and the finding is recorded. | User direction for A-9; design §3.18. The user's direction stands in for the design's "presented to the integration architect before it is built" (A-9). Reported as an assumption. |
| P-4 | **The moved upload keeps C-014 and the route's guards:** resolve the inbound folder with the D16 rule; `stat` the final name and fail if it exists (the old `fileExist: Fail`); write `<file>.tmp` (overwriting a leftover `.tmp`, as Camel's temp-file handling does); rename `.tmp` → final; no `mkdir` (the old `autoCreate: false`); UTF-8 bytes. JSch's `rename` sends `posix-rename@openssh.com` when the server offers it (JSch 2.28.6 `sendRENAME`), which overwrites. Camel's producer calls the same `ChannelSftp.rename`, so this window is unchanged from today, and the stat before it is the guard (as with `fileExist: Fail`). The route step keeps id `to-warehouse-sftp` and becomes a `bean` call. | Design A-9 ("never overwriting a final file"), C-014, §3.17 last bullet. |
| P-5 | **Red before green** (design §3.14, user direction): each test task comes before its change task and records its red run (test, code/image state, result, assertion, message, duration). The change task records the green run. Rows go to `docs/camel-kit/006-deployment/evidence/red-green-runs.md`. Cluster behaviour goes to `evidence/deploy-after-runs.md` (A1–A10, raw output under `evidence/runs-after/`). Build times go to `evidence/build-times.md`. A new class that the test needs gets its final signatures with bodies `throw new UnsupportedOperationException("design 006: not implemented")`, so the red is an assertion and not a compile error (005 P-5). | Design §3.14. |
| P-6 | **Per-IT command** (005 P-11, still valid): `./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=<Class>`. The gate of a wave is always the full `./mvnw -B verify`. | Avoids the spec matrix in single-IT runs. |
| P-7 | **Spec-test static membership id is one fixed value, `spec-test`, for every integration process of a build** (in `application-spec-test.properties`). The harness runs one process at a time, so a new process takes over the previous static member at once. Distinct ids per process would make every next process wait for the previous member's 45 s session (a static member does not leave on a graceful stop, design §3.10). | Design §3.10 "a per-run fixed value in the spec-test profile". |
| P-8 | **Management port in tests:** `IntegrationProcess` writes `management.server.port=<free port>` into every run's settings and exposes `managementPort()`. No IT binds 9090. | Constitution of 005's harness: fixed ports are never bound (8080, 8081, 5432 excluded); two builds on one machine must not collide. |
| P-9 | **D17 failure reason without message text:** a new `WarehouseSftpTimeoutException` (in `J/`) carries the step (`session-connect`, `channel-open`, `channel-start`, `stat`, `upload`, `rename`) as a constant code. `FailureKinds.code` returns it, so `last_failure` = `WarehouseSftpTimeoutException channel-start`. The exception message is `SFTP <step> took longer than <n> ms` (constants only). | Design D17 names the step; 005 rule "class names and codes only, never a message" (NFR-011, C-021). |
| P-10 | **Release Partner Configuration (OQ-095):** the schema requires `minItems: 1` and no partner has been confirmed. `deploy/k8s/base/partner-configuration.json` therefore holds the `summit-outfitters` entry of `docs/messages/examples/partner-configuration/summit-outfitters.json` as a stand-in, with the runbook line "replace with the partners the partner manager confirms before the first real deployment". With no key in the production Secret Store it is UC-007 A4 (not registered, logged). | OQ-095's taken answer; a valid file is required by T10 and by the start. |
| P-11 | **Release overlay image:** `deploy/k8s/release/kustomization.yaml` names `ghcr.io/OWNER/order-integration` with digest `sha256:` + 64 zeros. CI job 5 sets owner and digest with `kustomize edit set image` before rendering `release.yaml`, then fails if the rendered file still contains the zero digest or `OWNER`. T4 checks the digest form. | D14: "never a tag"; no real digest exists before the first push. |
| P-12 | **Fault image (CI and lab only)** starts the release image's jar through `PropertiesLauncher` with the fault-hook jar on `loader.path` and `--spring.profiles.active=default,fault-injection`. Naming `default` explicitly keeps `application-default.properties` (every setting from the environment). The release manifests never set a profile (T4). | `FaultHook` is `@Profile("fault-injection")`; Spring loads `application-default` only when no profile, or `default` itself, is active. |
| P-13 | **`HOSTNAME` becomes a required variable without a profile** (`peakmart.group-instance-id=${HOSTNAME}`); Kubernetes and Docker set it. `src/test/scripts/required-variables-runs.sh` goes from 26 to 27 names. | D10; `RequiredVariablesCheck` reads every `${NAME}` of the config files. |
| P-14 | **Kustomize locally** runs as `deploy/local/kube.sh kustomize <dir>` (kubectl's built-in kustomize; the wrapper only adds the lab context, no API call). `kubectl` is denied, and kustomize/kubeconform/actionlint run only through `kube.sh` and `deploy/local/tool.sh`. Waves 4–6 need `KIND_KUBECONFIG` set by the developer. If it is unset, the step returns `NEEDS_CONTEXT` and the wave stops. | `.claude/settings.json` deny rules; design §3.16. |
| P-15 | **No downstream artifact exists** (`execution-report.md`, `validation-report.md`), so the staleness cascade after this plan is a no-op. `.camel-kit/project-graph.json` does not exist, so no graph guide is loaded. Guides come from the active `camel-implement` and `camel-test` manifests, plus `shared/camel-security-checklist.md` (camel-validate manifest). | `camel-plan` rules. |
| P-16 | **No new flow, so no per-flow `.camel.it.yaml` task.** The testing template is used for its role only (tests after the artifacts they need, with `tests`/`testData` metadata). The tests are the design's T1–T14, as JUnit/failsafe classes and CI scripts. | Design §3.14 fixes test names, kinds and harness (same as 004 P-2 and 005 P-3). |
| P-17 | A test that exposes a defect beyond its own change is **not** weakened. The task records the defect for the execution report, and `src/main/` changes only where this plan's tasks name them. A remaining fixable HIGH/CRITICAL that needs a minor or major upgrade becomes `NEEDS_USER_CONFIRMATION` (a `.trivyignore.yaml` entry is a reviewed change, §3.3). It is never added by an agent. | Design §3.3, §3.14. |

**Path resolution** (`camel-implement/guides/orchestrator.md`, runtime `spring-boot`, Target Module = project root, so
`[MODULE_DIR]` is empty): routes `src/main/resources/camel/`, properties `src/main/resources/`, Java
`src/main/java/com/trailheadgear/orderintegration/` (= `J/`), tests `src/test/java/com/trailheadgear/orderintegration/`
(= `TJ/`; sub-packages `failure/`, `fault/`, `spec/`, `spec/support/`), test resources `src/test/resources/`. Deployment
files under `deploy/`, CI under `.github/workflows/`. The **Files** lists and the metadata always give full paths.

**Common Camel MCP binding (run once per task before any detail call):**
`camel_catalog_components(limit=0, runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2")`.
The returned `camelVersion` must be `4.22.2` (planning result: `4.22.2`, MATCHED). Below, "`B`" means
`runtime="spring-boot", platformBom="org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2"`. Planning
re-checks (MATCHED): `camel_catalog_component_doc(component="kafka", includeOptions="all", optionsFilter="groupInstanceId", B)`
→ consumer option `groupInstanceId`, string; `camel_catalog_component_doc(component="sftp", includeOptions="all", B)` →
the options of P-3. Local Maven repository holds `camel-catalog` 4.22.2 and `camel-yaml-dsl-validator` 4.22.2; the
plugin `camel-yaml-dsl-validator-maven-plugin` 4.22.2 is resolved by Task 5.

**Hard constraints for every task (design Not Doing, §3.16, §4):**
- No monitoring/metrics/alerts, no second instance/HPA/PDB/leader election, no Helm/operator/Knative/buildpack, no
  Ingress/cert-manager/egress policy, no Kafka/catalog TLS, no image signing, no multi-arch image, no load test, no
  UC-005/006/008.
- The agent never runs `kubectl`, `helm`, `docker run|exec|cp|tag|push|login`, `kind get kubeconfig`, `kind export`,
  `kind delete`, `git push` or `gh`, never edits `.claude/**`, and never reads `~/.kube` or other credential folders.
  Cluster access is `deploy/local/kube.sh` (only `kind-trailhead-lab`). Tools run only through `deploy/local/tool.sh`.
  Docker inside the project's own scripts (`deploy/ci/*.sh`, `deploy/local/**`) is allowed (§3.16). Everything lab-side
  uses the cluster `trailhead-lab`, never `trailhead-prod`.
- No secret in the image, the repository, the manifests or the release. Secrets are created by a person, or randomly
  per CI run by the CI script. Every image reference in manifests and Dockerfiles carries `@sha256:`, except the
  locally built integration image in the `ci`/lab overlays. No new production dependency (§4).
- No message text in any new log line or DB column (NFR-011, C-021).
- A fault applied for a red run is one exact, reversible text edit, undone by reversing that edit (never
  `git checkout` of a file with uncommitted pipeline changes). The task's verify step proves it is gone.
- Commits: none by the agent unless the developer asks. Waves leave the working tree for the next session as is.

---

```yaml plan-metadata
tasks:
  - id: 1
    title: Wave 1 Entry — Preflight and Build Time Before
    files:
      creates:
        - docs/camel-kit/006-deployment/evidence/build-times.md
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      reads:
        - pom.xml
    provides:
      testData:
        - build-times-before
    consumes: {}
    dependsOn: []
  - id: 2
    title: T1 JavaRuntimeTest (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/JavaRuntimeTest.java
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/JavaRuntimeTest.java
    provides:
      testData:
        - red-T1
    consumes: {}
    dependsOn:
      - 1
  - id: 3
    title: Java 25 and Jib Profile Removed (D1 build part)
    files:
      modifies:
        - pom.xml
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      properties:
        - java.version
    consumes:
      testData:
        - red-T1
    dependsOn:
      - 2
  - id: 4
    title: T7 Red — Planted Unknown Option Passes Today's Build
    files:
      reads:
        - src/main/resources/camel/pick-list-delivery.camel.yaml
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      testData:
        - red-T7
    consumes: {}
    dependsOn:
      - 3
  - id: 5
    title: T7 RouteCatalogTest and YAML DSL Validator Plugin (D11)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/RouteCatalogTest.java
      modifies:
        - pom.xml
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/RouteCatalogTest.java
    provides:
      routeContracts:
        - route-catalog-validation
    consumes:
      testData:
        - red-T7
    dependsOn:
      - 4
  - id: 6
    title: T3 Scan Script and Ignore File (red on today's jar)
    files:
      creates:
        - deploy/ci/scan.sh
        - .trivyignore.yaml
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      reads:
        - deploy/local/tool.sh
        - deploy/local/lab/scan-summary.sh
    provides:
      routeContracts:
        - scan-gate
      testData:
        - red-T3-jar
    consumes: {}
    dependsOn:
      - 3
  - id: 7
    title: D2 Managed-Version Patch Upgrades (T3 jar green)
    files:
      modifies:
        - pom.xml
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      properties:
        - tomcat.version
    consumes:
      routeContracts:
        - scan-gate
      testData:
        - red-T3-jar
    dependsOn:
      - 6
  - id: 8
    title: Wave 1 Close — verify and Handoff
    files:
      modifies:
        - docs/camel-kit/006-deployment/execution-report.md
    provides:
      testData:
        - wave-1-handoff
    consumes:
      routeContracts:
        - route-catalog-validation
        - scan-gate
    dependsOn:
      - 5
      - 7
  - id: 9
    title: Wave 2 Entry — Test SFTP Server Support
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/TestSftpServer.java
      reads:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/WarehouseInbox.java
        - src/test/resources/spec-test/spec-test.properties
    provides:
      externalServices:
        - test-sftp-server
    consumes:
      testData:
        - wave-1-handoff
    dependsOn:
      - 8
  - id: 10
    title: T13 PickListLoginDirectoryIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/PickListLoginDirectoryIT.java
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/PickListLoginDirectoryIT.java
    provides:
      testData:
        - red-T13
    consumes:
      externalServices:
        - test-sftp-server
    dependsOn:
      - 9
  - id: 11
    title: D16 Folder Resolution in WarehouseSftp (T13 green)
    files:
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java
        - src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      beans:
        - warehouseSftp.remoteFolder
    consumes:
      testData:
        - red-T13
    dependsOn:
      - 10
  - id: 12
    title: T14 SftpSubsystemHangIT (red, both cases)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/SftpSubsystemHangIT.java
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/SftpSubsystemHangIT.java
    provides:
      testData:
        - red-T14
    consumes:
      externalServices:
        - test-sftp-server
    dependsOn:
      - 11
  - id: 13
    title: D17 Deadline on Every WarehouseSftp Call (T14 check case green)
    files:
      creates:
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftpTimeoutException.java
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java
        - src/main/java/com/trailheadgear/orderintegration/FailureKinds.java
        - src/test/java/com/trailheadgear/orderintegration/FailureKindsTest.java
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      beans:
        - warehouseSftp.deadline
    consumes:
      testData:
        - red-T14
    dependsOn:
      - 12
  - id: 14
    title: A-9 Upload Moved into WarehouseSftp (T14 upload case green)
    files:
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java
        - src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java
        - src/main/resources/camel/pick-list-delivery.camel.yaml
        - src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      routes:
        - pick-list-delivery
      beans:
        - warehouseSftp.upload
    consumes:
      beans:
        - warehouseSftp.deadline
        - warehouseSftp.remoteFolder
    dependsOn:
      - 13
  - id: 15
    title: Wave 2 Close — verify and Handoff
    files:
      modifies:
        - docs/camel-kit/006-deployment/execution-report.md
    provides:
      testData:
        - wave-2-handoff
    consumes:
      routes:
        - pick-list-delivery
    dependsOn:
      - 14
  - id: 16
    title: Wave 3 Entry — Harness Management Port and Build Time Mid
    files:
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java
        - docs/camel-kit/006-deployment/evidence/build-times.md
    provides:
      routeContracts:
        - harness-management-port
    consumes:
      testData:
        - wave-2-handoff
    dependsOn:
      - 15
  - id: 17
    title: T6 ProbesIT (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/ProbesIT.java
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/ProbesIT.java
    provides:
      testData:
        - red-T6
    consumes:
      routeContracts:
        - harness-management-port
    dependsOn:
      - 16
  - id: 18
    title: D3 Health Port and Probe Groups, D5 Shutdown Settings (T6 green)
    files:
      modifies:
        - src/main/resources/application.properties
        - config/application-dev.properties
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      properties:
        - management.server.port
        - management.endpoint.health.group.readiness.include
        - management.endpoint.health.group.liveness.include
        - server.shutdown
        - camel.main.shutdown-timeout
    consumes:
      testData:
        - red-T6
    dependsOn:
      - 17
  - id: 19
    title: T8 KillIT Intake-Resume Assertion (red)
    files:
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      tests:
        - src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java
    provides:
      testData:
        - red-T8
    consumes: {}
    dependsOn:
      - 18
  - id: 20
    title: D10 Kafka Static Membership (T8 green)
    files:
      modifies:
        - src/main/resources/camel/peakmart-order-intake.camel.yaml
        - src/main/resources/application-default.properties
        - config/application-dev.properties
        - src/test/resources/spec-test/application-spec-test.properties
        - src/test/scripts/required-variables-runs.sh
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      properties:
        - peakmart.group-instance-id
      routes:
        - peakmart-order-intake
    consumes:
      testData:
        - red-T8
    dependsOn:
      - 19
  - id: 21
    title: T9 Failure ITs Report the Failed Assertion (D13)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/failure/Checks.java
      modifies:
        - src/test/java/com/trailheadgear/orderintegration/failure/ConnectionSettingsIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/DatabaseFrozenIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/DeliveryFailureIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/EndStateQueriesIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/NotificationTextIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/UnexpectedErrorIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/UnstorableOrderIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/WaitingSelectIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/PickListLoginDirectoryIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/SftpSubsystemHangIT.java
        - src/test/java/com/trailheadgear/orderintegration/failure/ProbesIT.java
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      routeContracts:
        - failure-it-assertion-messages
    consumes: {}
    dependsOn:
      - 20
  - id: 22
    title: T10 PartnerConfigurationReleaseTest (red)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/PartnerConfigurationReleaseTest.java
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      reads:
        - src/main/java/com/trailheadgear/orderintegration/PartnerRegistration.java
      tests:
        - src/test/java/com/trailheadgear/orderintegration/PartnerConfigurationReleaseTest.java
    provides:
      testData:
        - red-T10
    consumes: {}
    dependsOn:
      - 21
  - id: 23
    title: Release Partner Configuration File (T10 green)
    files:
      creates:
        - deploy/k8s/base/partner-configuration.json
      modifies:
        - src/main/java/com/trailheadgear/orderintegration/PartnerRegistration.java
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      testData:
        - release-partner-configuration
    consumes:
      testData:
        - red-T10
    dependsOn:
      - 22
  - id: 24
    title: Wave 3 Close — verify, Build Time After D10, Handoff
    files:
      modifies:
        - docs/camel-kit/006-deployment/evidence/build-times.md
        - docs/camel-kit/006-deployment/execution-report.md
    provides:
      testData:
        - wave-3-handoff
        - build-times-after
    consumes:
      properties:
        - peakmart.group-instance-id
        - management.server.port
    dependsOn:
      - 23
  - id: 25
    title: Wave 4 Entry — T2 Image Check Script (red on today's image)
    files:
      creates:
        - deploy/ci/image-check.sh
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      reads:
        - deploy/local/lab/inspect-image.sh
    provides:
      routeContracts:
        - image-check
      testData:
        - red-T2
    consumes:
      testData:
        - wave-3-handoff
    dependsOn:
      - 24
  - id: 26
    title: D1 Dockerfile (T2 green)
    files:
      modifies:
        - src/main/docker/Dockerfile
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      externalServices:
        - release-image
    consumes:
      routeContracts:
        - image-check
      testData:
        - red-T2
    dependsOn:
      - 25
  - id: 27
    title: T3 Image Scan and SBOM (red on today's image, green on the new one)
    files:
      modifies:
        - deploy/ci/scan.sh
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      testData:
        - scan-image-green
    consumes:
      externalServices:
        - release-image
      routeContracts:
        - scan-gate
    dependsOn:
      - 26
  - id: 28
    title: T4 ManifestPolicyTest (red against today.yaml)
    files:
      creates:
        - src/test/java/com/trailheadgear/orderintegration/ManifestPolicyTest.java
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      reads:
        - deploy/local/lab/k8s/today.yaml
      tests:
        - src/test/java/com/trailheadgear/orderintegration/ManifestPolicyTest.java
    provides:
      testData:
        - red-T4
    consumes:
      properties:
        - management.server.port
        - camel.main.shutdown-timeout
    dependsOn:
      - 27
  - id: 29
    title: Base and Release Manifests (D4, D5, D6, D8, D9; T4 green)
    files:
      creates:
        - deploy/k8s/base/kustomization.yaml
        - deploy/k8s/base/statefulset.yaml
        - deploy/k8s/base/services.yaml
        - deploy/k8s/base/serviceaccount.yaml
        - deploy/k8s/base/networkpolicy.yaml
        - deploy/k8s/release/kustomization.yaml
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
      reads:
        - deploy/k8s/base/partner-configuration.json
    provides:
      routeContracts:
        - release-manifests
    consumes:
      testData:
        - red-T4
        - release-partner-configuration
    dependsOn:
      - 28
  - id: 30
    title: CI Overlay and T5 kubeconform
    files:
      creates:
        - deploy/k8s/ci/kustomization.yaml
        - deploy/k8s/ci/namespace.yaml
        - deploy/k8s/ci/systems.yaml
        - deploy/k8s/ci/settings.env
      modifies:
        - deploy/local/lab/sftp.Dockerfile
      reads:
        - deploy/local/lab/k8s/systems.yaml
    provides:
      routeContracts:
        - ci-manifests
    consumes:
      routeContracts:
        - release-manifests
    dependsOn:
      - 29
  - id: 31
    title: Wave 4 Close — verify, Image, Scan, kubeconform, Handoff
    files:
      modifies:
        - docs/camel-kit/006-deployment/execution-report.md
    provides:
      testData:
        - wave-4-handoff
    consumes:
      routeContracts:
        - ci-manifests
        - release-manifests
      externalServices:
        - release-image
    dependsOn:
      - 30
  - id: 32
    title: Wave 5 Entry — Fault Image and T12 Smoke Script (red, then green in the lab)
    files:
      creates:
        - deploy/ci/fault.Dockerfile
        - deploy/ci/smoke.sh
        - deploy/local/lab/k8s/release/kustomization.yaml
      modifies:
        - deploy/local/lab/lab.sh
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      routeContracts:
        - smoke-test
    consumes:
      testData:
        - wave-4-handoff
      routeContracts:
        - ci-manifests
    dependsOn:
      - 31
  - id: 33
    title: After-Runs A1, A2, A9, A10 — Image, Rollout, Restricted, One Pod
    files:
      creates:
        - docs/camel-kit/006-deployment/evidence/deploy-after-runs.md
      modifies:
        - deploy/local/lab/runs.sh
    provides:
      testData:
        - after-runs-1
    consumes:
      routeContracts:
        - smoke-test
    dependsOn:
      - 32
  - id: 34
    title: After-Runs A3, A4, A5, A6, A6b — Stop, Bad Secret, Outages, Memory
    files:
      modifies:
        - docs/camel-kit/006-deployment/evidence/deploy-after-runs.md
        - deploy/local/lab/runs.sh
    provides:
      testData:
        - after-runs-2
    consumes:
      testData:
        - after-runs-1
    dependsOn:
      - 33
  - id: 35
    title: After-Runs A7, A8 and Wave 5 Close
    files:
      modifies:
        - docs/camel-kit/006-deployment/evidence/deploy-after-runs.md
        - deploy/local/lab/partner-change.sh
        - docs/camel-kit/006-deployment/execution-report.md
    provides:
      testData:
        - wave-5-handoff
    consumes:
      testData:
        - after-runs-2
    dependsOn:
      - 34
  - id: 36
    title: Wave 6 Entry — T11 Test Inventory Script (red, then green)
    files:
      creates:
        - deploy/ci/test-inventory.sh
        - deploy/ci/test-summary.sh
      modifies:
        - docs/camel-kit/006-deployment/evidence/red-green-runs.md
    provides:
      routeContracts:
        - test-inventory
    consumes:
      testData:
        - wave-5-handoff
    dependsOn:
      - 35
  - id: 37
    title: D14 CI Gate Workflow
    files:
      creates:
        - .github/workflows/gate.yml
    provides:
      routeContracts:
        - ci-gate
    consumes:
      routeContracts:
        - test-inventory
        - smoke-test
        - image-check
        - scan-gate
        - ci-manifests
        - release-manifests
    dependsOn:
      - 36
  - id: 38
    title: Runbook, Open Questions and Wave 6 Close
    files:
      creates:
        - deploy/README.md
      modifies:
        - docs/open_questions.md
        - README.md
        - docs/camel-kit/006-deployment/execution-report.md
    provides:
      testData:
        - wave-6-handoff
    consumes:
      routeContracts:
        - ci-gate
    dependsOn:
      - 37
```

---

## Wave Overview

| Wave | Tasks | Java change | Gate at the end | Leaves for later waves |
|---|---|---|---|---|
| 1 Build: Java 25, catalog validation, dependency fixes | 1–8 | yes (`pom.xml`, 2 unit tests) | `./mvnw -B verify` | `deploy/ci/scan.sh`, `.trivyignore.yaml`, evidence files, build time before |
| 2 Warehouse SFTP: D16, D17, A-9 | 9–15 | yes | `./mvnw -B verify` | `TestSftpServer`; route `pick-list-delivery` without an sftp endpoint |
| 3 In-process settings: D3, D5, D10, D13, T10 | 16–24 | yes | `./mvnw -B verify` | management port 9090, probe groups, `peakmart.group-instance-id`, `deploy/k8s/base/partner-configuration.json`, build time after D10 |
| 4 Image and manifests: D1, D2 image, D4–D9 | 25–31 | yes (`ManifestPolicyTest`) | `./mvnw -B verify` + T2 + T3 + T5 | image `trailhead/order-integration:006`, `deploy/k8s/{base,release,ci}` |
| 5 Lab: smoke and after-runs | 32–35 | no | T12 smoke green in `trailhead-lab`; A1–A10 recorded | `deploy/ci/smoke.sh`, `deploy/ci/fault.Dockerfile`, lab overlay |
| 6 CI gate, runbook, docs | 36–38 | no Java (scripts, YAML, docs) | `./mvnw -B verify` + T11 + actionlint + T5 | the developer's first CI run (§7) |

`camel-kit plan analyze` orders the 38 tasks as a strict sequence: they share the evidence files, so it finds no parallel
tasks. Its numbered "waves" are those 38 levels. The **session waves** of this plan are the six above, and execute
runs tasks in number order inside each one.

Every wave's last task appends `## Wave N handoff` to `execution-report.md` with: tasks done; files created/modified;
red-green rows added (by test); findings (P-17); build/scan/lab state; **what the next wave needs** (the entry check of
the next wave below, with its result in this session).

---

## Wave 1 — Build: Java 25, Catalog Validation, Dependency Fixes

**Wave entry (needs):** the code at `a1b55cf` (design commit), clean working tree for `src/` and `pom.xml`, Docker
running, a JDK 25 for Maven (`./mvnw -v` prints `Java version: 25`). If no JDK 25 is available, Task 1 returns
`NEEDS_CONTEXT` ("install Temurin 25 and point `JAVA_HOME` at it") and the wave stops. **Leaves:** Java 25 build,
`RouteCatalogTest`, YAML validator, `deploy/ci/scan.sh`, `.trivyignore.yaml`, patched versions, evidence files.

### Task 1: Wave 1 Entry — Preflight and Build Time Before

**Agent:** implementation-engineer

**Files:**
- Create: `docs/camel-kit/006-deployment/evidence/build-times.md`
- Create: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md` — path table

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.15 "Build time (005 decision 6)"; §3.14 "Red before green"; P-2, P-5

- [ ] **Step 1:** `git status --porcelain -- src pom.xml config deploy` prints nothing. `./mvnw -v` shows Java 25 (else `NEEDS_CONTEXT`, see wave entry). `docker info` exits 0.
- [ ] **Step 2:** Run `/usr/bin/time -p ./mvnw -B verify` on the unchanged code (`java.version` 21 compiles on JDK 25). Record exit code and `real` seconds. Record each IT's `time` from `target/failsafe-reports/TEST-*.xml`, and `KillIT` and `UnexpectedErrorIT` separately.
- [ ] **Step 3:** Write `build-times.md`: "Before (code `a1b55cf`)" table, per-IT times, empty headings "After D10 (Task 24)" and "Final (Task 38)". Create `red-green-runs.md` with header `| Test | Code/image state (fault) | Result | Assertion | Message | Duration |`.
- [ ] **Step 4:** Verify: `grep -q 'Before' docs/camel-kit/006-deployment/evidence/build-times.md && grep -q 'After D10' docs/camel-kit/006-deployment/evidence/build-times.md && grep -q '| Test |' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo BASELINE-OK` prints `BASELINE-OK`.

**Review:**
- [ ] Spec compliance: measured on unchanged code, whole `verify`, per-IT times.
- [ ] Code quality: numbers from the actual run; no source file changed.

---

### Task 2: T1 JavaRuntimeTest (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/JavaRuntimeTest.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 T1; §3.2 D1; C-003 (amended, review 3)

- [ ] **Step 1:** Surefire unit test: read the first 8 bytes of `target/classes/com/trailheadgear/orderintegration/CamelApplication.class` (resolved from the class's own `getProtectionDomain().getCodeSource()`), assert magic `0xCAFEBABE` and major version `69` (Java 25). Message: `compiled for class file major <actual>, expected 69 (Java 25, C-003)`.
- [ ] **Step 2:** Red: `./mvnw -q -DskipITs test -Dtest=JavaRuntimeTest` with `java.version` 21. Expect a failure with major 65. Append the row.
- [ ] **Step 3:** Verify: `test -f src/test/java/com/trailheadgear/orderintegration/JavaRuntimeTest.java && grep -q 'JavaRuntimeTest.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T1-RED-OK` prints `T1-RED-OK`.

**Review:**
- [ ] Spec compliance: checks the compiled classes, not the running JVM; red recorded with 65.
- [ ] Code quality: no hard-coded path outside `target/classes`; clear message.

---

### Task 3: Java 25 and Jib Profile Removed (D1 build part)

**Agent:** implementation-engineer

**Files:**
- Modify: `pom.xml`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/pom-spring-boot.md`
- `camel-implement/guides/maven-dependencies.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.2 D1 ("`pom.xml`: `java.version` 25. The Jib profile … is removed")

- [ ] **Step 1:** `<java.version>25</java.version>` with comment `Design 006 D1, C-003 amended (review 3)`. Remove the whole `<profile><id>jib</id>…</profile>`. Nothing else changes.
- [ ] **Step 2:** Green: `./mvnw -q -DskipITs test -Dtest=JavaRuntimeTest`, then `./mvnw -q -DskipITs verify` (all unit tests on Java 25). Append rows. A failure caused by Java 25 is a P-17 finding: fix it only if it is in build configuration, otherwise record it and stop with `NEEDS_CONTEXT`.
- [ ] **Step 3:** Verify: `grep -q '<java.version>25</java.version>' pom.xml && ! grep -q 'jib-maven-plugin' pom.xml && ./mvnw -q -DskipITs verify && echo T1-GREEN-OK` prints `T1-GREEN-OK`.

**Review:**
- [ ] Spec compliance: Java 25; one way to build the image (no Jib).
- [ ] Code quality: no unrelated POM change; versions still from the BOMs.

---

### Task 4: T7 Red — Planted Unknown Option Passes Today's Build

**Agent:** test-engineer

**Files:**
- Read (temporary fault, reverted): `src/main/resources/camel/pick-list-delivery.camel.yaml`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)` — confirm `delay` exists and `delayy` does not

**Design Spec Section:** §3.14 T7 ("a planted unknown option (`delayy`) passes today's build"); §3.11 D11

- [ ] **Step 1:** Fault: in `from-pick-list-outbox` parameters, rename `delay:` to `delayy:` (one edit).
- [ ] **Step 2:** Run `./mvnw -q -DskipITs verify`. Expect exit 0 (the red: nothing catches it). Append the row with the result `passes (not caught)`.
- [ ] **Step 3:** Revert the edit.
- [ ] **Step 4:** Verify: `! grep -q 'delayy' src/main/resources/camel/pick-list-delivery.camel.yaml && grep -q 'delayy' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T7-RED-OK` prints `T7-RED-OK`.

**Review:**
- [ ] Spec compliance: the exact planted option of §3.14; result recorded.
- [ ] Code quality: route byte-identical afterwards (`git diff --quiet -- src/main/resources/camel/`).

---

### Task 5: T7 RouteCatalogTest and YAML DSL Validator Plugin (D11)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/RouteCatalogTest.java`
- Modify: `pom.xml` (test dependency `org.apache.camel:camel-catalog`, BOM-managed or `4.22.2`; plugin `org.apache.camel:camel-yaml-dsl-validator-maven-plugin:4.22.2`)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/maven-dependencies.md`
- `camel-implement/guides/pom-spring-boot.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/route-validation.md`
- `camel-test/guides/test-generation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sql", B)`, `(component="kafka", B)`, `(component="http", B)`, `(component="smtp", B)`, `(component="sftp", B)`, `(component="platform-http", B)`, `(component="json-validator", B)`, `(component="direct", B)`, `(component="bean", B)`: the schemes the routes use, so the test's expected scheme list matches the catalog

**Design Spec Section:** §3.11 D11; §3.14 T7; §4 "New build plugin … new test dependency"

- [ ] **Step 1:** `pom.xml`: `camel-catalog` (scope test). If the Camel BOM manages it, no version. Otherwise `4.22.2` with a comment. Run `./mvnw -q dependency:tree -Dincludes=org.apache.camel:camel-catalog` and confirm `4.22.2`.
- [ ] **Step 2:** `pom.xml`: plugin `camel-yaml-dsl-validator-maven-plugin` version `4.22.2`, execution `validate-routes`, phase `process-classes`, goal `validate`, `failOnError=true`, `onlyCamelYamlExt=true`, comment `Design 006 D11`. Confirm the goal name and parameters with `./mvnw -q org.apache.camel:camel-yaml-dsl-validator-maven-plugin:4.22.2:help -Ddetail` (resolves the plugin at 4.22.2). If the parameters differ, use the plugin's own names and record the difference.
- [ ] **Step 3:** `RouteCatalogTest`: parse every `src/main/resources/camel/*.camel.yaml` with SnakeYAML. For each `from`/`to`/`toD`/`wireTap`/`enrich`/`pollEnrich` with `uri` plus `parameters`, build the endpoint URI (parameters as query, each value URL-encoded) and call `new DefaultCamelCatalog().validateEndpointProperties(uri, true)`. Fail on `unknownComponent`, `unknown`, `invalidBoolean`, `invalidInteger`, `invalidNumber`, `invalidEnum`, `required`. A value that contains `{{…}}` or `${…}` counts as valid (`placeholders = true`; also skip type errors for such values). Check `catalog.getCatalogVersion()` equals `4.22.2`. One parameterized case per file, and messages name the file, the step id, the option and the reason.
- [ ] **Step 4:** Plant `delayy` again (Task 4's edit). `./mvnw -q -DskipITs verify` must now fail, naming `delayy`. Record whether the validator, the test or both caught it. Revert.
- [ ] **Step 5:** Green: `./mvnw -q -DskipITs verify`; append the row.
- [ ] **Step 6:** Verify: `! grep -q 'delayy' src/main/resources/camel/pick-list-delivery.camel.yaml && grep -q 'camel-yaml-dsl-validator-maven-plugin' pom.xml && ./mvnw -q -DskipITs verify && grep -q 'RouteCatalogTest' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T7-OK` prints `T7-OK`.

**Review:**
- [ ] Spec compliance: every route file, catalog 4.22.2, unknown component/option/type caught; YAML schema validated in `verify`; `camel-report-maven-plugin` not used.
- [ ] Code quality: no network at test time; placeholders accepted; messages actionable.

---

### Task 6: T3 Scan Script and Ignore File (red on today's jar)

**Agent:** implementation-engineer

**Files:**
- Create: `deploy/ci/scan.sh` (executable)
- Create: `.trivyignore.yaml` (empty list, with a header comment stating the entry rule)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.3 D2; §3.14 T3; §3.15 job 2; review decision 8

- [ ] **Step 1:** `scan.sh jar <jar>` and `scan.sh image <tar>` (tar from `docker save`). Both run Trivy through `deploy/local/tool.sh trivy` (pinned digest), with `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile /repo/.trivyignore.yaml`. `jar` mode extracts the jar's `BOOT-INF/lib` into `target/scan/jar-rootfs/` and runs `trivy rootfs --offline-scan` on it. `image` mode runs `trivy image --input`. Each mode also writes a full JSON report (all severities, fixed and unfixed) to `target/scan/<mode>-report.json` and prints a table: id, package, installed, fixed, severity.
- [ ] **Step 2:** `scan.sh` fails (exit 1, `FAILED ignore: <id> expired <date>`) on an entry of `.trivyignore.yaml` whose `expired_at` has passed or lies more than 90 days ahead, or that lacks `statement` (reason and who accepted it). Option `--baseline <report.json>` marks each fixable HIGH/CRITICAL not in the baseline as `NEW`.
- [ ] **Step 3:** Red: `./mvnw -q -DskipTests package` and `deploy/ci/scan.sh jar target/order-integration-0.1.0.jar`. Expect exit 1 with the L0 findings (4 CRITICAL Tomcat 11.0.24; 13 HIGH Jackson, snappy-java, lz4-java). Record the counts and ids.
- [ ] **Step 4:** Verify: `test -x deploy/ci/scan.sh && test -f .trivyignore.yaml && ! deploy/ci/scan.sh jar target/order-integration-0.1.0.jar >/dev/null 2>&1 && grep -q 'scan.sh jar.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T3-RED-OK` prints `T3-RED-OK`.

**Review:**
- [ ] Spec compliance: threshold exactly fixable HIGH/CRITICAL; image and jar; expired-entry rule; new-since-baseline marking.
- [ ] Code quality: `set -euo pipefail`; Trivy only by digest; no network beyond Trivy's DB; nothing under `/repo` written by the container (read-only mount; output via stdout redirect).

---

### Task 7: D2 Managed-Version Patch Upgrades (T3 jar green)

**Agent:** implementation-engineer

**Files:**
- Modify: `pom.xml`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/maven-dependencies.md`
- `camel-implement/guides/pom-spring-boot.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.3 "Today's findings"; Constraints (C-001, C-002)

- [ ] **Step 1:** Check whether a Spring Boot 4.1.x later than 4.1.1 manages all fixed versions (`./mvnw -B -ntp versions:display-parent-updates -DallowMinorUpdates=false -DallowMajorUpdates=false`). If one does, take that parent and BOM patch in both places of `pom.xml`, update `project.springBootVersion` in `.camel-kit/config.properties`, and skip the properties whose versions it already manages. Otherwise go on with Step 2.
- [ ] **Step 2:** Maven properties, each with a comment naming the CVE ids from Task 6's table: `tomcat.version` 11.0.25; Jackson 2 → 2.21.7 and Jackson 3 → 3.1.7, using the property names the Spring Boot 4.1 BOM uses (read them from `./mvnw help:effective-pom`); snappy-java 1.1.10.9 and lz4-java 1.11.4 as `dependencyManagement` entries if no BOM property exists. Camel stays 4.22.2.
- [ ] **Step 3:** Green: `./mvnw -q -DskipTests package && deploy/ci/scan.sh jar target/order-integration-0.1.0.jar` exits 0. A remaining fixable finding that needs a minor/major upgrade is `NEEDS_USER_CONFIRMATION` (P-17); record it.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipTests package && deploy/ci/scan.sh jar target/order-integration-0.1.0.jar && grep -q 'tomcat' pom.xml && echo T3-JAR-GREEN-OK` prints `T3-JAR-GREEN-OK` (or the `NEEDS_USER_CONFIRMATION` record exists).

**Review:**
- [ ] Spec compliance: patch upgrades only, inside C-001/C-002; each with its CVE comment.
- [ ] Code quality: properties override only what is needed; no `.trivyignore.yaml` entry added by the agent.

---

### Task 8: Wave 1 Close — verify and Handoff

**Agent:** implementation-engineer

**Files:**
- Modify (create on first use, with the frontmatter of `camel-kit doc init --by camel-execute --from implementation-plan.md`): `docs/camel-kit/006-deployment/execution-report.md`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.19; P-2

- [ ] **Step 1:** `./mvnw -B verify` (Docker running). Every unit test, the validator, the five spec ITs, the failure ITs and the matrix pass.
- [ ] **Step 2:** Append `## Wave 1 handoff` (P-2 contents). "Next wave needs": Java 25 build green, `deploy/ci/scan.sh` present, no fixable HIGH/CRITICAL in the jar.
- [ ] **Step 3:** Verify: `./mvnw -B verify | tail -20 | grep -q 'BUILD SUCCESS' && grep -q '## Wave 1 handoff' docs/camel-kit/006-deployment/execution-report.md && echo WAVE-1-OK` prints `WAVE-1-OK`.

**Review:**
- [ ] Spec compliance: wave ends with a green `verify`; handoff states what wave 2 needs.
- [ ] Code quality: handoff lists real files and results, not intentions.

---

## Wave 2 — Warehouse SFTP: D16, D17, A-9

**Wave entry (needs):** `## Wave 1 handoff` present. Check: `grep -q '<java.version>25' pom.xml && ./mvnw -q -DskipITs verify`.
Docker running. **Leaves:** `failure/TestSftpServer.java`; `WarehouseSftp` with folder resolution, deadline and
upload; route `pick-list-delivery` with the bean upload; T13/T14 green.

### Task 9: Wave 2 Entry — Test SFTP Server Support

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/TestSftpServer.java`

**Guides to Load:**
- `camel-test/guides/test-configuration.md` — Testcontainers
- `camel-test/guides/test-generation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", B)` — the producer's absolute-path conversion the server must expose

**Design Spec Section:** §3.1.8 W1, W2; §3.14 T13, T14

- [ ] **Step 1:** `TestSftpServer` (AutoCloseable): a Testcontainers container from `spec-test.image.sftp` (classpath `spec-test/spec-test.properties`, pinned digest). Its `sshd_config` and a small subsystem script are copied in with Testcontainers' `withCopyToContainer` (no new image, no Docker CLI). It has a host key, a known-hosts file for `IntegrationProcess.withSftpPort(port, knownHosts)`, and user `admin` with a random password exposed to the harness (as `SpecEnvironment` does).
- [ ] **Step 2:** Two modes, chosen by a static factory: `loginDirectory("/work")`: chroot `%h`, `ForceCommand internal-sftp -d /work`, folders `work/inbound/picklists/processed` owned by `admin`, so the login directory is `/work` and an absolute `/inbound/picklists` does not exist. `subsystemHang()`: no chroot, home `/home/admin` with `inbound/picklists/processed`, and the sftp subsystem is a script that runs the real `sftp-server` unless the flag file `/tmp/hang` exists, in which case it sleeps without reading (accepts the login and channel, never answers `SSH_FXP_INIT`). `hang(boolean)` creates or removes the flag through Testcontainers' `execInContainer`.
- [ ] **Step 3:** Helpers: `files()` (every regular file under the user's tree with its path relative to the login directory), `move(from, to)` (as the Warehouse does), `count(name)`.
- [ ] **Step 4:** Verify: `./mvnw -q -DskipITs test-compile && grep -q 'spec-test.image.sftp' src/test/java/com/trailheadgear/orderintegration/failure/TestSftpServer.java && ! grep -Eq 'atmoz/sftp:|alpine:' src/test/java/com/trailheadgear/orderintegration/failure/TestSftpServer.java && echo SFTP-SERVER-OK` prints `SFTP-SERVER-OK` (first real use proves it in Task 10).

**Review:**
- [ ] Spec compliance: one server whose login directory is not `/`; one that accepts the login and never starts the subsystem, switchable per connection.
- [ ] Code quality: pinned image; random password never logged; container closed after the class.

---

### Task 10: T13 PickListLoginDirectoryIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/PickListLoginDirectoryIT.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", B)` — `fileExist`, `tempFileName`
- `camel_catalog_component_doc(component="sql", B)` — the poller's `delay`

**Design Spec Section:** §3.14 T13; §3.17 D16; review decision 1

- [ ] **Step 1:** Shared `SpecEnvironment` (PostgreSQL, Kafka, catalog stub, mail) plus `TestSftpServer.loginDirectory("/work")`. Start `IntegrationProcess.withFaults("to-pick-list-delivered:error:1").withSftpPort(port, knownHosts).withProperty("warehouse.sftp.inbound-directory", "/inbound/picklists").withProperty("warehouse.sftp.processed-directory", "/inbound/picklists/processed")` (the production defaults, absolute).
- [ ] **Step 2:** Submit one Partner Order (accepted) so its Pick List is pending. Wait until the file is uploaded and the attempt failed (`attempts` = 1, `last_failure` = `unexpected error: NoSuchMethodError`, state Pending). Move the file to the processed folder, as the Warehouse does (`work/inbound/picklists/PL-x.csv` → `work/inbound/picklists/processed/PL-x.csv`). Wait for the next poll: the pick list is Delivered (counted as already delivered, folder `processed`).
- [ ] **Step 3:** Assert: the file `PL-x.csv` exists exactly once on the server, in `inbound/picklists/processed` relative to the login directory; no `.tmp` is left; log line `Pick list already on the warehouse server … folder=processed`. Messages name the check, expected, actual and order number.
- [ ] **Step 4:** Red: run (P-6). Expect a second `PL-x.csv` in `work/inbound/picklists/` (W1). Record.
- [ ] **Step 5:** Verify: `test -f src/test/java/com/trailheadgear/orderintegration/failure/PickListLoginDirectoryIT.java && grep -q 'PickListLoginDirectoryIT.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T13-RED-OK` prints `T13-RED-OK`.

**Review:**
- [ ] Spec compliance: login directory not `/`; upload, failed recording, Warehouse move, retry; one file in the configured folder.
- [ ] Code quality: cleanup in `finally`; process always stopped; no fixed port.

---

### Task 11: D16 Folder Resolution in WarehouseSftp (T13 green)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", B)`

**Design Spec Section:** §3.17 D16; A-8

- [ ] **Step 1 (unit red first):** In `WarehouseSftpLookupTest` add: a bean built with `/inbound/picklists` and `/inbound/picklists/processed` looks up exactly `[inbound/picklists/x, inbound/picklists/processed/x]`. Plus a pin `remoteFolder("/inbound/picklists") == "inbound/picklists"`, `remoteFolder("inbound/picklists") == "inbound/picklists"`, `remoteFolder("//a/b") == "a/b"` (every leading `/` removed, as the producer's message `"/inbound/picklists" will be converted to "inbound/picklists"`). Add `static String remoteFolder(String)` with the P-5 not-implemented body. Run `./mvnw -q -DskipITs test -Dtest=WarehouseSftpLookupTest`; record red.
- [ ] **Step 2:** Implement `remoteFolder` and use it in the constructor for both folders, so every `stat` uses the converted path. Javadoc: the rule and why (F-2, D16).
- [ ] **Step 3:** Green: the unit test; then T13 (P-6); then `./mvnw -q -DskipITs test`. Record rows.
- [ ] **Step 4:** Verify: `grep -q 'remoteFolder' src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java && ./mvnw -q -DskipITs test && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=PickListLoginDirectoryIT && echo T13-OK` prints `T13-OK`.

**Review:**
- [ ] Spec compliance: one rule for both folders, same as the producer; chrooted paths unchanged.
- [ ] Code quality: one method; no change in bean wiring; unit pin present.

---

### Task 12: T14 SftpSubsystemHangIT (red, both cases)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/SftpSubsystemHangIT.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="sftp", includeOptions="all", optionsFilter="timeout", B)`

**Design Spec Section:** §3.14 T14; §3.18 D17; review decision 2; P-3

- [ ] **Step 1:** `TestSftpServer.subsystemHang()`; folders relative (`inbound/picklists`, `inbound/picklists/processed`); `warehouse.sftp.timeout-ms=3000` through `withProperty`; poll delay 1 s (spec-test profile). Margin: 5 s. The test's own deadline for each wait is `timeout-ms + margin + 2 poll delays`.
- [ ] **Step 2:** Case **check** (channel start of `WarehouseSftp`): `hang(true)` before the integration starts. Submit one Partner Order. Within the deadline: `attempts` ≥ 1 and `last_failure` set (any value; Task 13 pins it), state Pending, and a second attempt counted (the next poll tries again). Then `hang(false)`: the pick list is Delivered, exactly one file.
- [ ] **Step 3:** Case **upload** (the producer's own connection): `hang(false)`, start, submit an order whose catalog lookup is delayed until the check's session exists (or first deliver one warm-up order). Then `hang(true)` so only new connections hang. Submit a second order. Within the deadline the attempt must have ended: either Delivered, or `attempts` + 1 with `last_failure` set. Then `hang(false)`: delivered exactly once.
- [ ] **Step 4:** Red: run (P-6). Expect both cases to fail on the test's deadline (the poller held). Record each case separately, with the thread dump line if the harness has one (`ChannelSftp.start`).
- [ ] **Step 5:** Verify: `grep -q 'SftpSubsystemHangIT.*check.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && grep -q 'SftpSubsystemHangIT.*upload.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T14-RED-OK` prints `T14-RED-OK`.

**Review:**
- [ ] Spec compliance: login accepted, subsystem never started; bounded failure counted and retried; delivered once after recovery; upload case separate.
- [ ] Code quality: the process is stopped even when held (destroy after the deadline); no sleep-only assertions, polling with a deadline.

---

### Task 13: D17 Deadline on Every WarehouseSftp Call (T14 check case green)

**Agent:** implementation-engineer

**Files:**
- Create: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftpTimeoutException.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/FailureKinds.java`
- Modify: `src/test/java/com/trailheadgear/orderintegration/FailureKindsTest.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`
- `shared/camel-security-checklist.md` — no message text, password never logged

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.18 D17; P-9

- [ ] **Step 1 (unit red first):** `FailureKindsTest`: `describe(new WarehouseSftpTimeoutException("channel-start", 10000))` is `WarehouseSftpTimeoutException channel-start`. Create the exception (extends `java.io.IOException`, `step()` getter, message `SFTP <step> took longer than <ms> ms`) and record red against the unchanged `FailureKinds.code`.
- [ ] **Step 2:** `FailureKinds.code`: return `step()` for `WarehouseSftpTimeoutException`. Unit green.
- [ ] **Step 3:** `WarehouseSftp`: one deadline of `timeoutMs` per public call, covering session connect, channel open, channel start (`ChannelSftp.connect`) and each `stat`. The bean owns one daemon `ScheduledExecutorService` (thread name `warehouse-sftp-deadline`), shut down in `destroy()`. Before each step it schedules a watchdog at the remaining time that disconnects the session (this ends the blocked `PipedInputStream.read`, W2). When the watchdog fired, the step's exception is replaced by `WarehouseSftpTimeoutException(step, timeoutMs)` and the session is closed. `session.setTimeout(timeoutMs)` stays.
- [ ] **Step 4:** Green: T14 case **check** (P-6). Then run case **upload** against this code: expected still red (P-3). Record both results. If **upload** is green here, record a finding and do **not** do Task 14 (P-3); go to Task 15.
- [ ] **Step 5:** Verify: `grep -q 'ScheduledExecutorService' src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java && ./mvnw -q -DskipITs test && grep -q 'SftpSubsystemHangIT.*check.*green' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo D17-OK` prints `D17-OK`.

**Review:**
- [ ] Spec compliance: every SFTP call within `warehouse.sftp.timeout-ms`; failure counted with a step-naming reason; next poll retries; no new state.
- [ ] Code quality: watchdog cancelled on success; no thread leak; synchronized semantics kept; reason has no message text.

---

### Task 14: A-9 Upload Moved into WarehouseSftp (T14 upload case green)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java`
- Modify: `src/main/java/com/trailheadgear/orderintegration/IntegrationBeans.java` (Javadoc only)
- Modify: `src/main/resources/camel/pick-list-delivery.camel.yaml`
- Modify: `src/test/java/com/trailheadgear/orderintegration/WarehouseSftpLookupTest.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/route-validation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="bean", B)` — `bean:` URI syntax and `method` option
- `camel_catalog_language_doc(language="simple", B)` — `${exchangeProperty.file-name}`, `${body}`
- `camel_validate_yaml_dsl` / `camel_validate_route` on the changed route

**Design Spec Section:** §3.18 last bullet; A-9; C-014; P-3, P-4

- [ ] **Step 1 (precondition):** Task 13 recorded T14 **upload** red after D17. Otherwise skip this task (P-3).
- [ ] **Step 2 (unit red first):** extend the `RemoteFiles` seam with `put(String tmpPath, byte[])`, `rename(String from, String to)`. Unit cases with the fake: `upload("PL-TG-1001.csv", bytes)` → `exists(inbound/PL-TG-1001.csv)` false → `put(inbound/PL-TG-1001.csv.tmp)` → `rename(… .tmp, inbound/PL-TG-1001.csv)`, in that order. A final file already present → exception, no put. An invalid name → `IllegalArgumentException`, no call. Add `upload` with the P-5 body; record red.
- [ ] **Step 3:** Implement `public synchronized void upload(String fileName, String csv)`: validate the name; same connection and per-call deadline as the check (steps `upload`, `rename`); P-4 order; UTF-8 bytes; no mkdir. Failure closes the session as the check does. Javadoc: C-014, P-4 (rename semantics same as Camel's producer).
- [ ] **Step 4:** Route: replace the `to-warehouse-sftp` sftp endpoint (and its parameter block) with `to: {id: to-warehouse-sftp, uri: "bean:warehouseSftp", parameters: {method: "upload(${exchangeProperty.file-name}, ${body})"}}`. The comments state C-014 and that the guards are in `WarehouseSftp` (design 006 A-9). The header comment of the file names "the bean's existence check before every write, the atomic rename, one instance". Validate with the MCP tools; `RouteCatalogTest` and the YAML validator run in `verify`. `camel-ftp-starter` stays (it brings JSch); `warehouse.sftp.*` keys are unchanged.
- [ ] **Step 5:** Green: unit; T14 both cases; T13; `Tc001PartnerOrderToWarehouseIT`, `Tc005PickListOnceAfterRestartIT`, `KillIT` (K2) via P-6. Record rows.
- [ ] **Step 6:** Verify: `! grep -q 'uri: "sftp:' src/main/resources/camel/pick-list-delivery.camel.yaml && grep -q 'bean:warehouseSftp' src/main/resources/camel/pick-list-delivery.camel.yaml && ./mvnw -q -DskipITs verify && grep -q 'SftpSubsystemHangIT.*upload.*green' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo A9-OK` prints `A9-OK`.

**Review:**
- [ ] Spec compliance: temporary name then rename (C-014); final never overwritten by the integration's own check; same folder rule as the check; route step id kept.
- [ ] Code quality: Kaoto-compatible YAML; no credential in the route; one connection path; seam tested.

---

### Task 15: Wave 2 Close — verify and Handoff

**Agent:** implementation-engineer

**Files:**
- Modify: `docs/camel-kit/006-deployment/execution-report.md`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.19; P-2

- [ ] **Step 1:** `./mvnw -B verify`.
- [ ] **Step 2:** Append `## Wave 2 handoff`: T13/T14 rows; whether A-9 was done; "Next wave needs": `TestSftpServer` exists, `verify` green.
- [ ] **Step 3:** Verify: `./mvnw -B verify | tail -20 | grep -q 'BUILD SUCCESS' && grep -q '## Wave 2 handoff' docs/camel-kit/006-deployment/execution-report.md && echo WAVE-2-OK` prints `WAVE-2-OK`.

**Review:**
- [ ] Spec compliance: green `verify`; handoff complete.
- [ ] Code quality: handoff names real results.

---

## Wave 3 — In-Process Settings: D3, D5, D10, D13, T10

**Wave entry (needs):** `## Wave 2 handoff` present; check `./mvnw -q -DskipITs verify` and
`grep -q 'remoteFolder' src/main/java/com/trailheadgear/orderintegration/WarehouseSftp.java`. **Leaves:** management
port 9090 (health only), probe groups pinned, Camel shutdown timeout, static membership, failure ITs reporting their
assertion, `deploy/k8s/base/partner-configuration.json`, build time after D10.

### Task 16: Wave 3 Entry — Harness Management Port and Build Time Mid

**Agent:** implementation-engineer

**Files:**
- Modify: `src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java`
- Modify: `docs/camel-kit/006-deployment/evidence/build-times.md`

**Guides to Load:**
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 T6; P-8

- [ ] **Step 1:** `writeRunSettings` adds `management.server.port=<FreePorts.next()>` (chosen once per `IntegrationProcess`, kept across `restart()`); add `public int managementPort()`. Nothing else changes.
- [ ] **Step 2:** Record the duration of the wave 2 `verify` as "after waves 1–2" in `build-times.md`.
- [ ] **Step 3:** Verify: `grep -q 'management.server.port' src/test/java/com/trailheadgear/orderintegration/spec/support/IntegrationProcess.java && ./mvnw -q -DskipITs test-compile && echo HARNESS-OK` prints `HARNESS-OK`.

**Review:**
- [ ] Spec compliance: no IT binds 9090.
- [ ] Code quality: port excluded list still honoured by `FreePorts`.

---

### Task 17: T6 ProbesIT (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/ProbesIT.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-configuration.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)` — the partner endpoint stays on `server.port`

**Design Spec Section:** §3.14 T6; §3.4 D3; §3.1.6 P1–P4

- [ ] **Step 1:** Packaged jar via `IntegrationProcess` (spec-test profile, shared `SpecEnvironment`). Assert on `managementPort()`: `GET /actuator/health/liveness` 200 `{"status":"UP"}`; `/actuator/health/readiness` 200; `/actuator/health` body without `components`/`details`; `/actuator/env` 404 on the management port and on `serverPort()`; `/orders` (partner path) 404 on the management port.
- [ ] **Step 2:** Outage: pause the PostgreSQL container through the Testcontainers Docker client (`pauseContainerCmd`, as 005 did). While paused, liveness and readiness stay 200 (three samples over 10 s) and `/actuator/health` is 503. Unpause in `finally`.
- [ ] **Step 3:** Red: run (P-6) against today's `application.properties`. The harness's run settings (Task 16) start a management server on the free port, but the probe groups are not enabled outside Kubernetes and are not pinned. Expect `/actuator/health/liveness` 404 and/or readiness not 200 during the pause. Record exactly which assertions fail and their messages.
- [ ] **Step 4:** Verify: `grep -q 'ProbesIT.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T6-RED-OK` prints `T6-RED-OK`.

**Review:**
- [ ] Spec compliance: every T6 assertion; outage via pause.
- [ ] Code quality: always unpaused; HTTP client timeouts set.

---

### Task 18: D3 Health Port and Probe Groups, D5 Shutdown Settings (T6 green)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `config/application-dev.properties`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/properties-generation.md`
- `shared/camel-security-checklist.md` — actuator exposure

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.4 D3 (properties block); §3.6 D5 (`camel.main.shutdown-timeout=45`)

- [ ] **Step 1:** Replace `management.server.port=-1` and its comment with the eight D3 lines and their comments, exactly as §3.4 lists them, plus `server.shutdown=graceful`. Add `camel.main.shutdown-timeout=45` with the D5 comment.
- [ ] **Step 2:** `config/application-dev.properties`: comment `# Health on http://localhost:9090/actuator/health/{liveness,readiness} (design 006 D3)`; no new key.
- [ ] **Step 3:** Green: T6 (P-6). Second red (design §3.14: "with the groups unpinned and `db` added, readiness goes 503"): change `…readiness.include=readinessState` to `readinessState,db`; run; expect the outage assertion to fail; record; revert. Green again; record.
- [ ] **Step 4:** Verify: `grep -q '^management.server.port=9090$' src/main/resources/application.properties && grep -q '^management.endpoint.health.group.readiness.include=readinessState$' src/main/resources/application.properties && grep -q '^camel.main.shutdown-timeout=45$' src/main/resources/application.properties && ./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=ProbesIT && echo D3-OK` prints `D3-OK`.

**Review:**
- [ ] Spec compliance: health only, own port, plain HTTP, groups pinned, details hidden, graceful shutdown, Camel timeout written.
- [ ] Code quality: no other actuator endpoint; comments name the design.

---

### Task 19: T8 KillIT Intake-Resume Assertion (red)

**Agent:** test-engineer

**Files:**
- Modify: `src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", includeOptions="all", optionsFilter="session", B)` — `sessionTimeoutMs` default 45000

**Design Spec Section:** §3.14 T8; §3.1.5 K4; §3.10 D10

- [ ] **Step 1:** For each Peakmart kill point (K1a, K1b): after `restartWithoutFaults`, publish one new event and assert its receipt is written within 15 s of the restarted process being ready (message `K1x: first event after restart taken in after <s> s, expected ≤ 15 s`). Keep `topicWait()` for the older waits.
- [ ] **Step 2:** Red: `KillIT` (P-6). Expect ~40 s (K4). Record seconds per kill point and the duration of the class.
- [ ] **Step 3:** Verify: `grep -q 'KillIT.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && grep -q '15' src/test/java/com/trailheadgear/orderintegration/failure/KillIT.java && echo T8-RED-OK` prints `T8-RED-OK`.

**Review:**
- [ ] Spec compliance: every Peakmart kill point; 15 s bound.
- [ ] Code quality: time measured from "ready", not from the kill.

---

### Task 20: D10 Kafka Static Membership (T8 green)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/resources/camel/peakmart-order-intake.camel.yaml`
- Modify: `src/main/resources/application-default.properties`
- Modify: `config/application-dev.properties`
- Modify: `src/test/resources/spec-test/application-spec-test.properties`
- Modify: `src/test/scripts/required-variables-runs.sh`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/component-loading.md`
- `camel-implement/guides/yaml-catalog-rules.md`
- `camel-implement/guides/yaml-structure.md`
- `camel-implement/guides/properties-generation.md`
- `camel-implement/guides/route-validation.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="kafka", includeOptions="all", optionsFilter="groupInstanceId", B)`
- `camel_validate_yaml_dsl` on the changed route

**Design Spec Section:** §3.10 D10; P-7, P-13

- [ ] **Step 1:** Route: add `groupInstanceId: "{{peakmart.group-instance-id}}"` after `groupId`, with comment `# Design 006 D10: static member; a restarted pod takes its partitions back at once`.
- [ ] **Step 2:** `application-default.properties`: the D10 line and comment of §3.10. `config/application-dev.properties`: `peakmart.group-instance-id=dev`. `application-spec-test.properties`: `peakmart.group-instance-id=spec-test` with the P-7 reason as a comment.
- [ ] **Step 3:** `required-variables-runs.sh`: add `HOSTNAME` to the list, 26 → 27 in the comments and echo lines.
- [ ] **Step 4:** Green: `KillIT` (P-6) with the 15 s bound; `UnexpectedErrorIT`; `Tc002PeakmartRedeliveryShippedOnceIT`. Record rows and durations.
- [ ] **Step 5:** Verify: `grep -q 'groupInstanceId: "{{peakmart.group-instance-id}}"' src/main/resources/camel/peakmart-order-intake.camel.yaml && grep -q '^peakmart.group-instance-id=${HOSTNAME}$' src/main/resources/application-default.properties && grep -q 'peakmart.group-instance-id=spec-test' src/test/resources/spec-test/application-spec-test.properties && grep -q 'HOSTNAME' src/test/scripts/required-variables-runs.sh && ./mvnw -q -DskipITs verify && echo D10-OK` prints `D10-OK`.

**Review:**
- [ ] Spec compliance: id from a property in every profile; offsets, `breakOnFirstError`, duplicate rule unchanged.
- [ ] Code quality: catalog-verified option; no hard-coded id in the route.

---

### Task 21: T9 Failure ITs Report the Failed Assertion (D13)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/failure/Checks.java`
- Modify: every `src/test/java/com/trailheadgear/orderintegration/failure/*IT.java` (list in the metadata)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.13 D13; §3.14 T9

- [ ] **Step 1 (red):** Fault: in `NotificationTextIT` change one expected reason text (one edit). Run (P-6). Read `target/failsafe-reports/TEST-*NotificationTextIT.xml`: the `<failure message=…>` is `Test failed` (or has no assertion). Record. Keep the fault for Step 3.
- [ ] **Step 2:** `Checks`: `check(String name, Object expected, Object actual, String orderNumber)` throws `AssertionError("<name>: expected <x>, got <y> (order <n>)")`. Plus `unwrap(Throwable)`: the first `AssertionError` in a cause chain. Each failure IT runs its Citrus `runner.run(...)` blocks through a helper that rethrows `unwrap(e)` when Citrus wraps it (`CitrusRuntimeException`), so the reported message is the assertion's. Existing `assert*` calls with messages stay. Bare checks inside Citrus actions move to `Checks.check`.
- [ ] **Step 3:** Run the faulted `NotificationTextIT`: the report's failure message names the check, expected, actual and order number. Record. Revert the fault; run green; record.
- [ ] **Step 4:** Verify (the green run proves the fault is reverted): `./mvnw -q -DskipTests package && ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=NotificationTextIT && grep -q 'NotificationTextIT.*expected' docs/camel-kit/006-deployment/evidence/red-green-runs.md && test -f src/test/java/com/trailheadgear/orderintegration/failure/Checks.java && echo T9-OK` prints `T9-OK`.

**Review:**
- [ ] Spec compliance: every failure IT; message has check, expected, actual, order number.
- [ ] Code quality: no assertion weakened; fault reverted.

---

### Task 22: T10 PartnerConfigurationReleaseTest (red)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/PartnerConfigurationReleaseTest.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 T10; §3.9 D9; UC-007

- [ ] **Step 1:** Read `PartnerRegistration` and find how its checks (schema `partner-configuration.schema.json`, ranges, overlaps, contacts, safe key names) can run without a `DataSource` and without keys. If they can't, the test calls a package-private static `PartnerRegistration.check(String json)` returning the list of problems. Create it here with the P-5 body.
- [ ] **Step 2:** Test: `deploy/k8s/base/partner-configuration.json` (relative to the project base dir) exists, and `check` returns no problem. A second case: `docs/messages/examples/partner-configuration/invalid/api-key-in-configuration.json` gives at least one problem, which proves the checks run.
- [ ] **Step 3:** Red: run; expect "file does not exist". Record.
- [ ] **Step 4:** Verify: `grep -q 'PartnerConfigurationReleaseTest.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T10-RED-OK` prints `T10-RED-OK`.

**Review:**
- [ ] Spec compliance: UC-007's own checks, without keys or database.
- [ ] Code quality: seam package-private; no duplicate rule logic in the test.

---

### Task 23: Release Partner Configuration File (T10 green)

**Agent:** implementation-engineer

**Files:**
- Create: `deploy/k8s/base/partner-configuration.json`
- Modify: `src/main/java/com/trailheadgear/orderintegration/PartnerRegistration.java` (only if Task 22 created the `check` seam: implement it by extracting the existing checks; `register()` calls it; behaviour unchanged)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.9 D9; OQ-095; P-10

- [ ] **Step 1:** Write the file from `docs/messages/examples/partner-configuration/summit-outfitters.json` (P-10). JSON allows no comment, so the stand-in is noted in `deploy/README.md` (Task 38) and in the handoff.
- [ ] **Step 2:** Implement the seam if present; `PartnerRegistrationTest` stays green.
- [ ] **Step 3:** Green: `./mvnw -q -DskipITs test -Dtest='PartnerConfigurationReleaseTest,PartnerRegistrationTest'`; record.
- [ ] **Step 4:** Verify: `test -f deploy/k8s/base/partner-configuration.json && ./mvnw -q -DskipITs test -Dtest='PartnerConfigurationReleaseTest,PartnerRegistrationTest' && echo T10-OK` prints `T10-OK`.

**Review:**
- [ ] Spec compliance: a valid released file; no API Key in it.
- [ ] Code quality: registration behaviour byte-for-byte the same at startup.

---

### Task 24: Wave 3 Close — verify, Build Time After D10, Handoff

**Agent:** implementation-engineer

**Files:**
- Modify: `docs/camel-kit/006-deployment/evidence/build-times.md`
- Modify: `docs/camel-kit/006-deployment/execution-report.md`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.15 "Build time"; §3.19

- [ ] **Step 1:** `/usr/bin/time -p ./mvnw -B verify`. Record under "After D10": total, per-IT times, `KillIT` and `UnexpectedErrorIT` against "Before".
- [ ] **Step 2:** Append `## Wave 3 handoff`. "Next wave needs": `management.server.port=9090`, `camel.main.shutdown-timeout=45`, `deploy/k8s/base/partner-configuration.json`, `verify` green, local image `trailhead/order-integration:today` present or not (`docker image inspect`), `KIND_KUBECONFIG` needed from wave 4 on.
- [ ] **Step 3:** Verify: `grep -A3 'After D10' docs/camel-kit/006-deployment/evidence/build-times.md | grep -q '[0-9]' && grep -q '## Wave 3 handoff' docs/camel-kit/006-deployment/execution-report.md && echo WAVE-3-OK` prints `WAVE-3-OK` (after a `verify` that ended in `BUILD SUCCESS`).

**Review:**
- [ ] Spec compliance: before/after measured locally, as §3.15 asks.
- [ ] Code quality: numbers from the run.

---

## Wave 4 — Image and Manifests: D1, D2 (image), D4–D9, T2–T5

**Wave entry (needs):** `## Wave 3 handoff` present; `./mvnw -q -DskipITs verify` green; Docker running;
`KIND_KUBECONFIG` set and `deploy/local/kube.sh version --client` exits 0 (else `NEEDS_CONTEXT`, P-14). **Leaves:**
image `trailhead/order-integration:006`, `deploy/ci/image-check.sh`, `deploy/k8s/{base,release,ci}`, rendered files under
`target/k8s/`.

### Task 25: Wave 4 Entry — T2 Image Check Script (red on today's image)

**Agent:** implementation-engineer

**Files:**
- Create: `deploy/ci/image-check.sh` (executable)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 T2; §3.2 D1; §3.1.1 B1–B3, B5

- [ ] **Step 1:** `image-check.sh <image>`: reads `docker image inspect` (`Config.User` = `10001:10001`, `Config.Entrypoint` = the D1 array exactly, `Config.WorkingDir` = `/app`). Then, inside the image (the script runs the container, §3.16): `java -version` reports `25.0.5`; no file under `/app` writable by uid 10001 (`find /app -writable` as 10001 prints nothing); no `config/` under `/app`; no class or file whose name contains `Test`, `fault`, `FaultHook` or `spec-test` under `/app`. It also checks that the `FROM` lines of `src/main/docker/Dockerfile` carry the pinned digest of §3.2. On each failure it prints `FAILED <property>: expected <x>, got <y>` and exits 1 after all checks.
- [ ] **Step 2:** Red: use `trailhead/order-integration:today` if `docker image inspect` finds it. Otherwise build today's Dockerfile as `trailhead/order-integration:pre-d1` (`./mvnw -q -DskipTests package && docker build -f src/main/docker/Dockerfile -t trailhead/order-integration:pre-d1 .`). Run the check; expect the L0 failures (Java 21, uid 185, no JVM options). Record the image used and the failing properties.
- [ ] **Step 3:** Verify: `test -x deploy/ci/image-check.sh && grep -q 'image-check.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T2-RED-OK` prints `T2-RED-OK`.

**Review:**
- [ ] Spec compliance: every T2 property; prints the property and value.
- [ ] Code quality: strict bash; container removed after each check (`--rm`); no host folder mounted.

---

### Task 26: D1 Dockerfile (T2 green)

**Agent:** implementation-engineer

**Files:**
- Modify: `src/main/docker/Dockerfile`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.2 D1; §3.7 D6 (JVM options)

- [ ] **Step 1:** Replace the file. Both stages `FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c`. Builder: copy `target/order-integration-0.1.0.jar`, `java -Djarmode=tools -jar application.jar extract --layers --destination extracted`. Runtime: copy the four layers into `/app` owned by `root:root`, then `chmod -R a-w /app`; `WORKDIR /app`; `USER 10001:10001`; the exact `ENTRYPOINT` of §3.2 (exec form). No `EXPOSE` needed; no `config/`. The header comment names design 006 D1/D6 and the build command.
- [ ] **Step 2:** `./mvnw -q -DskipTests package && docker build -f src/main/docker/Dockerfile -t trailhead/order-integration:006 .`; `deploy/ci/image-check.sh trailhead/order-integration:006` exits 0. Record green.
- [ ] **Step 3:** Verify: `grep -c 'eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c' src/main/docker/Dockerfile | grep -q '^2$' && deploy/ci/image-check.sh trailhead/order-integration:006 && echo D1-OK` prints `D1-OK`.

**Review:**
- [ ] Spec compliance: pinned base, root-owned read-only app, numeric user, two JVM options, exec form.
- [ ] Code quality: no secret, no setting baked in; layer order kept.

---

### Task 27: T3 Image Scan and SBOM (red on today's image, green on the new one)

**Agent:** implementation-engineer

**Files:**
- Modify: `deploy/ci/scan.sh` (add `sbom <tar>`: Trivy CycloneDX to stdout, redirected to `target/scan/sbom.cdx.json`)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.3 D2; §3.14 T3; §3.15 job 2

- [ ] **Step 1:** Red: `docker save -o target/image-pre.tar <Task 25 image>`; `deploy/ci/scan.sh image target/image-pre.tar` exits 1 (L0: OS HIGH fixable). Record counts.
- [ ] **Step 2:** Green: `docker save -o target/image.tar trailhead/order-integration:006`; `deploy/ci/scan.sh image target/image.tar` exits 0; `deploy/ci/scan.sh sbom target/image.tar > target/scan/sbom.cdx.json`. Record. A remaining fixable finding is P-17.
- [ ] **Step 3:** Verify: `deploy/ci/scan.sh image target/image.tar && test -s target/scan/sbom.cdx.json && echo T3-IMAGE-OK` prints `T3-IMAGE-OK`.

**Review:**
- [ ] Spec compliance: image and jar both clean of fixable HIGH/CRITICAL; SBOM produced.
- [ ] Code quality: tars under `target/` only (gitignored).

---

### Task 28: T4 ManifestPolicyTest (red against today.yaml)

**Agent:** test-engineer

**Files:**
- Create: `src/test/java/com/trailheadgear/orderintegration/ManifestPolicyTest.java`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-generation.md`
- `camel-test/guides/test-runner.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 T4; §3.4–§3.9 (D3–D9); P-11

- [ ] **Step 1:** Unit test with SnakeYAML. It reads every document of the files in the directory given by system property `manifest-policy.dir` (default `deploy/k8s/base`; a single file is accepted too), plus `src/main/resources/application.properties` for `management.server.port` and `camel.main.shutdown-timeout`. One test method per rule, each message naming file, resource and field:
  - exactly one workload, `kind: StatefulSet`, `replicas: 1`, `podManagementPolicy: OrderedReady`, `updateStrategy.type: RollingUpdate`; no `Deployment`, `HorizontalPodAutoscaler`, `PodDisruptionBudget`;
  - startup/liveness/readiness probes `httpGet` on port = `management.server.port` (9090), paths `/actuator/health/liveness` (startup, liveness) and `/actuator/health/readiness`, with the D3 settings;
  - `terminationGracePeriodSeconds` ≥ 30 + `camel.main.shutdown-timeout` (= 90 here);
  - memory request = memory limit (1Gi), cpu request 500m, no cpu limit;
  - pod `runAsNonRoot`, `runAsUser`/`runAsGroup` 10001, `seccompProfile.type: RuntimeDefault`, `automountServiceAccountToken: false`, own `serviceAccountName`; container `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`; `/tmp` an `emptyDir` with `sizeLimit: 256Mi`;
  - no env `SPRING_PROFILES_ACTIVE` or `JAVA_TOOL_OPTIONS` (in `env` or in a ConfigMap of the base); no `volumeMount` under `/app`;
  - Secret Store mounted at `/etc/trailhead/secret-store` with env `SECRET_STORE_DIR` equal to it; Partner Configuration mounted so that env `PARTNER_CONFIGURATION=/etc/trailhead/partners/partner-configuration.json` points into it; `envFrom` the ConfigMap `order-integration-settings` and the Secret `order-integration-secrets`; files Secret at `/etc/trailhead/files`;
  - the partner ConfigMap comes from a `configMapGenerator` in `kustomization.yaml` (hash suffix not disabled);
  - no `kind: Secret` with `data` or `stringData` anywhere in `deploy/k8s/`;
  - the Service `order-integration` exposes only 8443; the management port is in no Service; a NetworkPolicy admits 8443 from anywhere and 9090 only from the namespace;
  - `deploy/k8s/release/kustomization.yaml` sets the image by `digest: sha256:<64 hex>`, never `newTag`.
- [ ] **Step 2:** Red: `./mvnw -q -DskipITs test -Dtest=ManifestPolicyTest -Dmanifest-policy.dir=deploy/local/lab/k8s/today.yaml`. Every rule fails (design §3.14). Record the count of failing rules and three example messages. A rule that passes against `today.yaml` is a finding: fix the test first (design §3.14).
- [ ] **Step 3:** Verify: `grep -q 'ManifestPolicyTest.*today.yaml.*red' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T4-RED-OK` prints `T4-RED-OK`.

**Review:**
- [ ] Spec compliance: every T4 assertion of §3.14, values cross-checked with the properties.
- [ ] Code quality: no Kubernetes client; readable messages.

---

### Task 29: Base and Release Manifests (D4, D5, D6, D8, D9; T4 green)

**Agent:** implementation-engineer

**Files:**
- Create: `deploy/k8s/base/kustomization.yaml`, `deploy/k8s/base/statefulset.yaml`, `deploy/k8s/base/services.yaml`, `deploy/k8s/base/serviceaccount.yaml`, `deploy/k8s/base/networkpolicy.yaml`
- Create: `deploy/k8s/release/kustomization.yaml`
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.4 D3 (probes table); §3.5 D4; §3.6 D5; §3.7 D6; §3.8 D8; §3.9 D9; §3.12 (objects the person creates); P-10, P-11

- [ ] **Step 1:** `statefulset.yaml`: StatefulSet `order-integration`, `serviceName: order-integration-headless`, `replicas: 1`, OrderedReady, RollingUpdate; labels `app.kubernetes.io/name: order-integration`. Pod: the D8 security context, `serviceAccountName: order-integration`, `automountServiceAccountToken: false`, `terminationGracePeriodSeconds: 90`. Container `order-integration`, `image: order-integration` (set by overlays), ports 8443 (`partner`) and 9090 (`management`), the three probes of §3.4, resources of §3.7, `envFrom` ConfigMap `order-integration-settings` and Secret `order-integration-secrets`. Env `SECRET_STORE_DIR=/etc/trailhead/secret-store`, `PARTNER_CONFIGURATION=/etc/trailhead/partners/partner-configuration.json`, and the file paths the jar needs (`PARTNER_TLS_CERTIFICATE`, `PARTNER_TLS_PRIVATE_KEY`, `DB_SSL_ROOT_CERT`, `WAREHOUSE_SFTP_KNOWN_HOSTS`) pointing under `/etc/trailhead/files/`. Volumes: `tmp` emptyDir 256Mi at `/tmp`; Secret `order-integration-files` at `/etc/trailhead/files` (read-only); Secret `order-integration-partner-api-keys` at `/etc/trailhead/secret-store` (read-only); the generated partner ConfigMap at `/etc/trailhead/partners` (read-only).
- [ ] **Step 2:** `services.yaml`: headless `order-integration-headless` (clusterIP None, port 8443) and ClusterIP `order-integration` port 8443 → `partner`. `serviceaccount.yaml`: `order-integration`, `automountServiceAccountToken: false`. `networkpolicy.yaml`: ingress 8443 from anywhere; 9090 from `namespaceSelector` of the same namespace (`kubernetes.io/metadata.name`) plus the node CIDR comment (kubelet probes are not subject to NetworkPolicy in kind; recorded); no egress rule.
- [ ] **Step 3:** `base/kustomization.yaml`: resources above; `configMapGenerator` `order-integration-partners` from `partner-configuration.json`; labels. No namespace (set by the person or overlay). The settings ConfigMap and the three Secrets are **not** in the base (the person creates them, §3.9). Their key lists go to `deploy/README.md` (Task 38).
- [ ] **Step 4:** `release/kustomization.yaml`: `resources: [../base]`; `images: [{name: order-integration, newName: ghcr.io/OWNER/order-integration, digest: sha256:0000000000000000000000000000000000000000000000000000000000000000}]` (P-11, comment says CI replaces both).
- [ ] **Step 5:** Green: `./mvnw -q -DskipITs test -Dtest=ManifestPolicyTest`; render `deploy/local/kube.sh kustomize deploy/k8s/release > target/k8s/release.yaml` (P-14) and read it. Record green.
- [ ] **Step 6:** Verify: `./mvnw -q -DskipITs test -Dtest=ManifestPolicyTest && mkdir -p target/k8s && deploy/local/kube.sh kustomize deploy/k8s/release > target/k8s/release.yaml && grep -q 'kind: StatefulSet' target/k8s/release.yaml && grep -q '@sha256:' target/k8s/release.yaml && echo MANIFESTS-OK` prints `MANIFESTS-OK`.

**Review:**
- [ ] Spec compliance: every D4–D9 item; no Secret data; one replica; TLS at the pod.
- [ ] Code quality: plain manifests, no templating beyond kustomize; consistent labels.

---

### Task 30: CI Overlay and T5 kubeconform

**Agent:** implementation-engineer

**Files:**
- Create: `deploy/k8s/ci/kustomization.yaml`, `deploy/k8s/ci/namespace.yaml`, `deploy/k8s/ci/systems.yaml`, `deploy/k8s/ci/settings.env`
- Modify: `deploy/local/lab/sftp.Dockerfile` (pin `alpine:3.22` by its multi-arch index digest from `docker buildx imagetools inspect alpine:3.22`)

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.15 jobs 3 and 4; §3.8 (restricted namespace); §2 (systems)

- [ ] **Step 1:** `namespace.yaml`: namespace `trailhead-ci` with `pod-security.kubernetes.io/enforce: restricted` (and `warn`/`audit` restricted).
- [ ] **Step 2:** `systems.yaml`: the lab's systems (`deploy/local/lab/k8s/systems.yaml`) for namespace `trailhead-ci`: PostgreSQL 16 with TLS, Kafka, the native SFTP server (image `trailhead/lab-sftp:ci`, built from `deploy/local/lab/sftp.Dockerfile` and `kind load`ed), Mailpit with STARTTLS, the catalog stub, a client pod. Every public image by `@sha256:` digest (read with `docker buildx imagetools inspect`). Each system satisfies the restricted profile, or its deviation is listed in a comment and the systems run in a second namespace `trailhead-ci-systems` without the restricted label. Choose the second option if any system cannot run restricted.
- [ ] **Step 3:** `kustomization.yaml`: resources `namespace.yaml`, `systems.yaml`, `../base`; namespace `trailhead-ci`; `configMapGenerator` `order-integration-settings` from `settings.env` (CI hosts/ports/addresses, `disableNameSuffixHash: true`); image `order-integration` → `trailhead/order-integration:ci` (`imagePullPolicy: IfNotPresent` patch). No Secret: `smoke.sh` creates the three Secrets plus the API key Secret with random values per run (Task 32).
- [ ] **Step 4:** T5: `deploy/local/kube.sh kustomize deploy/k8s/ci > target/k8s/ci.yaml`; `deploy/local/tool.sh kubeconform -strict -summary -kubernetes-version 1.34.0 /repo/target/k8s/ci.yaml /repo/target/k8s/release.yaml` exits 0. Planted red: add an unknown field `replica: 1` to `statefulset.yaml`, re-render, and kubeconform fails naming it; revert. Record both in `red-green-runs.md`.
- [ ] **Step 5:** Verify: `! grep -q 'replica: 1$' deploy/k8s/base/statefulset.yaml && deploy/local/kube.sh kustomize deploy/k8s/ci > target/k8s/ci.yaml && deploy/local/tool.sh kubeconform -strict -summary -kubernetes-version 1.34.0 /repo/target/k8s/ci.yaml /repo/target/k8s/release.yaml && ./mvnw -q -DskipITs test -Dtest=ManifestPolicyTest && echo T5-OK` prints `T5-OK`.

**Review:**
- [ ] Spec compliance: CI overlay = lab systems + release with CI settings; restricted namespace for the integration; strict validation for 1.34.
- [ ] Code quality: no secret in the repo; every public image by digest.

---

### Task 31: Wave 4 Close — verify, Image, Scan, kubeconform, Handoff

**Agent:** implementation-engineer

**Files:**
- Modify: `docs/camel-kit/006-deployment/execution-report.md`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.19; P-2

- [ ] **Step 1:** `./mvnw -B verify`; rebuild the image from that jar (`docker build … -t trailhead/order-integration:006`); `deploy/ci/image-check.sh trailhead/order-integration:006`; `docker save` + `deploy/ci/scan.sh image target/image.tar` + `deploy/ci/scan.sh jar target/order-integration-0.1.0.jar`; T5 as in Task 30.
- [ ] **Step 2:** Append `## Wave 4 handoff`, including the local image ID of `trailhead/order-integration:006`. "Next wave needs": the image, `KIND_KUBECONFIG`, the lab cluster `trailhead-lab` reachable (`deploy/local/kube.sh get nodes`), `deploy/local/lab/lab.sh up` done or to be done.
- [ ] **Step 3:** Verify: `deploy/ci/image-check.sh trailhead/order-integration:006 && deploy/ci/scan.sh image target/image.tar && grep -q '## Wave 4 handoff' docs/camel-kit/006-deployment/execution-report.md && echo WAVE-4-OK` prints `WAVE-4-OK` (after a `verify` with `BUILD SUCCESS`).

**Review:**
- [ ] Spec compliance: all wave-4 checks green together on one build.
- [ ] Code quality: handoff names the image ID.

---

## Wave 5 — Lab: Smoke and After-Runs (no Java change)

**Wave entry (needs):** `## Wave 4 handoff` present; `trailhead/order-integration:006` exists (`docker image inspect`);
`KIND_KUBECONFIG` set; `deploy/local/kube.sh get nodes` lists the `trailhead-lab` node. Run `deploy/local/lab/lab.sh up`
when the namespace `trailhead-lab` is missing. Load the image: `kind load docker-image trailhead/order-integration:006
--name trailhead-lab`. The wave's own checks: T12 green in the lab; A1–A10 recorded with a result each. **Leaves:**
`deploy/ci/smoke.sh`, `deploy/ci/fault.Dockerfile`, the lab overlay, `evidence/deploy-after-runs.md`.

### Task 32: Wave 5 Entry — Fault Image and T12 Smoke Script (red, then green in the lab)

**Agent:** test-engineer

**Files:**
- Create: `deploy/ci/fault.Dockerfile`
- Create: `deploy/ci/smoke.sh` (executable)
- Create: `deploy/local/lab/k8s/release/kustomization.yaml`
- Modify: `deploy/local/lab/lab.sh` (`app-sts <dir>`: `kube.sh apply -k <dir>`, wait for `statefulset/order-integration` rollout)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/route-analysis.md`
- `camel-test/guides/test-runner.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)`
- `camel_catalog_component_doc(component="platform-http", B)` — the partner endpoint the smoke posts to

**Design Spec Section:** §3.15 job 4 (every smoke check); §3.14 T12; P-12

- [ ] **Step 1:** `fault.Dockerfile`: `ARG RELEASE_IMAGE`, `FROM ${RELEASE_IMAGE}`. Copy `target/order-integration-0.1.0-fault-hook.jar` to `/fault/` (root-owned, read-only). Entrypoint: `java -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -cp /app/application.jar -Dloader.path=/fault/order-integration-0.1.0-fault-hook.jar org.springframework.boot.loader.launch.PropertiesLauncher --spring.profiles.active=default,fault-injection`. `fault.points` comes from env `FAULT_POINTS` (Spring relaxed binding). The comment says CI and lab only, never published.
- [ ] **Step 2:** `smoke.sh` (env: `KUBE` = command, e.g. `kubectl` in CI or `deploy/local/kube.sh` in the lab; `NS`; `APPLY_DIR`; `RELEASE_IMAGE`; `FAULT_IMAGE`). Steps, each printing `FAILED <check>: expected <x>, got <y>` plus the pod's last 50 log lines on failure:
  1. create the four Secrets with random values and lab-generated certificates (reuse `deploy/local/lab/secrets.sh` logic, output under `target/smoke/`, never printed);
  2. apply, wait for `order-integration-0` ready;
  3. a partner order accepted, its resend answered as before, a rejected one 422;
  4. a Peakmart event recorded;
  5. both pick lists on the SFTP server, once each;
  6. TLS 1.2 and 1.3 accepted, 1.1 refused;
  7. restart after exit 70: switch the StatefulSet image to `FAULT_IMAGE` with `FAULT_POINTS=to-validate-order-placed:oom:1`, publish 20 events; `lastState.terminated.exitCode == 70`, `restartCount ≥ 1`; switch back to the release image; 20 of 20 recorded once, 20 pick lists, no set-aside;
  8. rollout: `rollout restart statefulset/order-integration` with partner traffic, watching pods: never two integration pods, no 404.
  
  Logs are read by order number only (NFR-011).
- [ ] **Step 3:** Lab overlay `deploy/local/lab/k8s/release/`: base + the lab systems' namespace `trailhead-lab` settings (from `deploy/local/lab/k8s/config-pods.yaml` values) + image `trailhead/order-integration:006`.
- [ ] **Step 3b:** Record `git diff --stat -- src pom.xml` in `execution-report.md` (wave-5 start; Task 35 compares).
- [ ] **Step 4:** Red: run `smoke.sh` against the lab before the release is applied (`APPLY_DIR` empty → no pod). Expect `FAILED ready …`. Record.
- [ ] **Step 5:** Green: `docker build -f deploy/ci/fault.Dockerfile --build-arg RELEASE_IMAGE=trailhead/order-integration:006 -t trailhead/order-integration:006-fault .`; `kind load docker-image trailhead/order-integration:006-fault --name trailhead-lab`; run `smoke.sh` with the lab overlay. Expect every check to pass. Record with times (restart → first receipt).
- [ ] **Step 6:** Verify: `test -x deploy/ci/smoke.sh && grep -q 'smoke.*green' docs/camel-kit/006-deployment/evidence/red-green-runs.md && echo T12-OK` prints `T12-OK`.

**Review:**
- [ ] Spec compliance: every job-4 check; fault image built FROM the release image, never published.
- [ ] Code quality: random secrets never echoed; cleanup of the namespace's integration objects at the end (not of the systems).

---

### Task 33: After-Runs A1, A2, A9, A10 — Image, Rollout, Restricted, One Pod

**Agent:** test-engineer

**Files:**
- Create: `docs/camel-kit/006-deployment/evidence/deploy-after-runs.md` (and raw outputs under `docs/camel-kit/006-deployment/evidence/runs-after/`)
- Modify: `deploy/local/lab/runs.sh` (cases target the StatefulSet: `statefulset/order-integration`, pod `order-integration-0`)

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 second table (A1, A2, A9, A10); §3.1 rows B1–B5, S1, S2, T3, S5–S7

- [ ] **Step 1:** A1: `deploy/local/lab/inspect-image.sh trailhead/order-integration:006`. Compare with L0 (base, user, Java, tools, writable folders).
- [ ] **Step 2:** A9: the lab namespace labelled `pod-security.kubernetes.io/enforce=restricted` (through `kube.sh label`). The release starts and serves (one partner order 200).
- [ ] **Step 3:** A2: `runs.sh rollout` against the StatefulSet. Refused-connection seconds, and **0 × 404** (S2).
- [ ] **Step 4:** A10: the L7 `replicas` run repeated against the release manifests as written (one replica, no scale command): during a rollout and a pod delete, `kube.sh get pods -w` never shows two `order-integration` pods. 0 duplicate Order Desk emails for 200 orders.
- [ ] **Step 5:** `deploy-after-runs.md`: one row per run (run, L-before, after result, pass/fail against §3.14's expectation), raw files referenced. A run that fails its expectation is a P-17 finding, not a re-run until green.
- [ ] **Step 6:** Verify: `for a in A1 A2 A9 A10; do grep -q "| $a " docs/camel-kit/006-deployment/evidence/deploy-after-runs.md || exit 1; done && echo AFTER-1-OK` prints `AFTER-1-OK`.

**Review:**
- [ ] Spec compliance: each run's expectation from §3.14 recorded against its L-run.
- [ ] Code quality: only `kube.sh`; no secret in the evidence.

---

### Task 34: After-Runs A3, A4, A5, A6, A6b — Stop, Bad Secret, Outages, Memory

**Agent:** test-engineer

**Files:**
- Modify: `docs/camel-kit/006-deployment/evidence/deploy-after-runs.md`
- Modify: `deploy/local/lab/runs.sh`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 second table (A3–A6b); §3.1 rows T1, T2, C2, P2–P4, K1, K2

- [ ] **Step 1:** A3: `runs.sh sigterm` during the 200-event burst: graceful stop, 200 of 200 recorded once, 200 pick lists, stop time < 90 s.
- [ ] **Step 2:** A4: `runs.sh badconfig` with a wrong DB password Secret: CrashLoopBackOff, the last log line names Flyway; roll back by the runbook's step (apply the previous objects), ready again. Time to ready.
- [ ] **Step 3:** A5: `runs.sh outages` (DB, Kafka, catalog): the pod stays ready (`kube.sh get pod -w`, no `Ready=False`), `restartCount` unchanged, partners get the contract 503.
- [ ] **Step 4:** A6: the 500-order burst of L7 under the 1 GiB limit: no OOMKill (`lastState` empty, `restartCount` unchanged); record peak memory with `deploy/local/kube.sh top pod` if the lab has metrics-server, otherwise write "not measured". A6b: a lab-only patch adding `-Xmx96m` (env `JDK_JAVA_OPTIONS` in a lab patch file under `deploy/local/lab/k8s/`, never in `deploy/k8s/`): exit 3, restart, nothing lost.
- [ ] **Step 5:** Rows in `deploy-after-runs.md`.
- [ ] **Step 6:** Verify: `for a in A3 A4 A5 A6 A6b; do grep -q "| $a " docs/camel-kit/006-deployment/evidence/deploy-after-runs.md || exit 1; done && echo AFTER-2-OK` prints `AFTER-2-OK`.

**Review:**
- [ ] Spec compliance: probes never take the pod away in an outside outage; OOM ends the process with 3.
- [ ] Code quality: lab-only patches stay under `deploy/local/lab/`.

---

### Task 35: After-Runs A7, A8 and Wave 5 Close

**Agent:** test-engineer

**Files:**
- Modify: `docs/camel-kit/006-deployment/evidence/deploy-after-runs.md`
- Modify: `deploy/local/lab/partner-change.sh` (StatefulSet and generated ConfigMap)
- Modify: `docs/camel-kit/006-deployment/execution-report.md`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 second table (A7, A8); §3.1 rows K3, K4, C4; §3.19

- [ ] **Step 1:** A7: exit 70 with the fault image (`to-validate-order-placed:oom:1`): intake takes its first event within 10 s of the restart; 20 of 20 recorded once.
- [ ] **Step 2:** A8: change the lab copy of the Partner Configuration (a new contact), re-apply the lab overlay: the generated ConfigMap name changes, the pod rolls, and the log shows the registration of the changed partner.
- [ ] **Step 3:** Rows in `deploy-after-runs.md`; summary line "A1–A10: n pass, m findings".
- [ ] **Step 4:** Append `## Wave 5 handoff`: lab state (namespace, objects left, image IDs); "Next wave needs": `smoke.sh` and `fault.Dockerfile` green in the lab; `verify` unchanged since wave 4.
- [ ] **Step 5:** Verify: `for a in A7 A8; do grep -q "| $a " docs/camel-kit/006-deployment/evidence/deploy-after-runs.md || exit 1; done && grep -q '## Wave 5 handoff' docs/camel-kit/006-deployment/execution-report.md && echo WAVE-5-OK` prints `WAVE-5-OK`. Also, `git diff --stat -- src pom.xml` is identical to the output recorded at the start of this wave (Task 32 records it in the report): no Java change in wave 5.

**Review:**
- [ ] Spec compliance: every A-run of §3.14 recorded; no Java change in the wave.
- [ ] Code quality: evidence holds no secret or personal data (order numbers only).

---

## Wave 6 — CI Gate, Runbook, Docs

**Wave entry (needs):** `## Wave 5 handoff` present; `deploy/ci/{scan,image-check,smoke,fault}` present;
`./mvnw -q -DskipITs verify` green; `KIND_KUBECONFIG` set for the T5 re-check. **Leaves:** the workflow, the runbook,
the open questions; everything the developer's first CI run needs (§7).

### Task 36: Wave 6 Entry — T11 Test Inventory Script (red, then green)

**Agent:** test-engineer

**Files:**
- Create: `deploy/ci/test-inventory.sh` (executable)
- Create: `deploy/ci/test-summary.sh` (executable: failed tests with their assertion messages, per-IT `Time elapsed`, from `target/surefire-reports` and `target/failsafe-reports`, as Markdown for `$GITHUB_STEP_SUMMARY`)
- Modify: `docs/camel-kit/006-deployment/evidence/red-green-runs.md`

**Guides to Load:**
- `camel-test/guides/test-runner.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.14 T11; §3.15 job 1; T9

- [ ] **Step 1:** `test-inventory.sh [reports-root]`: lists every class under `src/test/java` named `*Test` or `*IT` (excluding abstract/support classes without `@Test`, detected by grep for `@Test`/`@ParameterizedTest`/`@CitrusTest`). Each must have a `TEST-<fqcn>.xml` in surefire (`*Test`) or failsafe (`*IT`) reports with `skipped="0" errors="0" failures="0"`. It prints `FAILED inventory: <class> <no report|skipped n|errors n|failures n>`, exit 1.
- [ ] **Step 2:** Red: copy `target/surefire-reports` and `target/failsafe-reports` (from the last `verify`) into `target/inventory-red/`, delete one IT's report, run against it. Expect it to name that class. Also run `./mvnw -q -DskipITs verify` then the inventory: it names every IT. Record both.
- [ ] **Step 3:** Green: `./mvnw -B verify`, then `deploy/ci/test-inventory.sh` exits 0; `deploy/ci/test-summary.sh` prints the per-IT table. Record.
- [ ] **Step 4:** Verify: `deploy/ci/test-inventory.sh && deploy/ci/test-summary.sh | grep -q 'KillIT' && echo T11-OK` prints `T11-OK`.

**Review:**
- [ ] Spec compliance: no class without a report, 0 skipped; summary shows assertion messages.
- [ ] Code quality: XML read with a robust tool (`xmllint` if present, else `grep -o` on the root `testsuite` attributes), strict bash.

---

### Task 37: D14 CI Gate Workflow

**Agent:** integration-architect

**Files:**
- Create: `.github/workflows/gate.yml`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.15 D14 (the five jobs); §3.16 CI rows; review decisions 4 and 8; P-11, P-12

- [ ] **Step 1:** Triggers `pull_request` and `push: branches: [main]`; no `pull_request_target`. Top-level `permissions: contents: read`; job 2 adds `actions: read` (baseline artifact), job 5 adds `packages: write`. `runs-on: ubuntu-24.04`. Every `uses:` pinned by full commit SHA with the tag as a comment. Resolve each SHA with `git ls-remote https://github.com/<owner>/<action> refs/tags/<tag>` (read-only network call): `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `actions/download-artifact`. No third-party action for Trivy/kind/kubeconform/actionlint.
- [ ] **Step 2:** Job 1 `build` (timeout 45): checkout; setup-java Temurin 25 with Maven cache; `./mvnw -B -ntp verify`; `deploy/ci/test-inventory.sh`; `deploy/ci/test-summary.sh >> $GITHUB_STEP_SUMMARY` (`if: always()`); upload `target/*.jar`, reports, `target/spec-tests/` (`if: always()`).
- [ ] **Step 3:** Job 2 `image` (needs build): download the jar; `docker build` (amd64); `deploy/ci/image-check.sh`; `docker save`; `deploy/ci/scan.sh image` and `scan.sh jar` with `--baseline` from the scan report artifact of the last green `main` run. The run id comes from `curl` on the REST API (`/repos/{repo}/actions/workflows/gate.yml/runs?branch=main&status=success&per_page=1`, `GITHUB_TOKEN`, job permission `actions: read`), and the artifact is fetched by `actions/download-artifact` with `run-id` and `github-token`. With no baseline, every finding is marked `NEW`. Then SBOM; upload image tar, SBOM and reports.
- [ ] **Step 4:** Job 3 `manifests`: actionlint through `deploy/local/tool.sh actionlint` (Docker on the runner); `kubectl kustomize deploy/k8s/ci` and `deploy/k8s/release` → `deploy/local/tool.sh kubeconform -strict -summary -kubernetes-version 1.34.0`. (T4 ran in job 1.)
- [ ] **Step 5:** Job 4 `kind` (needs image, manifests): install kind from its release binary with SHA-256 check (pinned version and checksum in the workflow); create the cluster; build `trailhead/lab-sftp:ci` and the fault image; `kind load` the release, fault and sftp images; `deploy/ci/smoke.sh` with `KUBE=kubectl`, `NS=trailhead-ci`, `APPLY_DIR=deploy/k8s/ci`; on failure `kind export logs` and upload them.
- [ ] **Step 6:** Job 5 `release` (needs kind; `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`; job `permissions: contents: read, packages: write`): `docker login ghcr.io` with `GITHUB_TOKEN`; tag and push `ghcr.io/${owner,,}/order-integration:${{ github.sha }}`; read the pushed digest (`docker buildx imagetools inspect --format '{{json .Manifest.Digest}}'`); `kustomize edit set image order-integration=ghcr.io/<owner>/order-integration@<digest>` in a copy of `deploy/k8s/release`; render `release.yaml`; fail if it contains the zero digest or `OWNER` (P-11); upload `release.yaml`, SBOM, scan report, `deploy/README.md` as artifact `release`.
- [ ] **Step 7:** No deployment job, no cluster credential, no repository secret besides `GITHUB_TOKEN`. Header comment: what each job proves, review decision 4 (no branch protection on this plan, `needs:` chain instead).
- [ ] **Step 8:** Verify: `deploy/local/tool.sh actionlint -color=never /repo/.github/workflows/gate.yml && ! grep -Eq 'uses: [^@]+@(v[0-9]|main|master)' .github/workflows/gate.yml && ! grep -q 'pull_request_target' .github/workflows/gate.yml && grep -q 'packages: write' .github/workflows/gate.yml && echo GATE-OK` prints `GATE-OK`.

**Review:**
- [ ] Spec compliance: five jobs, `needs:` 5 → 4 → 2,3 → 1; release only on `main` push; least privilege; all §3.15 failure messages.
- [ ] Code quality: actionlint clean; SHA pins; no secret echoed; `if: always()` only on reporting steps.

---

### Task 38: Runbook, Open Questions and Wave 6 Close

**Agent:** implementation-engineer

**Files:**
- Create: `deploy/README.md`
- Modify: `docs/open_questions.md`
- Modify: `README.md` (one pointer section to `deploy/README.md` and the CI gate)
- Modify: `docs/camel-kit/006-deployment/execution-report.md`
- Modify: `docs/camel-kit/006-deployment/evidence/build-times.md`

**Guides to Load:**
- `camel-implement/guides/orchestrator.md`
- `shared/camel-security-checklist.md`

**MCP Tools:**
- `camel_catalog_components(limit=0, B)` (binding only)

**Design Spec Section:** §3.12 D12 (every bullet); §3.9 (objects, keys); §3.16; §7 (OQ table, "What the developer's first CI run must report"); P-10

- [ ] **Step 1:** `deploy/README.md`, for the person who deploys (here the developer, "production" = `trailhead-prod`, review decision 6). It covers every §3.12 bullet with commands and what to check: before the first real delivery (OQ-093), Prepare (namespace label; ConfigMap keys = every non-secret `${…}` of `application-default.properties` plus `HOSTNAME` note; the three Secrets and their keys; the API key Secret), Deploy (check the digest against the CI run), Roll back, Rotate/register/change a setting, Crash loop, Lost node, Never scale above 1. Plus the Partner Configuration stand-in (P-10), the `.trivyignore.yaml` entry rule (§3.3) and the §3.16 rule that no session ever gets the `trailhead-prod` kubeconfig. Commands use plain `kubectl` because they are for the person.
- [ ] **Step 2:** `docs/open_questions.md`: add OQ-087 to OQ-095 in the file's existing entry format, with the question, the answer taken and the reason from §7, plus the review's changes (OQ-090 answered, OQ-093 open only for the Warehouse lead, OQ-094 fixed). Mark OQ-078 and OQ-060 answers as proposed by design 006.
- [ ] **Step 3:** `README.md`: a short "Deployment" section pointing to `deploy/README.md`, `.github/workflows/gate.yml` and the Java 25 requirement.
- [ ] **Step 4:** Final `/usr/bin/time -p ./mvnw -B verify`; `deploy/ci/test-inventory.sh`; T5 re-run; actionlint. Record "Final" in `build-times.md`.
- [ ] **Step 5:** Append `## Wave 6 handoff`, then `## For the developer's first CI run`, which copies the six report items of §7 as a checklist, plus "push the branch, open a PR, then merge to `main`" (the developer does it; the agent does not push). End the report with the summary of every red/green row and every P-17 finding.
- [ ] **Step 6:** Verify: `grep -q 'Never scale' deploy/README.md && grep -q 'OQ-095' docs/open_questions.md && grep -q 'deploy/README.md' README.md && deploy/ci/test-inventory.sh && grep -q "developer's first CI run" docs/camel-kit/006-deployment/execution-report.md && echo WAVE-6-OK` prints `WAVE-6-OK` (after a `verify` with `BUILD SUCCESS`).

**Review:**
- [ ] Spec compliance: every runbook bullet; every OQ of §7; the CI-run checklist.
- [ ] Code quality: no secret or real credential in docs; commands correct for the manifests as built.

---

## Runtime Acceptance (performed by camel-execute in each wave's close task)

Waves 1–4 and 6: `./mvnw -B verify` with Docker running. That covers every unit test (existing plus T1, T4, T7, T10),
the YAML validator, the five spec ITs and the matrix, and the failure ITs including T6, T8, T9, T13 and T14. Wave 4
adds T2, T3 (image and jar) and T5. Wave 5 adds T12 in `trailhead-lab` and A1–A10. Wave 6 adds T11 and actionlint. The
execution report carries every row of `evidence/red-green-runs.md` (each test's red run with test, assertion and message,
then its green run), `evidence/build-times.md` (before, after D10, final), `evidence/deploy-after-runs.md`, the A-9
outcome (P-3), and every P-17 finding. The CI items of §7 are for the developer's run; no agent pushes or starts a
run.
