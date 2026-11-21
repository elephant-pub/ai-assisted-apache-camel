---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-13T21:20:58.188768Z"
  by: "camel-validate"
  from: "execution-report.md"
---
# Validation Report

**Date:** 2026-11-13 15:20
**Project:** trailhead-orders
**Runtime:** spring-boot
**Camel Version:** 4.22.2

Mode: standalone, pipeline-scoped (`/camel-validate 003-partner-orders-pick-lists`), non-interactive. This was a static
validation of the files. The application was not started, no build was run and no container was touched.

**Inputs:**
- `design-spec.md`, `implementation-plan.md` and `execution-report.md`. `camel-kit doc check` reported each one as not
  stale. The lineage is plan ← design and execution report ← plan.
- `design-review.md` and `developer-checks.md` carry no provenance frontmatter. They are used as developer-supplied
  evidence and checked against the files (A-1).
- The prior decisions of `002-spring-boot-move/validation-decisions.md` are used to label findings that were already
  accepted. They do not change any severity.

**Catalog binding:** `camel_catalog_components(limit=0, runtime=spring-boot,
platformBom=org.apache.camel.springboot:camel-catalog-provider-springboot:4.22.2)` returned `camelVersion=4.22.2`,
which matches. Every later catalog call used the same binding.

**Inventory** (single module, empty `MODULE_PREFIX`):
- `ROUTE_FILES`:
  - `src/main/resources/camel/order-desk-notification.camel.yaml`
  - `src/main/resources/camel/partner-order-api.camel.yaml`
  - `src/main/resources/camel/partner-rejection-email.camel.yaml`
  - `src/main/resources/camel/peakmart-order-check.camel.yaml`
  - `src/main/resources/camel/peakmart-order-intake.camel.yaml`
  - `src/main/resources/camel/pick-list-delivery.camel.yaml`
- `PROPS_FILE`: `src/main/resources/application.properties`, plus the profile files
  `src/main/resources/application-default.properties` and `config/application-dev.properties`.
- 1 `rest` (contract-first), 6 route configurations and 17 routes: 16 declared, plus `partner-orders-rest-route`, which
  Camel generates from the contract. That matches the `Routes startup (total:17 rest-dsl:1)` line in `target/ref2.log`.

**Scope of the developer review (commit "Developer review of 003"):**
- `pom.xml`: the partner contract is bundled at build time with `openapi-generator-maven-plugin` 7.14.0.
- `src/test/scripts/kill-test.sh`: the kill test now waits for `Sent to Warehouse` and checks the pick lists.
- `developer-checks.md`.

## Summary

| Category | Pass | Fail | Warn | Total |
|----------|------|------|------|-------|
| Schema Validation | 6 | 0 | 1 | 7 |
| Endpoint Verification | 11 | 0 | 1 | 12 |
| Quality Checks | 20 | 0 | 8 | 28 |
| Security Analysis | 20 | 4 | 5 | 29 |
| Anti-Patterns | 4 | 0 | 11 | 15 |
| Constitution Compliance | 4 | 0 | 4 | 8 |
| **Total** | **65** | **4** | **30** | **99** |

**Result: FAIL (4 items, all security).**
- Two are new: the dev passwords and the dev partner API key are committed to git.
- Two were already accepted in pipeline 002 (Kafka without TLS, Product Catalog over plain HTTP). They still fail
  checklist rule 2. The checklist's severity cannot be lowered, so they block until they are fixed or the integration
  architect accepts them again for 003.
- None is in the developer review's changes. The two developer-review items, the bundled contract and the kill test,
  are working, with warnings.

## Findings

### Schema Validation
- [PASS] `order-desk-notification.camel.yaml`: `camel_validate_yaml_dsl` gave valid with 0 errors. The file holds the
  routeConfiguration and the sql→smtp route.
- [PASS] `partner-order-api.camel.yaml`: valid, 0 errors. This covers the `rest.openApi` block (`specification`,
  `missingOperation: fail`, `routeId`), the onException with filter, the 4 routes, doTry/doCatch, `stop`,
  `convertBodyTo` and the Groovy `transform`.
