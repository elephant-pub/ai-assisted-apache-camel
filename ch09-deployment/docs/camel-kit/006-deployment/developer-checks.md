# Developer checks during execute (2026-11-16)

## After wave 1
1. **W1-F3, `deploy/ci/scan.sh` run whole.** The session could not run scripts outside `deploy/local/`. I ran it on
   the packaged jar of wave 1: exit 0, "no fixable HIGH/CRITICAL finding". On the jar of pipeline 005: exit 1 with the
   table of fixable findings (Jackson 2 and 3, snappy-java, Tomcat). From wave 2 on, sessions may run `deploy/ci/*`.
2. **W1-F2 decided:** as recommended. Wave 3 (Task 20 edits that file) writes `maximumRedeliveries: 2`, drops the
   `peakmart.set-aside-redeliveries` property and removes the validator exclusion from `pom.xml`.
3. **Stray file** outside the workspace (a mistyped path): removed.

## After wave 2
4. **W2-F4 fixed in wave 3.** Since the upload moved into `WarehouseSftp`, a failed delivery's `last_failure` reads
   `SftpException` with no status. The README's runbook says "fix what `last_failure` names (the folder, its rights, the
   SFTP server)", so it must name it again: the SFTP status (id and its text, e.g. permission denied, no such file) and
   the step. `DeliveryFailureIT` asserts the text for the read-only folder; red first.
5. **W2-F1, F2, F3 accepted.** The time limit is proven at the channel start, the hang seen in the lab (F-1); a hang
   in `stat`, the write or the rename goes to the monitoring pipeline's pending-pick-list alert. The connect edge case
   stays within twice the limit. UTF-8 is Java 25's default (JEP 400) and the image sets no `file.encoding`.

## After wave 3
6. **`required-variables-runs.sh` run by the developer:** `P-19 PASSED (29 runs)`, `HOSTNAME` among the 27 variables.
7. **W3-F2 to W3-F5 accepted.** The harness's 30 s kill after a stop is safe (KillIT proves a kill at any step loses
   nothing); the release's grace period is wave 4's (T4). W3-F3 is wave 4's StatefulSet. A dropped connection reading
   `SftpException 4 failure` still names the step and the code.

## After wave 4
8. **W4-F3 checked:** every image pinned by digest in `deploy/`, `src/main/docker/` and the tools (alpine, Trivy,
   Mailpit, curl, Temurin 25.0.5, kubeconform, Kafka 4.3.1, PostgreSQL 16, Python, actionlint) is a multi-architecture
   index that includes `linux/amd64` (`docker buildx imagetools inspect --raw`).
9. **W4-F4 decided:** Burstable is accepted (memory request = limit, CPU request without a limit, so no CPU
   throttling). Wave 6 corrects design §3.7.
10. **W4-F5 decided:** the runbook's rollback step deletes `order-integration-0` when the bad release's pod never
    became ready; lab run A4 in wave 5 runs it that way; wave 6 writes it into `deploy/README.md`.
11. **W4-F2 accepted** for CI; the runbook says that a mail server with a private CA needs the same truststore option.
12. The images `trailhead/order-integration:t2-plant` and `:t2-plant2` removed.

## After wave 5
13. **The usage limit.** Wave 5's first session stopped at the account's weekly usage limit after A1–A9 and the smoke
    re-run; the commit `7ccf545` is `run-session.sh`'s, made when the session ended. The same session was resumed
    (`08b`) and finished the wave.
14. **TLS 1.1 refusal (open in A1):** checked by the developer against the production deployment with
    `openssl s_client -tls1_1` (see the deployment check).
15. **A6b accepted:** the lab load never exhausted even a 64 MiB heap, so exit 3 was not seen; the option is in the
    entrypoint (T2 checks it). **A2's 16 s** without a ready pod per rollout is accepted under OQ-088.
16. **The runbook (Task 38)** covers both cases of W4-F5: a bad release and the switch back from a crash-looping image
    both need `order-integration-0` deleted after the rollback.

