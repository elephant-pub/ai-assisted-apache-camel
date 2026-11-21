---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T18:36:13.006347Z"
  by: "camel-brainstorm"
---
# Trailhead Order Integration — Spring Boot Move — Design Spec

**Date:** 2026-11-13
**Camel Version:** 4.22.2
**Runtime:** Spring Boot 4.1.1 (Maven layout, YAML DSL routes under `src/main/resources/camel/`), Java 21
**Platform BOM Version:** 4.22.2 (`org.apache.camel.springboot:camel-spring-boot-bom:4.22.2`; catalog
`org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2`)
**Status:** Approved 2026-11-13 by the integration architect, with the four changes of
`docs/camel-kit/002-spring-boot-move/design-review.md` (R1–R4), which are written into this spec (see §8). The open
decisions of the first draft took the recommended option and are listed in §7.

**Sources:** the accepted prototype `prototype/` and its pipeline `docs/camel-kit/001-peakmart-intake/`
(`design-spec.md`, `design-review.md`, `validation-decisions.md`, `kill-test-results.md`), the exported starting point
(`pom.xml`, `src/`, commit `ca1acac`), `docs/api/external/order-desk-mail.md`, `docs/requirements.md`,
`docs/open_questions.md`, `docs/test_cases/TC-003-peakmart-order-rejected.md`.

**Behavior baseline:** "same behavior" means the behavior of the accepted prototype as it is in `prototype/` at commit
`144e002`. That includes the post-acceptance developer review (decisions 9 and 10: `http://{{catalog.host}}`, one
reason per line as `- <text>`). It also includes guards the prototype code has that the 001 spec text does not list:
the record size limit `peakmart.max-record-chars`, the `too large` set-aside, the unstorable `order_ref`/`event_id`
guard, and the U+0000 escape in `event_content`. The business rules come from the use cases, and this spec does not
change them. Where this spec does not mention a route step, the step moves over unchanged.

## Not Doing (and Why)

- **UC-001 partner orders and UC-004 pick lists**: they belong to the next pipeline, as the request says. Recorded
  orders stay `Recorded` and have no `PICK_LIST` row, as in the prototype.
- **Citrus tests**: they come after the next pipeline, with the test cases, as the request says. This pipeline proves
  behavior with scenarios 1–12 and the kill test (§4), as the prototype did. `src/test/` holds only the kill-test hook
  (§3, Flow kill-test support) and one JUnit unit test, `EmailPatternTest`, for the E7 regression (R2).
- **90-day retention purge (C-022)**: the prototype does not do it, and the request does not list it. It is a
  scheduled housekeeping job with its own design questions (what is deleted together, and when). The Flyway schema
  keeps `received_at` on every receipt, so the job can be added later without a migration.
- **UC-006 alert emails**: the prototype logs a WARN line instead, and that stays. UC-006 is a separate use case with
  its own mailbox and tables (A-10 of pipeline 001).
- **OQ-075 (an order that passes the checks but does not fit the Order Database)**: it is an open business question.
  The behavior stays as in the prototype: the order waits and is checked again on every poll.
- **TLS or SASL to Kafka**: Peakmart has not said which broker settings it uses (OQ-038, open). C-024 does not list
  Kafka. The brokers come from the environment. Security settings for Kafka are added when OQ-038 is answered.
- **HTTPS to the Product Catalog**: only the SKU goes to the catalog, so no personal data travels on that connection.
  C-024 does not list it, and the catalog contract has `security: []`. The route keeps `http://`.
- **Several instances at once**: the pollers still take no row locks (no `FOR UPDATE SKIP LOCKED`), the same as A-9
  and Not Doing of pipeline 001. The application runs as one instance.
- **HTTP endpoints (actuator over HTTP, metrics)**: ports 8080 and 8081 are taken, and the prototype opened no port.
  No web server starter is added, so the application listens on no port. Actuator stays on the classpath as exported.
- **Porting the Product Catalog stub**: it is a test double for development. It keeps running from
  `prototype/stubs/product-catalog/run.sh` on 127.0.0.1:8091.
- **Container image and Jib changes**: the exported `src/main/docker/Dockerfile` and the `jib`/`camel.debug` Maven
  profiles stay as they are. Deployment is not part of this request.
- **Removing `prototype/`**: it stays as the record of pipeline 001 and holds the stub. Nothing in the Spring Boot
  project reads it at run time. The prototype integration itself is retired: `prototype/run.sh` refuses to start unless
  `PROTOTYPE_ANYWAY=1` is set (R4, §3 Supporting change: retiring the prototype).

---

## 1. Executive Summary

### Business Purpose
The Peakmart intake, which runs today only as an accepted Camel JBang prototype, becomes the Spring Boot project that
Trailhead Gear will deploy (C-001, C-002, C-003). Peakmart orders are still taken in exactly once, checked, and then
recorded or rejected. The Order Desk now gets a real email instead of a file. The application can run against
production services from the environment alone, and it refuses to start when a setting is missing.

### Business Value
- Same guarantees as the prototype: no Peakmart Order is lost or recorded twice (FR-021, FR-023, NFR-006, NFR-007), and
  this is proven again with the kill test.
- The Order Desk receives the rejection email of FR-031/NFR-014 in its mailbox.
- The Order Database schema is versioned (Flyway V1), and the next pipeline (partner orders, pick lists) adds V2.
- A missing setting in production is caught at startup, not when the first order arrives.
- The Order Database connection is encrypted outside development (C-024, validation decision 2 of pipeline 001).

### Stakeholders
Order Desk (receives the notification email), Integration Team (operates the application, owns the kill test),
Warehouse (later consumer of recorded orders), Peakmart (source only, C-007).

---

## 2. Systems Landscape

