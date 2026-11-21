---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-16T23:44:35.613012Z"
  by: "camel-validate"
  from: "execution-report.md"
---
# Validation Report

**Date:** 2026-11-16 17:44
**Project:** trailhead-orders
**Runtime:** spring-boot
**Camel Version:** 4.22.2

Mode: standalone, pipeline-scoped (`/camel-validate 006-deployment`), non-interactive. This is a static validation of
the files. No build was run, the application was not started, and no cluster or container was touched (user
instruction). The developer deploys the release to `trailhead-prod` separately, so this report does not cover that
deployment.

**Inputs:**
- `design-spec.md`, `implementation-plan.md` and `execution-report.md`. `camel-kit doc check` reported each one as not
  stale. The lineage is plan ← design and execution report ← plan.
- `design-review.md` and `developer-checks.md` have no provenance frontmatter. Both are committed (`59efbac`,
  `d252dbb`). They are used as developer evidence and checked against the files (A-1).
- `evidence/red-green-runs.md`, `build-times.md`, `deploy-lab-runs.md`, `deploy-after-runs.md`.
- `005-hardening/validation-report.md` and `validation-decisions.md`, for the carried-over findings (A-2).

**Catalog binding:** `camel_catalog_components(limit=0, runtime=spring-boot,
platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2)` returned `camelVersion=4.22.2`
(MATCHED). Every later catalog call used the same binding.

**Inventory** (single module, empty `MODULE_PREFIX`):
- `ROUTE_FILES`: the six files under `src/main/resources/camel/`. Two of them changed in this pipeline
  (`git diff f091fbb HEAD`):
  - `peakmart-order-intake.camel.yaml`: `groupInstanceId` (D10), and `maximumRedeliveries: 2` (W1-F2).
  - `pick-list-delivery.camel.yaml`: `to-warehouse-sftp` became `bean:warehouseSftp` (A-9).
- `PROPS_FILE`: `src/main/resources/application.properties` (D3 management, `server.shutdown`, D5
  `camel.main.shutdown-timeout`; `peakmart.set-aside-redeliveries` removed). Also checked:
  `application-default.properties` (D10), `config/application-dev.properties` and the spec-test properties.
- Deployment files in scope: `src/main/docker/Dockerfile`, `deploy/k8s/**`, `deploy/ci/**`,
  `.github/workflows/gate.yml`, `.trivyignore.yaml`, `deploy/README.md`, `pom.xml`.

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | 2 | 0 | 1 | 3 |
| Endpoint Verification | 4 | 0 | 1 | 5 |
| Quality Checks | 6 | 1 | 9 | 16 |
| Security Analysis | 6 | 4 | 2 | 12 |
| Anti-Patterns | 1 | 0 | 3 | 4 |
| Constitution Compliance | 6 | 0 | 2 | 8 |
| **Total** | **25** | **5** | **18** | **48** |

**Result: FAIL (5 items).**
- **One new failure, in this pipeline's gate (Q-1, the developer's D10).** A secret value written as a literal into a
  manifest passes every gate check. D10 failed the gate only because the planted password was the wrong one. With the
  right password the gate is green and job 5 publishes a `release.yaml` that holds the production database password.
  `ManifestPolicyTest` checks only for `kind: Secret` with data and for a `secretGenerator`. The design's claim that
  "every secret [comes] from a Secret; T4 forbids settings baked into … the release" (§5, rule 6) is not enforced.
- **Four security failures, carried unchanged from 003–005 (S-1 to S-4).** They were re-accepted for 005 (005
  decision 1). They block 006 until they are accepted again or fixed.

**The rest of the pipeline holds, with warnings.**
- Every design item (D1–D17, T1–T14, A1–A10, the CI gate and the runbook) is in the files.
- The CI fixes of session 10 are in the files, and the developer's green PR and `main` runs prove them.
- The developer's fault table agrees with the files: eleven of the twelve faults are caught by the check named.
- The main warnings:
  - T4 reads the YAML files as written, not the kustomize output (Q-2).
  - The released image is `linux/amd64` only, while `trailhead-prod` is an arm64 kind cluster, and no decision is
    recorded for that (Q-4).
  - Developer check 14 says TLS 1.1 was checked against a production deployment that the record does not contain
    (Q-5).

## Findings