## The first CI run (run 43964902986, push to `main`, 2026-11-16)
17. **Result: failure in job 4, as it should be.** `build` passed in 16:38 (so it stays one job, §3.15), `manifests`
    0:15, `image` 1:33, `kind` failed after 5:45, `release` skipped. The smoke printed
    `FAILED pick-list: expected PL-TG-1000.csv on the SFTP server once, got 0 time(s)`; the integration logged
    `Pick list not delivered … cause=JSchUnknownHostKeyException` every 30 s. **CI-1:** `deploy/ci/smoke.sh` writes
    `known_hosts` for host `sftp`, while `deploy/k8s/ci/settings.env` sets `WAREHOUSE_SFTP_HOST=sftp.trailhead-ci-systems.svc`.
    The lab, where both are `sftp`, could not show it; this is the two-namespace path wave 5 named as never run.
18. **CI-2:** the four actions (checkout v4.2.2, setup-java v4.7.1, upload-artifact v4.6.2, download-artifact v4.3.0)
    target Node.js 20, which GitHub has deprecated (the run forced them onto Node.js 24 with a warning). Current
    releases on Node.js 24, each published more than two weeks ago, resolved by the developer with `git ls-remote`:
    `actions/checkout` v7.0.1 `3d3c42e5aac5ba805825da76410c181273ba90b1`, `actions/setup-java` v6.1.0
    `b843ebf9b913146b38da2eaa7aeddf76074ae1ad`, `actions/upload-artifact` v7.0.2
    `cf430e030ddbb5b0abf93d22962f4752f3646cd9`, `actions/download-artifact` v8.0.2
    `9000827ccba6bdab643e8b6fd33ac0654aef8333`.

## The release and the deployment faults (2026-11-16)
19. **Gate green, then the release.** PR #1 (the CI fixes): all four gate jobs green in 26:48, `release` skipped as on
    every pull request. Merged; the `main` run 43975492281 green in 30:00 (build 19:36), `release` green: image
    `ghcr.io/<owner>/order-integration@sha256:780d25d2fbdb71a587c9e168a16b17783bf17f8bf611fbc3929bfdcc2b6550c2`,
    `release.yaml` names it, with the SBOM and both scan reports. The smoke on CI: the known-hosts check, partner,
    Peakmart, both pick lists once, TLS 1.2/1.3, exit 70 (restart count 1; 20 of 20 recorded once), the rollout (at most
    one pod, 0 × 404).
