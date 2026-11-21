# Release runbook — order integration (design 006 D12)

For the person who deploys the integration. Each step gives the command and what to check. For pipeline 006 that person
is the developer, and "production" is the local kind cluster `trailhead-prod` (design review decision 6), deployed from
the release artifact of a green `main` run of `.github/workflows/gate.yml`.

**No agent session ever gets the `trailhead-prod` kubeconfig** (design §3.16). Run these commands yourself, in a shell
that no agent uses, with your own credentials. In a real target environment the credentials live with the person
deploying, never on a machine where agents build with Docker, in the repository or in GitHub.

The commands use plain `kubectl` and assume two shell variables:

```bash
kubectl config use-context kind-trailhead-prod   # the target cluster (here: trailhead-prod)
NS=trailhead                                      # the integration's namespace there
```

What the release is: the image `ghcr.io/<owner>/order-integration@sha256:<digest>` and `release.yaml`, which renders
`deploy/k8s/release` with that digest. `release.yaml` holds the StatefulSet `order-integration` (one pod,
`order-integration-0`), the Services `order-integration` (port 8443) and `order-integration-headless`, the ServiceAccount,
the NetworkPolicy and the Partner Configuration ConfigMap `order-integration-partners-<hash>`. It holds **no namespace,
no setting and no secret**: those are the objects of "Prepare".

## Before the first real delivery

- **The Warehouse folders (OQ-093).** The Warehouse lead confirms the inbound and processed folder names **as the SFTP
  login sees them**. The integration takes both relative to the login directory (a leading `/` is removed) for the
  upload and for the "already delivered?" check alike (D16). The defaults are `/inbound/picklists` and
  `/inbound/picklists/processed`; when the confirmed names differ, set them in the settings ConfigMap (below) as given:
  `WAREHOUSE_SFTP_INBOUNDDIRECTORY`, `WAREHOUSE_SFTP_PROCESSEDDIRECTORY`.
- **The partners (OQ-095, P-10).** `deploy/k8s/base/partner-configuration.json` is a stand-in: it holds only the
  development example `summit-outfitters`. Before production, the partners the partner manager confirms replace it, as a
  reviewed change in the repository (T10 checks the file in every build). Never edit the ConfigMap in the cluster.
- **The mail server (OQ-076).** The release expects STARTTLS and a login (`SMTP_USERNAME`, `SMTP_PASSWORD`). The JVM
  trusts only the public CAs of its own truststore. **A mail server whose certificate comes from a private CA needs the
  same truststore option as the CI overlay** (`deploy/k8s/ci/kustomization.yaml`): a `truststore.p12` holding that CA in
  `order-integration-files`, and `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/trailhead/files/truststore.p12
  -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=…` on the StatefulSet. The base and the
  release forbid `JAVA_TOOL_OPTIONS` (T4), so that is an overlay of that environment and a reviewed change, not an edit of
  the release. The truststore then replaces the JVM's own, so it must hold every CA the JVM has to trust (today only the
  mail server's: the database uses `db-ca.crt`, the SFTP server `warehouse_known_hosts`).

## Prepare (once per environment)

1. **The namespace**, with Pod Security "restricted" (D8):

   ```bash
   kubectl create namespace "$NS"
   kubectl label namespace "$NS" pod-security.kubernetes.io/enforce=restricted
   ```

   Check: `kubectl get namespace "$NS" --show-labels` shows `pod-security.kubernetes.io/enforce=restricted`.
   The release's NetworkPolicy admits port 9090 (health) only from the namespace's own pods. If the cluster's network
   plugin also filters the kubelet's probes (kind and most do not), the probes fail and the pod never becomes ready:
   that cluster needs an `ipBlock` rule for its node addresses (W4-F6).