### Schema Validation
- [PASS] `camel_validate_yaml_dsl` on the changed parts, comments removed, in one payload, returned valid with 0
  errors. The payload held:
  - the full `pick-list-delivery` route and its `pick-list-delivery-error-handling` configuration, with the
    `bean:warehouseSftp` step;
  - the `on-intake-cannot-take-in` clause with `maximumRedeliveries: 2`;
  - the intake `from` with `groupInstanceId`.
- [PASS] **W1-F2 is closed in the files, and 005's schema WARN with it.** `maximumRedeliveries` is the number `2`
  (`peakmart-order-intake.camel.yaml:50`). The property `peakmart.set-aside-redeliveries` is gone from every
  properties file, and the `pom.xml` has no validator exclusion. The build now runs
  `camel-yaml-dsl-validator-maven-plugin` 4.22.2 with `failOnError=true` (`pom.xml:247-265`). The execution report
  records `Validation success (files:6)`.
- [WARN] The four unchanged route files were not sent to the MCP validator in this pass. The plugin covers them in
  every build, and the developer's green CI runs (check 19) count as that evidence (A-1).

### Endpoint Verification
- [PASS] Catalog binding MATCHED for 4.22.2 spring-boot.
- [PASS] `kafka` `groupInstanceId`: the catalog lists it as a consumer endpoint option of type string, "only one
  instance with this ID is allowed in the consumer group". `camel_validate_route` on the intake URI with it returned
  valid. The placeholder `{{peakmart.group-instance-id}}` is defined in all three profiles:
  - `application-default.properties:9` (`${HOSTNAME}`);
  - `config/application-dev.properties:7` (`dev`);
  - `application-spec-test.properties:21` (`spec-test`).
- [PASS] `bean:warehouseSftp?method=upload(…)` is valid in the catalog. The route no longer has an `sftp:` endpoint, so
  the SFTP credentials and known-hosts options are now applied in `WarehouseSftp`, which sets
  `StrictHostKeyChecking=yes` and `setKnownHosts` (`WarehouseSftp.java:263-266`).
- [PASS] `camel_configuration_validate`: `camel.main.shutdown-timeout=45` is valid.
- [WARN] `camel.main.run-controller` is reported unknown again. This is the known false positive of 002 decision 4,
  and the line did not change (A-3). The new Spring keys (`management.*`, `server.shutdown`) are not in the Camel
  catalog. They match design §3.4, and `ProbesIT` (T6) exercises them.

### Quality Checks
- [PASS] **Design coverage.** Every item of design §6 is in the tree:
  - The Dockerfile (D1/D6) has the Temurin 25.0.5 base pinned by digest in both stages, `USER 10001:10001`, an
    exec-form entrypoint with `MaxRAMPercentage=50` and `ExitOnOutOfMemoryError`, and no Jib profile.
  - `java.version` is 25. Spring Boot 4.1.2 manages the fixed Tomcat and Jackson versions, and the pom pins
    snappy-java and lz4-java (D2).
  - The manifests match D3–D9: the StatefulSet with 1 replica and `OrderedReady`, the three probes on `management`,
    `terminationGracePeriodSeconds: 90`, memory 1 Gi request = limit with no CPU limit, the restricted security
    context, the `/tmp` emptyDir, the hashed Partner Configuration ConfigMap, the Secret Store directory, and a
    NetworkPolicy that admits 9090 only from the namespace.
  - Also in the tree: D10, D11 (validator and `RouteCatalogTest`), D13, D16/D17 with A-9, the runbook (D12), and the
    gate (D14).
- [PASS] **CI-1 and CI-2 are fixed in the files and proven in CI.**
  - `smoke.sh` reads `WAREHOUSE_SFTP_HOST`/`PORT` from the rendered settings and writes `known_hosts` for that name
    (`smoke.sh:57-90,117`). A `known-hosts` check runs before anything is applied.
  - `gate.yml` pins exactly the four SHAs of developer check 18 (`:51,52,67,92`).
  - Check 19 then reports the PR run and `main` run 43975492281 green, with the known-hosts check in the smoke output.
  - This closes W5-F6 (the two-namespace path) and W6-F1 (the action SHAs).
