# Chapter 9: deploying to production

The chapter 8 order integration made deployable by the Camel Kit pipeline `006-deployment`. The design ran a deploy
lab (a kind cluster, `trailhead-lab`) against the chapter 8 jar and designed the deployment from how it failed there:

- one instance, a StatefulSet with one replica (two pods sent 152 of 200 emails twice in the lab);
- health on its own port, liveness and readiness pinned to the process's own state, a startup probe;
- a Temurin 25 JRE image: root-owned read-only `/app`, user 10001, heap from the container's limit;
- the Kafka consumer as a static member named after the pod; a 90 s grace period; restricted pod security;
- TLS at the pod, secrets and the Secret Store as mounted Secrets, the Partner Configuration as a hashed ConfigMap;
- a CI gate on GitHub (build, image and vulnerability scan, manifests, a smoke test in kind) whose last job
  publishes the release: the image by digest and `release.yaml`;
- two delivery fixes the lab found: a time limit on the SFTP channel start (F-1), and folders taken relative to the
  login directory for the upload and the "already delivered?" check alike (F-2).

The developer deployed the release to a second kind cluster, `trailhead-prod`, with the runbook
(`docs/camel-kit/006-deployment/developer-checks.md`, check 22). Spring Boot 4.1.2, Camel 4.22.2, Java 25 (Temurin).