2. **The settings**, ConfigMap `order-integration-settings` (every non-secret `${…}` of
   `src/main/resources/application-default.properties`). Write them to a file, e.g. `settings.env`:

   ```
   KAFKA_BROKERS=<host:port>[,<host:port>…]
   DB_HOST=<order database host, as in its certificate>
   DB_PORT=5432
   DB_NAME=<database>
   DB_USERNAME=<user>
   CATALOG_HOST=<product catalog host>
   CATALOG_PORT=<port>
   SMTP_HOST=<mail server host>
   SMTP_PORT=<port, STARTTLS>
   ORDERDESK_MAILBOX=<order desk mailbox>
   ORDERDESK_SENDER=<sender of the Order Desk Notification>
   PARTNER_HTTP_PORT=8443
   PARTNER_MAIL_SENDER=<sender of the Rejection Email>
   WAREHOUSE_SFTP_HOST=<warehouse SFTP host>
   WAREHOUSE_SFTP_PORT=22
   WAREHOUSE_SFTP_USERNAME=<login>
   # only when the Warehouse lead's confirmed folders differ from the defaults (OQ-093):
   # WAREHOUSE_SFTP_INBOUNDDIRECTORY=<inbound folder>
   # WAREHOUSE_SFTP_PROCESSEDDIRECTORY=<processed folder>
   ```

   ```bash
   kubectl -n "$NS" create configmap order-integration-settings --from-env-file=settings.env
   ```

   `PARTNER_HTTP_PORT` must stay 8443 (the StatefulSet's port and the Service's). Six more variables (`SECRET_STORE_DIR`,
   `PARTNER_CONFIGURATION`, `PARTNER_TLS_CERTIFICATE`, `PARTNER_TLS_PRIVATE_KEY`, `DB_SSL_ROOT_CERT`,
   `WAREHOUSE_SFTP_KNOWN_HOSTS`) are file paths set by the StatefulSet itself. `HOSTNAME` is set by Kubernetes to the pod
   name (`order-integration-0`, the Kafka static member id, D10); never set it. Never set `SPRING_PROFILES_ACTIVE` or
   `JAVA_TOOL_OPTIONS` here (D9).

3. **The secrets**, Secret `order-integration-secrets`, from a file with mode 600 that you delete afterwards (no value
   on a command line, no trailing newline in a value):

   ```bash
   umask 077; cat > secrets.env   # DB_PASSWORD=…  SMTP_USERNAME=…  SMTP_PASSWORD=…  WAREHOUSE_SFTP_PASSWORD=…  (one per line)
   kubectl -n "$NS" create secret generic order-integration-secrets --from-env-file=secrets.env
   rm secrets.env
   ```

4. **The files**, Secret `order-integration-files` (mounted at `/etc/trailhead/files`):

   ```bash
   kubectl -n "$NS" create secret generic order-integration-files \
     --from-file=partner-tls.crt=<partner endpoint certificate chain, PEM> \
     --from-file=partner-tls.key=<its private key, PEM> \
     --from-file=db-ca.crt=<the CA of the Order Database's certificate, PEM> \
     --from-file=warehouse_known_hosts=<known_hosts line(s) of the Warehouse SFTP server>
   ```

   TLS ends at the pod (OQ-078): whatever is in front of the Service must pass the TCP connection through, and the
   partner certificate is the one partners see. `warehouse_known_hosts` names the host as `WAREHOUSE_SFTP_HOST` does
   (`[host]:port` when the port is not 22).

5. **The Secret Store**, Secret `order-integration-partner-api-keys` (mounted at `/etc/trailhead/secret-store`): one key
   per `apiKeyName` of the Partner Configuration, its value the partner's API Key. Created by the Integration Team
   member who registers the partner, with their own access; never in the repository, the pipeline or the release.

   ```bash
   kubectl -n "$NS" create secret generic order-integration-partner-api-keys \
     --from-file=partner-api-key-summit-outfitters=<file holding the key on one line>
   ```

   A partner whose key is missing is not registered, and the log says so (UC-007 A4).

6. **Pulling the image.** The image is in the GHCR package of a private repository, so the cluster needs a pull
   secret: a token with `read:packages` only, of your own account. It must be on the ServiceAccount **before the first
   apply**: Kubernetes gives a pod its ServiceAccount's pull secrets only when the pod is created. Create the account
   yourself (the first `apply` of `release.yaml` adopts it and keeps the field). The token goes into a file with mode 600,
   never onto a command line:

   ```bash
   umask 077
   printf '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$(printf '%s:%s' '<your GitHub user>' "$(cat <token file>)" | base64 | tr -d '\n')" > ghcr.json
   kubectl -n "$NS" create secret generic ghcr-pull --type=kubernetes.io/dockerconfigjson --from-file=.dockerconfigjson=ghcr.json
   rm ghcr.json
   kubectl -n "$NS" create serviceaccount order-integration
   kubectl -n "$NS" patch serviceaccount order-integration -p '{"imagePullSecrets":[{"name":"ghcr-pull"}]}'
   ```

   If `order-integration-0` was created before the patch (`ErrImagePull`, `ImagePullBackOff`), delete it after the
   patch: `kubectl -n "$NS" delete pod order-integration-0`.

7. **The node's architecture.** The release image is `linux/amd64` only (design: no multi-architecture image).

   ```bash
   kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name} {.status.nodeInfo.architecture}{"\n"}{end}'
   ```

   Every node must say `amd64`. On an `arm64` node (a kind cluster on an Apple silicon Mac, such as `trailhead-prod`)
   the image runs under emulation at best: slow enough to strain the 5-minute startup probe, and the lab saw an SFTP
   server never answer under emulation. If it fails with `exec format error` or a startup-probe kill, the release
   cannot run there as built. Use an amd64 node, or (for the local stand-in only) a local `linux/arm64` build of the same
   commit, which is then not the released image.