| System | Type | Protocol | Role | Development instance (`dev` profile) | Without a profile |
|---|---|---|---|---|---|
| Peakmart order topic | Kafka topic `peakmart.orders.v2` | Kafka | Source | `localhost:9092` (running) | `KAFKA_BROKERS` |
| Order Database | PostgreSQL (C-004; development 17.10) | JDBC | Target and durable waiting store | `localhost:15432`, db `postgres`, user/password `test`, no TLS (running, empty) | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_ROOT_CERT`; `sslmode=verify-full` |
| Product Catalog | HTTP JSON API `GET /products/{sku}` | HTTP | Lookup | stub `127.0.0.1:8091` (running) | `CATALOG_HOST`, `CATALOG_PORT` |
| Order Desk mailbox | Email | SMTP | Target | Mailpit `localhost:1025`, no auth, no TLS; API `http://localhost:8025/api/v1` (running) | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`; STARTTLS required |

The application opens no port. Ports 8080, 8081 and 5432 are not used. No container is started or stopped. The scripts
use `docker exec` only to read from the running `camel-postgres` and `camel-kafka` containers, as the prototype's
scripts did.

---

## 3. Flow Designs

Routes, route ids, node ids, SQL statements, the `OrderChecks` and `CannotReadReason` beans, the DataMapper
`kaoto-datamapper-5c1e9a27`, the reason texts and the log lines all move over from the prototype unchanged, except for
the changes listed here. For the full behavior of each route, see `docs/camel-kit/001-peakmart-intake/design-spec.md`
§3 and the prototype YAML.

### Startup defects of the export (why it does not start, and what changes)

Static review of the export against the prototype found these defects. The binder error the request names is only the
first one to surface.

| # | Defect in the export | Effect | Change |
|---|---|---|---|
| E1 | `camel.component.http.*-timeout={{catalog.timeout-ms}}` | Spring Boot's binder converts the raw text to an integer and fails (the reported startup error) | Spring placeholder `${catalog.timeout-ms}`. In `application*.properties`, a value that refers to another property uses `${…}`. Routes keep `{{…}}` |
| E2 | `{{env:DB_PASSWORD}}`, `{{env:COMMIT_DELAY_MS:0}}`, `{{db.url}}` in properties | Camel Main syntax that Spring does not resolve | `${ENV_NAME}` with no default (§3 Configuration), or removed |
| E3 | `camel.beans.orderDb=#class:org.postgresql.ds.PGSimpleDataSource` | A hand-rolled datasource next to Spring Boot's own, and no pool | Spring Boot datasource `spring.datasource.*` (HikariCP). Routes use `dataSource: "#dataSource"`. Flyway uses the same datasource |
| E4 | `camel.beans.orderChecks=#class:OrderChecks`, `…=#class:CannotReadReason` | The classes are now in package `com.trailheadgear.orderintegration`, so the short names no longer resolve | `@Configuration` class `IntegrationBeans` with `@Bean("orderChecks")` and `@Bean("cannotReadReason")`. The bean names the routes use stay the same, and the two classes stay unchanged |
| E5 | `sql.dir=sql`, `sql:file:{{sql.dir}}/…`; the `prototype/sql/` files were not exported | Paths are relative to the working directory, and the files are not in the jar | The SQL files move to `src/main/resources/sql/` with the same content (except D4). Endpoints become `sql:classpath:sql/<name>.sql` (the catalog lists the `classpath:` prefix for `query`) |
| E6 | `schemas.dir=../docs/messages`, `json-validator:file:{{schemas.dir}}/…` | Works only when started from one directory, and the jar has no schemas | At build time `maven-resources-plugin` copies `peakmart-order-placed.schema.json`, `product.schema.json` and `order.schema.json` from `docs/messages/` into `target/classes/schemas/`. `docs/messages/` stays the single source of truth, and none of the files is copied by hand. The three files have only internal `$ref`s. Endpoints become `json-validator:classpath:schemas/<name>` |
| E7 | `peakmart.email-pattern=^[^@\s]+@[^@\s]+\.[^@\s]+$` (the export dropped the doubled backslashes) | `java.util.Properties` reads `\s` as `s`, so the pattern becomes `^[^@s]+@…`. Any valid address with an `s` is then set aside, and addresses with spaces pass. This is a silent behavior change | Restore `^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$`. Scenario 1 (`…@…` with an `s`) and scenario 5 prove it, and the unit test `EmailPatternTest` guards it in `./mvnw verify` (R2, §3 Supporting change: E7 regression test) |
| E8 | `delay-before-commit` with `peakmart.test.commit-delay-ms` in the intake route | A test hook in production code | Removed from the route and from properties (D5) |
| E9 | `camel.main.name=peakmart-intake-prototype` | Wrong name in logs and JMX | `trailhead-order-integration` |
| E10 | `camel-file-starter`, `camel-timer-starter` | No longer used (the file sink is replaced by SMTP, and the timer-driven schema init by Flyway) | Removed. Added: `camel-mail-starter` (catalog-verified for `smtp`), Spring Boot's Flyway starter and Flyway's PostgreSQL database module, both managed by the Spring Boot 4.1.1 BOM. Implementation takes the exact artifact names from that BOM and does not guess them. `org.postgresql:postgresql` loses its explicit `42.7.13` and uses the version the Boot BOM manages. The execution report records the resolved version. Everything else in the pom, including the `spring-boot-starter-parent` 4.1.1 parent, stays as exported (R1, §3 Supporting change: the pom) |

Implementation also checks the Spring Boot names of the remaining Camel Main keys by starting the application:
`camel.main.log-mask`, `camel.main.additional-sensitive-keywords` and `camel.main.run-controller`. The application must
stay up with no web server. Any further startup error is fixed under the same rule: Spring Boot owns the property files
and the beans, and the YAML routes keep their meaning.

### Flow: peakmart-order-intake (moves; one change)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Purpose, source, routes, DataMapper and error handling:** unchanged from the prototype. Component `kafka`
(MCP-verified) with `breakOnFirstError=true`, `autoCommitEnable=false`, `allowManualCommit=true`. The last step
`commit-kafka-offset` (EIP `script`, MCP-verified) calls `${header.CamelKafkaManualCommit.commit()}` (header
MCP-verified). The routes are `peakmart-order-intake`, `peakmart-event-read`, `peakmart-event-set-aside` and
`peakmart-order-receive`, with `routeConfiguration` `peakmart-intake-error-handling` (`onException` SQL/DataAccess, 3
redeliveries with exponential backoff, not handled).