- [PASS] **The release follows the design.**
  - Job 5 `needs: kind`, which needs `image` and `manifests`, which need `build`.
  - It runs only on a push to `main`, and it is the only job with `packages: write`.
  - It pushes the image tar that job 4 tested.
  - It reads the pushed digest and fails on a non-sha256 digest, the zero digest, `OWNER`, or an `image:` line other
    than the one pushed (`gate.yml:256-292`).
  - Check 19's `release.yaml` names
    `ghcr.io/<owner>/order-integration@sha256:780d25d2…6550c2`.
- [PASS] **Build-time rule (§3.15).** Job 1 took 16:38 (first run) and 19:36 (`main`), both under 25 minutes, so it
  stays one job, as check 17 says.
- [PASS] **Eleven of the twelve faults (D1–D9, D11, D12) are caught for the reason they were planted, and the files
  agree.**
  - `ManifestPolicyTest` has the replica, probe, grace-period, by-digest and generator-hash rules (it reads
    `management.server.port` and the shutdown timeout from `application.properties`).
  - `image-check.sh` checks the user (`:39`) and the entrypoint (`:40`).
  - `scan.sh` runs with `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1` (`:123`).
  - `test-inventory.sh` lists the classes from `src/test` (`:61`), not from the surefire/failsafe configuration.
- [PASS] **Red before green.** `red-green-runs.md` and the execution report summary have a red and a green row for
  T1–T14. Session 10 adds the planted `known-hosts` mismatch and a lab smoke run that is green again.
- [FAIL] **Q-1: no gate check catches a secret value in a manifest (D10).**
  - The planted fault was `DB_PASSWORD: changeit` as a literal `env` value in the StatefulSet. It failed in job 4 only
    because Flyway's login was refused.
  - `noSecretWithDataInTheManifests` (`ManifestPolicyTest.java:332-350`) looks only at `secretGenerator` entries and
    at `kind: Secret` documents with `data`/`stringData`.
  - The only env rule checks two names, `SPRING_PROFILES_ACTIVE` and `JAVA_TOOL_OPTIONS` (`:225-233`). No rule looks
    at an env value.
  - Nothing reads ConfigMap literals or `configMapGenerator` `envs`/`files` inputs (`deploy/k8s/ci/settings.env` is
    not YAML and is never read).
  - What happens when the planted password is the right one: the gate is green, job 5 publishes `release.yaml` with
    the password inside, as a workflow artifact anyone with repository read access can download, and the repository
    history keeps it. Constitution rule 6 and design §3.9 ("never in the repository, the pipeline or the release") are
    broken, and the gate gives no signal.
  - Recommended correction (implementation, not applied):
    - A T4 rule over the kustomize-rendered base and release: every `env` entry whose name matches the known secret
      keys (`DB_PASSWORD`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `WAREHOUSE_SFTP_PASSWORD`, and anything matching
      `PASSWORD|SECRET|TOKEN|KEY`) must use `valueFrom.secretKeyRef` or come through `envFrom.secretRef`.
    - The same names forbidden as keys in any ConfigMap or generator input.
    - Record its red run against D10 first, then green.
    - The CI overlay's literal `trustStorePassword=changeit` (S-5) needs an explicit, commented allowance, or should
      move into the files Secret.
- [WARN] **Q-2: T4 reads raw YAML, not what kustomize renders.**
  - The release overlay may hold only `resources` and `images`, but the base `kustomization.yaml` itself is not
    restricted. For example, `replicas: [{name: order-integration, count: 2}]` or a `patches:` entry there changes the
    rendered StatefulSet, and T4 still passes, because D1 is caught only as the literal `replicas: 2` in
    `statefulset.yaml`.
  - The same holds for patches that change probes, the grace period or the security context.
  - Also not checked:
    - `initContainers` or a second container (only `order-integration` is read);
    - `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS`;
    - `command`/`args` overrides;
    - the headless `serviceName`;
    - "no egress rule".
  - Recommended: run the T4 rules on `kubectl kustomize deploy/k8s/release` output (the rendered document), with the
    raw-file rules kept for the generator and the overlay.
- [WARN] **Q-3: the gate's own definition is guarded only by review** (developer check 20, D8).
  - A pull request that edits `gate.yml` or `test-inventory.sh`, or filters at method level (`-Dit.test=KillIT#x`,
    JUnit tags), passes T11. T11 checks classes, not methods.
  - Branch protection and CODEOWNERS are not available on this plan (review decision 4). This is accepted by design,
    but the deployment-fault run shows it in practice.
  - Recommended: say it in the runbook's "Deploy a release" step 1. The person deploying checks that `gate.yml`,
    `deploy/ci/` and `pom.xml`'s test executions are unchanged since the last reviewed release, or reads their diff.
