# Decisions on the validation report of 006 (integration architect and developer, 2026-11-16)

1. **S-1 to S-4, carried over from 003 (fail).** Re-accepted for 006 with the reasons of 003 decisions 2 and 3, 004
   decision 1 and 005 decision 1. Nothing under `config/` is in the image (T2 checks it), and Kafka and catalog TLS stay
   OQ-038 and OQ-039.

2. **A secret value in a manifest passes the gate (fail; developer check 20, fault D10). Fixed in this pipeline, before
   it is accepted** (session 12). The release is what a person deploys, so a password in it is exactly what
   constitution rule 6 forbids. A policy check on the **rendered** manifests (`kubectl kustomize` of `deploy/k8s/release`
   and `deploy/k8s/ci`, in the gate's `manifests` job, and locally) rejects:
   - any `env` entry with a literal `value` whose name looks like a secret (`PASSWORD`, `SECRET`, `TOKEN`, `KEY`,
     `CREDENTIAL`, case-insensitive), and any such key with a value in a ConfigMap, a `configMapGenerator` or an
     `.env` file the overlays read;
   - a `Secret` with data, as T4 already does for the files as written.

   Its red run against D10 (with a correct-looking value) is recorded first, then green on `main`'s files. The fault is
   run again through the gate by the developer afterwards.

3. **The policy reads the YAML as written (warn).** The release-relevant rules of T4 (kind, replicas, the probes, the
   image by digest, no `SPRING_PROFILES_ACTIVE`/`JAVA_TOOL_OPTIONS`) also run on the rendered output, in the same check
   as decision 2, so an overlay or a patch in `kustomization.yaml` can't get around them. A case with `replicas: 2` set
   in a kustomization patch, red first.

4. **The image architecture (warn, W6-F6).** Decided by the developer's deployment: the released `linux/amd64` image is
   deployed to `trailhead-prod` by digest as released. If it does not run there, the release becomes a
   multi-architecture image (amd64 and arm64, one index digest, both scanned) in a later change, never a local build
   deployed as if it were the release.

5. **The TLS 1.1 refusal (warn).** Developer check 14 named a deployment check that did not exist yet. It is run against
   the release on `trailhead-prod` with a client that offers TLS 1.1, and recorded in the deployment check.

6. **Smaller warnings.**
   - The execution report's closing summary is brought up to date in session 12 (the findings closed since, developer
     checks 17–20).
   - A-9 (the upload moved into `WarehouseSftp`) was approved by the developer before execute, in the plan prompt
     (`03-prompt.md`: "Move the upload into `WarehouseSftp` only if no option covers it, and then keep C-014's temporary
     name and rename"); wave 2 found no option covered it.
   - 005 decision 4's Q-3, Q-5, Q-6, Q-10 and Q-7 were not taken up here: this pipeline touched the failure tests only for
     Q-8. They go to the next pipeline that changes delivery or the catalog calls, in the order 005 gave.

Pipeline 006 is accepted once session 12's changes pass the gate on GitHub, D10 is caught by the new check, and the
release is deployed to `trailhead-prod` with the runbook.

**Accepted (integration architect and developer, 2026-11-18).** Session 12's changes passed the gate on GitHub (PR #14
and the `main` run 43988785699, developer check 21); D10 with a right-looking value is caught in `build` by
`ManifestPolicyTest.noSecretValueInTheManifests`; and the release of that run is deployed to `trailhead-prod` with
the runbook and serves (developer check 22, which also settles decisions 4 and 5). P-1 and P-2 of check 22 go to the
next pipeline that touches the runbook.