20. **Twelve deployment faults through the real gate.** D1–D12 were described on 2026-11-15 before any session of this
    chapter (`faults/faults.py`); each ran as a pull request from `main` (#2–#13, closed, never merged).

    | Fault | Change | Failed job | Caught by |
    |---|---|---|---|
    | D1 | `replicas: 2` | build | `ManifestPolicyTest` |
    | D2 | liveness group includes `db` | build | `ProbesIT` |
    | D3 | readiness probe a TCP check on 8443 | build | `ManifestPolicyTest` |
    | D4 | grace period 30 s | build | `ManifestPolicyTest` |
    | D5 | image `USER 0:0` | image | `image-check.sh`: `FAILED user: expected 10001:10001, got 0:0` |
    | D6 | commons-text 1.9 in the jar | image | `scan.sh`: 1 fixable CRITICAL |
    | D7 | entrypoint without `MaxRAMPercentage` | image | `image-check.sh`: `FAILED entrypoint` |
    | D8 | the gate's verify with `-DskipITs` | build | `test-inventory.sh`: each IT `no report` |
    | D9 | release image by tag `latest` | build | `ManifestPolicyTest` |
    | D10 | `DB_PASSWORD: changeit` as a literal in the StatefulSet | kind | **by accident**: the literal was the wrong password, Flyway's login failed, CrashLoopBackOff. No check looks for a secret value in a manifest; the right password committed there would pass the gate |
    | D11 | `spring.profiles.active=dev` in the jar | kind | smoke: CrashLoopBackOff (the dev file is not in the image) |
    | D12 | Partner Configuration without the name hash | build | `ManifestPolicyTest` |

    Twelve of twelve fail the gate; eleven for the reason they were planted. **D10 is a miss** of the design: §3.16
    and T4 check for a `Secret` with data, not for a secret value written into a manifest's `env`.
    **D8 holds only for this change**: a pull request that also edits `test-inventory.sh` or the job would pass. The
    gate's own definition is guarded by review alone, and branch protection and CODEOWNERS are not available on this
    plan (review decision 4).

## Session 12 through the gate, and the deployment (2026-11-17 and 2026-11-18)
21. **Session 12's changes on GitHub.** PR #14 ("Fixes after validation"): build, manifests, image and kind green in
    26:52, `release` skipped. Merged; the `main` run 43988785699 green in 29:04 (build 19:23), `release` green: image
    `ghcr.io/<owner>/order-integration@sha256:fe251f968e95168e082da696cb3c23d9913f602f4f2e4de28450aad22424b856`, and
    the run's summary and `release.yaml` name the same digest. **D10 again**, with a value that looks right (D10b, PR #15,
    closed): `build` fails at once, in `ManifestPolicyTest.noSecretValueInTheManifests` ("T4 no secret value in a
    manifest …: 1 violation(s)"); the later jobs are skipped. The miss of check 20 is closed by a check that looks for
    it, not by a wrong password.
22. **Deployment to `trailhead-prod` with the runbook** (`evidence/deploy-prod.md`). The developer, in a shell no
    session uses, did "Prepare" 1–7 and "Deploy a release" 1–4 for the release of check 21:
    - **Pull secret** (Prepare 6): a classic token of the repository's owner with `read:packages` only (the API answered
      `x-oauth-scopes: read:packages`), into `ghcr-pull` through a mode-600 file, on the ServiceAccount before the
      first apply. The pod pulled the image by digest in 5.3 s. Afterwards the token file and `ghcr-pull` were deleted
      and the owner revokes the token; the pod keeps the image (`IfNotPresent`), and the next release needs a new token.
    - **The mail server's CA** ("The mail server", OQ-076): the stand-in Mailpit has a certificate of the private
      `trailhead-prod` CA, so `truststore.p12` was added to `order-integration-files` and `JAVA_TOOL_OPTIONS` set by a
      local overlay of `release.yaml`, outside the repository. `kubectl kustomize` of the overlay differs from
      `release.yaml` in that one env entry; the image is the released digest.
    - **Architecture (validation decision 4):** the node is `arm64`, the image `linux/amd64`. It ran under emulation
      (`uname -m` in the pod: `x86_64`): ready 27 s after the apply, `Started CamelApplication in 16.177 seconds`, 0
      restarts. The release runs as released; no multi-architecture image is needed for this stand-in. Runbook
      Prepare 7 predicts a startup that strains the 5-minute probe on such a node: that held for the lab's SFTP server,
      not for this image here (P-1).
    - **Serving** (Deploy 4): `Partner Configuration registered partners=1`. Partner order TG-1000: 200, the resend
      the same answer, TG-10000 422; Peakmart PM-719079 recorded; `PL-TG-1000.csv` and `PL-PM-719079.csv` on the
      Warehouse SFTP server once each; one Order Desk Notification for TG-1000.
    - **TLS (validation decision 5):** 1.2 and 1.3 answered (405 for a GET on `/orders`). For 1.1, the client pod's curl
      refuses to offer it (`no protocols available`, as in CI, W5-F1). So from the developer's machine, OpenSSL 3.6.2
      with `-tls1_1 -cipher DEFAULT:@SECLEVEL=0` through a port-forward: it sends a TLS 1.1 ClientHello and the
      integration answers `Alert … fatal protocol_version` (alert 70). The server refuses TLS 1.1 itself.
    - **Two warnings in the start log**, `CamelSecurityPolicyAutoConfiguration`: "Secret property is configured with
      plain-text value" for `camel.component.smtp.username` and `camel.component.smtp.password`. Both are
      `${SMTP_USERNAME}` and `${SMTP_PASSWORD}`, from the Secret `order-integration-secrets`; Spring resolves them before
      Camel looks, so Camel sees the value, not the placeholder. Not a finding about the release; noted so that nobody
      reads the warning as a leak (P-2).

    Findings for a later pipeline: **P-1** the runbook's Prepare 7 says what this deployment did not see; it should say
    what was measured (an emulated amd64 image started in 16 s; the lab's SFTP stand-in was the one that failed).
    **P-2** the two Camel warnings, explained in the runbook's "Check" step or silenced for these two keys.
