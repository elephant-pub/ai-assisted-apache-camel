---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-16T12:56:13.081237Z"
  by: "camel-brainstorm"
---
# Trailhead Order Integration: Deployment (006) — Design Spec

**Date:** 2026-11-16
**Camel Version:** 4.22.2
**Runtime:** Spring Boot 4.1.1
**Platform BOM Version:** org.apache.camel.springboot:camel-spring-boot-bom:4.22.2
**Pipeline:** 006-deployment (standalone; no plan is started from here)
**Status:** Approved 2026-11-16 by the integration architect, with the eight changes of
[`design-review.md`](design-review.md), which are written into this spec (marked *review n*).
**Inputs:** the spec (`docs/requirements.md`, `docs/use_cases/`, `docs/open_questions.md`, `docs/constitution.md`), the
code at `f091fbb`, `docs/camel-kit/005-hardening/` (design, validation decisions 4 and 6, developer checks), and the
runs of [`evidence/deploy-lab-runs.md`](evidence/deploy-lab-runs.md) (L0–L7) in the kind cluster `trailhead-lab`, and
the design review of 2026-11-16 ([`design-review.md`](design-review.md)).

This design starts from how a deployment fails. §3.1 lists every case of the process in a container and in a pod:
built, configured, started, stopped, killed, probed, scaled. For each: what happens today (from a lab run where a run
is needed, otherwise from the code named), what the spec requires, and what changes. Only then come the changes
(§3.2–§3.13), the tests that prove them (§3.14), the CI gate (§3.15), who may do what where (§3.16), the two delivery
fixes the review moved into this pipeline (§3.17, §3.18) and how execute runs (§3.19). Where the spec
says nothing, no rule is invented: §7 has an open question with the recommended answer, chosen to keep the spec's
guarantees — no lost order (NFR-007), no second pick list (NFR-006), the contract's answer during an outage (FR-030).

The integration stays the Spring Boot jar it is. The image wraps that jar; nothing builds it for us.

## Not Doing (and Why)

- **Monitoring, metrics, dashboards, alerts**: the next pipeline. The probes here are for Kubernetes, not for people.
  Where this design leaves something for a person to notice (a crash loop, a pending pick list), §3.1 names the signal
  the monitoring pipeline will hook into.
- **UC-005, UC-006, UC-008, load tests, a real cloud**: out of scope by the request. Memory and CPU sizes (§3.7) come
  from the lab's measurements under light load, not from a load test.
- **A second instance, autoscaling, leader election**: the outbox pollers are correct only with one instance (L7:
  152 of 200 emails sent twice with two pods), and the catalog limit of 20 calls a second (C-010) is per process. One
  instance is the deployment's invariant (§3.6, OQ-087). Leader election is the way to more instances later.
- **Helm, an operator, Knative, another runtime, a buildpack**: the request rules out operators and other runtimes;
  plain manifests rendered with `kubectl kustomize` need no new tool (and `helm` is denied in this project).
- **The Ingress or load balancer in front of the Service, cert-manager, an external secrets operator, an egress
  NetworkPolicy**: they belong to the target cluster, which nobody here can reach. The release states what they must
  do (TLS passed through to the pod, OQ-078; the Secrets it expects, §3.9) and ships an ingress-only NetworkPolicy.
- **TLS and authentication to Kafka and the Product Catalog** (OQ-038, OQ-039): still out of scope, as in 005. Until
  OQ-038 is answered the release can reach a plain-text broker only, which Peakmart's real topic is unlikely to be.
- **Confirming the Warehouse folder names**: F-1 and F-2 are fixed here (*review 1, 2*; §3.17, §3.18), but which
  folders the Warehouse SFTP login sees is the Warehouse lead's to confirm (OQ-093 stays open for that only).
- **Image signing**: it needs a key and a registry policy in the target cluster; the release names its image by
  digest, which is what a person verifies before deploying. An SBOM is produced (§3.15).
- **A multi-architecture image**: CI builds `linux/amd64`; the lab builds `linux/arm64` locally from the same
  Dockerfile.

---

## 1. Executive Summary

### Business Purpose

The integration is correct as a process: 005 proved that a kill at any step loses no order and delivers no second pick
list. The lab shows that the way it would be deployed today breaks those guarantees or the partner's answer in ways the
build cannot see:

1. **Two pods send emails twice and are one race away from a second pick list** (L7). The pick-list route says
   "exactly once rests on … one instance"; the default Deployment gives two pods during every rollout.
2. **A rollout with a wrong secret takes the partner endpoint down for good** (L4: 109 of 149 requests refused). With no
   readiness probe the old pod is stopped as soon as the new container runs, and the new one crash-loops.
3. **Traffic reaches a pod that is not ready**: 5 s of refused connections and 1.1 s of HTTP 404 during every rollout
   (L2), because Tomcat listens before the Camel routes exist.
4. **The image** runs Java 21 on a base pulled by tag, lets the process rewrite its own jar, and holds 4 CRITICAL and
   13 HIGH known vulnerabilities in the jar, all with a fix (L0). Under the "restricted" Pod Security profile it does not
   start (L4: read-only `/tmp`).
5. **A memory limit without a heap setting** gets the pod OOMKilled in a loop (L6); without a limit the heap is sized
   from the node.
6. **A restart after exit code 70** happens (the kubelet restarts the container) and loses nothing (L6: 20 of 20), but
   the Peakmart intake waits ~42 s for the dead consumer's Kafka session on every restart.
7. **The first real Warehouse SFTP server can break NFR-006 or stop delivery** (F-1, F-2): if its login directory is not
   `/`, the upload and the "already delivered?" check look at different folders and a retry sends a second pick list;
   a server that never starts the SFTP subsystem holds the pick-list poller with no time limit.

Nothing builds, scans or deploys the release automatically yet, and nothing says who may deploy what.

### Business Value

- The release (an image by digest and its manifests) is produced by a CI gate that runs everything `./mvnw verify`
  runs, scans, validates, deploys to a kind cluster and smoke-tests it, and says why when it fails.
- Production stays out of reach of every agent: a person deploys the release (§3.16).
- The deployment keeps NFR-006 and NFR-007 (one instance, durable state, restart on every exit) and FR-030 (probes
  that do not take the pod away during an outage the integration answers itself).

### Stakeholders

Integration Team (deploys and runs it; the runbook, §3.12), Partners (the answer during restarts, OQ-088), Order Desk
and Warehouse Team (no second email or pick list), the developer (pushes the workflow and reports the CI run, §7), spec
owner (OQ-087 to OQ-095).

---

## 2. Systems Landscape

| System | Role in this design | Reached by | In the lab (L0–L7) | In the CI kind cluster |
|---|---|---|---|---|
| The integration process | the jar in a container, one pod | — | `trailhead/order-integration:today` | the release image, by digest |
| Kubernetes | runs, restarts, probes and replaces the pod | the person deploying (production); `deploy/local/kube.sh` (lab) | kind `trailhead-lab`, v1.34.0, 1 node | kind in the runner |
| Order Database | PostgreSQL 16 (C-004), TLS `verify-full` | `sql`, JDBC, Flyway | PostgreSQL 16 with TLS and a volume | the same manifests as the lab |
| Peakmart order topic | Kafka (C-005) | `kafka` | Kafka 4.3.1 KRaft | same |
| Product Catalog | HTTP (C-010) | `http` | stub serving the product examples | same |
| Warehouse SFTP Server | SFTP (C-011) | `sftp`, JSch | native OpenSSH, chroot | same |
| Mail server | SMTP, STARTTLS required (OQ-076) | `smtp` | Mailpit, STARTTLS, any login | same |
| Partners | HTTPS (C-009, NFR-010) | `platform-http` | curl in a client pod | the smoke test |
| Container registry | holds the release image | CI only (publish job) | none (`kind load`) | none (`kind load`) |
| GitHub Actions | the gate and the release | the developer pushes; no agent | — | — |