| Folder | What it holds |
|---|---|
| `src/main/` | Routes (YAML), SQL, Flyway migrations V1 to V3, Java beans, `application.properties` and `application-default.properties` |
| `src/test/` | 204 unit tests (among them `ManifestPolicyTest`, `JavaRuntimeTest`, `PartnerConfigurationReleaseTest`), the five spec tests, and 31 integration tests: the kill test, the failure tests, the probe and SFTP tests |
| `config/` | Development settings, read from the working directory and never in the image |
| `src/main/docker/` | The `Dockerfile`: a Temurin 25 JRE image around the gate's jar |
| `deploy/` | `README.md` (the runbook), `k8s/` (base, release and CI manifests), `ci/` (the gate's scripts), `local/` (the lab: `kube.sh`, the only way a session reaches a cluster, and `tool.sh`, the pinned scanners) |
| `.github/workflows/` | `gate.yml`, the five jobs |
| `.claude/settings.json` | The deny rules the developer gave the sessions: no credentials, no cluster tools, no edits to `.claude/` |
| `docs/` | The spec, and `camel-kit/001…006/`: design specs, reviews, plans, execution and validation reports, decisions, the developer checks and their evidence |
| `faults/` | The twelve deployment faults and the script that ran each through the gate as a pull request |

## Build

```bash
./mvnw verify            # unit tests, the five spec tests, then the kill test and the failure tests; needs Docker; bundles the partner contract into the jar
./mvnw verify -DskipITs  # unit tests only (JsonValidatorPinTest, FailureKindsTest, ErrorContainmentTest and WarehouseSftpLookupTest included), without Docker
```

`./mvnw verify` needs Docker and takes several minutes. Besides the unit tests it runs the five executable spec tests,
one per approved test case (TC-001 to TC-005). They run against the packaged jar and share one set of Kafka,
PostgreSQL 16 and SFTP containers that Testcontainers starts once per run (TC-005 adds its own SFTP server). Nothing
has to be running before, and no port is fixed. Without
Docker the build fails rather than skipping them. Developers without Docker run `./mvnw verify -DskipITs`.

After the spec tests, `./mvnw verify` runs the kill test and the failure tests (`src/test/java/…/failure/`), also
against the packaged jar. The kill test (`KillIT`) stops the process with a hard kill at each kill point and checks that
after the restart no order is lost and no second pick list is delivered. The failure tests give the integration a
failing database, SFTP folder or mail server, or a step that throws an `Error`, and check where each order ends. Tests
that need a fault start the same jar with a test-only fault hook (`target/order-integration-0.1.0-fault-hook.jar`);
the production jar has none.

Every `verify` writes the traceability matrix to `target/spec-tests/traceability-matrix.md` (and `.json` for CI). For
each use case it shows which test case steps ran and which are still *not yet testable* because their use case (UC-005,
UC-006, UC-008) is not built. The integration's logs from the run are in `target/spec-tests/logs/`.

## Run it in development

Each long-running command in its own terminal:

```bash
camel infra run kafka --background
camel infra run postgres --port 15432 --background
docker run -d --name trailhead-mailpit -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit:v1.21

# The warehouse SFTP server, on this machine only
mkdir -p /tmp/warehouse/picklists/processed && chmod -R 777 /tmp/warehouse
docker run -d --name trailhead-warehouse-sftp -p 127.0.0.1:2222:22 \
  -v /tmp/warehouse:/home/admin/inbound atmoz/sftp:alpine admin:admin:1001
ssh-keyscan -p 2222 -t ed25519 127.0.0.1 > config/dev/warehouse_known_hosts

(cd ../ch05-prototype/prototype/stubs/product-catalog && ./run.sh)     # Product Catalog stub, 127.0.0.1:8091
java -jar target/order-integration-0.1.0.jar --spring.profiles.active=dev
```

Then:

```bash
src/test/scripts/partner-post.sh TG-1060          # a partner order with the dev API key; the answer is printed
ls /tmp/warehouse/picklists                        # PL-TG-1060.csv, about 30 s later
open http://localhost:8025                         # rejection emails and Order Desk notifications
```

The sessions used `camel infra run sftp` for the warehouse. Don't: it serves the whole file system of the machine with
the password `admin`, on every network interface. The container above is bound to 127.0.0.1 and sees one folder.

Without a profile, every host, port and secret comes from the environment; the application refuses to start and
names every missing variable (`RequiredVariablesCheck`).

## Finding and moving stuck orders

For the Integration Team. An order, event, pick list or email that cannot go on stays in the Order Database with the
reason, and a WARN or ERROR line with the order number and correlation id is logged. For each such end state below:
the query that finds it and the step that gets it moving again. `EndStateQueriesIT` reads every `sql` block of this
section and runs it against PostgreSQL, so the statements are tested, not only written.

The statements use two parameters, `:id` and `:order_number`. In `psql`, set them first; a text value needs its
quotes inside the variable, because psql puts the value in unquoted (`:order_number` without them fails):

```text
\set id 42
\set order_number '''TG-1931'''
```

Or replace the parameter by its value, a text value as a quoted literal (`'TG-1931'`).

### Set aside: unreadable, or cannot be taken in

```sql
-- end-state: set-aside
SELECT id, event_id, order_number, cannot_read_reason, arrived_at
FROM set_aside_order_event
WHERE set_aside_state = 'Set Aside'
ORDER BY arrived_at DESC, id DESC;
```

Nothing to run now. The event waits for resubmission (UC-008, not built yet) and is kept for 90 days. The
`cannot_read_reason` names what to fix first (`unreadable: …` for the event content, `cannot be taken in: …` for the
step that failed).

### Waiting for Check: catalog outage or unexpected error

```sql
-- end-state: waiting-for-check
SELECT id, order_number, received_at, waiting_reason, last_check_at
FROM order_receipt
WHERE waiting_for = 'Check'
ORDER BY received_at, id;
```

Nothing to run. The order is checked again on every poll and moves on once the Product Catalog answers
(`catalog outage`) or the defect is fixed (`unexpected error: …`).

### Waiting for Recording: cannot be recorded

```sql
-- end-state: waiting-for-recording
SELECT id, order_number, received_at, waiting_reason
FROM order_receipt
WHERE waiting_for = 'Recording'
ORDER BY received_at, id;
```

The order passed every check but does not fit the Order Database (ERROR `kind=unstorable`); it is not polled again.
First fix what `waiting_reason` names. Then send it back to the check with its `id`; `last_check_at = NULL` puts it
first in the next poll:

```sql
-- end-state: release-recording
UPDATE order_receipt SET waiting_for = 'Check', waiting_reason = NULL, last_check_at = NULL WHERE id = :id AND waiting_for = 'Recording' AND order_state = 'Received';
```

The statement changes nothing for an order that is not waiting for Recording (0 rows updated).

### Pick list not delivered

```sql
-- end-state: pick-list-not-delivered
SELECT p.id, c.order_number, p.file_name, p.attempts, p.last_attempt_at, p.last_failure
FROM pick_list p
JOIN customer_order c ON c.id = p.customer_order_id
WHERE p.delivery_state = 'Pending'
ORDER BY p.created_at, p.id;
```

Nothing to run. Fix what `last_failure` names (the folder, its rights, the SFTP server); the pick list is tried again
on every poll.

### Notification not sent

```sql
-- end-state: notification-not-sent
SELECT n.id, r.order_number, n.notification_type, n.attempts, n.last_attempt_at, n.last_failure
FROM notification n
JOIN order_receipt r ON r.id = n.order_receipt_id
WHERE n.sent_at IS NULL
ORDER BY n.created_at, n.id;
```

Nothing to run. Fix what `last_failure` names (for a `550`, the recipient's setting); the email is tried again on
every poll.

### Partner answered 503 for an order that does not fit

Take the order number from the ERROR `kind=unstorable` line:

```sql
-- end-state: partner-unstorable
SELECT r.id, r.order_number, p.partner_code, r.received_at, r.order_state
FROM order_receipt r
JOIN partner p ON p.id = r.partner_id
WHERE r.channel = 'Partner' AND r.order_state = 'Received' AND r.order_number = :order_number
ORDER BY r.received_at, r.id;
```

Nothing to run in this version: tell the partner (OQ-083).

### Topic not read

Kafka is down, or setting an event aside fails. The consumer group lag shows it:

```bash
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group trailhead-order-integration
```

Bring Kafka or the Order Database back; the consumer resumes from its last commit.

### Process stopped

After a `VirtualMachineError` (for example `OutOfMemoryError`) the process logs `Fatal error … the process stops` and
exits with a non-zero code. Restarting it is the deployment's job (chapter 9). After the restart every order moves on
from where it was.

## Deployment

The build needs **Java 25** (Eclipse Temurin, C-003 as amended; `JavaRuntimeTest` checks the class files). Pipeline
`006-deployment` adds the release:

- `deploy/README.md`: the runbook for the person who deploys: prepare, deploy, roll back, rotate, register a partner,
  crash loop, lost node, and never more than one instance.
- `.github/workflows/gate.yml`: the CI gate on every pull request and push to `main` (build and every test, the image,
  the vulnerability scan, the manifests, a smoke test in kind), and on `main` the release: the image by digest and
  `release.yaml`. No job deploys anywhere else.
- `deploy/k8s/` (base, release and CI manifests), `deploy/ci/` (the gate's scripts), `deploy/local/` (the lab).

## The deployment faults

`faults/faults.py` holds D1 to D12, written before the first session, each as exact text replacements in the
project's files. `faults/run_faults.py push` applies each to a branch from `main` and opens a pull request; `collect` reads each
gate run: whether it failed, which job, and the failing lines (the chapter's results are in `faults/results.txt`). Nothing is merged; close the pull requests afterwards.

```bash
cd faults
python3 run_faults.py push <clone of your repository> <owner>/<repo> D3-no-readiness-gate   # one fault; no name: all
python3 run_faults.py collect <owner>/<repo> D3-no-readiness-gate                          # after the gate has run
```

Twelve of twelve fail the gate; D10 (a password in the StatefulSet) only by accident until validation decision 2.
D10b, the same fault with a value that looks right (run by hand as pull request #15), is caught by `ManifestPolicyTest.noSecretValueInTheManifests`.

