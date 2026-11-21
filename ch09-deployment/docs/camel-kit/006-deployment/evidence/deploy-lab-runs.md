# Deploy lab runs — pipeline 006-deployment (2026-11-16)

The "today" column of design 006 §3.1 for every case that needs a run. Each run used the code at `f091fbb`, nothing
changed in `src/` or `pom.xml`.

## Setup

- **Cluster:** kind `trailhead-lab` (Kubernetes v1.34.0, one node, arm64), reached only through `deploy/local/kube.sh`.
- **Systems** (`deploy/local/lab/k8s/systems.yaml`, namespace `trailhead-lab`): PostgreSQL 16 with TLS and a persistent
  volume; Kafka 4.3.1 (KRaft, topic `peakmart.orders.v2`, 2 partitions); a native OpenSSH SFTP server
  (`deploy/local/lab/sftp.Dockerfile`); Mailpit with STARTTLS required and any login accepted; a Product Catalog stub
  serving `docs/messages/examples/product`; a `client` pod with curl. Certificates, keys and passwords are lab-only
  (`deploy/local/lab/secrets.sh`, files under `target/lab/secrets/`, never committed).
- **The integration "today"** (`deploy/local/lab/k8s/today.yaml`): the image `src/main/docker/Dockerfile` builds
  (`trailhead/order-integration:today`, image id `sha256:93b66e6c…`), with **no profile**, so every host, port and secret
  comes from the environment (`application-default.properties`, `RequiredVariablesCheck`). One Deployment, replicas 1,
  no probes, no resources, no security context, default grace period (30 s), default rolling update (25 % surge).
- **Load:** `deploy/local/lab/lab.sh stream` sends new Partner Orders from the client pod, one every 0.25 s unless
  stated; `000` means curl got no connection. `lab.sh events` publishes Peakmart `order.placed` events.
- **Scripts:** `deploy/local/lab/runs.sh` (rollout, sigterm, badconfig, outages, replicas), `partner-change.sh`,
  `inspect-image.sh`, `scan-summary.sh`. Raw outputs of the runs marked † are in `runs/`.

Lab problems met on the way (not findings about the integration):

- `atmoz/sftp:alpine` is amd64 only; and the kind node gives containers `nofile=infinity`, so `sshd-session` spins
  closing descriptors and never starts the SFTP subsystem. Fixed in the lab image with `ulimit -n 4096`.
  What the integration did meanwhile is finding **F-1** below.
- The first Order Database outage ran against a PostgreSQL without a volume; scaling it to 0 wiped the schema. That
  run was discarded and repeated with a persistent volume (L5).

## L0 Built — what the image holds

`deploy/local/lab/inspect-image.sh` and `scan-summary.sh` (Trivy 0.76.1, the digest of `deploy/local/tool.sh`).

| | today (`ubi9/openjdk-21-runtime:1.24`, by tag) | requested base `eclipse-temurin:25.0.5_8-jre-noble` |
|---|---|---|
| Resolved digest | not pinned | `sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c` (resolved by `docker build` on arm64, 2026-11-16) |
| OS | RHEL 9.9 | Ubuntu 24.04.5 |
| Java | Red Hat OpenJDK 21.0.13 | Temurin 25.0.5+8 |
| User | `185`, group `0`; the application files are **owned by 185** (`--chown=185`), so the process can rewrite its own jar and libraries | **root** (no `USER`) |
| Size | 504 MB | 334 MB |
| Tools | curl, bash, microdnf, rpm, python3, jcmd | bash, apt-get, jfr |
| Writable by the user | `/deployments/lib`, `/deployments/data`, `/tmp`, home | everything (root) |
| Test or fault classes | none (the production jar has none, as 005 checked) | — |
| Entrypoint | `java -jar application.jar` (no heap setting) | `/__cacert_entrypoint.sh` |

Trivy, vulnerability scanner, all severities:

| Target | CRITICAL | HIGH | MEDIUM | LOW |
|---|---|---|---|---|
| today, OS packages (RHEL 9.9) | 0 | 27 fixable + 9 without fix | 53 + 181 | 4 + 120 |
| today, Java (the jar's dependencies) | **4 fixable** | **13 fixable** | 8 | 1 |
| Temurin 25 base alone | 0 | 0 | 18 fixable + 20 | 4 |
| the jar alone (`trivy rootfs --offline-scan target/order-integration-0.1.0.jar`) | 4 fixable | 13 fixable | 8 | 1 |

HIGH/CRITICAL in the jar, all with a fixed version: `tomcat-embed-core` 11.0.24 → 11.0.25 (4 CRITICAL),
`jackson-core`/`jackson-databind` 2.21.5 → 2.21.7 and `tools.jackson.core` 3.1.5 → 3.1.7, `snappy-java` 1.1.10.7 →
1.1.10.9, `lz4-java` 1.11.1 → 1.11.4. `trivy fs` on `pom.xml` could not run: Maven Central answered 429 to Trivy's
resolution, so the dependency scan has to read the built jar (or a populated `~/.m2`).

## L1 Started — the first seconds †`today-start.log`

| Time (UTC) | Event |
|---|---|
| 11:59:54.57 | Flyway connects (TLS, `verify-full`) and applies V1–V3 in 0.1 s |
| 11:59:55.10 | `Partner Configuration registered partners=1` |
| 11:59:56.31 | **Tomcat started on port 8443 (https)** |
| 11:59:57.41 | Camel routes started (19), `Started CamelApplication in 6.201 seconds` |

Requests before 11:59:56 were refused (`000`). Tomcat accepts **1.1 s before** the partner route exists: in that window
the endpoint answers **HTTP 404** (L2, L3). Spring Boot sets its readiness state to accepting traffic at
`ApplicationReadyEvent`, after `Started CamelApplication`; that the 404 window is then closed is not yet observed (the
after-run A2 of the design checks it). Today nothing asks it.

## L2 Rollout under traffic †`rollout.txt`

`kubectl rollout restart` at 12:09:18 with partner traffic. (The order numbers of this run were outside the partner's
range, so the served answers are 422 rejections; what matters is the connection-level outcome.)

| Status | Count | When |
|---|---|---|
| served (422 here) | 197 | before and after |
| `000` no connection | **19** | 12:09:20.9–12:09:25.9 (5 s) |
| `404` | **6** | 12:09:26–12:09:27 (1.1 s, Tomcat up, routes not yet) |

Without a readiness probe the new pod is "available" the moment its container runs, so the old one is stopped at once
(the rollout finished in 2 s) and the Service sends partners to a pod that is not listening yet.

## L3 Stopped — SIGTERM while orders are in processing †`sigterm.txt*`

200 Peakmart events published, then the pod deleted (SIGTERM, grace 30 s) three seconds later, with partner traffic.

- The old process stopped **gracefully in 1.5 s**: `Apache Camel 4.22.2 … shutdown in 1s477ms`; the Kafka consumer
  sent `LeaveGroup` (`Unsubscribed all topics`), so the replacement got the partitions without waiting for a session
  timeout; Hikari closed.
- **200 of 200 orders recorded once, 200 pick lists** (no loss, no duplicate).
- Partners: 141 × 200, **16 × `000`, 3 × `404`** while the replacement started (one replica).

## L4 Configured

| Run | Outcome |
|---|---|
| **Wrong Order Database password** in a rollout †`badconfig.txt*` | The new pod fails at Flyway (`FATAL: password authentication failed for user "trailhead"`), exit 1, CrashLoopBackOff (3 restarts in 60 s). The **old pod was removed at once** (no readiness probe), so from 12:12:57 on **every partner request was refused: 109 × `000` of 149**. Rolled back by hand. |
| **No settings at all** (a missing ConfigMap/Secret reference) | exit 1 within 2 s: `Missing environment variables: CATALOG_HOST, DB_HOST, DB_NAME, DB_PASSWORD, …` (all 26 names, sorted, no values). |
| **Dev profile** (`SPRING_PROFILES_ACTIVE=dev`, all production variables present) | exit 1: `Missing environment variables: spring.datasource.password, spring.datasource.url, spring.datasource.username`. `config/application-dev.properties` is not in the image, and with `dev` active `application-default.properties` is not read. Safe, but the message is misleading. A file mounted at the image's working directory under `config/` would be read (Spring's `./config/`). |
| **Partner Configuration change** (`partner-change.sh`): the Order Contact changed in the ConfigMap | The kubelet updated the mounted file after **~80 s**; the `partner` row and the in-memory registry did **not change**. After a restart: `Partner Configuration registered partners=1`, new `registered_at`, new contact. Registration runs only at start (UC-007 at startup, design 003). |
| **Restricted security context** (Pod Security "restricted", `readOnlyRootFilesystem`) | exit 1: `FileSystemException: /tmp/tomcat.8443…: Read-only file system`, CrashLoopBackOff; again the old pod had been removed first (outage). `runAsNonRoot` itself works (numeric `USER 185`). |
| **TLS** (`lab.sh tls`) | TLS 1.2 and 1.3 accepted; plain HTTP on 8443 answered 400. TLS 1.1 could not be offered by the client's OpenSSL, so its refusal is from configuration (`server.ssl.enabled-protocols=TLSv1.3,TLSv1.2`), not from this run. |

## L5 Probed — Kafka, the Order Database, the Product Catalog down

The actuator was exposed by environment only (`k8s/patch-actuator.yaml`: management port 9090, health with details,
probes enabled), to see what a probe would see. Each system scaled to 0 for 30 s (refused connections).

| Down | `/actuator/health` | `…/readiness` | `…/liveness` | A partner request |
|---|---|---|---|---|
| Kafka | **DOWN** (`camelHealth`) | UP | UP | 200 accepted (0.75 s) |
| Order Database | **DOWN** (`db`, `camelHealth`) | UP | UP | 503 contract answer in 2.1–3.6 s |
| Product Catalog | **DOWN** (`camelHealth`) | UP | UP | 503 contract answer in 0.04 s |