- [WARN] **Q-4: the released image cannot run natively on the designated production cluster (W6-F6, still open).**
  - The release is `linux/amd64` only (`gate.yml:97`, design Not Doing).
  - `trailhead-prod` is a kind cluster on this Apple silicon machine, so it is arm64 (`deploy/README.md:150-154`).
  - The runbook offers emulation "at best", or a local arm64 build, "which is then not the released image". That is
    the opposite of D14 ("the release is the image by digest").
  - No developer check decides W6-F6. Checks 17–20 do not mention it.
  - Recommended decision before the production deployment:
    - (a, recommended) build a multi-architecture image in job 2 (`docker buildx --platform linux/amd64,linux/arm64`,
      T2/T3 on both), so the digest in `release.yaml` is an index that runs on both;
    - or (b) state that `trailhead-prod` runs under emulation, and record the startup-probe margin it was seen with.
- [WARN] **Q-5: the TLS 1.1 refusal is not shown by any record (W5-F1).**
  - Developer check 14 (after wave 5) says it was "checked by the developer against the production deployment with
    `openssl s_client -tls1_1` (see the deployment check)".
  - At that point no release existed (the first release is check 19). The user says the production deployment is done
    separately. No "deployment check" exists in `developer-checks.md` or `evidence/`.
  - The execution report summary still marks W5-F1 "checked … (check 14)".
  - The smoke counts the client's own refusal as a pass: `curl: (35) … no protocols available` → "refused by the
    client's OpenSSL, the server not reached" (`smoke.sh:261-271`). So the green CI runs do not prove it either.
  - The configuration is right (`server.ssl.enabled-protocols=TLSv1.3,TLSv1.2`, `application.properties`), so the risk
    is low, but the claim is unsupported.
  - Recommended:
    - record the `openssl s_client -tls1_1 -cipher 'DEFAULT:@SECLEVEL=0'` output (the server's `protocol version`
      alert) when the developer deploys `trailhead-prod`, or make it a smoke step with a client that can offer TLS 1.1;
    - correct check 14's wording.
- [WARN] **Q-6: the execution report's closing summary is out of date.**
  - It lists W5-F6 and W6-F1 as **open** (`execution-report.md:946,948`). Its own "Fixes after the first CI run"
    section closes W6-F1 (`:861`), and check 19 proves W5-F6.
  - Check 19's results (the release, the digest, the §7 report items) and check 20 (the fault table) are not in the
    report.
  - The frontmatter's `generated.at` (14:13Z) predates session 10's edits, although `doc check` passes.
  - Recommended: one closing row per finding, with the status after checks 17–20.
- [WARN] **Q-7: 005 decision 4 was not carried forward.** It sent Q-3, Q-5, Q-6, Q-7 and Q-10 "to the next pipeline
  that touches the failure tests". 006 touches them (D13/T9 closed Q-8, and T13/T14 were added), but the design, the
  plan and the report do not mention the other five. They are still open, with no record of the decision to defer
  them. Recommended: list them as deferred in `open_questions.md` or in the next design's inputs.
- [WARN] **Q-8: A-9's "presented to the integration architect before it is built" is not recorded as done.**
  - The design requires it because A-9 removes an endpoint from a route.
  - The plan resolved it at planning time (P-3/P-4), and Task 14 built it after review lenses. No approval by the
    architect is written down; developer check 5 accepts only W2-F1–F3.
  - The change itself is sound: C-014's temporary name and rename, the stat-before-write guard, no `mkdir`, UTF-8.
  - Recommended: record the approval in `developer-checks.md` or in a design-review addendum.
- [WARN] **Q-9: some gate tools are not pinned.**
  - `kubectl kustomize` (jobs 3 and 5) and `kustomize edit` (job 5) come from the runner image at whatever version it
    holds. The release render therefore depends on an unpinned tool (W6-F7 checks only that it is present).
  - `kind` is a `go install` at a pinned version, built with the runner's Go (W6-F2).
  - Design §3.15 pins Trivy, kubeconform and actionlint only, so this is within the design, but the render of the
    release is the step whose output is deployed.
  - Recommended: run `kustomize` from a digest in `deploy/local/tool.sh`, as the other tools are.