- [PASS] `partner-rejection-email.camel.yaml`: valid, 0 errors.
- [PASS] `peakmart-order-check.camel.yaml`: valid, 0 errors. This covers the split (`parallelProcessing`,
  `executorService`, `#class:` aggregation strategy), the throttle (`mode: TotalRequests`) and the nested doTry.
- [PASS] `peakmart-order-intake.camel.yaml`: valid, 0 errors.
- [PASS] `pick-list-delivery.camel.yaml`: valid, 0 errors. This covers choice/otherwise, `marshal.csv` and the sftp
  parameters.
- [WARN] Schema validation ran on reduced payloads (A-4). Comments, long log texts and Groovy bodies were cut, and a few
  repeated sibling steps were dropped. Every DSL element type and option key was still sent.
  - A control call with a bogus sftp option did return `unknownOptions`, so an empty error list is a real pass for what
    was sent.
  - A full-file check (`camel-yaml-dsl-validator` or `camel run --check`) was not run because this run excludes
    starting the build.

### Endpoint Verification
- [PASS] sql consumers (`dataSource`, `delay`, `maxMessagesPerPoll`, `onConsume`/`onConsumeFailed`) have no catalog
  errors:
  - `order-desk-notification.camel.yaml:32`
  - `partner-rejection-email.camel.yaml:33`
  - `peakmart-order-check.camel.yaml:33`
  - `pick-list-delivery.camel.yaml:34`
- [PASS] sql producers (`outputType: SelectOne/SelectList`, `dataSource`) have no errors.
- [PASS] smtp: `from`, `useHeaderRecipients`, `useHeaderSubject`, `contentType` and `connectionTimeout` are valid. The
  `mail.smtp.*` keys are legal under the catalog's `additionalJavaMailProperties` option, which takes a `mail.` prefix.
- [PASS] kafka consumer: `brokers`, `groupId`, `autoOffsetReset`, `breakOnFirstError`, `autoCommitEnable=false` and
  `allowManualCommit=true` are valid (`peakmart-order-intake.camel.yaml:30-37`).
- [PASS] http producer: `httpMethod=GET` and `throwExceptionOnFailure=false` are valid
  (`peakmart-order-check.camel.yaml:179-182`).
- [PASS] sftp producer: all options are valid (`pick-list-delivery.camel.yaml:117-133`). They are `fileName`,
  `tempFileName`, `fileExist=Fail`, `charset`, `strictHostKeyChecking=yes`, `knownHostsFile`,
  `useUserKnownHostsFile=false`, `connectTimeout`, `soTimeout` and `autoCreate=false`. The catalog default of
  `strictHostKeyChecking` is `no`, so `yes` is the hardened setting.
- [PASS] json-validator uses `classpath:` resources only. The partner request is checked against
  `classpath:contract/messages/partner-order.schema.json` (`partner-order-api.camel.yaml:159`).
- [PASS] direct: every `to: direct:X` has a matching consumer. `direct:submitPartnerOrder` is the operation target that
  the contract's `operationId` generates.
- [PASS] csv data format: `header` and `delimiter` are catalog options (`camel-csv-starter` 4.22.2).
- [PASS] Every `{{key}}` used in the routes resolves:
  - from `application.properties`;
  - or, for environment-specific values, from both `application-default.properties` (environment variables) and
    `config/application-dev.properties`.
- [PASS] `camel_configuration_validate` accepts `camel.main.name`, `log-mask`, `additional-sensitive-keywords`, the
  three `camel.component.http.*` timeouts and `camel.component.smtp.username/password`. The validator still reports
  `camel.main.run-controller` as unknown. Pipeline 002 ruled that a false positive (decision 4), so it is not
  re-flagged.
- [WARN] The Product Catalog URI uses plain `http://{{catalog.host}}:{{catalog.port}}`
  (`peakmart-order-check.camel.yaml:179`). It is valid in the catalog. The security finding is under Security Analysis
  (S-4).