**Change D5:** the `delay` step `delay-before-commit` is removed. `commit-kafka-offset` keeps its node id, because the
kill test hooks onto that id from outside the route (Flow kill-test support).

**Endpoint changes:** `sql:classpath:sql/set-aside-insert.sql`, `sql:classpath:sql/receipt-insert.sql`,
`json-validator:classpath:schemas/peakmart-order-placed.schema.json`, and `dataSource: "#dataSource"`.

**Configuration properties** (`application.properties`, shared by all profiles):
```properties
peakmart.topic=peakmart.orders.v2
peakmart.group-id=trailhead-order-integration
peakmart.auto-offset-reset=earliest
peakmart.max-record-chars=1048576
peakmart.email-pattern=^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$
```
`kafka.brokers` comes from the profile files (§3 Configuration).

### Flow: peakmart-order-check (moves; endpoints only)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Unchanged:** `sql` consumer (MCP-verified) on the waiting receipts, `split` (MCP-verified) with
`GroupedBodyAggregationStrategy`, the shared `throttle` (MCP-verified, `TotalRequests`, `{{catalog.max-calls-per-second}}`
per 1000 ms), `http` (MCP-verified) with `throwExceptionOnFailure=false`, the 200/404/other mapping, `IOException` →
OUTAGE, the single-statement CTEs for reject and record, the `order.schema.json` guard, and `routeConfiguration`
`order-check-error-handling`.

**Endpoint changes:**
- `sql:classpath:sql/waiting-select.sql` (consumer), `sql:classpath:sql/order-reject.sql`,
  `sql:classpath:sql/order-record.sql`, with `dataSource: "#dataSource"`.
- `json-validator:classpath:schemas/product.schema.json`, `json-validator:classpath:schemas/order.schema.json`.
- Catalog: `uri: "http://{{catalog.host}}:{{catalog.port}}"`. The host and the port are separate settings, so the
  environment supplies each one (prototype: `catalog.host=localhost:8091`). The `http` component stays visible in the
  URI (developer review decision 9).
- The catalog verification confirms that the three timeouts are component options only. They stay
  `camel.component.http.connect-timeout`, `…response-timeout` and `…connection-request-timeout`, each
  `${catalog.timeout-ms}` (E1).

**Configuration properties** (shared):
```properties
order-check.poll-delay-ms=5000
order-check.batch-size=50
catalog.timeout-ms=2000
catalog.max-calls-per-second=20
camel.component.http.connect-timeout=${catalog.timeout-ms}
camel.component.http.response-timeout=${catalog.timeout-ms}
camel.component.http.connection-request-timeout=${catalog.timeout-ms}
```

### Flow: order-desk-notification (changes: SMTP instead of a file)

| Field | Value |
|---|---|
| Target Module | (repository root) |

**Purpose:** Deliver every pending Order Desk Notification by email, once per Rejected Peakmart Order, retrying until
the mail server takes it, also after a restart (UC-002 BR-004, OQ-045 resolved, `order-desk-mail.md`).

**Source (unchanged outbox):** `sql` consumer (MCP-verified) `sql:classpath:sql/notification-select.sql` with
`dataSource: "#dataSource"`, `delay: "{{notification.poll-delay-ms}}"`, and `onConsume` / `onConsumeFailed`
(MCP-verified options; `RAW(...)` kept). `onConsume` runs only when the exchange completed without an exception, so
the row is marked sent only after the mail server took the message.

**Change D4: the attempts cap.** The `notification.attempts` CHECK allows 0–1000. After 1000 failed polls (about 83
minutes of mail-server outage at 5 s), `attempts + 1` breaks the CHECK. Then `onConsumeFailed` fails, and after the
next successful send `onConsume` fails too. `sent_at` would never be set, and the Order Desk would get the email again
on every poll, forever. With a file this could not happen; with a real mail server it can. Both updates become
`attempts = LEAST(attempts + 1, 1000)`. The CHECK and the meaning of the column stay the same.

**Routes:**
- `routeConfiguration` `order-desk-notification-error-handling` (new; EIP `routeConfiguration` and `onException`,
  MCP-verified):
  - `onException` `java.lang.Exception`, `maximumRedeliveries: 0`, `handled: false`, `logExhaustedMessageBody: false`,
    `logExhaustedMessageHistory: false`. It logs WARN `Order Desk notification not sent, tried again on the next poll
    orderNumber=… correlationId=…`.
  - It must stay **not handled**: a handled failure would run `onConsume` and mark an unsent notification as sent.
  - Redelivery is the next poll, as in the prototype.
  - Why it is new: it keeps the body and the message history out of the failure log line, and it puts the order
    number on that line (NFR-011, NFR-013).
- `order-desk-notification-send` (from the `sql` consumer):
  1. `setProperty` `order-number`, `correlation-id` from the row (unchanged).
  2. EIP `removeHeaders` (MCP-verified) pattern `*`. Camel mail turns message headers into mail headers, so nothing
     from the row or the consumer may leak into the email.
  3. EIP `setHeader` (MCP-verified) `To` = `${body[recipient_email]}`, the mailbox written with the rejection, as the
     prototype's `To:` line used it.
  4. `setHeader` `Subject` = `Peakmart order ${exchangeProperty.order-number} rejected` (FR-031,
     `order-desk-mail.md`). The Order Number cannot hold control characters, because the intake's unstorable guard
     sets such events aside. So the subject cannot carry a header injection, and the prototype's file-name guard
     `file-order-number` is no longer needed.
  5. `setBody` (simple), English plain text, with only the Order Number and the reasons in check order (C-023):
     ```text
     Order number: ${exchangeProperty.order-number}
     ${body[reason_lines]}
     ```
     The prototype's `To:` and `Subject:` lines move into the mail headers. `reason_lines` stays one `- <text>` line per
     reason (developer review decision 10).
  6. `to` component `smtp` (MCP-verified, `org.apache.camel.springboot:camel-mail-starter:4.22.2`):
     `smtp://{{smtp.host}}:{{smtp.port}}` with:
     - `from: "{{orderdesk.sender}}"`;
     - `useHeaderRecipients: true` and `useHeaderSubject: true` (catalog: without them the `To`/`Subject` headers are
       ignored);
     - `contentType: "text/plain; charset=UTF-8"`;
     - `connectionTimeout: "{{smtp.timeout-ms}}"`, plus `mail.smtp.timeout` and `mail.smtp.writetimeout` =
       `{{smtp.timeout-ms}}` through the `mail.`-prefixed `additionalJavaMailProperties`. A mail server that stalls
       must not hang the poller thread, and JavaMail's read timeout is unlimited by default;
     - `mail.smtp.starttls.enable` and `mail.smtp.starttls.required` = `{{smtp.starttls}}`;
     - `useJavaMailSessionPropertiesFromHeaders` stays `false` (default).

     Credentials are component options and never appear in the URI: `camel.component.smtp.username=${SMTP_USERNAME}`
     and `camel.component.smtp.password=${SMTP_PASSWORD}`, set only without a profile (§3 Configuration). The `dev`
     profile sets no credentials. Implementation verifies against Mailpit that no `AUTH` is attempted then.
  7. `log` INFO `Order Desk notified orderNumber=… correlationId=…` (unchanged).