---

## 3. Failure-Mode Analysis and Design

| Field | Value |
|---|---|
| Target Module | (project root) |

**How "today" is known.** *(Lx)* is a lab run of [`evidence/deploy-lab-runs.md`](evidence/deploy-lab-runs.md),
against the image of `src/main/docker/Dockerfile` deployed with only what the jar needs (no profile, no probes, no
resources, default Deployment). *(code)* is read from the file named. *(not run)* says why a run was not possible.

### 3.1 Failure modes

#### 3.1.1 Built — what the image holds

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| B1 | Base image | `ubi9/openjdk-21-runtime:1.24` by tag; Java 21.0.13 *(L0)* | request: Temurin 25 JRE pinned by digest; C-003 amended to Java 25, Eclipse Temurin (OQ-090, *review 3*) | **D1**: `eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f788…968c`; `java.version` 25 |
| B2 | User and file ownership | uid 185, gid 0; the application files are owned by 185, so the process can rewrite its own jar and libraries *(L0)* | C-024, constitution 6 (no secret in the image) | **D1**: numeric non-root user 10001; application files owned by root, read-only; the only writable path is `/tmp` (an `emptyDir`, D8) |
| B3 | What else is inside | curl, python3, microdnf, rpm, bash *(L0)*. No test, fault-hook or `config/` class or file *(L0; 005 developer check 4)* | — | **D1**: the Temurin JRE base has bash and apt-get, no curl/python *(L0)*; the image check (T2) asserts no test, fault or `config/` file |
| B4 | Known vulnerabilities | jar: 4 CRITICAL (Tomcat 11.0.24), 13 HIGH (Jackson 2.21.5/3.1.5, snappy-java, lz4-java), all fixable; OS: 27 HIGH fixable *(L0)*. Temurin base alone: 0 HIGH/CRITICAL *(L0)* | none stated (OQ-091) | **D2**: the gate fails on any HIGH or CRITICAL finding that has a fix, in the image and in the jar; patch upgrades of managed versions inside C-001/C-002 |
| B5 | Heap and exit on OOM | `java -jar` with no heap option: `MaxHeapSize` 3.9 GB from the node *(L6)* | NFR-007 | **D6**: `-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError` in the entrypoint |
| B6 | Reproducible identity | image by tag only; nothing records what was released | request: the release is the image by digest | **D14**: CI pushes once and the manifests name `…@sha256:<digest>`; never a tag |