### Quality Checks
- [PASS] All 17 routes have an id and a `from`. The `direct:` sub-routes are exempt from needing an external sink.
- [PASS] All 16 declared routes have a meaningful `description`.
- [PASS] All route ids match `<domain>-<action>[-<qualifier>]`.
- [PASS] No host, port or credential is written into route YAML. Every endpoint uses `{{…}}`.
- [PASS] Placeholder syntax is right for spring-boot: `{{…}}` only in routes, `${…}` only in properties files.
- [PASS] No properties file has a duplicate key. The dev profile overrides the SFTP directories on purpose.
- [PASS] Every route has a `routeConfigurationId`, and every `onException` sets
  `logExhaustedMessageBody/History: false`.
- [PASS] The three outbox pollers (notification, rejection email, pick list) mark a row done only after success. They
  use `handled: false` with `onConsumeFailed`.
- [PASS] Pick-list delivery is exactly-once for a single instance:
  - before writing, it checks the inbound and processed folders (`WarehouseSftp.java:59-76`);
  - it writes a `.tmp` file, renames it, and uses `fileExist: Fail`;
  - `pick-list-delivered.sql` is safe to run again (guards on `Pending`/`Recorded`, plus `ON CONFLICT DO NOTHING`).
- [PASS] Peakmart duplicates are handled. `receipt-insert.sql` uses `ON CONFLICT DO NOTHING`, the route branches on
  `CamelSqlUpdateCount`, and the Kafka offset is committed manually as the last step.
- [PASS] The partner claim is one atomic CTE (`partner-receipt-claim.sql`). The release is guarded by
  `order_state='Received'`, and an `IN_PROGRESS` claim returns no `receipt_id`.
- [PASS] The catalog throttle is one shared node: `TotalRequests`, 1000 ms, `{{catalog.max-calls-per-second}}`=20.
- [PASS] The parallel split runs on `#catalogLookupPool` with `GroupedBodyAggregationStrategy`.
- [PASS] HTTP connect, response and connection-request timeouts are all 2000 ms (`application.properties:34-36`).
- [PASS] SFTP timeouts are set on both the endpoint and the `WarehouseSftp` session.
- [PASS] SMTP timeouts `connectionTimeout`, `mail.smtp.timeout` and `mail.smtp.writetimeout` are set on both mail routes.
- [PASS] A correlation id is set at every entry point, and the pollers carry it over from the database row.
- [PASS] **Kill test (developer check 2).** `kill-test.sh` now waits for `order_state = 'Sent to Warehouse'` (line 30)
  and checks 3 history entries and one delivered pick list per order (lines 80-81). The checks match the schema:
  - `pick_list.customer_order_id` is `UNIQUE` (V2:34), so a count of N delivered means one per order;
  - `order_state_change` is unique per (receipt, state) (V1:41), so 3N entries is exact.

  The result file `target/kill-test/151115/result.txt` matches developer-checks.md: 11 received and 10 committed at
  the kill (gap 1), 50/0/150/50, and 1 Duplicate skipped. This closes the P-15 kill-test follow-up of the execution
  report.
- [PASS] **Contract bundling (developer check 1), build and startup.**
  - The `bundle-partner-contract` execution (`generate-resources`) runs before `copy-partner-contract-api`
    (`process-resources`).
  - `target/classes/contract/api/partner-orders.openapi.yaml` exists, and every `$ref` in it is internal
    (`#/components/schemas/…`).
  - `target/generated-contract/README.md` is not copied into the jar.
  - `target/ref2.log`, written after the pom change, has no `ERROR` line and shows `Routes startup (total:17
    rest-dsl:1)`.

  `docs/` stays the single source; no copy is kept under `src/`.
- [WARN] **The bundled contract is not equivalent to `docs/api/partner-orders.openapi.yaml`.** openapi-generator's
  `openapi-yaml` generator rewrote the inlined JSON Schema 2020-12 schemas
  (`target/classes/contract/api/partner-orders.openapi.yaml:181-251, 286-323`):
  - `Partner_Order` and `Order_Answer` got `additionalProperties: {}`. The source has `false`, and the contract says the
    Partner Order "allows no field it does not name" (UC-001 BR-010).
  - `Partner_Order_shipTo` and `Partner_Order_lines_inner` keep `additionalProperties: false`, but all their `type`
    keywords are gone. So are the `type`s of `orderId`, `partnerId`, `orderedAt`, `customerEmail`, `lines`,
    `quantity` and `unitPrice`.
  - `Rejection_Reason` and `Order_Answer`'s properties also lost their `type`s.
  - `Rejection_Reason` is emitted twice (`Rejection_Reason`, `Rejection_Reason_1`), and the second copy lost
    `additionalProperties: false`.
  - A `servers: - url: /` entry was added.
  - The `externalValue` example links still point at `../messages/examples/…`, which does not exist on the classpath.

  **No runtime effect today:** `clientRequestValidation` is off and the route validates against the original
  `partner-order.schema.json` (line 159), which keeps every type and `additionalProperties: false`. The difference
  matters if this file is ever handed to partners, published, or used for request validation.