- **Rationale:** `smtp` is the protocol the mail contract and the Mailpit stand-in use, and the request names it. The
  outbox row stays, because it is what makes retry-until-sent survive a restart (BR-004). Only the sink of the flow
  changes, as pipeline 001 planned ("The Spring Boot step replaces only this `to` with `smtp`").
- **Constraints:** at least once. If the process dies after the mail server took the message but before `onConsume`
  updates the row, that one email goes out a second time on the next poll. The prototype's `fileExist=Override` hid
  this window; email cannot be overwritten. Of the two failure modes, this design takes "sometimes twice" over "never"
  (BR-004 says "tried again until it is sent"). The window is the length of one UPDATE, and it is recorded as
  assumption B-6. A Duplicate Peakmart Order still gets no notification at all, because `notification.order_receipt_id`
  is UNIQUE and the receipt insert skips Duplicates (UC-002 A3).

**Configuration properties** (shared):
```properties
notification.poll-delay-ms=5000
smtp.timeout-ms=10000
```
`smtp.host`, `smtp.port`, `smtp.starttls`, `orderdesk.mailbox`, `orderdesk.sender` come from the profile files.

### Supporting change: Flyway migration V1 (replaces `order-db-schema-init`)

- `src/main/resources/db/migration/V1__order_database.sql` holds the tables, columns, constraints, indexes and CHECKs
  of `prototype/sql/schema.sql`, one statement each:
  - `order_receipt`, `order_state_change`, `rejection_reason`, `notification`, `customer_order`, `order_line` and
    `set_aside_order_event`;
  - the partial unique index on Peakmart order numbers and every other unique constraint listed in 001 §3.

  Two things change, and nothing else:
  - The `DO $$ … $$` wrapper goes. It existed only so that camel-sql could run one statement, and Flyway runs a
    script.
  - `IF NOT EXISTS` goes, so a database that drifted from the history fails loudly instead of being skipped.
- Spring Boot's Flyway auto-configuration runs it at startup, on the same datasource, before the Camel routes start.
  Camel's context starts after the Spring context refresh, so no poller sees a missing table. Implementation verifies
  this order in the startup log.