A missing key stops the start and names every missing variable (`Missing environment variables: …`, C1); see "A crash
loop".

## Deploy a release

1. **Take the artifact `release` of a green `main` run** of the gate. On this repository's plan there is no branch
   protection (design review decision 4): what makes the artifact trustworthy is that job `release` runs only after
   `build`, `image`, `manifests` and `kind` passed **in the same run**. Check that all five jobs are green in that run.
2. **Check the digest.** The run's summary says `pushed ghcr.io/<owner>/order-integration@sha256:<digest>` and
   `release.yaml names …`. Both must name the same digest, and so must the file:

   ```bash
   grep 'image:' release.yaml     # ghcr.io/<owner>/order-integration@sha256:<digest>, the one of the run; never a tag
   ```

3. **Apply** and wait for the pod:

   ```bash
   kubectl -n "$NS" apply -f release.yaml
   kubectl -n "$NS" rollout status statefulset/order-integration --timeout=10m
   kubectl -n "$NS" get pod order-integration-0 -o wide     # READY 1/1, RESTARTS 0
   ```

   The old pod is stopped (up to 90 s, D5) before the new one starts: partners see about 15 s of refused connections
   per release (OQ-088; 16 s in the lab), and resend. A changed Partner Configuration is a new ConfigMap name, so the
   pod rolls by itself and registers the partners at its start (C4).
4. **Check** that it serves: one test order of a test partner gets its answer, and the log shows the start:

   ```bash
   kubectl -n "$NS" logs order-integration-0 | grep -E 'Started CamelApplication|Partner Configuration'
   # "Partner Configuration registered partners=<n>"; any "not registered" or "left out" line names the problem
   ```

   Read logs by order number only (NFR-011).

## Roll back

Apply the previous release's `release.yaml` (C2):

```bash
kubectl -n "$NS" apply -f <previous>/release.yaml
kubectl -n "$NS" rollout status statefulset/order-integration --timeout=2m   # a normal rollback is done here
kubectl -n "$NS" get statefulset order-integration -o jsonpath='{.status.updateRevision}{"\n"}'
kubectl -n "$NS" get pod order-integration-0 -o jsonpath='{.metadata.labels.controller-revision-hash}{"\n"}'
```

**If the rollout did not finish and the pod is still on the old revision** (the two lines differ after the 2 minutes;
a pod being stopped takes up to 90 s, so do not compare earlier), **delete it**:

```bash
kubectl -n "$NS" delete pod order-integration-0
kubectl -n "$NS" rollout status statefulset/order-integration --timeout=10m
```

This is needed in **both** cases the lab showed (W4-F5, W5-F4, developer checks 10 and 16): the bad release's pod
never became ready (a wrong secret, a missing key), and a pod that was ready once and then crash-loops (an image that
fails). With `podManagementPolicy: OrderedReady` the StatefulSet does not replace a pod that is not ready. In the lab
(A4) the pod was ready again 39 s after the rollback, 8 s after the delete. A setting or secret you changed for the bad
release is not undone by `release.yaml`: put the previous object back first.

## Rotate a secret or an API Key, register a partner, change a setting

The process reads its settings, secrets, files and API Keys at start only (C5). So: change the object, then restart.

Each object is replaced whole, with **all** its keys, the same way as in "Prepare" plus `--dry-run=client -o yaml |
kubectl apply -f -`; then the restart:

```bash
# order-integration-secrets: recreate secrets.env with every key (umask 077), then delete it again
umask 077; cat > secrets.env
kubectl -n "$NS" create secret generic order-integration-secrets --from-env-file=secrets.env \
  --dry-run=client -o yaml | kubectl -n "$NS" apply -f -
rm secrets.env
# order-integration-settings (a ConfigMap):
kubectl -n "$NS" create configmap order-integration-settings --from-env-file=settings.env \
  --dry-run=client -o yaml | kubectl -n "$NS" apply -f -
# order-integration-files and order-integration-partner-api-keys: every --from-file=<key>=<file> of Prepare 4 and 5
kubectl -n "$NS" create secret generic order-integration-files --from-file=partner-tls.crt=… --from-file=partner-tls.key=… \
  --from-file=db-ca.crt=… --from-file=warehouse_known_hosts=… --dry-run=client -o yaml | kubectl -n "$NS" apply -f -

kubectl -n "$NS" rollout restart statefulset/order-integration
kubectl -n "$NS" rollout status statefulset/order-integration --timeout=10m
```

- **Rotate** a password, a certificate or a partner's API Key: as above. The partner endpoint certificate is a file of
  `order-integration-files`; a rotation is a restart.