- [WARN] **The design and plan do not describe the delivered build.**
  - Design §3 *Constraints* (`design-spec.md:197-202`) prescribes copying the contract with its relative `$ref` layout.
    It says that "the fallback (a bundled single-file copy) needs a design amendment".
  - The design still says `kill-test.sh` is unchanged (`design-spec.md:887`), and the plan requires it to be unchanged
    (`implementation-plan.md:1280`).
  - Both changes are reasonable and closed the execution report's two follow-ups, but they were made as a developer
    decision (`developer-checks.md`). The design was not amended.
- [WARN] **Leftovers from the old `$ref` layout.**
  - `copy-partner-contract-messages` still copies `order-answer.schema.json` and `rejection-reason.schema.json` to
    `contract/messages/` (`pom.xml:226-228`). Nothing reads them now; only `partner-order.schema.json` is used.
  - The route header comment still says the contract is "copied to the classpath at build"
    (`partner-order-api.camel.yaml:3`).
  - The pom comment says "as bundled by openapi-generator below" (`pom.xml:192`), but the plugin is declared above.
- [WARN] **P-15: the 002 scenarios are still partly unproven on the 003 build.** `scenarios.sh` refuses to run on a
  database that already holds PM-50117/18/19 (`developer-checks.md` item 3), so scenarios 1-6 were not rerun.
  - Covered by other runs: scenario 1 (recorded, PM-6031001), scenario 3 (rejected with notification, PM-6031002) and
    duplicate skipping (kill test).
  - Not rerun since 002: scenarios 4 (other event type skipped), 5 (set aside for its buyer email) and 6 (unreadable
    events set aside).
  - The risk is low: `peakmart-order-intake.camel.yaml` is not in 003's changed-file list.
- [WARN] The kill test checks the database only. It does not check the SFTP side (one file per order, no `.tmp`).
  developer-checks.md reports a manual check of run 151115 (50 `PL-PM-151115*.csv` files, no `.tmp`).
- [WARN] Five routes have more than 7 steps, and one is deeply nested (top-level steps / total with nested steps):
  - `partner-order-submit`: 14 / about 30
  - `pick-list-delivery`: 9 / 14
  - `order-desk-notification-send`: 8
  - `partner-rejection-email-send`: 8
  - `partner-order-check`: 6 / about 35 nested

  The design prescribes their structure; see Constitution rule 2.
- [WARN] Groovy in routes holds business logic instead of a bean:
  - the DataMapper `transform` steps (`partner-order-api.camel.yaml:255-290`, `peakmart-order-intake.camel.yaml:277-314`);
  - the "unstorable" check, written twice (`peakmart-order-intake.camel.yaml:230-249`);
  - three Groovy property expressions in set-aside (`:327-350`).
- [WARN] The generated route `partner-orders-rest-route` has no `description`. The `rest`/`openApi` element sets
  `routeId` only (`partner-order-api.camel.yaml:5-11`).

### Security Analysis
- [FAIL] **S-1 (rule 1): dev passwords committed.** `config/application-dev.properties` holds literal values for
  `spring.datasource.password` (line 8) and `warehouse.sftp.password` (line 29), and the file is tracked by git.
  - `.gitignore` excludes only `prototype/target*`, `.camel-jbang/` and `target/`.
  - They are the default credentials of the local `camel infra` test services, and P-20 shows they are no longer in the
    jar (the 002 carry-over item 7 is met).
  - The values are not reproduced here.