#### 3.1.2 Configured

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| C1 | A setting missing (a ConfigMap/Secret not referenced, a key absent) | exit 1 in 2 s, `Missing environment variables:` with all 26 names, sorted, no values *(L4)* | constitution 6; `RequiredVariablesCheck` (003) | none in the jar: it fails fast and names every variable. With one pod (D4) the old pod is gone before the new one fails, so the remedy is the runbook's rollback (§3.12), and the CI smoke test (D14) catches a release whose manifests miss a key |
| C2 | A wrong secret (DB password) | Flyway fails, exit 1, CrashLoopBackOff; the old pod had already been removed: **total outage**, 109 of 149 refused *(L4)* | NFR-008 | **D4** keeps one pod, so a bad release still stops the endpoint until rolled back (OQ-087's price). **D14**: the CI smoke test deploys the same manifests first; **D12** runbook: rollback is one command; the crash message names the cause |
| C3 | The dev settings | `SPRING_PROFILES_ACTIVE=dev` refuses to start (the dev file is not in the image) with a misleading message *(L4)*. A file under the working directory's `config/` would be read by Spring *(code: Spring Boot `./config/`)* | constitution 6; C-024 (TLS) | **D9**: the release never sets a profile and mounts nothing under the working directory; T4 (manifest policy) fails on either |
| C4 | A Partner Configuration change (NFR-012) | the mounted file changes after ~80 s; the registry and the `partner` row do not, until a restart *(L4)* | NFR-012 (released change only), UC-007 (registered at start), OQ-029 | **D9**: the ConfigMap is generated from the released file with a content hash in its name, so a changed file is a new pod template: Kubernetes restarts the pod and the registration runs |
| C5 | A partner's API Key created or rotated in the Secret Store | read at start only *(code: `PartnerRegistration.afterPropertiesSet`)*; same mechanism as C4 | OQ-028, OQ-060, UC-007 BR-003 | **D9**: the Secret Store is a Kubernetes Secret mounted as a directory (OQ-060 reading a); a change takes effect with a restart, which the runbook gives (§3.12) |
| C6 | TLS on the partner endpoint | the jar terminates TLS (PEM bundle), 1.2 and 1.3 accepted, plain HTTP gets 400 *(L4)* | NFR-010, C-024, OQ-078 | **D9**: TLS ends at the pod (OQ-078); anything in front passes TCP through. Certificate and key from a Secret; a rotation is a restart |
| C7 | Restricted security context | crash: `/tmp/tomcat.8443…: Read-only file system`; outage (the old pod was gone) *(L4)* | — (cluster policy) | **D8**: `/tmp` is an `emptyDir`; the namespace enforces Pod Security "restricted" |

#### 3.1.3 Started

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| S1 | Traffic before the endpoint listens | refused for ~5 s while the JVM starts *(L1, L2)* | NFR-008 | **D3**: readiness probe; the Service sends traffic only to a ready pod. With one pod, a partner still gets a refused connection while no pod is ready (OQ-088) |
| S2 | Traffic after Tomcat, before the routes | **HTTP 404** for 1.1 s *(L1, L2: 6 × 404)* — not a contract answer (OQ-037) | OQ-037, FR-004 | **D3**: readiness from Spring's readiness state, which turns UP at `ApplicationReadyEvent`, after Camel has started *(code: Spring Boot availability)*; after-run A2 checks 0 × 404 |
| S3 | Kafka records, polls before the registry and schema are ready | not possible: Flyway, then partner registration, then the routes *(code: `@DependsOn("flywayInitializer")`, routes start after refresh; L1 timeline)* | UC-007 | none |
| S4 | A long Flyway migration | runs before Tomcat; no probe today | 005 decision 3 | **D3**: a startup probe allows 5 minutes before the liveness probe applies |

#### 3.1.4 Stopped

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| T1 | SIGTERM while orders are in processing | graceful in 1.5 s; the consumer leaves the group; 200 of 200 recorded once, 200 pick lists *(L3)* | NFR-006, NFR-007 | none in behaviour |
| T2 | A stop that takes longer than the grace period | Kubernetes kills at 30 s; Camel's own shutdown timeout is 45 s *(code: Camel default; the pod default)*, so Camel never gets to log the in-flight exchanges it forces. The kill itself is safe (005 KillIT) | NFR-007 | **D5**: `terminationGracePeriodSeconds: 90` ≥ Spring's 30 s web phase + Camel's 45 s; `server.shutdown=graceful` set explicitly; T4 checks the sum |
| T3 | A rollout | the default Deployment surges a second pod: two instances for a moment (S7) and an outage when the new one fails (C2) *(L2, L4)* | NFR-006, NFR-008 | **D4**: one pod, replaced stop-then-start (StatefulSet, 1 replica). About 10–15 s of refused connections per release (OQ-088) |

#### 3.1.5 Killed

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| K1 | OOMKilled (a memory limit the JVM does not know) | 256 MiB and no heap option: OOMKilled, exit 137, loop; 300 of 300 recorded once afterwards *(L6)* | NFR-007 | **D6**: memory request = limit = 1 GiB, heap at most 50 % of it; measured 418 MB resident without a limit *(L6)* |
| K2 | Heap exhausted in a thread H1 does not wrap (Kafka client, a pool) | the thread dies and the process runs on without it *(code: 005 §3.2 "an Error thrown outside a step is not seen by H1")* | NFR-007, OQ-084 | **D6**: `-XX:+ExitOnOutOfMemoryError`: any `OutOfMemoryError` the JVM throws stops the process (exit 3), and the kubelet restarts it |
| K3 | Exit code 70 from H1 | the kubelet restarts the container (restartCount 2, last exit 70); 20 of 20 recorded once after the fault was removed *(L6)*. A fault that repeats on the same event is a crash loop: the event is redelivered on every start *(L6)* | OQ-084 ("restart is deployment's"), NFR-007 | **D4**: `restartPolicy: Always` (a StatefulSet's only policy); the smoke test shows the restart and no loss on every CI run (§3.15). The loop stays a loop (OQ-089) |
| K4 | Restart delay of the Peakmart intake | ~42 s after every restart before the consumer gets its partitions (the dead member's 45 s session) *(L6)*; the same wait costs `KillIT` most of its 387 s *(005 build-times; local build)* | NFR-001 (15 min) | **D10**: Kafka static membership, `groupInstanceId` = the pod name, stable across restarts and rollouts of a StatefulSet |
| K5 | The node is lost | *(not run: one node)*. A Deployment's pod is replaced after the 300 s unreachable toleration while the old process may still run on a partitioned node: two instances | NFR-006, NFR-008 | **D4**: a StatefulSet does not start a second `order-integration-0` until the old one is known to be gone; a person confirms the node is gone (runbook, §3.12). Minutes of outage instead of a duplicate (OQ-087) |
| K6 | SIGKILL at any step | no lost order, no second pick list *(005 KillIT, in every build)* | NFR-006, NFR-007 | none |

#### 3.1.6 Probed

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| P1 | No probe at all | the pod is "ready" when the container runs (S1, S2, C2) *(L2, L4)*; the actuator is not on HTTP (`management.server.port=-1`, design 003) | — | **D3**: actuator **health only**, on its own port 9090, plain HTTP, not in the Service; startup, liveness and readiness probes |
| P2 | Kafka down | `/actuator/health` DOWN; readiness and liveness groups UP; a partner order accepted *(L5)* | FR-043 (alert, UC-006), C-008 | **D3**: probes use the readiness and liveness groups only, which hold Spring's availability states and nothing else (pinned, T6) |
| P3 | Order Database down | `/actuator/health` DOWN (`db`); groups UP; the partner gets the contract's 503 in 2.1–3.6 s *(L5)* | FR-030, OQ-037, NFR-002 | as P2: the pod stays ready, so the partner keeps getting the contract answer and not a refused connection; liveness never restarts a healthy process for an outside outage |
| P4 | Product Catalog down | `/actuator/health` DOWN; groups UP; 503 contract answer in 0.04 s *(L5)* | FR-029, FR-030 | as P2 |
| P5 | Recovery | `camelHealth` stays DOWN ~35 s after the database is back *(L5)* | — | none: not used by a probe |
| P6 | A hung poller (F-1) | not seen by any probe; the process is otherwise healthy *(L-setup, F-1)* | NFR-001 | **D17** (W2): every SFTP call has the time limit, so the attempt fails, is counted and retried. Still no probe for it: a liveness probe that restarted on it would hide the defect |
#### 3.1.7 Scaled

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| S5 | Two pods, emails | **152 of 200** Order Desk Notifications sent twice; 0 of 30 in a run where the polls did not overlap *(L7)* | FR-031/FR-006 ("an" email), NFR-014 | **D4**: one pod |
| S6 | Two pods, pick lists | 0 of 300 received twice in the runs; the race exists *(code: `pick-list-select.sql` takes no claim; route comment "one instance")* | NFR-006 (0 duplicates) | **D4** |
| S7 | Two pods during a rollout | the default Deployment surges a second pod *(L2)* | NFR-006 | **D4**: stop-then-start; T4 forbids a Deployment, more than one replica and an autoscaler |
| S8 | Two pods, catalog load | each pod throttles to 20 calls/s, so two may make 40 *(code: `catalog.max-calls-per-second`)* | C-010 | **D4** |

#### 3.1.8 Delivered — the first real Warehouse SFTP server (*review 1, 2*)

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| W1 | Login directory not `/` (F-2) | Camel's sftp producer drops the leading `/` and writes under the login directory; `WarehouseSftp` stats the configured absolute path. Every check misses; a retry after an upload whose recording failed delivers a second pick list *(L-setup log line; code: `WarehouseSftp.deliveredFolder`, `to-warehouse-sftp`)* | NFR-006, C-011 | **D16**: upload and check resolve the configured folders the same way, whatever the login directory |
| W2 | Server accepts the login, never starts the SFTP subsystem (F-1) | `ChannelSftp` start waits >136 s with `warehouse.sftp.timeout-ms` at 10 s; nothing logged, `attempts` stays 0, no pick list moves until a restart *(L-setup thread dump)* | NFR-001 | **D17**: every SFTP call, the channel start included, ends within `warehouse.sftp.timeout-ms`; the attempt fails, is counted with its `last_failure`, and the next poll tries again |

### 3.2 D1 — The image

A new `src/main/docker/Dockerfile` replaces the one `camel export` wrote:

- Both stages `FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c`
  (the digest `docker build` resolved on 2026-11-16). It is the multi-architecture index digest (`docker buildx
  imagetools inspect`, *review*), so the amd64 runner and the arm64 lab resolve the same pinned base. The first stage extracts the Spring Boot layers
  (`java -Djarmode=tools -jar … extract --layers`), as today.
- `/app` holds the layers, owned by `root:root`, mode `a-w`. `USER 10001:10001` (numeric, so `runAsNonRoot` can verify
  it). `WORKDIR /app`, and no `config/` folder in it.
- `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/application.jar"]`
  (D6). No shell form, so SIGTERM reaches the JVM as PID 1.
- `pom.xml`: `java.version` 25. The Jib profile (`allowInsecureRegistries`, an entrypoint of its own) is removed: one way
  to build the image.

**Rationale.** The request fixes the base and the Java version. Root-owned, read-only application files mean a
compromised process cannot plant code that survives in the container; the restricted profile (D8) needs a numeric
user. **Constraints.** C-003 is amended to Java 25, Eclipse Temurin, by the spec owner (OQ-090, *review 3*); T1 and
T2 check it. The image holds no secret and no setting
(constitution 6); everything comes from the pod (D9).

### 3.3 D2 — Known vulnerabilities: a stated threshold

- **Threshold.** The gate fails when Trivy reports a **HIGH or CRITICAL vulnerability that has a fixed version**, in
  the image (OS packages and the jar's libraries) or in the jar alone: `--severity HIGH,CRITICAL --ignore-unfixed
  --exit-code 1`. Findings without a fix and MEDIUM/LOW are reported in the job summary and do not fail it.
- **Exceptions** go into `.trivyignore.yaml` only: the vulnerability id, the package, the reason, an expiry date at most
  90 days out, and who accepted it. The gate fails on an expired entry. Adding one is a reviewed change (§3.16).
- **Today's findings** (L0) are fixed by patch upgrades of versions the Spring Boot and Camel BOMs manage, set as Maven
  properties (`tomcat.version` 11.0.25, Jackson 2.21.7 and 3.1.7, snappy-java 1.1.10.9, lz4-java 1.11.4), each with a
  comment naming the CVE. They stay inside C-001 (Camel 4.22) and C-002 (Spring Boot 4.1). If a newer Spring Boot 4.1.x
  already manages them, the plan takes that patch release instead and removes the properties.
- The scanner is the Trivy image of `deploy/local/tool.sh`, by digest, locally and in CI. The dependency scan reads the
  built jar (`trivy rootfs --offline-scan`): `trivy fs pom.xml` needs Maven Central and got 429 (L0).
- **A gate that turns red with no code change is expected** (*review 8*): the vulnerability database changes daily.
  The job summary says which finding is new since the last green run (T3 compares against the scan report artifact of
  that run), and the fix or the expiring exception is a reviewed change, as above.

**Rationale.** "Fixable" is the line a person can act on today; a threshold that fails on findings nobody can fix only
teaches people to ignore the gate. **Constraints.** The upgrades must pass `./mvnw verify` unchanged; a fix that needs a
minor or major version leaves the constraint and becomes a reviewed exception.

### 3.4 D3 — Health endpoints and probes

`application.properties` (replaces `management.server.port=-1` of design 003 C-22: the partner port still serves only
the partner endpoint):

```properties
# Design 006 D3: health only, on a port of its own, plain HTTP inside the pod network, never in the Service
management.server.port=9090
management.server.ssl.enabled=false
management.endpoints.web.exposure.include=health
management.endpoint.health.probes.enabled=true
management.endpoint.health.show-details=never
# Pinned: an outside outage must never take the pod out of the Service (FR-030) or restart it (lab L5)
management.endpoint.health.group.readiness.include=readinessState
management.endpoint.health.group.liveness.include=livenessState
server.shutdown=graceful
```

Probes (the pod, D4):

| Probe | Path (port 9090) | Settings | Why |
|---|---|---|---|
| startup | `/actuator/health/liveness` | period 5 s, failureThreshold 60 (5 min) | Flyway runs before the web server (S4) |
| liveness | `/actuator/health/liveness` | period 10 s, timeout 2 s, failureThreshold 3 | restarts a process whose management server no longer answers; never for an outside outage (P2–P4) |
| readiness | `/actuator/health/readiness` | period 5 s, failureThreshold 2 | no traffic before the routes exist (S1, S2) or while the context is stopping |

**Rationale.** L5 shows that `/actuator/health` is DOWN in every outage, including the catalog's, while the
integration itself answers partners correctly. A probe on it would replace the contract's 503 by a refused connection
and, as liveness, restart the process in a loop during a database outage. The groups hold only Spring's availability
states, which are what Kubernetes needs. **Constraints.** Port 9090 is plain HTTP and carries only `UP`/`DOWN`; the
NetworkPolicy (D8) admits it only from inside the namespace and the node.

### 3.5 D4 — One instance

The integration runs as a **StatefulSet `order-integration` with `replicas: 1`**, `podManagementPolicy: OrderedReady`,
`updateStrategy: RollingUpdate` (with one replica: the old pod is stopped and deleted before the new one is created).
`serviceName` is a headless Service; the partner Service `order-integration` (ClusterIP, port 8443) selects the pod.
No Deployment, no HorizontalPodAutoscaler, no PodDisruptionBudget (a drain moves the pod, with the same ~15 s gap as a
release).

**Rationale.** Today's correctness rests on one instance: the outbox pollers take no claim (L7, S5, S6) and the catalog
limit is per process (S8). A StatefulSet guarantees at most one `order-integration-0`: during a rollout (stop, then
start) and after a node loss, where a Deployment would start a replacement while the old process may still run (K5).
The price is availability: every release, drain or crash is a gap of refused connections (L3: ~5 s on a warm node;
~10–15 s expected with the image start), and a lost node needs a person (OQ-087, OQ-088). NFR-008's 99.5 % leaves
3.6 h a month. **Constraints.** A person must never scale it above 1; T4 fails on a manifest that does, and the runbook
says so. Leader election for the four pollers is the way to two instances later (Not Doing).

### 3.6 D5 — Stopping

`terminationGracePeriodSeconds: 90`: Spring's graceful web shutdown (`spring.lifecycle.timeout-per-shutdown-phase`,
30 s by default) runs first, then Camel's, and Kubernetes kills only after both. Camel's timeout is written down instead
of left to its default, so that T4 can compare it:

```properties
# Design 006 D5: Camel's graceful shutdown (its default); the pod's grace period (90 s) covers 30 s web + this
camel.main.shutdown-timeout=45
```

No `preStop` sleep: with one pod there is no other endpoint to drain to. **Rationale.** T1 is fine today; T2 is the
case where Camel's own forced shutdown, which logs the in-flight exchanges it gives up, should come before the kill. A
kill stays safe (K6).

### 3.7 D6 — Memory, CPU and the JVM

`resources: {requests: {cpu: 500m, memory: 1Gi}, limits: {memory: 1Gi}}`, no CPU limit (throttling would stretch
NFR-002's 10 s; the request guarantees the share). Heap at most 50 % of the limit (512 MiB); the rest is metaspace,
threads, Kafka and Tomcat buffers. Measured without a limit: 418 MB resident with 76 MB of heap in use (L6).
`-XX:+ExitOnOutOfMemoryError` (K2): an `OutOfMemoryError` the JVM raises ends the process with exit code 3 before
anything else runs; H1 still halts with 70 for one thrown inside a step by other means (005 T3/T7 keep their codes).

**Rationale.** A limit the JVM does not know is an OOMKill loop (L6). Memory request = limit means the pod is never
evicted for using more memory than it asked for. With a CPU request and no CPU limit the QoS class is **Burstable**, not
Guaranteed (corrected 2026-11-16, developer check 9 of execute, finding W4-F4: Burstable accepted, so that there is no
CPU throttling). **Constraints.** No load test (Not Doing); after-run A6 checks the 500-order burst of L7
under the limit, and the monitoring pipeline will watch the real use.

### 3.8 D8 — Security context and network

- Pod: `runAsNonRoot: true`, `runAsUser: 10001`, `runAsGroup: 10001`, `seccompProfile: RuntimeDefault`,
  `automountServiceAccountToken: false` on its own ServiceAccount (the process never talks to the API server).
- Container: `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities: {drop: [ALL]}`; `/tmp`
  is an `emptyDir` with `sizeLimit: 256Mi` (C7).
- Namespace label `pod-security.kubernetes.io/enforce: restricted` (in the CI kind cluster; the release documents it
  for the target namespace).
- NetworkPolicy: ingress to 8443 from anywhere (partners, through the load balancer) and to 9090 from the namespace
  only; no egress rule (the hosts are the target environment's, Not Doing).

### 3.9 D9 — Settings, secrets, the Secret Store and the Partner Configuration in the cluster

| What | Kubernetes object | Mounted as | Who creates it | Change takes effect |
|---|---|---|---|---|
| Hosts, ports, addresses (every non-secret `${…}` of `application-default.properties`) | ConfigMap `order-integration-settings` | `envFrom` | the person deploying, per environment | a restart (`kubectl rollout restart`) |
| `DB_PASSWORD`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `WAREHOUSE_SFTP_PASSWORD` | Secret `order-integration-secrets` | `envFrom` | the person deploying | a restart |
| Partner endpoint certificate and key (PEM), Order Database CA, Warehouse known_hosts | Secret `order-integration-files` | files under `/etc/trailhead/files` | the person deploying | a restart |
| **The Secret Store** (OQ-028, OQ-060) | Secret `order-integration-partner-api-keys`, one key per `apiKeyName`, value = the API Key | the directory `SECRET_STORE_DIR=/etc/trailhead/secret-store` | the Integration Team member registering the partner (UC-007 step 1), with their own cluster access; never in the repository, the pipeline or the release | a restart (C5) |
| **The Partner Configuration** (NFR-012, OQ-029) | ConfigMap generated by kustomize from `deploy/k8s/base/partner-configuration.json`, name with a content hash | `PARTNER_CONFIGURATION=/etc/trailhead/partners/partner-configuration.json` | a reviewed change in the repository, released by CI | **automatically**: a new hash is a new pod template, so deploying the release restarts the pod and UC-007 registration runs (C4) |

**The Secret Store in the cluster** is that Secret, mounted as a directory: exactly the shape `DirectorySecretStore`
reads ("one file per API Key, named by apiKeyName"), so no code changes. The Partner Configuration names the key
(OQ-060 reading a, as built). A missing key is UC-007 A4 (the partner is not registered, logged). **Rationale:** it is
the cluster's own secret store; a vault later can mount the same directory shape (CSI driver) without a code change.

**The dev settings never reach a pod** (C3): the release sets no `SPRING_PROFILES_ACTIVE` and mounts nothing under
`/app` (the working directory, whose `config/` Spring would read). `JAVA_TOOL_OPTIONS` is not set either: a truststore
there would replace the JVM's own (the lab needed one only for Mailpit's lab certificate; OQ-076 decides the real mail
server).

**TLS (OQ-078): it ends at the pod.** The integration keeps terminating TLS 1.2/1.3 itself (L4). Whatever the target
cluster puts in front (a load balancer, an ingress) must pass the TCP connection through. **Rationale:** NFR-010 and
C-024 then hold by the integration's own configuration, which the build and the smoke test check, and no hop inside the
cluster carries personal data in plain text. The alternative (termination at a load balancer) would make NFR-010 depend
on a system nobody here can test, and re-encrypting to the pod would need the same certificate handling anyway.

### 3.10 D10 — Kafka static membership

```properties
# Design 006 D10: the pod name; a restarted or replaced order-integration-0 takes its partitions back at once
peakmart.group-instance-id=${HOSTNAME}
```

in `application-default.properties` (Kubernetes sets `HOSTNAME` to the pod name), `peakmart.group-instance-id=dev` in
`config/application-dev.properties`, and a per-run fixed value in the spec-test profile (the harness restarts one
process at a time). The intake endpoint gets `groupInstanceId: "{{peakmart.group-instance-id}}"` (catalog-verified,
§5).

**Rationale.** K4: every restart (exit 70, OOMKilled, a release) waits ~42 s for the dead member's session; with a
stable instance id the new member replaces the old at once. With one pod (D4) the id is unique; a second process with
the same id is fenced by the broker, which is one more guard against two instances reading the topic. **Constraints.**
A static member does not leave the group on a graceful stop, so a stop without a restart holds its partitions until the
45 s session ends; with one pod that is the same gap as today. Offsets, `breakOnFirstError` and the duplicate rule are
unchanged, so NFR-006/NFR-007 are unchanged.

### 3.11 D11 — Routes validated against the Camel catalog in the build

- `camel-yaml-dsl-validator-maven-plugin` (Camel 4.22.2), goal `validate` at `process-classes`, `failOnError=true`,
  `onlyCamelYamlExt=true`: every `*.camel.yaml` against the YAML DSL schema.
- `RouteCatalogTest` (unit): every endpoint of `src/main/resources/camel/*.camel.yaml` (`uri` plus `parameters`) is
  validated with `camel-catalog` 4.22.2 (`validateEndpointProperties`): unknown component, unknown option, wrong type.
  Property placeholders count as valid values. `camel-report-maven-plugin:validate` reads Java and XML sources (its
  4.22 documentation names no YAML), so it is not used.

Both run inside `./mvnw verify`, so the gate validates the routes by running the build.

### 3.12 D12 — The release runbook (`deploy/README.md`)

For the person who deploys. Each step with the command and what to check. For this pipeline that person is the
developer, and "production" is a second local kind cluster, `trailhead-prod`, which the developer creates and deploys
to with this runbook, step by step, from the release artifact of a green `main` run (*review 6*, §3.16).

- **Before the first real delivery**: the Warehouse lead has confirmed the inbound and processed folder names as the
  SFTP login sees them (OQ-093); they are set in the ConfigMap as given (D16).

- **Prepare** the namespace (label "restricted"), the ConfigMap and the three Secrets of §3.9 (keys listed; a missing one
  stops the start and names it, C1).
- **Deploy** a release: check the digest in `release.yaml` against the CI run, `kubectl apply -f release.yaml`, wait for
  `order-integration-0` ready; partners see ~15 s of refused connections (OQ-088).
- **Roll back**: apply the previous release's `release.yaml` (C2).
- **Rotate** a secret or an API Key, **register a partner** (a released Partner Configuration plus the key in the Secret
  Store, the key first), **change a setting**: the object, then `kubectl rollout restart statefulset/order-integration`.
- **A crash loop** (`CrashLoopBackOff`): the last log line names the cause (`Missing environment variables`, Flyway,
  `Fatal error … the process stops`); for a repeating fatal error on the same event see OQ-089.
- **A lost node**: confirm the node is gone, then delete the pod (`--force --grace-period=0`) so the StatefulSet starts
  it elsewhere — never before (K5).
- **Never scale above 1** (D4).

### 3.13 D13 — Failure ITs report the failed assertion (005 decision 4, Q-8)

Today a failing failure IT reports `CitrusRuntimeException: Test failed` with no assertion in the surefire report
(005 developer check 7e). Each failure IT's checks raise an `AssertionError` whose message names the check, the
expected and the actual value, and the order number; the Citrus wrapper keeps it as the reported message. The gate's
summary (§3.15) prints it.

### 3.14 Tests: every change fails without it

"Red before green" as in 005 (design review decision 2): the plan orders each test before its change; execute records
the red run (test, assertion, message) against the code or image without the change, then the green run. For cluster
behaviour a test in the build cannot reach, the record is a lab run before (L0–L7, done) and after (A1–A10, during
execute), with the same scripts.

| Test | Kind | Proves | Red without the change because |
|---|---|---|---|
| **T1** `JavaRuntimeTest` | unit | the compiled classes have major version 69 (Java 25) | `java.version` 21 gives 65 |
| **T2** `deploy/ci/image-check.sh <image>` | CI step and lab | base is Temurin 25.0.5 by the pinned digest (label/`java -version`), `USER` 10001, no file under `/app` writable by 10001, no test/fault/`config/` file, entrypoint with the two JVM options | today's image: Java 21, uid 185 owns `/app` files, no JVM options (L0) |
| **T3** Trivy gate (`deploy/ci/scan.sh`) | CI step and lab | no fixable HIGH/CRITICAL in image and jar; no expired ignore entry | today: 4 CRITICAL, 13 HIGH in the jar (L0) |
| **T4** `ManifestPolicyTest` | unit (reads `deploy/k8s/base/*.yaml`) | kind StatefulSet, replicas 1, no Deployment/HPA; probes on 9090 at the two group paths, matching `management.server.port`; grace ≥ 30 s + Camel's shutdown timeout; request = limit memory; security context of D8 and the `/tmp` volume; no `SPRING_PROFILES_ACTIVE`, no `JAVA_TOOL_OPTIONS`, no mount under `/app`; Secret Store and partner configuration mounted at the `SECRET_STORE_DIR`/`PARTNER_CONFIGURATION` paths; partner ConfigMap from a generator with hash; no `Secret` with data; image given by digest in the release overlay | run against `deploy/local/lab/k8s/today.yaml` (today's deployment), every assertion fails |
| **T5** kubeconform | CI step | `kubectl kustomize` output valid for Kubernetes 1.34, `-strict` | an unknown field fails |
| **T6** `ProbesIT` | failsafe, packaged jar (`IntegrationProcess`) | 9090 answers `…/liveness` and `…/readiness` 200; `/actuator/health` details hidden; `/actuator/env` 404 on 9090 and 8443; `/orders` not on 9090; with PostgreSQL paused (`docker pause`) both groups stay 200 while `/actuator/health` is 503 | today: port 9090 refuses (`management.server.port=-1`); with the groups unpinned and `db` added, readiness goes 503 |
| **T7** `RouteCatalogTest` + YAML validator | unit + plugin | every endpoint and option exists in the 4.22.2 catalog; every file matches the schema | a planted unknown option (`delayy`) passes today's build |
| **T8** `KillIT` (extended) | failsafe | after each Peakmart kill point the restarted process takes in its first event within 15 s | today ~40 s (the session timeout, K4) |
| **T9** failure ITs (Q-8) | failsafe | a deliberately broken check (a fixture with a wrong expected value, run once by execute) shows its assertion message in `failsafe-reports/*.xml` | today the message is `Test failed` |
| **T10** `PartnerConfigurationReleaseTest` | unit | `deploy/k8s/base/partner-configuration.json` passes UC-007's checks (schema, ranges, overlaps, contacts, safe key names), without the keys | the file does not exist |
| **T11** test inventory (`deploy/ci/test-inventory.sh`) | CI step | every `*Test`/`*IT` class under `src/test` has a report, 0 skipped, 0 errors, 0 failures | a class excluded from surefire/failsafe, or `-DskipITs`, passes `verify` today |
| **T12** smoke (`deploy/ci/smoke.sh`) | CI kind and lab | §3.15 job 4 | no release exists |
| **T13** `PickListLoginDirectoryIT` (*review 1*) | failsafe | with an SFTP server whose login directory is not `/`: a pick list is uploaded, recording it fails, the Warehouse moves the file to the processed folder, the retry finds it there; each pick list is on the server once, in the folder the configuration names | today the check misses and the retry uploads a second file (W1); the red run is recorded first |
| **T14** `SftpSubsystemHangIT` (*review 2*) | failsafe | with a server that accepts the login and never starts the SFTP subsystem: the delivery attempt fails within `warehouse.sftp.timeout-ms` (plus a stated margin), `attempts` + 1 and `last_failure` set, and the next poll tries again; once the server answers, the pick list is delivered once | today the poller waits with no limit (W2); the red run (the test's own deadline expiring) is recorded first |

| Change | Tests | Lab after-run |
|---|---|---|
| D1 image | T1, T2 | A1 inspect |
| D2 threshold and upgrades | T3 | — |
| D3 probes | T4, T6 | A2 rollout (0 × 404), A5 outages (pod stays ready, never restarted) |
| D4 one instance | T4 | A10: rollout and `replicas` run — never two pods (`kubectl get pods -w`), 0 duplicate emails |
| D5 stop | T4 | A3 SIGTERM during the 200-event burst |
| D6 memory and JVM | T2, T4 | A6 the 500-order burst under 1 GiB: no OOMKill; A6b heap forced small (`-Xmx96m` lab override): exit 3, restart, nothing lost |
| D8 security | T4 | A9 restricted namespace: starts and serves |
| D9 settings, Secret Store, partners | T4, T10 | A4 bad secret (CrashLoop with the cause, rollback by the runbook); A8 a Partner Configuration change rolls the pod and is registered |
| D10 static membership | T8 | A7 exit 70: intake within 10 s of the restart; 20 of 20 recorded once |
| D11 catalog validation | T7 | — |
| D13 failure reports | T9 | — |
| D14 CI | T5, T11, T12, actionlint | the developer's CI run (§7) |
| D16 folder resolution | T13 | — |
| D17 SFTP time limit | T14 | — |

### 3.15 D14 — The CI gate in GitHub Actions (`.github/workflows/gate.yml`)

On every pull request and every push to `main`. `permissions: contents: read` at the top. Every action pinned by commit
SHA; Trivy, kubeconform and actionlint run from the digests of `deploy/local/tool.sh`. Runner `ubuntu-24.04`.

| Job | Steps | Fails when — and says |
|---|---|---|
| 1 `build` (timeout 45 min) | checkout; `actions/setup-java` Temurin **25**, Maven cache; `./mvnw -B -ntp verify` (unit tests, the YAML validator, `RouteCatalogTest`, the five spec ITs, the failure ITs, the matrix); T11; per-IT times into the summary; upload `target/*.jar`, reports, `target/spec-tests/` | any test fails: the summary lists each failed test with its assertion message (T9) and links the integration log; T11 names the class with no report or the skipped test |
| 2 `image` (needs 1) | `docker build` (amd64); T2; `docker save`; T3 (image and jar); SBOM (Trivy CycloneDX) and the scan report as artifacts | T2 names the property and its value; T3 prints the table of fixable HIGH/CRITICAL (id, package, installed, fixed) and marks each finding that is new since the last green run (§3.3, *review 8*) |
| 3 `manifests` | actionlint; T4 ran in job 1; `kubectl kustomize deploy/k8s/ci` and `deploy/k8s/release` → kubeconform `-strict -kubernetes-version 1.34.0` | the file, the resource and the field |
| 4 `kind` (needs 2, 3) | kind (pinned binary, checksum), `kind load` the image; apply `deploy/k8s/ci` (the systems of the lab, pinned by digest, and the release with CI settings and random CI secrets); wait for `order-integration-0` ready; **T12 smoke**: a partner order accepted, its resend answered as before, a rejected one 422; a Peakmart event recorded; both pick lists on the SFTP server, once; TLS 1.2/1.3 only; **restart after exit 70**: the CI-only fault image (built `FROM` the release image plus the fault-hook jar, never published) with `to-validate-order-placed:oom:1`, 20 events: `lastState.terminated.exitCode == 70`, `restartCount ≥ 1`; then the release image again: 20 of 20 recorded once, 20 pick lists, no set-aside; **rollout**: `rollout restart` with partner traffic — never two integration pods, no 404; `kind export logs` on failure | each check prints `FAILED <check>: expected <x>, got <y>` and the pod's last 50 log lines |
| 5 `release` (needs 4; only on push to `main`) | `permissions: packages: write`; push the image to `ghcr.io/<owner>/order-integration`; read the pushed digest; render `deploy/k8s/release` with `image: ghcr.io/…@sha256:<digest>` into `release.yaml`; upload `release.yaml`, the SBOM, the scan report and `deploy/README.md` as the release artifact | the push or the render, with its output |

Nothing in the workflow deploys anywhere but the kind cluster inside the runner. There is no deployment job and no
cluster credential in the repository or in its secrets.

**Build time (005 decision 6).** Measured locally at `f091fbb`: 17:39 (KillIT 387 s, UnexpectedErrorIT 242 s). Decided
now: the gate runs the whole `./mvnw verify` in one job, on every pull request and push; no test leaves it, none is
skipped (T11). D10 removes the session-timeout wait after each Peakmart kill point of `KillIT` and of the fatal case of
`UnexpectedErrorIT`; execute measures `verify` before and after it, locally. **Decided on the developer's CI run:** if
job 1 takes more than 25 minutes after D10, failsafe is split across two parallel jobs with the same Testcontainers
setup, and T11 checks that the two together ran every class `verify` runs; otherwise it stays one job. What the run
must report is in §7.

### 3.16 D15 — What an agent may and may not do, per environment

| Environment | An agent may | An agent may not | Enforced by |
|---|---|---|---|
| **Developer machine** (this repository, Claude Code) | read and change the repository; `./mvnw`; `docker build`; `kind load docker-image --name trailhead-lab`; `deploy/local/kube.sh` (only `kind-trailhead-lab`); `deploy/local/tool.sh`; scripts under `deploy/local/` | `kubectl`/`helm` directly; another cluster, context, user or kubeconfig; read `~/.kube`, `~/.ssh`, `~/.docker`, `~/.aws`, `~/.config`, `~/.gnupg`; `git push`; `gh`; cloud CLIs; `kind delete`; remove or stop containers, images, volumes | `.claude/settings.json` deny rules (in place); `kube.sh` (one context, refused flags, a kubeconfig file holding only the kind cluster) |
| | | **gaps found in this session, closed by D15:** `docker run`/`docker exec`/`docker cp` at the top level can mount any host folder (a `Read` deny does not reach into a container); `kind get kubeconfig`/`kind export kubeconfig` reach any kind cluster on the machine; `docker login`/`docker push` publish; an agent editing its own permissions | deny rules, **in place in `.claude/settings.json`, added by the developer** (*review 5*): `Bash(docker run *)`, `Bash(docker exec *)`, `Bash(docker cp *)`, `Bash(docker login *)`, `Bash(docker push *)`, `Bash(docker tag *)`, `Bash(kind get kubeconfig*)`, `Bash(kind export *)`, and `Edit`/`Write` of `.claude/**`, so no session can change its own permissions. **D15 is not a task for execute.** The project's scripts keep running Docker inside (`tool.sh`, the lab), and they are reviewed files |
| | | | **A gap of this machine, not a property of the design** (*review 5*): settings cannot stop a script an agent writes and runs (under `deploy/local/` it runs with the developer's rights), and Claude Code's sandbox cannot either, because its documentation lists `docker` as incompatible with it and this build needs Docker. This machine's default kubeconfig holds a credential for a real cluster of another project; no session has read it and the deny rules block the agent's own tools, but a script could. The design's rule: **on a machine where agents build with Docker, production credentials live elsewhere**; the developer keeps them so |
| **CI** (GitHub Actions, private repository on a free account) | nothing directly: an agent cannot push, open a pull request or start a run (`git push`, `gh` denied) | — | the developer pushes |
| | the workflow builds, tests, scans, deploys to kind in the runner, and on `main` publishes the image and the release artifact | deploy outside the runner; hold any cluster or cloud credential | `permissions: contents: read`, `packages: write` only in job 5 on `main`; no repository secrets besides `GITHUB_TOKEN`; no `pull_request_target`; actions pinned by SHA. **Branch protection is not available** (*review 4*): protected branches and rulesets on a private repository need a paid GitHub plan, so the gate cannot be required for a merge. What holds: the release job runs only after every gate job has passed in the same run (`needs:`, job 5 → 4 → 2, 3 → 1), so no release artifact exists without a green gate. What a team repository sets on top: branch protection on `main` (pull request, one approving review, the gate required) and CODEOWNERS for `.github/`, `deploy/`, `config/`, `pom.xml`, `.trivyignore.yaml`, `.claude/` |
| **Production** — for this pipeline the second local kind cluster `trailhead-prod` (*review 6*) | nothing | anything: read, deploy, scale, exec, read logs; **no session ever gets its kubeconfig** | the developer creates `trailhead-prod` and deploys the release artifact of a green `main` run with `deploy/README.md`, step by step, as a person would. `kube.sh` accepts only `kind-trailhead-lab`, and `kind get kubeconfig`/`kind export` are denied. In a real target environment: a person with their own credentials, none of them on the developer machine, in the repository or in GitHub |

### 3.17 D16 — Upload and "already delivered?" check resolve the folders the same way (F-2, *review 1*)

The release is the first time the integration meets a Warehouse SFTP server whose login directory nobody has
confirmed. If it isn't `/`, today every existence check misses and a retry delivers a second pick list (W1): NFR-006,
the guarantee this integration is built around. A runbook line can't hold that, so the code changes.

- **One rule for both:** a configured folder (`warehouse.sftp.inbound-directory`, `warehouse.sftp.processed-directory`)
  is taken relative to the SFTP login directory, with any leading `/` removed. That is what Camel's sftp producer
  already does with the endpoint path (it logs `SftpComponent doesn't support absolute paths … will be converted`).
  `WarehouseSftp` resolves both folders with the same conversion before every `stat`, in one method that a unit test
  pins against the producer's behaviour (`/inbound/picklists` → `inbound/picklists`).
- On a chrooted server (the lab, the tests) the paths are the same as today, so nothing else changes. The Warehouse
  lead confirms which folder names the login sees (OQ-093); they are configuration, not code.
- The route, `fileExist: Fail`, the temporary name of C-014 and the inbound-then-processed order are unchanged.

**Test:** T13, with its red run against today's code recorded first. **Rationale.** Both sides must agree whatever the
server; making the check follow the producer needs no new endpoint option and nothing outside the delivery bean.

### 3.18 D17 — A time limit on every SFTP call (F-1, *review 2*)

Every SFTP call, the channel start included, ends within `warehouse.sftp.timeout-ms` (10 s by default):

- `WarehouseSftp`: session connect, channel open and start, and every `stat` run under one deadline of
  `warehouse.sftp.timeout-ms`. When it passes, the session is disconnected (which ends the read that `ChannelSftp`
  start blocks in, W2) and the call throws a timeout that names the step (`SFTP channel start took longer than
  10000 ms`). The session's socket timeout stays as today.
- The delivery attempt then fails the way every SFTP failure does today: `pick-list-failed.sql` counts it (`attempts`
  + 1, `last_failure` = the message) and the next poll tries again. No new state, no new end state.
- The upload (Camel's sftp producer, its own connection) is held to the same limit. T14 has a case for it: the check's
  connection is healthy and the upload's connection hangs at the subsystem start. If that case is red too, the
  upload is guarded the same way (A-9).

**Test:** T14, red first. **Rationale.** A poller held without a limit stops every pick list with nothing logged
(NFR-001), and no probe sees it (P6); a bounded failure is logged, counted and retried.

### 3.19 How execute runs (*review 7*)

Execute runs **one wave per session**. The plan groups its tasks in waves that each end in a state that builds and
tests. Each wave's session starts fresh from the plan and the
execution report, and the report says at the end of each wave what the next one needs (files, open red runs, lab
state). Reason: one long session cost most of 005's budget in re-reading its own context.

---

## 4. Cross-Cutting Concerns

### Performance
NFR-002 is unchanged inside the process (005's 9 s safety net). New: ~10–15 s of refused connections per release or
restart (OQ-088), counted against NFR-008. NFR-001: the intake resumes at once after a restart (D10). No load test.

### Security
NFR-010 at the pod (OQ-078); C-024 unchanged (TLS to the database `verify-full`, SFTP known hosts, SMTP STARTTLS);
secrets only in Secrets created by a person, never in the image, repository or release; restricted pod; a stated
vulnerability threshold (D2); production out of reach of agents (D15). NFR-011: the smoke test and the runbook read
logs by order number only.

### Monitoring
Not in scope. Hooks for the next pipeline: the restart count and `CrashLoopBackOff` (K3, C2), OOMKilled (K1), the pod
not ready, plus 005's end states.

### Constraints
C-001, C-002, C-004 unchanged. **C-003 is amended to Java 25, Eclipse Temurin** by the spec owner, with the design
review as the reference (OQ-090, *review 3*); T1 and T2 check it. C-011's folders are taken as the SFTP login sees them
(D16, OQ-093). No new component.
New build plugin: `camel-yaml-dsl-validator-maven-plugin`; new test dependency: `camel-catalog`. No new production
dependency.

---

## 5. Constitution Compliance

- [x] Route Structure — no route added or removed
- [x] Single Responsibility — unchanged
- [x] Separation of Concerns — deployment in manifests, configuration in properties; no logic added to routes
- [x] Naming Conventions — unchanged; new property `peakmart.group-instance-id` in the project's style
- [x] Observability — routeId and description kept; T9 makes failing tests say why
- [x] External Configuration — `groupInstanceId` from a property, its value from the environment; every secret from a
  Secret; T4 forbids settings baked into the image or the release
- [x] Component Support — the one changed endpoint option is catalog-verified below; T7 validates every endpoint
  against the 4.22.2 catalog in every build
- [x] Infrastructure via Forage — no bean added (D16 and D17 change the existing `warehouseSftp` bean only)

### Catalog Verification Evidence

Catalog binding: camel_catalog_components(limit=0); runtime=spring-boot; platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2; returned camelVersion=4.22.2; binding=MATCHED

| Artifact identity | Result | Verification provenance |
|---|---|---|
| component:kafka | VERIFIED | camel_catalog_component_doc (groupInstanceId: consumer, string, "only one instance with this ID is allowed in the consumer group"; sessionTimeoutMs: default 45000); requested component:kafka; catalog binding=MATCHED |

The YAML validator plugin and `camel-catalog` are build artifacts, not catalog components; their 4.22 documentation was
read with `camel_docs_search` (camel-knowledge, version 4.22) and the plan checks that both resolve at 4.22.2.

---

## 6. Project Structure (changes only)

```
.github/workflows/gate.yml                     [D14]
.trivyignore.yaml                              [D2, empty at first]
.claude/settings.json                          [D15 deny rules: already in place, set by the developer; not an execute task]
pom.xml                                        [java.version 25, managed-version patches (D2), YAML validator, camel-catalog (test), Jib profile removed]
src/main/docker/Dockerfile                     [D1, replaces camel export's]
src/main/resources/application.properties      [D3 management, server.shutdown; D5 camel.main.shutdown-timeout]
src/main/resources/application-default.properties  [D10 peakmart.group-instance-id=${HOSTNAME}]
src/main/resources/camel/peakmart-order-intake.camel.yaml  [D10 groupInstanceId]
config/application-dev.properties              [D10 peakmart.group-instance-id=dev; management port note]
src/test/java/…/JavaRuntimeTest.java, ManifestPolicyTest.java, RouteCatalogTest.java, PartnerConfigurationReleaseTest.java
src/main/java/…/WarehouseSftp.java              [D16 folder resolution; D17 deadline on every call]
src/test/java/…/failure/ProbesIT.java, KillIT.java [T8], failure ITs [T9], PickListLoginDirectoryIT.java [T13], SftpSubsystemHangIT.java [T14]
src/test/resources/spec-test/application-spec-test.properties  [group instance id]
deploy/k8s/base/                               [StatefulSet, Services, ServiceAccount, NetworkPolicy, kustomization, partner-configuration.json]
deploy/k8s/release/                            [overlay: image by digest]
deploy/k8s/ci/                                 [overlay: systems for kind, CI settings, restricted namespace]
deploy/ci/image-check.sh, scan.sh, test-inventory.sh, smoke.sh, fault.Dockerfile
deploy/README.md                               [D12 runbook]
deploy/local/lab/                              [the lab of this design; after-runs A1–A10]
docs/open_questions.md                         [OQ-078 answer proposed; OQ-087 … OQ-095 added]
docs/requirements.md                           [C-003 → Java 25, Eclipse Temurin: done with the approval (review 3)]
docs/camel-kit/006-deployment/
├── design-spec.md                             ← this file
├── design-review.md                           ← the approval and its eight changes
└── evidence/deploy-lab-runs.md, runs/         ← L0–L7
```

---

## 7. Open Questions and the answers taken

The run is non-interactive, so each recommended answer is taken and built. None adds a business rule. The design
review accepted the answers to OQ-078, OQ-060, OQ-087 to OQ-089, OQ-091, OQ-092 and OQ-095 as written, and changed
OQ-090, OQ-093 and OQ-094 (rows below as amended).

| OQ | Question | Recommended answer (taken) | Why |
|---|---|---|---|
| OQ-078 (existing) | Who terminates TLS for the partner endpoint? | The integration, in the pod; whatever is in front passes TCP through | NFR-010 and C-024 then hold by what the build and the smoke test check (§3.9) |
| OQ-060 (existing; OQ-028 resolved) | How does the Partner Configuration name a key, and what is the Secret Store in the cluster? | Reading (a), as built; the Secret Store is the Kubernetes Secret `order-integration-partner-api-keys` mounted as a directory, created and rotated by the Integration Team outside the repository and the pipeline; a change takes effect at the next restart | the directory shape the code reads; no key ever passes through an agent or CI |
| OQ-087 | How many instances may run? | Exactly one (StatefulSet, 1 replica); every release or crash is a short gap; a lost node waits for a person | NFR-006 (0 duplicates) and C-010 over minutes of availability; NFR-008 leaves 3.6 h a month (L7) |
| OQ-088 | What does a partner get while the only pod restarts? | A refused connection, not a contract answer; counted against NFR-008; the partner resends (C-009, BR-007) | there is no process to answer; a resend is safe (BR-007) |
| OQ-089 | A `VirtualMachineError` that repeats on every start (the same event) | The pod restarts with back-off for as long as it lasts; the event is never set aside for it; `CrashLoopBackOff` and the restart count are the signal for the monitoring pipeline | OQ-084: after a VM error nothing in the process can be trusted to decide; no order is lost (L6) |
| OQ-090 | C-003 said Java 21; the request says Temurin 25 | **Answered** (*review 3*): Java 25, Eclipse Temurin; the spec owner amended C-003 with the design review as the reference | Camel 4.22 and Spring Boot 4.1 support it; the build and image prove it (T1, T2) |
| OQ-091 | Which vulnerabilities stop a release? | HIGH or CRITICAL with a fix, in the image or the jar; exceptions only by a reviewed, expiring entry | a line a person can act on (§3.3) |
| OQ-092 | Who may deploy, and from where? | A person, with credentials no agent and no CI job holds, from the release artifact of a green `main` run | the request: production out of reach of every agent (§3.16). For this pipeline, production is the local kind cluster `trailhead-prod`, deployed by the developer with the runbook (*review 6*) |
| OQ-093 | (F-2) Which folders does the Warehouse SFTP login see? | **Changed** (*review 1*): fixed in this pipeline: upload and check resolve the configured folders the same way, whatever the login directory (D16, T13). Open **only** for the Warehouse lead's confirmation of the folder names, before the first real delivery | otherwise every existence check misses and a retry delivers a second pick list (NFR-006); a runbook line can't hold that |
| OQ-094 | (F-1) How long may one SFTP call take? | **Changed** (*review 2*): fixed in this pipeline: `warehouse.sftp.timeout-ms` on every SFTP call, the channel start included (D17, T14) | the poller waited >136 s with nothing logged (NFR-001) |
| OQ-095 | Which partners does the first production Partner Configuration hold? | The partners the partner manager confirms; `summit-outfitters` stays a development example | T10 checks whatever is released; the content is a business decision |

### Assumptions (non-interactive run)

- A-1: the recommended answers above are taken.
- A-2: the pipeline ID `006-deployment` was given; no `camel-kit nextId` was run.
- A-3: the base digest is the one `docker build` resolved on arm64 on 2026-11-16. The review confirmed it is the
  multi-architecture index digest (`docker buildx imagetools inspect`), so it holds on the amd64 runner.
- A-4: the target cluster runs Kubernetes ≥ 1.30 with Pod Security admission; the lab and CI use 1.34.
- A-5: memory sizes come from light load in the lab (L6); no load test (out of scope).
- A-6: the registry is GHCR of the developer's private repository; any OCI registry works the same way.
- A-7: the lab's systems (Mailpit, the catalog stub, the native SFTP server, Kafka without TLS) stand in for the real
  ones; F-1's hang was triggered by a lab defect, the missing time limit is the integration's.

Taken while writing in the review's changes (amendment, non-interactive):

- A-8 (D16): the check follows the producer: folders relative to the login directory, leading `/` removed. The
  alternative (both absolute) would need the producer to keep an absolute path, which nothing here has verified in
  the 4.22.2 catalog; on a chrooted server the two are the same.
- A-9 (D17): if T14's upload case is red too, the recommended fix moves the upload into `WarehouseSftp`, on the same
  guarded connection, keeping C-014 (temporary name, then rename) and never overwriting a final file. The route then
  calls the bean instead of the sftp endpoint. That change is presented to the integration architect before it is
  built, because it removes an endpoint from a route.

### What the developer's first CI run must report

1. Wall-clock time of each job and of each step; the runner's CPU count and memory.
2. Job 1: `Tests run`/`Skipped` per execution, and the `Time elapsed` of every IT (from the summary) — for the
   build-time decision of §3.15.
3. Job 2: the image digest, the T2 output, and the T3 tables (image and jar), including findings without a fix.
4. Job 4: the smoke output, the restart check (exit code, restart count, time from restart to the first receipt), and
   the rollout check (pods seen, statuses).
5. Job 5 (on `main`): the pushed digest and that `release.yaml` names it, and that it ran only after jobs 1–4 passed
   in the same run (*review 4*).
6. Any test that failed and passed on a re-run, with its log.