- The route `order-db-schema-init`, `prototype/db/` and the timer dependency do not move.
- A database that already has these tables but no Flyway history is refused (Flyway's default; no `baseline-on-migrate`).
  The development database is empty, as the request says.
- The next pipeline adds `V2__…` for `partner`, `pick_list` and the rest.

### Supporting change: beans and application class

- `CamelApplication` (exported) stays the main class.
- New `IntegrationBeans` (`@Configuration`) declares `orderChecks` and `cannotReadReason` (E4).
- `OrderChecks` and `CannotReadReason` stay unchanged apart from the package line the export added. They use Jackson 2
  (`com.fasterxml.jackson`) and networknt `json-schema-validator`, which come with the Camel starters. Implementation
  records the resolved versions. The accessors `CannotReadReason` uses (`getInstanceLocation`, `getKeyword`) must still
  resolve under the Spring Boot dependency set. Scenario 5 proves it.

### Supporting change: the pom (R1)

- The pom stays what `camel export` wrote, with only the changes E10 lists. In particular it keeps
  `org.springframework.boot:spring-boot-starter-parent:4.1.1` as its parent, next to the Camel Spring Boot BOM 4.22.2
  in `dependencyManagement`.
- **This is a deliberate exception to Camel Kit's pom guide**, which says not to use `spring-boot-starter-parent`
  because a parent can fight the Camel Spring Boot BOM over versions. Here it cannot: Camel Spring Boot 4.22.2 is built
  on Spring Boot 4.1.1, the parent's version. Reviewers keep this exception and do not report the parent as a finding.
- If a later pipeline moves Spring Boot or Camel to versions where Camel Spring Boot is no longer built on the parent's
  Spring Boot version, the exception no longer holds and the parent is revisited then.

### Supporting change: E7 regression test (R2)

The export changed the email pattern without a word, and nothing failed. A unit test makes that failure loud:

- `src/test/java/com/trailheadgear/orderintegration/EmailPatternTest.java`, JUnit with the exported
  `spring-boot-starter-test` (test scope, already in the pom). No Spring context, no Camel context, no services.
- It loads the packaged `application.properties` from the classpath the way Spring Boot does, with Spring Boot's
  `PropertiesPropertySourceLoader`, so the backslash escapes are read exactly as at run time. It does not read the file
  as plain text and does not repeat the pattern in the test.
- It compiles `peakmart.email-pattern` and checks:
  - it **accepts** the buyer email of `docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json`
    (`buyer.email`, which contains an `s`, so the broken pattern `^[^@s]+@…` of the export fails here);
  - it **rejects** the buyer email of
    `docs/messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json`.
- The test reads both emails from the example files (Jackson), from `docs/messages/examples/` in the project directory,
  where Surefire runs. The values are not copied into the test.
- It runs in `./mvnw verify` (Surefire, `test` phase), so `./mvnw verify` fails if the pattern loses its escapes again.

### Supporting change: retiring the prototype (R4)

The Spring Boot project uses the same consumer group as the prototype (B-11), so the two must never consume at the
same time.

- `prototype/` stays as the record of pipeline 001. Its routes, beans, SQL and results are not changed.
- One line at the top of `prototype/run.sh`, right after the shebang, refuses to start unless `PROTOTYPE_ANYWAY=1` is
  set. It exits non-zero and says why: the prototype is retired, the Spring Boot project now consumes with the same
  consumer group `trailhead-order-integration`, and the two must not run at the same time.
- `prototype/kill-test.sh` starts the prototype through `run.sh`, so the guard covers it too.
- The Product Catalog stub `prototype/stubs/product-catalog/run.sh` is a different script and stays unguarded, because
  the Spring Boot project still uses it in development.
- **Check:** `prototype/run.sh` without the variable exits non-zero with the message and starts no JVM. With
  `PROTOTYPE_ANYWAY=1` it gets past the guard. The execution report records both.

### Configuration: profiles and refusing to start

Three files under `src/main/resources/`:

1. **`application.properties`** holds what does not depend on the environment: the shared properties of the flows
   above, plus these:
   ```properties
   camel.main.name=trailhead-order-integration
   camel.main.run-controller=true
   camel.main.log-mask=true
   camel.main.additional-sensitive-keywords=email,phone,ssn,creditCard
   spring.flyway.locations=classpath:db/migration
   ```
2. **`application-default.properties`.** Spring Boot activates the profile `default` only when no profile is active,
   so this file is exactly "without a profile". Every host, port and secret is an environment variable **with no
   default**:
   ```properties
   kafka.brokers=${KAFKA_BROKERS}
   spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=verify-full&sslrootcert=${DB_SSL_ROOT_CERT}
   spring.datasource.username=${DB_USERNAME}
   spring.datasource.password=${DB_PASSWORD}
   catalog.host=${CATALOG_HOST}
   catalog.port=${CATALOG_PORT}
   smtp.host=${SMTP_HOST}
   smtp.port=${SMTP_PORT}
   smtp.starttls=true
   camel.component.smtp.username=${SMTP_USERNAME}
   camel.component.smtp.password=${SMTP_PASSWORD}
   orderdesk.mailbox=${ORDERDESK_MAILBOX}
   orderdesk.sender=${ORDERDESK_SENDER}
   ```
3. **`application-dev.properties`** (`--spring.profiles.active=dev`) holds the local services of §2:
   ```properties
   kafka.brokers=localhost:9092
   spring.datasource.url=jdbc:postgresql://localhost:15432/postgres
   spring.datasource.username=test
   spring.datasource.password=test
   catalog.host=127.0.0.1
   catalog.port=8091
   smtp.host=localhost
   smtp.port=1025
   smtp.starttls=false
   orderdesk.mailbox=order-desk@trailhead.example
   orderdesk.sender=order-integration@trailhead.example
   ```
   The `test`/`test` credentials are those of the local `camel infra` PostgreSQL. They are committed in the dev file
   only, because the request lists them as the development values (B-4).

**Refusing to start.** An unresolvable `${…}` stops Spring Boot at startup:
- the datasource and Flyway resolve their values while the context refreshes;
- the Camel routes resolve `{{kafka.brokers}}`, `{{catalog.*}}`, `{{smtp.*}}` and `{{orderdesk.*}}` through the
  Spring environment when the context starts, before any message is consumed.

Either way the process exits non-zero and names the missing variable. **Acceptance:** without a profile, with all 15
variables set, the application starts. Then, unsetting each variable on its own, the application exits non-zero
before any route consumes, and the output names that variable. The execution report records all 15 runs (they need
not reach the services, because the check fails before any connection). An empty value counts as set, which is Spring's
meaning of a defined variable (B-3).

**Why `verify-full` and STARTTLS without a profile:**
- C-024 and validation decision 2 of pipeline 001 require an encrypted Order Database connection for the Spring Boot
  project. `verify-full` also checks the server's identity, which `require` does not do.
- STARTTLS keeps `SMTP_PASSWORD` off the wire in clear text.
- The local services have neither, so the `dev` profile turns both off.

**The production mail server is not specified (B-7, R3).** Nothing says how it is reached. The design keeps the
assumption (STARTTLS required, a login with `SMTP_USERNAME`/`SMTP_PASSWORD`), and implementation adds it to
`docs/open_questions.md` as a new open, non-blocking question:

- `## OQ-076: How is the production mail server reached?`
- `**Element:** UC-002 (Order Desk Notification), order-desk-mail.md`
- `**Raised by:** design 002-spring-boot-move (design review, B-7), 2026-11-13`
- `**Blocking:** No`
- `**Status:** Open`
- Text: the application assumes STARTTLS required and a login, and asks operations to confirm the host, port, TLS
  mode and authentication before chapter 9 deploys anything. If the server takes no login, an amendment makes the
  credentials optional.

OQ-076 is the next free number today. If another entry took it first, implementation takes the next free one. The
entry keeps the file's existing format.

### Flow: kill-test support (test classpath only; replaces the route hook) — D5

The prototype held the commit back with a `delay` inside the intake route. The Spring Boot project keeps the test
deterministic in the same way, but the hook lives outside the production code:

- `src/test/java/com/trailheadgear/orderintegration/KillTestApplication.java` is a test main class. It runs
  `SpringApplication.from(CamelApplication::main).with(KillTestHook.class)`.
- `KillTestHook` (`src/test/java`, `@Configuration`, `@Profile("kill-test")`) declares one Camel `InterceptStrategy`
  bean. The strategy wraps only the processor whose node id is `commit-kafka-offset`. Before that processor it sleeps
  `kill-test.commit-delay-ms` milliseconds on the same thread, so the commit stays on the Kafka poll thread, as with
  the prototype's synchronous delay.
  - At startup it logs `Kill-test hook active commitDelayMs=…`, and the script waits for that line.
  - Camel Spring Boot registers `InterceptStrategy` beans from the context. Implementation verifies this with that log
    line and with a delayed commit. If it does not, `KillTestHook` adds the strategy through a `CamelContextConfiguration`
    bean instead.
- `src/test/scripts/kill-test.sh` is the prototype's final script (run 120414) with these changes:
  - It starts the application with `./mvnw -q spring-boot:test-run -Dspring-boot.run.profiles=dev,kill-test`, with
    `kill-test.commit-delay-ms=500` for the first start only. The second start uses only `dev`, so no delay.
  - It waits for `Started peakmart-order-intake`, the Camel route-started line, and on the first start also for the
    hook line. Implementation confirms the exact marker in the Spring Boot log.
  - It kills the forked application JVM, found by `KillTestApplication` in its command line, with `kill -9`.
  - It keeps all counts and checks: received at kill, committed offset moved, gap, `N` recorded, 0 recorded twice,
    `2N` history entries, and Duplicates skipped after the restart = gap.
  - Exit codes stay the same: 0 pass, 1 a check failed, 2 the kill missed the gap.
  - It reads with `docker exec camel-postgres psql` and `docker exec camel-kafka kafka-consumer-groups.sh` /
    `kafka-console-producer.sh`, and it does not start or stop containers.
- The production jar has no hook: `src/test/` is not packaged, and nothing under `src/main/` knows the property.
  Implementation checks that `peakmart.test.commit-delay-ms`, `commit-delay` and `kill-test` do not occur in
  `src/main/` or in the built jar.
- **Pass criterion** (the same as pipeline 001): at least one run with exit 0 and a gap ≥ 1. Its output is kept as
  `target/kill-test/<run>/result.txt`, and the execution report quotes it.

---

## 4. Cross-Cutting Concerns

### Performance
Unchanged: 800 orders/hour (NFR-003), 20 catalog calls/s, 5 s polls. NFR-014 (notification within 5 minutes) is met
with a 5 s outbox poll while the mail server is up. No load test (not requested).

### Security
- NFR-011, C-023: the log lines are unchanged and hold no body. The new notification error handler sets
  `logExhaustedMessageBody=false` and `logExhaustedMessageHistory=false`. The email holds only the Order Number and the
  reasons. `removeHeaders "*"` comes before the `smtp` step.
- Secrets come only from the environment without a profile, and none is in the URIs. The smtp credentials are
  component options, and the DB credentials are `spring.datasource.*`. The `camel.main.log-mask` safety net stays.
- C-024: `sslmode=verify-full` to the Order Database without a profile.
- `useJavaMailSessionPropertiesFromHeaders=false`, so no header can weaken the SMTP transport security.

### Monitoring
Unchanged: every route has a `routeId` and a `description`, and every decision point logs the order number and the
correlation id. There is one new WARN line, for a notification that was not sent.

### Constraints
- YAML DSL routes in `src/main/resources/camel/` (the Spring Boot default `classpath:camel/*`). The Java is
  `CamelApplication`, `IntegrationBeans` and the two existing beans, plus the two test classes of the kill test and
  `EmailPatternTest` (R2).
- The pom keeps the exported `spring-boot-starter-parent` 4.1.1 parent (R1, a reviewed exception to the pom guide).
- Versions come only from `.camel-kit/config.properties`: Camel 4.22.2, Camel Spring Boot BOM 4.22.2, Spring Boot
  4.1.1, Java 21.
- Do not start or stop containers. Do not use ports 8080, 8081 or 5432.

### Acceptance criteria and scenario approach
- `./mvnw verify` passes, and its output shows `EmailPatternTest` run and passed (R2).
- `./mvnw clean package` builds. With the stub and the local services running, `java -jar
  target/order-integration-0.1.0.jar --spring.profiles.active=dev` starts:
  - Flyway applies V1 to the empty database;
  - all routes start;
  - no port is opened.
- The refusal test of §3 Configuration (15 runs).
- `src/test/scripts/scenarios.sh` is the prototype's `scenarios.sh`, pointed at the Spring Boot application. It runs
  scenarios 1–6 of 001 §4 with the same expectations, except where a scenario checks the Order Desk Notification:
  - **Scenario 3:** Mailpit (`GET http://localhost:8025/api/v1/search?query=…`) holds exactly one new message
    with the subject `Peakmart order PM-50118 rejected`, To = `orderdesk.mailbox`, From = `orderdesk.sender`, and a
    plain-text body with `Order number: PM-50118` and the text of `order-desk-notification/pm-50118.json`. It holds no
    name, email address or Shipping Address. There is no file in any outbox.
  - The script counts messages before and after by search and does not delete Mailpit messages, because Mailpit is a
    shared running service.
- Scenarios 7–10 (catalog outage, `invalid`/`slow` stub, structural failure during an outage, restart while waiting) are
  run by hand as in pipeline 001. In scenario 9 the rejection email appears in Mailpit only after the stub starts again.
- **New scenario 11 (mail server down):** SMTP cannot be reached. Run with `SMTP_PORT`/`smtp.port` pointing at a closed
  port (no container is stopped). Then reject an order:
  - the notification row keeps `sent_at` NULL, and `attempts` grows on every poll;
  - the WARN line appears without the body;
  - after a restart with `smtp.port=1025`, exactly one email arrives and `sent_at` is set.
- **New scenario 12 (D4):** a notification row with `attempts = 1000` and `sent_at` NULL is sent once and marked sent,
  and it is not sent again on the next poll.
- The kill test (§3) passes.
- `src/main/` and the built jar contain no `kill-test` or `commit-delay` reference.
- `prototype/run.sh` refuses to start without `PROTOTYPE_ANYWAY=1` (R4).
- `docs/open_questions.md` holds the open, non-blocking mail-server question (R3).
- The execution report records scenarios 1–12, the refusal runs, the kill test, `EmailPatternTest` and the prototype
  guard check.

---

## 5. Constitution Compliance

- [x] Route Structure: every route has a source and a sink. `direct:` sub-routes are exempt. The pollers end in `sql`
  or `smtp`.
- [x] Single Responsibility: the routes are unchanged. The notification route still only delivers one notification.
- [x] Separation of Concerns: Ingestion (intake/receive) → Processing (check) → Delivery (record/reject/notify). Beans
  hold the rules, and SQL files hold the SQL.
- [x] Naming Conventions: route ids are unchanged. The new route configuration is
  `order-desk-notification-error-handling`, and the new headers are the mail headers `To`/`Subject`.
- [x] Observability: routeId and description on every route, and a correlation id on every order log line, including
  the new WARN.
- [x] External Configuration: every host, port, credential, address, interval and limit is a property. Without a
  profile, hosts, ports and secrets come only from the environment.
- [x] Component Support: all components, EIPs and languages are verified against the Spring Boot catalog (below).
- [x] Infrastructure via Forage: Forage is unavailable (`.camel-kit/config.properties` has no `forage.version`). The
  datasource is now Spring Boot's own `spring.datasource.*` (rung 2, framework properties), so the hand-rolled
  `camel.beans.orderDb` of the prototype is gone. The two Java beans are Spring `@Bean`s, not `camel.beans.*`.

### Catalog Verification Evidence

Catalog binding: camel_catalog_components(limit=0); runtime=spring-boot; platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2; returned camelVersion=4.22.2; binding=MATCHED

| Artifact identity | Result | Verification provenance |
|---|---|---|
| component:smtp | VERIFIED | camel_catalog_component_doc (all options + headers: from, to, subject, useHeaderRecipients, useHeaderSubject, contentType, connectionTimeout, additionalJavaMailProperties prefix `mail.`, username, password, useJavaMailSessionPropertiesFromHeaders; headers To, Subject) + camel_catalog_component_maven (org.apache.camel.springboot:camel-mail-starter:4.22.2); requested component:smtp; catalog binding=MATCHED |
| component:kafka | VERIFIED | camel_catalog_component_doc (options allowManualCommit, autoCommitEnable, breakOnFirstError, groupId; headers CamelKafkaManualCommit, CamelKafkaPartition, CamelKafkaOffset); requested component:kafka; catalog binding=MATCHED |
| component:sql | VERIFIED | camel_catalog_component_doc (options query with `classpath:` prefix, dataSource, onConsume, onConsumeFailed, maxMessagesPerPoll; header CamelSqlUpdateCount); requested component:sql; catalog binding=MATCHED |
| component:http | VERIFIED | camel_catalog_component_doc (component options connectTimeout, responseTimeout, connectionRequestTimeout); requested component:http; catalog binding=MATCHED |
| component:json-validator | VERIFIED | camel_catalog_component_doc (resourceUri with `classpath:` prefix); requested component:json-validator; catalog binding=MATCHED |
| component:direct | VERIFIED | camel_catalog_component_doc; requested component:direct; catalog binding=MATCHED |
| component:bean | VERIFIED | camel_catalog_component_doc; requested component:bean; catalog binding=MATCHED |
| eip:routeConfiguration | VERIFIED | camel_catalog_eip_doc (onException); requested eip:routeConfiguration; catalog binding=MATCHED |
| eip:onException | VERIFIED | camel_catalog_eip_doc (handled, redeliveryPolicy); requested eip:onException; catalog binding=MATCHED |
| eip:choice | VERIFIED | camel_catalog_eip_doc; requested eip:choice; catalog binding=MATCHED |
| eip:split | VERIFIED | camel_catalog_eip_doc (aggregationStrategy); requested eip:split; catalog binding=MATCHED |
| eip:throttle | VERIFIED | camel_catalog_eip_doc (mode TotalRequests, timePeriodMillis); requested eip:throttle; catalog binding=MATCHED |
| eip:doTry | VERIFIED | camel_catalog_eip_doc; requested eip:doTry; catalog binding=MATCHED |
| eip:filter | VERIFIED | camel_catalog_eip_doc; requested eip:filter; catalog binding=MATCHED |
| eip:script | VERIFIED | camel_catalog_eip_doc; requested eip:script; catalog binding=MATCHED |
| eip:transform | VERIFIED | camel_catalog_eip_doc; requested eip:transform; catalog binding=MATCHED |
| eip:removeHeaders | VERIFIED | camel_catalog_eip_doc (pattern); requested eip:removeHeaders; catalog binding=MATCHED |
| eip:convertBodyTo | VERIFIED | camel_catalog_eip_doc; requested eip:convertBodyTo; catalog binding=MATCHED |
| eip:log | VERIFIED | camel_catalog_eip_doc; requested eip:log; catalog binding=MATCHED |
| eip:setProperty | VERIFIED | camel_catalog_eip_doc; requested eip:setProperty; catalog binding=MATCHED |
| eip:setHeader | VERIFIED | camel_catalog_eip_doc; requested eip:setHeader; catalog binding=MATCHED |
| eip:setBody | VERIFIED | camel_catalog_eip_doc; requested eip:setBody; catalog binding=MATCHED |
| eip:bean | VERIFIED | camel_catalog_eip_doc; requested eip:bean; catalog binding=MATCHED |
| eip:to | VERIFIED | camel_catalog_eip_doc; requested eip:to; catalog binding=MATCHED |
| language:simple | VERIFIED | camel_catalog_language_doc (camel-core-languages 4.22.2); requested language:simple; catalog binding=MATCHED |
| language:jsonpath | VERIFIED | camel_catalog_language_doc (camel-jsonpath-starter 4.22.2; suppressExceptions, resultType); requested language:jsonpath; catalog binding=MATCHED |
| language:groovy | VERIFIED | camel_catalog_language_doc (camel-groovy-starter 4.22.2); requested language:groovy; catalog binding=MATCHED |
| language:constant | VERIFIED | camel_catalog_language_doc; requested language:constant; catalog binding=MATCHED |
| language:bean | VERIFIED | camel_catalog_language_doc (model name `method`, camel-bean-starter 4.22.2); requested language:bean; catalog binding=MATCHED |

No longer used, so not re-verified: `component:file`, `component:timer`, `eip:delay`, `eip:rest`,
`component:platform-http` (the stub stays in `prototype/`).

These are not catalog artifacts. Implementation verifies them:
- the Spring Boot names of `camel.main.*` and `camel.component.smtp.*`;
- Spring Boot's Flyway starter and the PostgreSQL module under the Boot 4.1.1 BOM;
- the order of Flyway and the Camel context start;
- the registration of `InterceptStrategy` beans;
- `spring-boot:test-run` of the Boot 4.1.1 Maven plugin;
- whether JavaMail attempts `AUTH` when no username is set.

---

## 6. Project Structure

```
trailhead-orders/
├── .camel-kit/{config.properties, pipeline.json}
├── docs/
│   ├── messages/*.schema.json                    (source of the three schemas copied at build, E6)
│   ├── messages/examples/peakmart-order-placed/  (read by EmailPatternTest, R2)
│   ├── open_questions.md                         (+ mail-server question, R3)
│   └── camel-kit/002-spring-boot-move/
│       ├── design-spec.md                        ← this file
│       ├── design-review.md                      (R1–R4, approved)
│       ├── implementation-plan.md                [created by camel-plan]
│       ├── execution-report.md                   [created by camel-execute]
│       └── validation-report.md                  [created by camel-validate]
├── pom.xml                                       (exported, parent kept (R1); E10 dependencies, resources copy of schemas)
├── mvnw, mvnw.cmd, .mvn/wrapper/                 (exported, unchanged)
├── src/main/java/com/trailheadgear/orderintegration/
│   ├── CamelApplication.java                     (unchanged)
│   ├── IntegrationBeans.java                     (new, E4)
│   ├── OrderChecks.java                          (unchanged)
│   └── CannotReadReason.java                     (unchanged)
├── src/main/resources/
│   ├── application.properties                    (shared)
│   ├── application-default.properties            (no profile: environment only)
│   ├── application-dev.properties                (local services)
│   ├── camel/
│   │   ├── peakmart-order-intake.camel.yaml      (D5, endpoints)
│   │   ├── peakmart-order-check.camel.yaml       (endpoints)
│   │   └── order-desk-notification.camel.yaml    (SMTP, error handling)
│   ├── sql/{receipt-insert, set-aside-insert, waiting-select, order-reject, order-record, notification-select}.sql
│   └── db/migration/V1__order_database.sql
├── src/main/docker/Dockerfile                    (exported, unchanged)
├── src/test/java/com/trailheadgear/orderintegration/
│   ├── KillTestApplication.java
│   ├── KillTestHook.java
│   └── EmailPatternTest.java                     (R2)
├── src/test/scripts/
│   ├── kill-test.sh
│   └── scenarios.sh
└── prototype/                                    (record of pipeline 001; the stub runs from here; not read at run time)
    └── run.sh                                    (+ one guard line: PROTOTYPE_ANYWAY=1, R4)
```

`notification-select.sql` itself is unchanged. The `onConsume`/`onConsumeFailed` statements with the D4 cap are in the
route's `RAW(...)` options. `export-readme.md` stays.

---

## 7. Assumptions Taken (non-interactive run — recommended option chosen)

| ID | Open decision | Taken |
|---|---|---|
| B-1 | How the kill test works without a hook in the route | A test-classpath `InterceptStrategy` on node `commit-kafka-offset`, active only with profile `kill-test` and run with `spring-boot:test-run`. Rejected alternatives: killing at random (pipeline 001's first version missed the gap 3 of 3 times), and a property-guarded hook in production code (it is what the request moves away from) |
| B-2 | Where schemas and SQL live in the jar | SQL moves to `src/main/resources/sql/`. Schemas are copied from `docs/messages/` at build time, so they keep a single source and the jar is self-contained |
| B-3 | What "missing" means | An unset environment variable. An empty value counts as set (Spring semantics) |
| B-4 | Development credentials in the repository | `test`/`test` in `application-dev.properties` only, as the request gives them for the local PostgreSQL |
| B-5 | Order Desk mailbox and sender address without a profile | Required environment variables `ORDERDESK_MAILBOX` and `ORDERDESK_SENDER`. The `trailhead.example` addresses are dev values only; a production default would send mail nowhere |
| B-6 | Duplicate email after a crash between SMTP accept and `onConsume` | Accepted (at least once). It is the only way to keep "tried again until it is sent" |
| B-7 | SMTP security without a profile | Authentication with `SMTP_USERNAME`/`SMTP_PASSWORD`, and STARTTLS required. The production mail server's settings are not specified anywhere. If it takes no authentication, an amendment makes the credentials optional. Kept by the design review (R3), and recorded as an open, non-blocking question in `docs/open_questions.md` for operations to answer before chapter 9 deploys anything |
| B-8 | Order Database TLS without a profile | `sslmode=verify-full` with `DB_SSL_ROOT_CERT` (C-024, validation decision 2 of pipeline 001) |
| B-9 | Attempts beyond 1000 (D4) | `LEAST(attempts + 1, 1000)`. The schema CHECK is unchanged |
| B-10 | An existing schema without Flyway history | Refused (no baseline). The development database is empty |
| B-11 | Consumer group | `trailhead-order-integration`, unchanged (A-11 of pipeline 001). The development group therefore continues from the prototype's committed offsets |
| B-12 | Scenario evidence for the email | Mailpit API search by subject, counting before and after, without deleting messages |

---

## 8. Design Review Decisions

Source: `docs/camel-kit/002-spring-boot-move/design-review.md` (integration architect, with the developer,
2026-11-13). Everything the review does not list is accepted as written, including E1–E10, D4 and B-1 to B-6 and
B-8 to B-12. The design is approved with these four changes.

| # | Decision | Where it is in this spec |
|---|---|---|
| R1 | Keep the export's parent POM (`spring-boot-starter-parent` 4.1.1). A reviewed exception to Camel Kit's pom guide, because Camel Spring Boot 4.22.2 is built on Spring Boot 4.1.1 | §3 E10, §3 Supporting change: the pom, §4 Constraints, §6 |
| R2 | E7 gets a unit test of `peakmart.email-pattern`, loaded as Spring Boot loads `application.properties`, run in `./mvnw verify` | §3 E7, §3 Supporting change: E7 regression test, §4 Acceptance, §6 |
| R3 | Keep B-7 (STARTTLS required, a login) and record the production mail server as an open, non-blocking question | §3 Configuration, §4 Acceptance, §7 B-7 |
| R4 | The prototype is retired: `prototype/run.sh` refuses to start unless `PROTOTYPE_ANYWAY=1` is set, and says why | Not Doing, §3 Supporting change: retiring the prototype, §4 Acceptance, §6 |