- [FAIL] **S-2 (rule 1): dev partner API key committed.** `config/dev/secrets/partner-api-key-summit-outfitters` is
  tracked by git. Its README says it is a random development key that no real partner uses. It is used only if
  `SECRET_STORE_DIR` points at this directory.
- [FAIL] **S-3 (rule 2): Kafka without TLS.** There is no security protocol and no SSL settings without a profile
  (`application-default.properties:7`, `peakmart-order-intake.camel.yaml:30-37`). Accepted by 002 decision 5 until
  OQ-038.
- [FAIL] **S-4 (rule 2): Product Catalog over plain HTTP** (`peakmart-order-check.camel.yaml:179`). Accepted by 002
  decision 6 (OQ-039). Pipeline 003 widens its scope: partner orders now use the same call
  (`partner-order-api.camel.yaml:460-462`).
- [PASS] Without a profile, every secret comes from an environment variable with no default. `RequiredVariablesCheck`
  names every missing variable and prints no values.
- [PASS] No credential appears in any route or in `application.properties`. The SMTP credentials are component options.
- [PASS] The partner endpoint uses TLS with a PEM SSL bundle (`application-default.properties:22-25`).
- [PASS] SFTP checks host keys strictly against a configured known-hosts file, and the user's own known-hosts file is
  ignored. This holds in the route and in the bean.
- [PASS] SMTP STARTTLS is enabled and required without a profile.
- [PASS] The database URL uses `sslmode=verify-full` with a root certificate (`application-default.properties:8`).
- [PASS] `camel.main.log-mask=true` is set, with the PII keywords added (`application.properties:14-15`).
- [PASS] Log lines carry ids, the order number and the exception class only. No log step prints a body, header, key,
  contact or address.
- [PASS] The API key is never logged. It is held only as a SHA-256 hash, and `Check.toString()` hides keys.
- [PASS] A partner order number is logged only when it is at most 30 characters with no control characters
  (`PartnerOrders.java:110-121`).
- [PASS] The partner request is validated against the original schema, which has `additionalProperties: false`. The
  format checks are done in Java.
- [PASS] Every SQL value is bound with `:#`. The pick-list file name is built from database columns and checked again
  against a strict pattern before any SFTP call.
- [PASS] Secret key names cannot escape their directory: there is a safe-name pattern, `..` is rejected, and the parent
  directory is checked (`DirectorySecretStore.java:18-38`).
- [PASS] No partner input is evaluated as an expression.
- [PASS] No mail header injection: every header is removed before `To` and `Subject` are set. `To` is the registered
  Order Contact, and the order number in the subject has no control characters.
- [PASS] API keys are compared in constant time (`MessageDigest.isEqual`) and the loop never stops early. A missing or
  unknown key gets 401 before the body is read.
- [PASS] `removeHeaders "*"` runs right after authentication, which drops `X-API-Key` and any `Camel*` header. Headers
  are removed again before the answer and before smtp. The execution report shows platform-http already filters
  `Camel*` headers, so this is a second guard (R4).
- [PASS] A partner mismatch gets 403. No CORS is configured, and the actuator is not exposed over HTTP.
- [PASS] Error responses reveal no internals: any failure becomes a fixed 503, and rejection reasons name fields,
  never values.
- [PASS] The `apiKey` security scheme (`X-API-Key`) and the global `security` requirement are kept in the bundled
  contract (`target/classes/contract/api/partner-orders.openapi.yaml:18-19, 356-363`).
- [PASS] `camel_security_advisories` lists no advisory affecting 4.22.2. That covers platform-http, rest-openapi, ftp
  and csv. openapi-generator-maven-plugin 7.14.0 runs only at build time and is not in the jar.
- [WARN] `server.ssl.enabled-protocols=TLSv1.3,TLSv1.2` (`application.properties:55`) may not apply when
  `server.ssl.bundle` is set, because the protocols then come from the bundle's options. This is not verified. To make
  TLS 1.2+ explicit, set `spring.ssl.bundle.pem.partner-endpoint.options.enabled-protocols`.
- [WARN] `mail.smtp.ssl.checkserveridentity` is not set, so server-identity checking depends on the mail library's
  default. This is not verified.
- [WARN] The partner body size is checked only after the whole body has been read into a String
  (`partner-order-api.camel.yaml:129-145`), and no server-level request-size limit is configured.
