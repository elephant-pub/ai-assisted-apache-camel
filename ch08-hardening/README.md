# Chapter 8: hardening

The chapter 7 order integration hardened from its failure modes by the Camel Kit pipeline `005-hardening`. The design
ran a failure lab against the chapter 7 jar and wrote a failure table: every system, every way it fails, what happens
today, what the spec requires. Six changes follow from it, each with a test that failed first:

- H1: an `errorContainment` wrapper around every step turns an `Error` into an `UnexpectedErrorException` and stops
  the process with exit code 70 on a `VirtualMachineError`.
- H2: a partner request is answered within 9 s whatever holds it.
- H3: time limits for the Order Database (Flyway has its own connection).
- H4: the intake waits for the database, and sets aside what it can't take in.
- H5: an order that can't be stored is parked, and the check poll takes the least recently checked first.
- H6: a failed pick list or email keeps the reason in `last_failure`.

Every failure ends in a state in the Order Database that the README's queries find (below). Spring Boot 4.1.1,
Camel 4.22.2, Citrus 5.0.1.

| Folder | What it holds |
|---|---|
| `src/main/` | Routes (YAML), SQL, Flyway migrations V1 to V3, Java beans (`ErrorContainment`, `PartnerTimeoutAnswer`), `application.properties` |
| `src/test/java/.../spec/` | The five spec tests of chapter 7 and their harness |
| `src/test/java/.../failure/` | `KillIT` and the failure tests: database frozen, delivery and mail failing, an `Error` at chosen steps, an order that can't be stored, the README's queries |
| `src/test/java/.../fault/` | The test-only fault hook, packaged as `order-integration-0.1.0-fault-hook.jar` |
| `src/test/` (rest) | 175 unit tests, among them `ErrorContainmentTest` and `JsonValidatorPinTest` |
| `config/` | Development settings, read from the working directory and not packaged |
| `docs/` | The spec, and `camel-kit/001…005/`. `005-hardening/` holds the design with its failure table, the review, the plan, the execution report, the evidence (lab runs, red and green runs, build times), the validation report, and the developer checks and decisions |
| `faults/` | The fourteen faults of the chapter, the script that runs the build once per fault, and the results |

## Build

```bash
./mvnw verify            # unit tests, the five spec tests, then the kill test and the failure tests; needs Docker; bundles the partner contract into the jar
./mvnw verify -DskipITs  # unit tests only (JsonValidatorPinTest, FailureKindsTest, ErrorContainmentTest and WarehouseSftpLookupTest included), without Docker
```

`./mvnw verify` needs Docker and takes 15 to 18 minutes on a laptop, most of it `KillIT` (about 40 s of Kafka session timeout after every restart). Besides the unit tests it runs the five executable spec tests,
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

## Fault injection

```bash
cd faults && python3 run_faults.py ..                      # all fourteen faults, about four hours
cd faults && python3 run_faults.py .. F9-intake-db-handled # one fault
```

Each run copies the project to `faults/work/<fault>`, applies one exact text change, runs `./mvnw verify` with test
failures ignored and prints the test classes that failed. `results.txt` holds the chapter's runs. F5 to F8 come from
chapter 7. F9 to F13 were written before any session of the pipeline and never shown to one. F14 to F16, R9 and R11
were written against execute's code.

A fault counts as caught when a test fails. That says nothing about what the integration did under the fault: F8 turned
tests red in the first round while the `Error` containment let its `NoSuchMethodError` through. Read the integration's
logs in `faults/work/<fault>/target/spec-tests/logs/` as well.

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