`camelHealth` stays DOWN for about 35 s after the database is back (the sql consumers' health checks need successful
polls). A probe on `/actuator/health` would take the only pod out of the Service in every one of these outages, and the
partner would get a refused connection instead of the contract's 503 (FR-030, OQ-037). The `readiness` and `liveness`
groups contain only Spring's availability states and stayed UP. The pod never restarted (no probe).

## L6 Killed

| Run | Outcome |
|---|---|
| **Memory limit 256 MiB**, JVM defaults (`k8s/patch-memory-256.yaml`), 300 events and partner traffic †`oom-stream.txt` | **OOMKilled, exit 137**, in a loop (2 restarts in 40 s); partners 19 × `404`, 134 × `000`. After rolling back: **300 of 300 recorded once**. |
| **No limit** (today) | `MaxHeapSize` = 3.9 GB (25 % of the node's 16 GB), heap in use 76 MB of 119 MB committed, cgroup `memory.current` **418 MB** after 30 s of traffic. The heap is sized from the node, not from any limit. |
| **Exit code 70 from H1** (lab image `fault.Dockerfile`: the packaged jar plus the test-only fault hook, `FAULT_POINTS=to-validate-order-placed:oom:1`), 20 events, partner traffic for 90 s †`exit70-stream.txt` | `Fatal error routeId=peakmart-event-read nodeId=to-validate-order-placed error=java.lang.OutOfMemoryError — the process stops`; **container restarted by the kubelet** (`restartCount` 2, last state `Error`, **exit code 70**). The fault fires again on every start, because the same event is redelivered (its offset was never committed): a loop, as a deterministic `VirtualMachineError` would cause. Each restarted consumer got its partitions **~42 s after start** (`Fault hook active` 12:23:51, fires 12:24:33): the dead member's 45 s Kafka session timeout. Partners: 94 × 200, 45 × `000`, 13 × `404`. Fault removed: **20 of 20 recorded once, 20 pick lists**, 0 set aside. |
| **Node lost** | not run: the lab has one node. From Kubernetes' defaults: a Deployment's pod on a lost node is replaced after the 300 s `not-ready`/`unreachable` tolerations, and the old process may still be running if the node is only partitioned. |

## L7 Scaled — two pods at once †`replicas.txt.events`

`runs.sh replicas`: accepted and rejected Peakmart orders; meanwhile a stand-in for the Warehouse moves every final file
in `inbound/picklists` to `processed/` once a second, keeping a second copy of a name as `<name>.again.<n>`.

| Pods | Orders | Pick lists received twice | Order Desk Notifications sent twice |
|---|---|---|---|
| 1 (control) | 100 accepted, 30 rejected | 0 of 100 | 0 of 30 |
| 2 | 100 accepted, 30 rejected | 0 of 100 | 0 of 30 (the two pods' polls did not overlap) |
| 2 | 300 accepted, 200 rejected | 0 of 300 | **152 of 200** |

Both pods poll the same outbox rows: `notification-select.sql` and `pick-list-select.sql` take no lock and no claim, and
`onConsume` marks a row only after the send. The pick-list route says so itself: *"Exactly once rests on: the
existence check … the atomic rename … and one instance."* No pick list was received twice in these runs; the race is
there in the code (both pods stat a missing file, one uploads, the Warehouse moves it, the other uploads), only not hit.
Each pod also has its own 20 calls/s catalog throttle (C-010), so two pods may call the catalog 40 times a second
(from the code; not measured).

## Findings outside the deployment (not changed by 006)

- **F-1 — an SFTP server that accepts the login but never starts the SFTP subsystem holds the pick-list poller with no
  time limit.** In the first lab setup, `WarehouseSftp.connect` waited in `ChannelSftp.start` (thread dump:
  `PipedInputStream.read` ← `ChannelSftp.header` ← `Channel.connect`) for more than 136 s, with `warehouse.sftp.timeout-ms`
  at 10 s; nothing was logged, `attempts` stayed 0, and no pick list moved until a restart. The process was otherwise
  healthy, so no probe would see it. NFR-001 (15 min). Proposed as OQ-094.
- **F-2 — the upload and the "already delivered?" check can look at different folders.** Camel's SFTP producer logs
  `SftpComponent doesn't support absolute paths, "/inbound/picklists" will be converted to "inbound/picklists"`, while
  `WarehouseSftp` stats `/inbound/picklists/<file>` as configured. They are the same folder only when the login
  directory is `/` (the chrooted lab and test servers). Otherwise every existence check misses, and a retry after an
  upload delivers a second pick list (NFR-006). Proposed as OQ-093.

## Local build (baseline for the build-time decision)

`./mvnw -B -ntp verify` at `f091fbb` on this machine (macOS, arm64, Temurin 25.0.3, Docker Desktop), nothing running
for the project beforehand, other projects' containers running: **exit 0, 17:39 min**. Unit tests 175, spec ITs 5,
failure ITs 26, **0 skipped**. Costliest: `KillIT` 387 s, `UnexpectedErrorIT` 242 s, `DatabaseFrozenIT` 94 s,
`Tc001` 56 s, `DeliveryFailureIT` 46 s. (005's acceptance: 15:32 on a clean copy.)