- [WARN] In the dev profile the partner endpoint is plain HTTP on 8082 with no `server.address`, so it probably listens
  on every interface. The committed dev key (S-2) works against it. Design review R5 approved plain HTTP in dev.
- [WARN] The SFTP password is passed as a `RAW()` URI parameter (masked by Camel), not as a component option
  (`pick-list-delivery.camel.yaml:129`). The value comes from a placeholder, so this is not a rule-1 violation.
  Already known from the execution report.

### Anti-Patterns
- [PASS] No body logging.
- [PASS] Inbound headers are removed at the partner ingress and before every outbound protocol.
- [PASS] Every ingress is validated against a schema: partner request, Peakmart event, product answer and completed
  order.
- [PASS] The Kafka record size is checked before parsing (`peakmart-order-intake.camel.yaml:61-65`).
- [WARN] No poison-message limit on the pollers. The outbox and waiting selects have no attempts cap, so a row that
  always fails is retried forever with no alert:
  - `pick-list-select.sql:12` (every 30 s)
  - `notification-select.sql:11-12` (every 5 s)
  - `waiting-select.sql:5`
- [WARN] `order-check-error-handling` handles only `SQLException`/`DataAccessException`
  (`peakmart-order-check.camel.yaml:9-11`). Any other failure in the check falls to the default handler, and the order
  is polled again forever. That includes bean exceptions and HTTP failures that are not `IOException`.
- [WARN] **Plausible, not verified at runtime.** `partner-order-check` calls `direct:order-check`, which runs under
  `order-check-error-handling`. A non-SQL exception there is likely exhausted by the sub-route's default handler and
  never reaches `partner-order-error-handling`. The partner would then get a 500 instead of the 503 Order Answer, and
  the claim would be freed only by the 30 s expiry. This needs a test before anyone acts on it.
- [WARN] A Kafka poison record can block the partition. Intake handles only database exceptions (with
  `handled: false`), and the consumer uses `breakOnFirstError: true`. Any other exception that escapes the `doTry`
  blocks is consumed again and again.
- [WARN] Known from the execution report: the `handled: false` handlers let the sql consumer also log exception
  messages. An SMTP error could quote the Order Contact address.
- [WARN] Known from the execution report: if `partner-order-answer` fails inside its own error handler, the partner
  gets a 500.
- [WARN] Pick-list exactly-once assumes a single instance. `pick-list-select.sql` takes no row lock (no
  `FOR UPDATE SKIP LOCKED`), as the route header says.
- [WARN] Known from the execution report: `catalogLookupPool` uses `Executors.newFixedThreadPool`, whose queue is
  unbounded (`IntegrationBeans.java:42`).
- [WARN] The split has no `timeout`. Under concurrent partner load, time waiting in the throttle and the pool queue can
  go past the 10 s NFR-002 budget. The 2 s HTTP timeout does not limit queue time.
- [WARN] The Product Catalog call has no circuit breaker. The design handles outages by leaving the order waiting or
  answering 503.
- [WARN] The two notification pollers set no `maxMessagesPerPoll`.

### Constitution Compliance
- Rule 1 (Route Structure): **PASS.** Every route has a source. Pollers end in smtp or sftp, and the `direct:`
  sub-routes are exempt.
- Rule 2 (Single Responsibility): **WARN.** Five routes have more than 7 steps (see Quality Checks).
- Rule 3 (Separation of Concerns): **WARN.** Some business logic is in Groovy inside the routes, not in beans (see
  Quality Checks).
- Rule 4 (Naming Conventions): **WARN.** `direct:submitPartnerOrder` does not follow `direct:<route-id>` (the route id
  is `partner-order-submit`). The name comes from the contract's `operationId`, which the rest-openapi binding
  requires. Route ids and custom property names follow the convention.
- Rule 5 (Observability): **WARN.** Every route has a `routeId`. The generated `partner-orders-rest-route` has no
  `description`. The constitution fails validation only for a missing `routeId`, so this is a WARN.
- Rule 6 (External Configuration): **PASS.** Routes and `application.properties` use placeholders only, and secrets
  come from the environment without a profile. The literal values in the committed dev profile are reported as S-1.