- **Register a partner**: **the key first** (add its `apiKeyName` to `order-integration-partner-api-keys`), then the
  released Partner Configuration (a reviewed change to `deploy/k8s/base/partner-configuration.json`, a green `main` run,
  "Deploy a release"). The new ConfigMap name rolls the pod; the log shows the registration of the partner.
- **Change a setting**: the ConfigMap `order-integration-settings`, then the restart.

Every restart is the same short gap for partners as a release (OQ-088).

## A crash loop (`CrashLoopBackOff`)

```bash
kubectl -n "$NS" get pod order-integration-0     # STATUS CrashLoopBackOff, RESTARTS growing
kubectl -n "$NS" get pod order-integration-0 -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}{"\n"}'
kubectl -n "$NS" logs order-integration-0 --previous --tail=200
```

The cause is in the log of the previous start:

- `Missing environment variables: …` (exit 1, within seconds): the listed keys are missing from the ConfigMap or the
  Secret. Add them (Prepare), then the restart.
- Flyway (`FlywaySqlUnableToConnectToDbException`, `FlywayValidateException`, …): read the **`Caused by` lines**, not
  only the last line, which is often the stack's `… common frames omitted` (A4: `Caused by: … PSQLException: FATAL:
  password authentication failed`). A wrong password or host is a setting or a secret; a failed migration is a release
  to roll back.
- `Fatal error routeId=… error=… — the process stops` (exit 70): an error of the runtime inside a step. Exit **3**: an
  `OutOfMemoryError` that the JVM itself ended (`-XX:+ExitOnOutOfMemoryError`). Kubernetes restarts the pod; nothing is
  lost (every state is durable). **A fatal error that repeats on every start** is the same event, redelivered: the pod
  restarts with back-off for as long as it lasts, the event is never set aside for it (OQ-089). Roll back to the
  previous release, or fix the cause.

After the fix or the rollback, check the revision and delete the stuck pod as in "Roll back".

## A lost node

When the node of `order-integration-0` is gone (`kubectl get nodes` shows it `NotReady`, the pod `Terminating` or
`Unknown`), the StatefulSet does **not** start a second `order-integration-0` while the old one may still run: that is
the guarantee against two instances (K5). The integration is down until you act:

1. **Confirm that the node is really gone**: powered off, or removed from the cluster's provider. A node that is only
   cut off from the API server may still run the old process, which would then run next to the new one (two
   instances: emails sent twice, a second pick list).
2. **Only then** delete the pod, so that the StatefulSet starts it on another node:

   ```bash
   kubectl -n "$NS" delete pod order-integration-0 --force --grace-period=0
   kubectl -n "$NS" rollout status statefulset/order-integration --timeout=10m
   ```

**Never before** the node is confirmed gone.

## Never scale above 1

`replicas` is 1 and stays 1. **Never** `kubectl scale`, never an autoscaler, never a Deployment of the same image
(D4, OQ-087). The outbox pollers take no claim: two pods sent 152 of 200 Order Desk Notifications twice in the lab
(L7) and race for pick lists, and the catalog limit of 20 calls a second is per process. `ManifestPolicyTest` (T4)
fails the build on a manifest that asks for more. More instances need leader election first (a later pipeline).

The Kafka consumer is a static member named after the pod (`order-integration-0`, D10). A restarted or replaced
`order-integration-0` takes its partitions back at once. A stop **without** a new `order-integration-0` (the StatefulSet
deleted or renamed, the image run under another pod name) leaves the partitions with the old member until its 45 s
session ends: about 45 s more without intake (W3-F3).

## Exceptions to the vulnerability gate (`.trivyignore.yaml`)

The gate (job `image`, `deploy/ci/scan.sh`) fails on any HIGH or CRITICAL vulnerability that has a fixed version, in the
image or in the jar (D2, OQ-091). It can turn red with no code change, because the vulnerability database changes daily;
the job summary marks each finding that is new since the last green `main` run. Fix it (a patch upgrade inside C-001 and
C-002), or add an exception to `.trivyignore.yaml`: the vulnerability id, the package (`purls`), the reason and who
accepted it (`statement`), and an expiry (`expired_at`) at most 90 days out, in the file's layout. An exception is a
reviewed change, never added by an agent (§3.16); the gate fails on an expired entry.

## Who may do what

| Where | Who | Not |
|---|---|---|
| Production (here `trailhead-prod`) | the person deploying, with this runbook | no agent session: none ever gets its kubeconfig |
| CI (`.github/workflows/gate.yml`) | builds, tests, scans, deploys only to kind inside the runner; on `main` pushes the image and the release artifact | no deployment job, no cluster credential, no secret besides `GITHUB_TOKEN` |
| Developer machine | agents: the repository, `./mvnw`, `docker build`, the lab cluster `trailhead-lab` through `deploy/local/kube.sh` | agents: `kubectl` directly, another cluster or kubeconfig, `git push` |