- [WARN] **Q-10: accepted residual risks, restated.**
  - W2-F1: the SFTP deadline is proven only at channel start. A hang in `stat`, `put` or `rename` on a live session is
    untested.
  - W5-F5: exit 3 from `ExitOnOutOfMemoryError` was never observed.
  - W5-F3: 16 s without a ready pod per rollout.
  - The developer accepted each one (checks 5 and 15). They stay inputs to the monitoring pipeline.

*(Quality has 16 items: 6 PASS, 1 FAIL and 9 WARN.)*

### Security Analysis
- [FAIL] **S-1 (rule 1): dev passwords committed** in `config/application-dev.properties`. Carried over; 006 added only
  the `peakmart.group-instance-id=dev` line and a comment.
- [FAIL] **S-2 (rule 1): dev partner API key committed** in `config/dev/secrets/partner-api-key-summit-outfitters`.
  Carried over and not changed.
- [FAIL] **S-3 (rule 2): Kafka without TLS** (OQ-038). The design's Not Doing repeats it: the release "can reach a
  plain-text broker only".
- [FAIL] **S-4 (rule 2): Product Catalog over plain HTTP** (OQ-039). Not changed.
- [PASS] **No new secret in the base or release manifests.** Every secret comes from Secrets that a person creates
  (`envFrom.secretRef`, the files Secret, the Secret Store directory). In CI they are random per run (`smoke.sh`
  step 1). `.trivyignore.yaml` is empty. Q-1 is the gap in the gate, not a secret present in the files.
- [PASS] **Pod and image hardening (D1, D8).**
  - Pod: `runAsNonRoot`, uid/gid 10001, `seccompProfile: RuntimeDefault`, `allowPrivilegeEscalation: false`,
    `readOnlyRootFilesystem`, `drop: [ALL]`.
  - ServiceAccount token not mounted (pod and account).
  - Image: `/app` owned by root with no write bit. Dockerfile `:23` (`chmod -R a-w extracted`) and `:31` are enough,
    since `COPY` keeps the stage-1 modes.
- [PASS] **Health surface (D3).**
  - Only `health` is exposed, with `show-details=never`, on 9090.
  - 9090 is in no Service, and the NetworkPolicy admits it only from the same namespace.
  - The probe groups are pinned to the availability states.
- [PASS] **SFTP after A-9.** The bean keeps `StrictHostKeyChecking=yes` with the configured known-hosts file, checks
  the file name against `^PL-(TG-\d{4,}|PM-\d+)\.csv$` before any call (`WarehouseSftp.java:59`), and logs no
  credentials.
- [PASS] **CI least privilege (D14/D15).**
  - `permissions: contents: read` at the top, `actions: read` only in `image`, `packages: write` only in `release`.
  - Only the `pull_request` and `push` triggers, and every action pinned by SHA.
  - The token reaches `docker login` through stdin, and no deployment job exists.
- [PASS] **Vulnerability threshold (D2).** `scan.sh` runs the design's flags. It fails on an expired entry, an entry
  more than 90 days out, or one without a package or statement. Findings new since the baseline are marked.
- [WARN] **S-5: the CI overlay deploys a variant of the release.**
  - `deploy/k8s/ci/kustomization.yaml:40-44` adds `JAVA_TOOL_OPTIONS` with a literal truststore password
    (`changeit`) and replaces the JVM's truststore with the CI CA only.
  - It holds no private key and the comment justifies it (W4-F2), but the smoke does not test the release's own JVM
    trust. T4 does not look at it (Q-1, Q-2).
- [WARN] **S-6: `.trivyignore.yaml` "who accepted it" is not enforced.** "The reason and who accepted it" is one
  free-text `statement` that only has to be non-blank. A statement of spaces passes (`scan.sh:73,96`), and the id
  format is not checked. An exception is still a reviewed change, so this is low risk.

### Anti-Patterns
- [PASS] **Duplicate consumers and pollers are designed out.** One StatefulSet pod with `OrderedReady` and Kafka static
  membership (the broker fences a second member with the same id) remove the double-email and double-pick-list races
  of L7. T4 and the smoke rollout check enforce it.