- Rule 7 (Component Verification): **PASS.** Every component (platform-http, rest-openapi, sql, http, smtp, kafka,
  sftp, json-validator, bean, direct) and the csv data format were found under the 4.22.2 spring-boot binding.
- Rule 8 (Infrastructure via Forage): **PASS.** There is no `camel.beans.*`, no YAML `beans` and no `forage.*` key.
  Infrastructure beans are Spring `@Bean` methods in `IntegrationBeans.java`.

## Assumptions (non-interactive run; recommended option taken)

- **A-1:** `developer-checks.md` and `design-review.md` have no provenance frontmatter. They were used as developer
  evidence and checked against the files:
  - `pom.xml` and `src/test/scripts/kill-test.sh`;
  - the bundled `target/classes/contract/api/partner-orders.openapi.yaml`;
  - `target/ref2.log`, a start with no ERROR line;
  - `target/kill-test/151115/result.txt`.

  These were not rerun.
- **A-2:** The design was not amended for the bundled contract or the changed kill test, although design §3 asks for
  an amendment. Validation went ahead on the code as delivered, and the gap is reported as a WARN instead of stopping.
- **A-3:** The 002 decisions on Kafka TLS and on plain HTTP to the Product Catalog are noted as already accepted. They
  are still counted as FAIL, because the checklist does not allow a lower severity.
- **A-4:** Schema validation used reduced YAML payloads, and no full-file DSL validator was run (no build).
- **A-5:** The finding that a sub-route exception bypasses the partner handler comes from reading the code. It was not
  observed at runtime.

## Recommendations

**Priority fixes (FAIL items):**
1. **S-1 / S-2.** Choose one, as the integration architect's decision:
   - take `config/application-dev.properties` and `config/dev/secrets/partner-api-key-*` out of git. Gitignore them,
     commit a template such as `application-dev.properties.example` and a README step that generates the key, and
     supply both locally;
   - or record a formal acceptance that they are dev-only values for local `camel infra` services.

   If you keep them, bind the dev partner endpoint to localhost with `server.address=127.0.0.1` in the dev profile.
2. **S-3 / S-4.** Either re-confirm the 002 acceptances for 003, or close OQ-038 and OQ-039. S-4 now also covers the
   partner path.

**Suggested improvements (WARN items):**
3. Amend the design with `/camel-brainstorm 003-partner-orders-pick-lists` to record the developer-review decisions:
   - the build-time bundled contract, replacing §3 *Constraints* and the project layout lines 856-857;
   - the changed `kill-test.sh` (line 887, P-15).
4. Make the bundled contract faithful, or label it internal:
   - give openapi-generator an option that keeps JSON Schema 2020-12 `type` and `additionalProperties: false`, or
     bundle with a ref-resolving tool that does not rewrite schemas, such as a plain `$ref` dereferencer;
   - at the least, add a build check that diffs the bundled `Partner_Order` against `partner-order.schema.json`;
   - never publish the bundled file to partners as the contract.
5. Remove `order-answer.schema.json` and `rejection-reason.schema.json` from `copy-partner-contract-messages`, and fix
   the two stale comments (`partner-order-api.camel.yaml:3`, `pom.xml:192`).
6. Give `scenarios.sh` a rerun mode with fresh order numbers, then rerun 002 scenarios 4-6 on the 003 build.
   Optionally, add an SFTP file-count check to `kill-test.sh`.
7. Error handling:
   - widen `order-check-error-handling`, or let partner calls of `direct:order-check` use the caller's handler;
   - cap attempts on the outbox and waiting selects and alert at the cap (UC-006);
   - add a split `timeout` within the NFR-002 budget.
8. Set the TLS protocols on the SSL bundle (`spring.ssl.bundle.pem.partner-endpoint.options.enabled-protocols`).
   Configure a server request-size limit for the partner endpoint, and set `mail.smtp.ssl.checkserveridentity=true`.
9. Move the DataMapper and "unstorable" Groovy logic into beans when these routes are next changed. Consider
   `maxMessagesPerPoll` on the notification pollers and `FOR UPDATE SKIP LOCKED` before running more than one instance.