- [WARN] No circuit breaker on the Product Catalog. This is deliberate and carried over from 005.
- [WARN] Carried over: long routes and inline Groovy mappings, unchanged by 006.
- [WARN] A stat-then-rename window in `upload`. JSch's `posix-rename` overwrites, so the stat before it is the only
  guard (plan P-4). This is the same window as the old `fileExist: Fail`, and one instance (D4) closes it. It becomes
  a race again only if D4 is ever broken.

### Constitution Compliance
- Rule 1 (Route Structure): PASS. No route added or removed. `to-warehouse-sftp` keeps its id and its place.
- Rule 2 (Single Responsibility): WARN, carried over (routes with more than 7 steps).
- Rule 3 (Separation of Concerns): WARN, carried over (inline Groovy). The new upload logic is in a bean, not in the
  route.
- Rule 4 (Naming Conventions): PASS. `peakmart.group-instance-id` follows the project's style. The Kubernetes objects
  are named `order-integration-*`.
- Rule 5 (Observability): PASS. Route ids and descriptions are kept. Failure ITs now report the assertion (T9). The
  upload's `last_failure` names the SFTP step and status (check 4).
- Rule 6 (External Configuration): PASS for the files. `groupInstanceId` comes from a property and `${HOSTNAME}`.
  There are no hosts or secrets in routes or images. The gate's enforcement gap is Q-1.
- Rule 7 (Component Verification): PASS. No new component. `kafka.groupInstanceId` and `bean` were checked against
  the MATCHED 4.22.2 catalog. `RouteCatalogTest` and the validator run in every build.
- Rule 8 (Infrastructure via Forage): PASS. No infrastructure bean added. D16/D17/A-9 change the existing
  `warehouseSftp` bean only.

## Recommendations

**Priority fixes (FAIL):**
1. **Q-1.** Add a T4 rule, run on the rendered manifests, that forbids a literal value for any secret-named env entry
  and any secret key in a ConfigMap or generator input. Record its red run against D10 first. This is an
  implementation change for a follow-up execute; validation does not apply it.
2. **S-1 to S-4.** Re-accept them for 006, as 004 and 005 did, or fix them (dev secrets out of git; close OQ-038 and
  OQ-039).

**Decide before the developer's `trailhead-prod` deployment:**
3. **Q-4 (W6-F6).** A multi-architecture release image (recommended), or emulation accepted on the record.
4. **Q-5.** Record the server-side TLS 1.1 refusal from that deployment, and correct developer check 14.

**Suggested improvements (WARN), in order of value:**
5. **Q-2.** Run T4 on `kubectl kustomize` output, so that base kustomization fields and patches are checked.
6. **Q-3.** A runbook step: before deploying, review the diff of `gate.yml`, `deploy/ci/` and the pom's test
  executions since the last release.
7. **Q-6.** Bring the execution report's closing summary up to date with checks 17–20.
8. **Q-7, Q-8.** Record the deferral of 005's Q-3/5/6/7/10 and the architect's approval of A-9.
9. **Q-9, S-5, S-6.** Pin `kustomize`, justify or move the CI truststore option, and make the exception's "accepted
  by" a field of its own.

## Assumptions (non-interactive run)

- A-1: the developer's runs (scripts, CI runs 43964902986 and 43975492281, PR #1, fault PRs #2–#13) are taken as
  reported. They were checked against the files (`gate.yml`, `smoke.sh`, `ManifestPolicyTest`, `image-check.sh`,
  `scan.sh`, `test-inventory.sh`), not rerun (user instruction: no build, application, cluster or container).
- A-2: S-1 to S-4 and the carried-over WARNs are taken from 003–005. Their files did not change in a way that matters.
- A-3: `camel.main.run-controller` is kept as the false positive of 002 decision 4 (recommended option) and not counted
  as a new FAIL.
- A-4: Q-1 is rated FAIL, not WARN. A release that contains the production password can pass the gate and be
  published, which breaks constitution rule 6 and design §3.9, not only test coverage. The alternative (WARN, as 005
  rated its untested faults) was not taken, because here the planted fault was caught by accident.
- A-5: Q-4 assumes that `trailhead-prod` is arm64, as the runbook and W6-F6 state. The cluster was not inspected.
- A-6: The T2/T3/T4/T10/T11 audit was done by a read-only subagent. Its two central claims (the secret rule and the
  env-name rule of `ManifestPolicyTest`) were checked against the file before they were used.
