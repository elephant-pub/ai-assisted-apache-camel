---
staleness:
  stale: false
  since: null
  reason: null
generated:
  at: "2026-11-14T03:06:36.588953Z"
  by: "camel-brainstorm"
---
# Trailhead Order Integration: Hardening (005) — Design Spec

**Date:** 2026-11-13
**Camel Version:** 4.22.2
**Runtime:** Spring Boot 4.1.1
**Platform BOM Version:** org.apache.camel.springboot:camel-spring-boot-bom:4.22.2
**Pipeline:** 005-hardening (standalone; no plan is started from here)
**Status:** Approved 2026-11-13 by the integration architect, with the five changes of
`docs/camel-kit/005-hardening/design-review.md` (decisions 1 to 5), which are written into this spec (see §7, "Design
review decisions"). The open questions of the design session took the recommended answer. They are listed in §7, and
the review accepted them, except that decision 1 changes OQ-084.
**Inputs:** the spec (`docs/requirements.md`, `docs/use_cases/`, `docs/entity_model.md`, `docs/open_questions.md`), the
code at commit `7f37e31`, `docs/camel-kit/004-spec-tests/validation-decisions.md` (decisions 2–5),
`docs/camel-kit/004-spec-tests/developer-checks.md` (faults F5–F8), and the runs of
[`evidence/failure-lab-runs.md`](evidence/failure-lab-runs.md).

This design starts from what can fail. §3.1 lists every failure of every route, of each system it touches and of the
process itself: what happens today, what the spec requires, and what changes. Only then come the changes (§3.2–§3.8)
and the tests that prove them (§3.9). Where the spec says nothing, no rule is invented: an open question with a
recommended answer is written (§7), and the chosen behaviour is the one that keeps the spec's three guarantees:
no lost order (NFR-007), no duplicate pick list (NFR-006), and no order rejected because of an outage (FR-029, FR-030).

## Not Doing (and Why)

- **UC-005 (order lookup), UC-006 (alerts), UC-008 (resubmission)**: out of scope by the request. Every end state
  below is chosen so that UC-006 can find it later. §3.8 records where each alert hooks in, and nothing here sends an
  alert.
- **Deployment, monitoring, health endpoints, restart policy**: out of scope by the request. An `Error` a step throws
  for a reason of its own is contained and the process keeps running. After a `VirtualMachineError` the process stops
  with a non-zero exit code (H1, OQ-084). Restarting it is deployment's job (chapter 9).
- **TLS to Kafka and the Product Catalog (OQ-038, OQ-039)**: out of scope by the request; decision 1 of 004 still holds.
- **Load tests**: out of scope by the request. The new time limits (§3.3) are chosen from NFR-002's 10 s budget, not
  from measurements under load.
- **A dead-letter topic or queue**: Peakmart takes nothing back (C-007), and the Order Database already holds every
  end state a person needs (set-aside events, waiting receipts, pending outbox rows). A second store would split where
  a person has to look.
- **New Order Checks for database limits (OQ-075 reading a)**: they would add Rejection Reasons, which is a business
  rule for the spec owner. This design takes reading (b), which needs no new rule, and leaves (a) open (OQ-083).
- **Circuit breaker on the Product Catalog**: every catalog failure is already a Catalog Outage, cheap and correct
  (run D: 15 ms – 2.9 s per answer). The 20 calls/s throttle (BR-014) bounds the load, so a breaker would add a state
  and change nothing the spec measures.
- **Backing off the mail and SFTP retries**: the spec says "tried again until it is sent" (UC-001 BR-005, UC-002
  BR-004) and "as soon as it is available again" (FR-028). A longer delay would cut into NFR-014's 5 minutes and
  NFR-001's 15 minutes for no gain in correctness.

---

## 1. Executive Summary

### Business Purpose

The integration already does the right thing when an outside system is down, slow, or answers with an error or
something unusable. The runs show that each of these ends in a defined state (run D). It does the wrong thing in four
cases that the spec tests cannot see:

1. **An `Error` that is not an `Exception`** (F8's `NoSuchMethodError`, an `OutOfMemoryError`) is never handled. One
   such `Error` leaves its exchange in flight and logs nothing. At intake it **stops the Peakmart topic** (run B: none
   of the next records is taken in). In the check it **stops every Peakmart check** (run B2). On the partner path the
   partner waits 30 s for a non-contract 503 (run A). Until a restart, no order moves, and nobody can see why.
2. **A frozen Order Database** makes a partner wait 31 s for that same non-contract 503 (run D). NFR-002 allows 10 s.
3. **An order that passes every check but does not fit the Order Database** waits forever and is never reported. Fifty
   of them block every newer Peakmart order (OQ-075, confirmed in run D).
4. **An event that can be read but fails in a way that will never succeed** (no such case exists today except an
   `Error`; a future defect would be one) blocks the topic for good, because only unreadable events are set aside.

The kill runs (K1–K6) show that a process killed at any step loses no order and delivers no second pick list. They
also show that it can send an Order Desk Notification twice (K3, OQ-081).

### Business Value

- No failure ends in a state nobody looks at. Each one ends recorded, rejected, delivered, set aside with its reason,
  or waiting with its reason, in a table UC-006 will query (§3.8).
- A partner always gets the contract's answer within NFR-002's 10 s.
- The guarantees of NFR-006 and NFR-007 are proven in every `./mvnw verify`: the kill test joins the build (decision 4
  of 004 / F7), and kill points are added after it.

### Stakeholders

Integration Team (finds and fixes the parked cases), Order Desk (gets orders that no longer wait silently), Partners
(get a contract answer in time), Warehouse Team (no second pick list), spec owner (seven open questions, two proposed
test cases).

---

## 2. Systems Landscape

| System | Type | Protocol (component) | Touched by | Failure tools used in the runs and the tests |
|---|---|---|---|---|
| Peakmart order topic | Kafka (C-005) | `kafka` | peakmart-order-intake | `docker pause` |
| Order Database | PostgreSQL 16 (C-004) | `sql`, JDBC (Hikari, pgjdbc), Flyway | every route | `docker pause`; data that does not fit |
| Product Catalog | HTTP (C-010) | `http` | product-lookup | switchable stub: slow, 500, 429, invalid 200, reset, down |
| Warehouse SFTP Server | SFTP (C-011) | `sftp`, JSch (`WarehouseSftp`) | pick-list-delivery | `docker pause`, `chmod`, a leftover `.tmp` file, missing `processed/` |
| Mail server | SMTP | `smtp` | order-desk-notification-send, partner-rejection-email-send | switchable SMTP server: 550 at RCPT, silent, down |
| Partner's HTTP client | HTTPS (C-009) | `platform-http` (`rest` with `openApi`) | partner-order-* | client timeout; client gone (K4) |
| The process | JVM 21, Spring Boot 4.1.1 | — | all | fault hook: `NoSuchMethodError` or `Runtime.halt` before a node |

---

## 3. Failure-Mode Analysis and Flow Designs

| Field | Value |
|---|---|
| Target Module | (project root) |

**How "today" is known.** A *(code)* entry is read from the route, SQL or bean named. A *(run X)* entry is
observed in run X of [`evidence/failure-lab-runs.md`](evidence/failure-lab-runs.md). The runs use the unchanged
packaged jar with a test-only fault hook on `loader.path`. *(code, mechanism of run B2)* marks a case that was not run
by itself but goes through the same consumer and the same unhandled `Error` as a run that was. The tests of §3.9 run
every such case.

### 3.1 Failure modes

Cases: **down** (refuses or does not connect), **slow** (connects, does not answer in time), **error** (answers with
an error), **unusable** (answers 200 with something that cannot be used), **never** (a failure that will never succeed
on retry), **kill** (the process dies at a step), **Error** (a step throws a `java.lang.Error` that is not a
`VirtualMachineError`, such as a `LinkageError` or an `AssertionError`). A `VirtualMachineError` is X6.

#### 3.1.1 partner-order-api (routes partner-orders-rest-route, partner-order-submit, -receive, -check, -answer)

| # | System / case | Today | Spec | Change |
|---|---|---|---|---|
| P1 | Product Catalog down / slow / 500 / 429 / invalid 200 / reset | 503 contract answer in 15–2 927 ms; receipt stays Received, mark released *(run D)* | UC-001 A6, UC-003 A2, BR-013, OQ-039, OQ-053, FR-030 | none |
| P2 | Order Database down | 503 contract answer *(code: onException `java.lang.Exception`)* when the pool fails fast | UC-001 A7, FR-030 | none (bounded by H3) |
| P3 | Order Database slow / frozen | **503 after 30 915 ms with Spring's generic body**. The exchange goes on and records the order after the answer *(run D)* | UC-001 A7, NFR-002 (100 % within 10 s), OQ-037 (503 with the contract answer), BR-007 | **H3** time limits; **H2** last-resort contract answer at 9 s. A request that completes after its 503 is a Duplicate on resend (BR-007, OQ-086) |
| P4 | Order Database: the order passes every check but does not fit (`varchar(100)` recipient name, a price ≥ 100000000.00, a product name > 200) | `DataIntegrityViolationException` gives 503; every resend gets 503 again, and only a WARN is logged *(code; same statement as run D's Peakmart case)* | OQ-075 (Open), FR-030 (outage only) | **H2**: still 503 (no invented rejection), logged at ERROR with kind `unstorable` as the alert hook. Spec owner: OQ-083 |
| P5 | Partner client gone before the answer | the exchange completes; recorded or rejected; resend answered from the receipt *(code; run K4 for the answer side)* | UC-001 A4, BR-007 | none |
| P6 | Resend while the first is being checked | 503 contract answer *(run K5)* | UC-001 A8, OQ-042, OQ-074 | none |
| P7 | **Error** in the request thread or a catalog-lookup thread | **No answer from Camel; Spring answers 503 after 30 s with its generic body; the exchange stays in flight for good; nothing is logged; the mark holds until its 30 s expiry; shutdown waits 45 s** *(run A)* | NFR-002, FR-004, OQ-037; decision 5 of 004 | **H1** contains the Error; **H2** answers the contract 503 at once, releases the mark, and logs ERROR `unexpected-error` |
| P8 | kill after the claim | resend within 30 s: 503 (A8); after: checked, recorded once *(run K5)* | OQ-074, NFR-007 | none (KillIT keeps it) |
| P9 | kill after recording, before the answer | resend: 200 accepted from the receipt, one Accepted Order *(run K4)* | BR-007, NFR-006 | none (KillIT keeps it) |
| P10 | Secret Store / Partner Configuration unusable at start | registration fails, none of its partners registered, logged *(code: PartnerRegistration, UC-007 BR-005)* | UC-007 A1–A4, OQ-059 | none |

#### 3.1.2 peakmart-order-intake (routes peakmart-order-intake, peakmart-event-read, peakmart-event-set-aside, peakmart-order-receive)

| # | System / case | Today | Spec | Change |
|---|---|---|---|---|
| I1 | Kafka down / frozen | consumer reconnects; the next event is taken in *(run D)* | C-008, FR-043 (alert after 15 min unread, UC-006) | none |
| I2 | Kafka: commit fails | `commit-kafka-offset` throws; not handled; the record is consumed again and skipped as a Duplicate *(code)* | NFR-007, FR-023 | **H4** keeps it this way explicitly: a Kafka exception is never a reason to set an event aside |
| I3 | Order Database down / frozen | 3 redeliveries (1, 2, 4 s), not handled; `breakOnFirstError` seeks back; the event is taken in once the database is back *(run D)* | UC-002 A6, OQ-049 | none in behaviour; **H4** narrows the clause to "database unavailable" |
| I4 | Order Database: an insert fails for good (constraint, value) | treated as "database down": retried forever, the topic is blocked *(code: `on-intake-database-failure` catches every `SQLException`/`DataAccessException`)*. No such value is known today (guards of 001/002), but a future one would stop the topic | NFR-007, OQ-049, UC-002 A2 | **H4**: set aside with reason after 2 redeliveries (OQ-079) |
| I5 | Event unusable (not JSON, too large, invalid, unstorable ids, email) | set aside with its reason, offset committed *(code; TC-004)* | UC-002 A2, BR-009, OQ-022, OQ-033 | none |
| I6 | **Error**, once | **the Kafka consumer thread blocks: no later record is taken in until a restart; nothing logged; shutdown waits 45 s** *(run B)*. After the restart nothing is lost *(run K1)* | NFR-007, FR-007, OQ-049; decision 5 of 004 | **H1** + **H4**: 2 redeliveries, then the event is set aside with reason `unexpected error`, and the next event goes on |
| I7 | **Error** on every attempt (F8) | same as I6 for good: the topic is never read again; after 7 days the events are gone (C-005) *(run BP)* | NFR-007, FR-043 | as I6 |
| I8 | kill after the receipt, before the commit (F7) | after the restart the event is a Duplicate; every order once *(run K1)* | NFR-007, FR-023, NFR-006 | none in behaviour; the kill test joins `./mvnw verify` (**T8**, decision 4 / F7) |
| I9 | Order Database down while setting aside | not handled, retried like I3 *(code)* | UC-002 A6 | none |

#### 3.1.3 peakmart-order-check (routes peakmart-order-check-poll, order-check, product-lookup, order-reject, order-record)

| # | System / case | Today | Spec | Change |
|---|---|---|---|---|
| C1 | Product Catalog down / slow / error / unusable | the order waits for Check, re-checked every poll, recorded once the catalog answers *(run D)* | UC-002 A5, FR-029, UC-003 BR-012/013, BR-014 | **H5** writes `waiting_reason = catalog outage`; nothing else |
| C2 | Order Database down while rejecting or recording | 2 redeliveries, handled, WARN; waits; next poll *(code; run D for the poller resuming)* | UC-002 A6 | none |
| C3 | Order Database: the completed order does not fit (OQ-075) or fails `order.schema.json` | **waits for Check forever**, re-checked on every poll, WARN each time, 0 ERROR *(run D)*. 50 of them block every newer order (batch 50, oldest first) *(code)* | OQ-075 (Open), UC-002 BR-006, FR-039 | **H5**: `waiting_for = Recording` with `waiting_reason`, ERROR `unstorable`; never re-polled; newer orders go on (OQ-075 reading b) |
| C4 | Head of line: an order that keeps failing at Check | it stays first in the oldest-first batch of 50 *(code: waiting-select.sql)* | OQ-049 (a waiting order does not hold up the others) | **H5**: the poll takes the orders that waited least recently checked first (`last_check_at`) |
| C5 | **Error** in a check or a recording | **the check poller stops: no Peakmart order is checked until a restart; nothing logged; shutdown waits 45 s** *(run B2)* | NFR-001, OQ-049; decision 5 | **H1** + **H5**: the order keeps waiting for Check with `waiting_reason = unexpected error`, ERROR, tried again on the next poll (OQ-080), and the others go on |
| C6 | kill in the middle of a check | checked again after the restart, recorded once *(run K6)* | NFR-007, NFR-006 | none (KillIT keeps it) |

#### 3.1.4 pick-list-delivery

| # | System / case | Today | Spec | Change |
|---|---|---|---|---|
| D1 | SFTP down / frozen | the attempt fails (`RuntimeBeanExpressionException` from the stat check), Pending, `attempts` +1; delivered after it is back *(run D)* | FR-028, UC-004 A1, C-015 | **H6**: `last_failure` |
| D2 | SFTP error: folder read-only, folder missing | tried every poll, Pending; delivered once writable *(run D)* | UC-004 A1 ("at any other time") | **H6**: `last_failure`; the attempt counter and FR-035's alert (UC-006) are the way it is found |
| D3 | SFTP: a leftover `.tmp` | overwritten, delivered once *(run D)* | UC-004 A2, BR-004 | none |
| D4 | the Warehouse moved the file to `processed/` before a retry (F5) | counted as delivered, no second file *(run K2)* | UC-004 A3, NFR-006 | none in behaviour; a fast test (**T4**) and a kill test (**T8** K2) prove it |
| D5 | Order Database down after the upload | Pending; next poll finds the file and records Delivered *(code: pick-list-delivered.sql is idempotent)* | UC-004 A4 | none |
| D6 | **Error** | not run by itself; the same sql consumer and unhandled `Error` as C5 *(code, mechanism of run B2)*: the pick-list poller stops | NFR-001, FR-027 | **H1** + **H6**: Pending with `last_failure = unexpected error`, tried again on the next poll |
| D7 | kill after the upload, before Delivered | after the restart: found, recorded, one file *(run K2)* | UC-004 A3/A4, BR-005, NFR-006 | none (KillIT keeps it) |

#### 3.1.5 order-desk-notification-send and partner-rejection-email-send

| # | System / case | Today | Spec | Change |
|---|---|---|---|---|
| M1 | mail server down / silent | each attempt fails (10 s timeout when silent), unsent, `attempts` +1; sent once it answers *(run D)* | UC-002 BR-004, UC-001 BR-005, OQ-045, NFR-014 | **H6**: `last_failure`, `last_attempt_at` |
| M2 | 550 (recipient refused for good) | tried every poll for good; `attempts` stops counting at 1000 (`LEAST`, entity model Max 1000); a full stack trace at ERROR each time *(run D)* | "tried again until it is sent" (BR-004/005); NOTIFICATION.attempts Max 1000; nothing on 5xx | **H6**: `last_failure` carries `550`; the retry stays (spec). OQ-082, OQ-085 |
| M3 | **Error** | as D6 *(code, mechanism of run B2)*: the poller stops | NFR-014 | **H1** + **H6** |
| M4 | kill after the mail server accepted, before the row is marked | **sent twice** *(run K3)* | FR-031/FR-006 say "an" email; nothing on duplicates | none: sending once at most would risk an email never sent, against BR-004/005. OQ-081 |

#### 3.1.6 The process itself

| # | Case | Today | Spec | Change |
|---|---|---|---|---|
| X1 | SIGKILL at any step | no lost order, no second pick list (K1, K2, K4, K5, K6); one duplicate email (K3) | NFR-006, NFR-007 | the kill points join `./mvnw verify` (**T8**) |
| X2 | SIGTERM with an exchange stuck by an Error | waits the 45 s shutdown timeout, then forces (runs A, B, B2) | — | goes away with **H1**; T7 checks a graceful stop |
| X3 | Order Database down at start | Flyway fails, the start fails, the reason logged *(code: `spring.flyway.*`, `@DependsOn("flywayInitializer")`)* | — | none |
| X4 | a missing setting without a profile | refuses to start, names every variable *(code: RequiredVariablesCheck)* | C-024 | none |
| X5 | the json-schema-validator pin removed (F8) | builds green with `-DskipITs`; the jar fails on every json-validator step | decision 2 of 004 | **T1** guard in the fast build |
| X6 | a step throws a `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`, `InternalError`) | as P7, I6 and C5: the exchange stays in flight, nothing is logged, the consumer or poller stops, and the process goes on running *(code, mechanism of runs A, B, B2)* | NFR-006, NFR-007 | **H1**: one ERROR line `Fatal error … the process stops`, then the process stops with a non-zero exit code, without a graceful shutdown. Afterwards it is a kill (X1). Restart is deployment's (chapter 9). OQ-084 |

### 3.2 H1 — Contain a step's `Error`, stop on a `VirtualMachineError` (all routes)

**What.** A Java bean `errorContainment` of type `org.apache.camel.spi.InterceptStrategy`, registered in
`IntegrationBeans`. It wraps every processor as an `AsyncProcessor`. What it does depends on the `Error` a step throws
(design review, decision 1):

- **A `VirtualMachineError`** (`OutOfMemoryError`, `StackOverflowError`, `InternalError`, `UnknownError`): after one,
  nothing in the JVM can be trusted, including the error handling that would run next. So it does not complete the
  callback and runs no error handler. It logs one line at ERROR,
  `Fatal error routeId=<id> nodeId=<id> error=<Error class> — the process stops`, and then stops the process with
  `Runtime.halt(70)`: a non-zero exit code, no shutdown hooks, no graceful shutdown. The halt is in a `finally`, so it
  runs even if the log line itself fails. This is safe for the reason this design gives everywhere: every state is
  durable, and the kill runs (K1–K6, T8) prove that a process dying at any step loses no order and delivers no second
  pick list. Restarting it is deployment's job (chapter 9).
- **Any other `Error`** (`LinkageError` and its subclasses `NoSuchMethodError`, `NoClassDefFoundError`,
  `ExceptionInInitializerError`; `AssertionError`; anything else a step throws for a reason of its own): it sets
  `UnexpectedErrorException` on the exchange (a `RuntimeException` holding the `Error` as its cause) and completes the
  callback, so the route's error handler runs as it does for any exception. It logs once at ERROR:
  `Unexpected error routeId=<id> nodeId=<id> error=<Error class> orderNumber=<order-number> correlationId=<id>`.

Both lines log the class only, no message: a message can quote data (NFR-011). The halt goes through a constructor
argument (`Runtime.getRuntime()::halt` in production), so the unit test T3 can see it without stopping its JVM.

**Rationale.** In 4.22.2 an `Error` thrown by a step is lost: the exchange never completes and nothing is logged (runs
A, B, B2). No `onException` or `doCatch` sees it. Only a wrapper around each step can turn it into something the
existing error handling handles. An `InterceptStrategy` is the same mechanism the kill-test hook already uses
(`KillTestHook`), so it is proven on this Camel version. It is a bean, not route steps, because it applies to every
step of every route (constitution rule 3: integration logic in routes, the rest in beans).
**Constraints.** It must not change the async behaviour of the EIPs it wraps (split, throttle, the sql/kafka
consumers). So it delegates `process(exchange, callback)` and only decorates the callback. It contains every `Error`
except a `VirtualMachineError`, which stops the process (OQ-084, design review decision 1). An `Error` thrown outside a
step (a pool or Kafka client thread) is not seen by H1 and stays as today.

Each route's error handling then gives the `UnexpectedErrorException` its end state (H2, H4, H5, H6).

**What H1 does on Camel 4.22.2 (amended in session 07, validation decisions 2 and 5).** "Catches only what the call
throws synchronously" was not enough: Camel's own adapters catch or reorder a step's `Error`. As built, a step's
`Error` is seen in three ways, all treated as that step's:

1. **Thrown by the step's call before the callback is completed.** Example: the fault hook, which throws before the
   node. A sync step (a `process` node, which 4.22.2 hands over as a `DelegateSyncProcessor`, or any plain
   `Processor`) is called directly, not through `AsyncProcessorConverterHelper`'s adapter. That adapter completes the
   callback even when a `VirtualMachineError` passes through, so the fatal path would not hold.
2. **Left on the exchange when the step completes.** Example: the bean processor and the bean component's producer,
   which catch the `Error` themselves. `Exchange.setException(Throwable)` wraps it in a plain
   `CamelExecutionException`. Only that exact class with an `Error` as its direct cause counts. An application
   exception is never replaced.
3. **Thrown by the step's call after Camel has already completed the callback.** This is the `Error` of F8. A `to:`
   node is a `SendProcessor`, which catches only `Exception`. A producer that is not async (every `ProcessorEndpoint`:
   `json-validator`, and `DefaultProducer`s in general) is called through
   `AsyncProcessorConverterHelper$ProcessorToAsyncProcessorBridge`. That bridge calls `callback.done(true)` in a
   `finally` and then rethrows the `Error` (read from the 4.22.2 bytecode; the F8 stack trace shows the same frames:
   `JsonValidatorEndpoint.onExchange` → `ProcessorEndpoint$1.process` → `ProcessorToAsyncProcessorBridge.process` →
   `SendProcessor.sendUsingProducer`). For F8 the `Error` comes from the endpoint's `uriSchemaLoader`
   (`DefaultJsonUriSchemaLoader.createSchema` → `SchemaRegistry.getSchema`). So H1 **holds a synchronous completion**
   made on the calling thread while the call runs, and passes it on only when the call returns. If the call then throws,
   the `Error` is the step's: it is contained (or halts), and the held completion is replaced by that outcome. Before
   session 07, H1 passed the completion on at once, took the later `Error` for "not this step's" and rethrew it.
   - On the partner path the `Error` reached Spring's servlet. Spring logged its stack trace with the message, and the
     partner got a 200 echoing its own request.
   - On the Peakmart path the exchange stayed in flight.

**Not this step's.** An `Error` thrown after the step's call has returned (by what runs on after the step), or after
the step completed *asynchronously* (`done(false)`, passed on at once), is not contained by that step. It is
rethrown. Holding a synchronous completion until the call returns keeps the first case outside the step's `try`. A
step that completes asynchronously and then throws stays a residual risk (no such step is known in these routes).

**Catch-alls inside `doTry` (session 07, Q-1, Q-2).** A `doCatch` of `java.lang.Exception` would also take an
`UnexpectedErrorException` and give it the wrong end state. In Camel 4.22, `onException` does not trigger inside
`doTry` ("doTry is in fact its own error handler", doTry EIP). So letting the exception pass the `doCatch` (`onWhen:
${exception} !is '…UnexpectedErrorException'`) is not enough when the clause is in the same route. The exception
must leave the route. Each such `doTry` therefore runs in a `direct:` route of its own, and the caller's `to:` is
where the route configuration's clause handles it:

| `doTry` | own route | handled at | end state |
|---|---|---|---|
| `try-read-partner-order` | `partner-order-read` | `to-partner-order-read` | `on-partner-order-unexpected-error`: 503 contract answer (H2), not 400 |
| `try-read-as-json` | `peakmart-event-read-json` | `to-peakmart-event-read-json` | `on-intake-cannot-take-in`: read redelivered twice, then set aside as `cannot be taken in: unexpected error: …` (H4), not `not JSON` |
| `try-validate-product` | already in `product-lookup` | `to-product-lookup` (split, `stopOnException`), then the order | Peakmart check only: `on-check-unexpected-error`, waits for Check with `waiting_reason = unexpected error: …` (H5), not `catalog outage`. A partner check keeps the OUTAGE answer, i.e. the 503 contract answer (H2). No clause of `order-check-error-handling` takes a partner's exception, and letting it pass gave a 500 with a stack trace (session 07, TC-911) |

**Residual stack-trace paths (Q-9, decision 5).** These are accepted as a risk:
- An exception that matches no clause reaches a default error handler or the consumer, which can log the `Error`'s
  message in a stack trace. Example: a partner-path `UnexpectedErrorException` inside `order-check` or
  `product-lookup`.
- The not-handled intake and outbox failures go to the consumers' own handlers.

F8's run after session 07 checks the most likely path: `json-validator` at the first validation step of both
channels.

### 3.3 H3 — Time limits on the Order Database

```properties
# Design 005 H3: a frozen Order Database must not hold a partner request past NFR-002's 10 s (run D: 30.9 s)
spring.datasource.hikari.connection-timeout=2000
spring.datasource.hikari.data-source-properties.connectTimeout=2
spring.datasource.hikari.data-source-properties.socketTimeout=5
```

**Rationale.** Hikari waited its default 30 s for a connection (run D), and pgjdbc has no socket timeout by default,
so a statement on a frozen server never returns. With these values each statement fails within about 7 s
(2 s for a connection, 5 s for the read). These are pgjdbc properties of the data source, so the per-profile JDBC URLs
stay unchanged. **Constraints.** 5 s is far above any statement of the routes (a few ms in the runs). A longer
statement, such as a future Flyway backfill, runs at startup before the routes.

**Flyway's connection (design review, decision 3).** Flyway runs with its own connection settings, without the 5 s
socket timeout: it does not use the Hikari pool and its data-source properties. The plan resolves how (for example
`spring.flyway.url`, `spring.flyway.user` and `spring.flyway.password` set from the same values as the data source,
which gives Flyway a data source of its own), keeping the per-profile URLs. Test **T12** shows which settings each one
uses.

### 3.4 H2 — Partner answers under failure

- `UnexpectedErrorException` gets its own clause in `partner-order-error-handling`, before the `java.lang.Exception`
  clause. Same steps: release the mark (best effort), then the 503 contract answer. Logged at ERROR. The
  `java.lang.Exception` clause stays and logs at WARN.
- A `DataIntegrityViolationException` from `order-record.sql` (P4) is logged at ERROR with
  `kind=unstorable orderNumber=… correlationId=…`. It is still answered 503, so no rejection is invented (OQ-083).
- **Last-resort answer.** `spring.mvc.async.request-timeout=9s`. A small `@RestControllerAdvice` (Java,
  `PartnerTimeoutAnswer`) answers `AsyncRequestTimeoutException` on `/orders` with the contract's temporarily
  unavailable Problem body, built by `OrderAnswers` (`order-answer.schema.json` requires only `result`, so the unknown
  order number can be left out). With H1 and H3 this should never fire. It is the guarantee behind NFR-002's
  "100 % within 10 s". **Rationale:** Spring answered at 30 s with a body the contract does not know (runs A and D).

**Retry.** None inside the request (unchanged, NFR-002). The partner resends. That is safe because the claim is
idempotent per partner, order number and content, and a recorded order is answered from its receipt (BR-007, K4).

### 3.5 H4 — Intake: wait for the database, set aside what will never succeed

`peakmart-intake-error-handling` gets three clauses, in this order:

| Clause | Matches (bean `failureKinds`) | Redelivery | Handled | End state |
|---|---|---|---|---|
| `on-intake-waits` | the database is unavailable (`databaseUnavailable(${exception})`: `SQLTransientConnectionException`, `SQLRecoverableException`, `CannotGetJdbcConnectionException`, `QueryTimeoutException`, a socket timeout, or SQLState class `08`, `53`, `57`) **or** a Kafka exception | 3, 1 s ×2 (unchanged) | no | offset not committed; the event is consumed again once the system is back (UC-002 A6) |
| `on-intake-cannot-take-in` | any other exception, `UnexpectedErrorException` included | 2, 1 s | yes | `direct:peakmart-event-set-aside` with `cannot-read-reason = cannot be taken in: <kind>`, then the offset is committed; WARN `Order event set aside` (unchanged) plus ERROR for an unexpected error |
| (set-aside fails) | the set-aside itself fails | — | no | not handled: the offset stays, and the event is consumed again (the topic waits; FR-043 is the alert) |

`<kind>` comes from `failureKinds.describe(${exception})`: the root cause's simple class name, plus the SQLState or
server reply code when there is one. It holds no message text, so no personal data (C-021, NFR-011), and is at most
500 characters (`cannot_read_reason varchar(500)`).

```properties
# Design 005 H4: redeliveries of an intake failure that is neither the database nor Kafka, before the event is set aside
peakmart.set-aside-redeliveries=2
```

**Retry reasons.** *Database/Kafka:* every intake step can be repeated. `receipt-insert.sql` and
`set-aside-insert.sql` are `ON CONFLICT DO NOTHING`, reading has no side effect, and the commit comes last. *The two
redeliveries before setting aside:* the same steps, so they are just as safe. Two retries let a passing glitch (a GC
pause, a reset socket) pass before an event is parked.
**Rationale.** Today the only way out for an event is "unreadable" (UC-002 A2). Anything else blocks the topic for
good (I4, I6, I7) and risks losing every later order after 7 days (C-005), against NFR-007 and OQ-049. A Set-Aside
Order Event is kept 90 days, can be found and resubmitted (UC-008), and is alerted (FR-042). So it is the existing end
state that keeps NFR-007. A Duplicate stays impossible because resubmission goes through the duplicate rule. This
extends UC-002 A2 from "cannot be read" to "cannot be taken in": OQ-079.

### 3.6 H5 — Check: park what will never fit, keep the others moving

Flyway **V3** (`V3__failure_reasons.sql`) adds `order_receipt.waiting_reason varchar(500)` and
`order_receipt.last_check_at timestamptz`, with a check that `waiting_reason` is set only while `waiting_for` is set.
`order-record.sql` and `order-reject.sql` clear both when they clear `waiting_for`.

- **Every check that leaves the order waiting** writes `last_check_at = now()` and its `waiting_reason`
  (`order-wait.sql`, one statement, guarded by `order_state = 'Received'`): `catalog outage` (C1), or
  `unexpected error: <kind>` (C5).
- **A recording that can never succeed** (C3): a `NonTransientDataAccessException` other than "database unavailable"
  from `order-record.sql`, or a `JsonValidationException` from `to-validate-order`. It writes
  `waiting_for = 'Recording'`, `waiting_reason = 'cannot be recorded: <kind>'` (`order-park.sql`) and logs ERROR
  `kind=unstorable`. `waiting-select.sql` still selects only `Check`, so the order is not polled again and does not
  hold up newer orders. It is not lost (it stays on its receipt) and it is not rejected. Getting it out is a person's
  job: after fixing the cause, the Integration Team sets `waiting_for` back to `Check` with the statement the README
gives (§3.8, design review decision 4). This is OQ-075 reading (b);
  `waiting_for = 'Recording'` already exists in V1 and in UC-006 BR-002.
- **Order of the poll** (C4): `waiting-select.sql` orders by `last_check_at NULLS FIRST, received_at, id`. A new order
  is checked first; orders that keep waiting take turns.
- `order-check-error-handling` gets an `UnexpectedErrorException` clause: handled, `order-wait.sql`, ERROR. The order
  stays waiting for Check and is tried again on the next poll (OQ-080).

**Retry reasons.** A re-check is safe: the catalog lookups are reads, and rejecting or recording is one atomic
statement guarded by `order_state = 'Received'`, so a second run changes nothing (K6). A recording that failed for good
is **not** retried: the same data fails the same constraint, and retrying it is exactly what blocks the queue today.

### 3.7 H6 — Pick lists and notifications: keep trying, say why

Flyway V3 also adds `pick_list.last_failure varchar(500)`, `notification.last_failure varchar(500)` and
`notification.last_attempt_at timestamptz`.

- The not-handled `java.lang.Exception` clause of `pick-list-delivery-error-handling`,
  `order-desk-notification-error-handling` and `partner-rejection-email-error-handling` stays not handled, so the row
  stays pending. It now also runs one statement (`pick-list-failed.sql`, `notification-failed.sql`) that writes
  `last_failure = failureKinds.describe(...)` (for example `SMTPAddressFailedException 550`,
  `GenericFileOperationFailedException`, `unexpected error: NoSuchMethodError`) and `last_attempt_at`. It replaces
  `onConsumeFailed`, so a failure is counted once. The WARN line names the same kind.
- `UnexpectedErrorException` (D6, M3) goes through the same clause, so the poller goes on with the next row.
- Success clears `last_failure` (`pick-list-delivered.sql`, the `onConsume` of both mail routes).

**Retry reasons.** *Pick list:* safe because every attempt first runs the stat check on inbound, then on processed
(UC-004 A3), writes under `.tmp` and renames (BR-004), and `fileExist: Fail` never overwrites (runs K2, D).
*Notification:* repeating is safe until the mail server has accepted the email; after acceptance, a kill can send it
twice (K3, OQ-081). The retry has no end, because the spec says "until it is sent" (BR-004/005). The row and its
`last_failure` are what a person finds.

### 3.8 Where each end state is found, and where UC-006 hooks in

| End state | Where a person finds it | Log event | Alert that will hook in (UC-006, not built) |
|---|---|---|---|
| Set aside, unreadable or cannot be taken in | `set_aside_order_event` (`cannot_read_reason`) | WARN `Order event set aside …`; ERROR `Unexpected error …` | FR-042 Order Event Set Aside: a row with no ALERT |
| Waiting for Check (outage, unexpected error) | `order_receipt` `waiting_for = Check`, `waiting_reason`, `last_check_at` | WARN `waits for Product Catalog`; ERROR `Unexpected error` | FR-039 Peakmart Order Waiting: `received_at` older than 2 h |
| Waiting for Recording (cannot be recorded) | `order_receipt` `waiting_for = Recording`, `waiting_reason` | ERROR `kind=unstorable` | FR-039 (UC-006 BR-002 names Recording) |
| Pick list not delivered | `pick_list` Pending, `attempts`, `last_attempt_at`, `last_failure` | WARN `Pick list not delivered … cause=` | FR-035 Not Sent to Warehouse: Recorded 30 min, outside the window |
| Notification not sent | `notification` `sent_at IS NULL`, `attempts`, `last_attempt_at`, `last_failure` | WARN `… not sent … cause=` | none in the spec (BR-004/005: nobody is alerted). Proposed to the spec owner in OQ-082 |
| Topic not read (Kafka down, set-aside failing) | consumer group lag | Kafka client WARN | FR-043 Peakmart Topic Not Read |
| Partner answered 503 for an unstorable order | partner receipt Received; ERROR log | ERROR `kind=unstorable` | none in the spec; OQ-083 |
| `Error` anywhere (contained) | the row of its flow, as above | ERROR `Unexpected error routeId nodeId error` | the alert of that row's flow |
| `VirtualMachineError` (X6) | the process has stopped with a non-zero exit code; after the restart every row is where a kill leaves it (K1–K6) and moves on | ERROR `Fatal error … the process stops` | none here: restart and its alerting are deployment's (chapter 9) |

**Getting a parked order out (design review, decision 4).** An end state is only an end state if a person knows what
to do with it. The README gains a short section for the Integration Team, "Finding and moving stuck orders". For each
end state of the table above it gives the query that finds it and the step that gets it moving again:

| End state | Query (README) | Step that gets it moving |
|---|---|---|
| Set aside | `set_aside_order_event`, newest first, with `cannot_read_reason` | none to run now: the event waits for resubmission (UC-008, not built), kept 90 days (C-022). The README says so and that the reason names what to fix first |
| Waiting for Check | `order_receipt` with `waiting_for = 'Check'`, with `waiting_reason` and `last_check_at` | none: it is checked again on every poll and moves once the catalog answers or the defect is fixed |
| Waiting for Recording | `order_receipt` with `waiting_for = 'Recording'`, with `waiting_reason` | after fixing the cause: `UPDATE order_receipt SET waiting_for = 'Check', waiting_reason = NULL, last_check_at = NULL WHERE id = :id AND waiting_for = 'Recording' AND order_state = 'Received'`. `last_check_at = NULL` puts it first in the next poll |
| Pick list not delivered | `pick_list` Pending, with `attempts`, `last_attempt_at`, `last_failure` | none: fix what `last_failure` names (folder, rights, server); it is tried on every poll |
| Notification not sent | `notification` with `sent_at IS NULL`, with `attempts`, `last_attempt_at`, `last_failure` | none: fix what `last_failure` names (for a `550`, the recipient's setting); it is tried on every poll |
| Partner answered 503 for an unstorable order | partner receipt `Received` with the ERROR `kind=unstorable` line's order number | none in this design: tell the partner; OQ-083 |
| Topic not read | consumer group lag (`kafka-consumer-groups.sh --describe`) | bring Kafka or the Order Database back; the consumer resumes |
| Process stopped (X6) | — | restart (deployment's, chapter 9) |

Each SQL statement of the section stands in its own fenced `sql` block marked `-- end-state: <name>`. **T13**
`EndStateQueriesIT` reads them from `README.md` and runs them, so the statements are checked, not only written.

### 3.9 Tests: every change fails without it

**Red before green (design review, decision 2).** "Every change has a test that fails without it" is checked, not
claimed. A test that never failed proves nothing about its change. So:

- The plan orders each test before its change (the test task, then the change task that makes it pass).
- Execute runs each test against the code without its change and records the failure in the execution report: test,
  assertion, message. It runs it again after the change and records the pass.
- For a test that guards code that already exists (T1, T4, T5, T8 K1 and K2), "without the change" is that code with
  its fault applied (the 2.0.1 pin removed, F5, F6, F7), as the last column of the table below names. That run is the
  one recorded.
- A test that passes against the code without its change is a finding: the test is fixed before the change is made.

Tests that need a failing system or a killed process run against the **packaged jar**, as the spec tests do. They
use `IntegrationProcess` and `SpecEnvironment`, in the package `com.trailheadgear.orderintegration.failure`, run by
failsafe after the spec ITs. They are not spec tests: they carry no `ITEMS`, and the matrix ignores them.

**Fault hook (test-only).** `fault/FaultHook` is a `@Profile("fault-injection")` `InterceptStrategy` (the lab's,
see the evidence file). From `fault.points=<node>:<error|oom|halt>:<times>,…` it throws `NoSuchMethodError`, throws
`OutOfMemoryError` (decision 1), or calls `Runtime.halt(137)` before the named node. What it throws must reach H1 as a
step's own `Error` would, so the hook wraps inside `errorContainment`; T3 and T7 fail if the order is wrong. The build packages it as `target/order-integration-0.1.0-fault-hook.jar`
(a `maven-jar-plugin` execution over the test classes, that package only).
`IntegrationProcess.withFaults(points)` then starts the same packaged jar through
`org.springframework.boot.loader.launch.PropertiesLauncher` with `-Dloader.path=<fault-hook jar>` and the profiles
`spec-test,fault-injection`. The jar under test is byte-identical; the production jar has no hook. This replaces
`KillTestHook`, `KillTestApplication` and `src/test/scripts/kill-test.sh` (the run timing of the 500 ms delay goes
away: the kill now lands exactly in the gap).

| Test | Kind | Proves | Fails today / without the change because |
|---|---|---|---|
| **T1** `JsonValidatorPinTest` | unit, runs with `-DskipITs` | a `json-validator:` endpoint validates an example Order against `schemas/order.schema.json` in a plain `CamelContext` on the test classpath (the same resolution as the jar) | without the 2.0.1 pin the classpath has 3.0.4 and the step throws `NoSuchMethodError` (F8, decision 2) |
| **T2** `FailureKindsTest` | unit | the classification table of H4/H5 (each exception class and SQLState → wait / set aside / park) and `describe()` (no message text, ≤ 500 chars) | the bean does not exist |
| **T3** `ErrorContainmentTest` | unit (`camel-test-spring-junit6`, no Docker) | a route whose step throws `NoSuchMethodError` (and one with `AssertionError`) reaches its `onException(java.lang.Exception)`, the exchange completes, in-flight is 0, and one ERROR line is logged; a step that throws `StackOverflowError` or `OutOfMemoryError` calls the halt argument once with 70, logs one `Fatal error` line, and does not reach `onException` | without H1 the exchange stays in flight (the test waits 5 s for completion and fails); with an H1 that contains every `Error`, the halt is never called |
| **T4** `WarehouseSftpLookupTest` | unit | `deliveredFolder` looks in inbound, then processed, and never in another order. It goes through a small `RemoteFiles` seam in `WarehouseSftp`, so no SFTP server is needed (F5) | removing the processed lookup (F5) makes it fail; today F5 passes every test |
| **T5** `NotificationTextIT` | failsafe, PostgreSQL container only (seconds) | `order-reject.sql` with three reasons, then `notification-select.sql` and `rejection-email-select.sql`, list them in check order (F6) | reversing `ORDER BY rr.position` (F6) makes it fail; today F6 passes every test |
| **T6** `WaitingSelectIT` | failsafe, PostgreSQL only | `waiting-select.sql` returns never-checked orders first, then by `last_check_at`; never `Recording` | without H5 the order is oldest-first |
| **T7** `UnexpectedErrorIT` | packaged jar + fault hook | an Error at `to-validate-product`, at `to-validate-partner-completed-order`, at `to-validate-order-placed` (every attempt), at `to-validate-order` (twice), at `marshal-pick-list-csv` (twice), at `to-order-desk-mailbox` (twice): the partner gets the 503 contract answer in under 10 s; the event is set aside with `cannot be taken in: …` and the next event is recorded; the Peakmart order waits with `unexpected error` and is then recorded once; the pick list and the notification have `last_failure` and are then delivered/sent once; ERROR `Unexpected error` logged; a SIGTERM stops gracefully (`lastStopGraceful`). **Fatal case (decision 1):** an `OutOfMemoryError` thrown by the fault hook at intake (`to-validate-order-placed`, once) ends the process with a non-zero exit code within 5 s and logs `Fatal error`; after a normal start (no fault) the event is recorded once | runs A, B, BP, B2: no answer within 30 s, the topic and the poller stop, nothing logged, a 45 s stop. Fatal case: the process keeps running with the topic stopped, so it never exits within 5 s |
| **T8** `KillIT` | packaged jar + fault hook (`halt`) | K1 (F7: the receipt written, the commit not done, then Duplicate after the restart, recorded once), K2 (uploaded, the file moved to `processed/`, then no second file), K4, K5, K6, and K3 (sent at least once, marked sent) | F7 makes K1 fail (a lost order); F5 makes K2 fail. Replaces the script, so the kill test runs in `./mvnw verify` (decision 4) |
| **T9** `DatabaseFrozenIT` | packaged jar, `docker pause` | while PostgreSQL is frozen a partner gets the contract 503 in ≤ 10 s; a Peakmart event published meanwhile is recorded after the unpause; every poller resumes | run D: 30.9 s, a generic body |
| **T10** `UnstorableOrderIT` | packaged jar | a Peakmart order with a 150-character recipient name ends `waiting_for = Recording` with its reason and ERROR `kind=unstorable`; an order published after it is recorded; the same as a Partner Order is answered 503 with ERROR `kind=unstorable` | run D: waits for Check forever, WARN only |
| **T11** `DeliveryFailureIT` | packaged jar, `chmod` on the SFTP container; switchable SMTP server (`RejectingSmtpServer` test helper) | inbound read-only: Pending, `attempts` > 0, `last_failure` set; writable again: Delivered once. Mail 550: `last_failure` holds `550`; accepted again: sent once | the columns do not exist (H6) |
| **T12** `ConnectionSettingsIT` | failsafe, application context against a PostgreSQL container | a connection from the Hikari pool has `Connection.getNetworkTimeout()` = 5000 ms and the pool's connection timeout is 2000 ms; the connection Flyway migrates with has no socket timeout (0) (decision 3) | without its own settings Flyway uses the pool and gets 5000 ms; without H3 the pool has 0 |
| **T13** `EndStateQueriesIT` | failsafe, PostgreSQL only, next to T6 | reads every `-- end-state:` block of the README section (§3.8) and runs it against rows in each end state: each query finds its row and no other; the release statement moves a `Recording` order to `Check` with no reason and `last_check_at` NULL, after which `waiting-select.sql` returns it first; it changes nothing for an order not in `Recording` (decision 4) | the README section does not exist, so the test finds no block and fails |

The changes and their tests:

| Change | Tests |
|---|---|
| H1 | T3, T7 (contained cases and the fatal case) |
| H2 | T7 (partner cases), T9, T10 (partner case) |
| H3 | T9, T12 |
| H4 | T2, T7 (intake) |
| H5 | T2, T6, T7 (check), T10 |
| H6 | T7 (pick list, mail), T11 |
| README end states (decision 4) | T13 |
| decision 2 | T1 |
| decision 3 (F5, F6, F7) | T4, T8 K2, T5, T8 K1 |

**Proposed test cases for the spec owner (decision 3), not approved, not run.** Under `docs/test_cases/` with
`Status: Proposed`, which SpecMatrix does not require: TC-006 "Pick list retried after the Warehouse moved it to
processed/" (F5) and TC-007 "Partner order rejected with two Rejection Reasons in check order" (F6). They give the
spec layer what T4, T8 K2 and T5 give the layer below.

### 3.10 Test harness items (decision 4 of 004)

| Item | Change | Test |
|---|---|---|
| Kafka topic creation flaky (2 of 11 runs) | `SpecEnvironment.createTopic` first waits for the broker (`describeCluster` until a node answers, within `spec-test.startup-timeout-ms`), then creates the topic with up to 3 tries, each with its own timeout. `TopicExistsException` counts as created | `SpecEnvironmentTopicTest` (unit, a stub `Admin`: fails twice, then succeeds) |
| "touched by the cleanup" names the wrong cause | `P-cleanup` of TC-001 and TC-005 first asks whether the partner was registered at `C-setup` (recorded in the run). If it was not: `Partner … was not registered at the start (see C-setup)`; otherwise the old message | unit test of the message choice in `SpecRun` (the decision moves into a small static method) |
| The matrix accepts split-item IDs the design does not name | `SpecMatrix.covers` accepts a split child only when it is listed in `src/test/resources/spec-test/split-items.properties` (one line per test case, holding exactly the split IDs the five ITs declare at commit `7f37e31`, for example
`TC-002=V3a,V3b` and `TC-004=S3-alert`; the plan copies the full list from the ITs and checks it against design 004
§3). Any other suffix is a violation | `SpecTraceabilityTest`: an unknown split ID fails |
| `C-alert` of TC-004 | `SpecMatrix.MARKERS` gains `C-alert` for the precondition "The Integration Team mailbox … holds no Alert". `Tc004…IT.ITEMS` gains `notYetTestable("C-alert", …, UC_006)` | the matrix itself: without the ITEMS entry, `SpecMatrix` reports the item missing |

---

## 4. Cross-Cutting Concerns

### Performance
NFR-002 is now a hard bound under every failure: the database limits (H3) and the 9 s last-resort answer (H2). H1
adds one wrapper call per step. The kill and failure ITs are estimated to add roughly 8–10 minutes to `./mvnw verify`
(each kill point restarts the jar). The fast build gains T1–T4 only (seconds).

**Build time is measured, not estimated (design review, decision 5).** The execution report gives the measured
duration of `./mvnw verify` and of `./mvnw verify -DskipITs`, before and after this pipeline, and the time of each new
IT. If `./mvnw verify` takes more than 15 minutes, the report names the ITs that cost the most. They are not moved out
of `verify`: the guarantees they prove (NFR-006, NFR-007) are proven in every build.

### Security
`failureKinds.describe` never takes a message text, so no order data reaches a column or a log line (NFR-011, C-021,
C-023). The `Unexpected error` and `Fatal error` lines carry the `Error` class only. The fault hook is in the test classpath only, packaged into a separate jar that the production image never
holds. The profile `fault-injection` does nothing without that jar.

### Monitoring
Not in scope. Every failure has an ERROR or WARN line with order number and correlation id (NFR-013), and an end
state in a table (§3.8).

### Constraints
C-001–C-004 unchanged. No new component, no new dependency in the production jar.

---

## 5. Constitution Compliance

- [x] Route Structure — no route added; every changed route keeps its source and sink
- [x] Single Responsibility — the new statements (`order-wait.sql`, `order-park.sql`, `pick-list-failed.sql`,
  `notification-failed.sql`) are steps of existing error clauses
- [x] Separation of Concerns — the classification and the containment are beans; the routes decide the end state
- [x] Naming Conventions — new node ids follow the routes' style (`on-intake-waits`, `on-intake-cannot-take-in`,
  `on-check-unexpected-error`, …)
- [x] Observability — routeId and description kept; every new log line carries the order number and correlation id
- [x] External Configuration — the new values are properties (§3.3, `peakmart.set-aside-redeliveries=2`,
  `spring.mvc.async.request-timeout=9s`)
- [x] Component Support — all components below are catalog-verified
- [x] Infrastructure via Forage — no infrastructure bean added; `errorContainment`, `failureKinds` are plain Java
  beans in `IntegrationBeans` (rule 3), not infrastructure

### Catalog Verification Evidence

Catalog binding: camel_catalog_components(limit=0); runtime=spring-boot; platformBom=org.apache.camel.springboot:camel-spring-boot-bom:4.22.2; returned camelVersion=4.22.2; binding=MATCHED

| Artifact identity | Result | Verification provenance |
|---|---|---|
| component:kafka | VERIFIED | camel_catalog_component_doc (breakOnFirstError, pollOnError, bridgeErrorHandler); requested component:kafka; catalog binding=MATCHED |
| component:sql | VERIFIED | camel_catalog_component_doc (onConsume, onConsumeFailed, onConsumeBatchComplete, breakBatchOnConsumeFail default false); requested component:sql; catalog binding=MATCHED |
| component:http | VERIFIED | camel_catalog_component_doc (connectTimeout, responseTimeout, connectionRequestTimeout, soTimeout); requested component:http; catalog binding=MATCHED |
| component:smtp | VERIFIED | camel_catalog_component_doc (connectionTimeout); requested component:smtp; catalog binding=MATCHED |
| component:sftp | VERIFIED | camel_catalog_component_doc (tempFileName, eagerDeleteTargetFile, maximumReconnectAttempts); requested component:sftp; catalog binding=MATCHED |
| component:platform-http | VERIFIED | camel_catalog_component_doc (muteException default true); requested component:platform-http; catalog binding=MATCHED |
| component:json-validator | VERIFIED | camel_catalog_component_doc; requested component:json-validator; catalog binding=MATCHED |
| component:direct | VERIFIED | camel_catalog_component_doc; requested component:direct; catalog binding=MATCHED |
| eip:onException | VERIFIED | camel_catalog_eip_doc (exception, onWhen, handled, redeliveryPolicy); requested eip:onException; catalog binding=MATCHED |
| eip:routeConfiguration | VERIFIED | camel_catalog_eip_doc; requested eip:routeConfiguration; catalog binding=MATCHED |
| eip:doTry | VERIFIED | camel_catalog_eip_doc; requested eip:doTry; catalog binding=MATCHED |
| eip:split | VERIFIED | camel_catalog_eip_doc (parallelProcessing, executorService, stopOnException, timeout); requested eip:split; catalog binding=MATCHED |
| eip:choice | VERIFIED | camel_catalog_eip_doc; requested eip:choice; catalog binding=MATCHED |
| eip:filter | VERIFIED | camel_catalog_eip_doc; requested eip:filter; catalog binding=MATCHED |
| language:simple | VERIFIED | camel_catalog_language_doc; requested language:simple; catalog binding=MATCHED |
| language:groovy | VERIFIED | camel_catalog_language_doc; requested language:groovy; catalog binding=MATCHED |
| language:jsonpath | VERIFIED | camel_catalog_language_doc; requested language:jsonpath; catalog binding=MATCHED |
| dataformat:csv | VERIFIED | camel_catalog_dataformat_doc; requested dataformat:csv; catalog binding=MATCHED |

---

## 6. Project Structure (changes only)

```
docs/camel-kit/005-hardening/
├── design-spec.md                         ← this file
├── evidence/failure-lab-runs.md           ← the runs behind §3.1
├── implementation-plan.md                 [camel-plan]
docs/open_questions.md                     [OQ-079 … OQ-086 added, OQ-075 recommendation]
docs/entity_model.md                       [the V3 attributes]
docs/test_cases/TC-006-…, TC-007-…         [Status: Proposed]
src/main/java/…/ErrorContainment.java, UnexpectedErrorException.java, FailureKinds.java, PartnerTimeoutAnswer.java
src/main/java/…/WarehouseSftp.java         [RemoteFiles seam]
src/main/java/…/IntegrationBeans.java      [errorContainment, failureKinds]
src/main/resources/application.properties  [§3.3 incl. Flyway's own connection, §3.4, peakmart.set-aside-redeliveries]
src/main/resources/db/migration/V3__failure_reasons.sql
src/main/resources/sql/order-wait.sql, order-park.sql, pick-list-failed.sql, notification-failed.sql
src/main/resources/sql/waiting-select.sql, order-record.sql, order-reject.sql, pick-list-delivered.sql  [changed]
src/main/resources/camel/*.camel.yaml      [error clauses of all six files]
src/test/java/…/fault/FaultHook.java
src/test/java/…/failure/*IT.java           [T5–T13]
src/test/java/…/*Test.java                 [T1–T4, harness tests]
src/test/java/…/spec/support/IntegrationProcess.java, SpecEnvironment.java, RejectingSmtpServer.java
src/test/java/…/spec/SpecMatrix.java, Tc001…IT, Tc004…IT, Tc005…IT
src/test/resources/spec-test/split-items.properties
pom.xml                                    [fault-hook jar execution; failsafe includes **/failure/*IT.java]
README.md                                  [kill test in verify; the script and the hook gone; "Finding and moving stuck orders" (§3.8)]
removed: src/test/scripts/kill-test.sh, KillTestHook.java, KillTestApplication.java
```

---

## 7. Open Questions (new, for docs/open_questions.md) and the answers taken

The run is non-interactive, so each recommended answer is taken as an assumption and built. None of them adds a
business rule. Each picks the existing end state that keeps NFR-006, NFR-007, FR-029 and FR-030.

| OQ | Question | Recommended answer (taken) | Why |
|---|---|---|---|
| OQ-075 (existing) | An order that passes every check but does not fit the Order Database | Reading (b) for Peakmart: waits as Recording, no re-poll, alerted after 2 h (FR-039); reading (a) stays open for the spec owner | (b) needs no new rule and stops the block |
| OQ-079 | Is a readable `order.placed` event that fails for good (not the database, not Kafka) set aside? | Yes, after 3 attempts, with reason `cannot be taken in: <kind>` | the only existing end state that keeps the event (90 days, UC-008) and the topic moving (OQ-049, NFR-007) |
| OQ-080 | A Peakmart check that fails unexpectedly (not an outage) | keeps waiting for Check with its reason, tried on every poll, alerted after 2 h (FR-039); never rejected | a check is safe to repeat; a rejection would be invented |
| OQ-081 | A second Order Desk Notification / Rejection Email after a crash between acceptance and marking (K3) | accepted (at least once) | marking before sending could lose an email, against BR-004/005 |
| OQ-082 | The mail server refuses a recipient for good (5xx) | keep trying (BR-004/005), with `last_failure`; propose an alert kind "notification not sent" to UC-006's owner | the spec says until sent; a person must fix the address |
| OQ-083 | A Partner Order that passes every check but does not fit | answered temporarily unavailable, ERROR `unstorable`; long term OQ-075 (a) | FR-030's answer without inventing a rejection |
| OQ-084 | What happens after a `java.lang.Error` (`OutOfMemoryError` included)? | **changed by design review decision 1:** an `Error` a step throws for a reason of its own (`LinkageError`, `AssertionError`, …) is contained, the order ends in its flow's end state, the process keeps running. A `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`, `InternalError`) logs one ERROR line and stops the process with a non-zero exit code, without a graceful shutdown; restart is deployment's (chapter 9) | after a `VirtualMachineError` nothing in the JVM can be trusted, including the error handling; every state is durable and the kill runs prove a process dying at any step loses no order and delivers no second pick list |
| OQ-085 | NOTIFICATION.attempts Max 1000 against "until sent" | the counter stops at 1000, the retries go on; `last_attempt_at` shows they do | keeps the entity model's limit and the rule |
| OQ-086 | A partner request answered 503 by the time limit that completes later | the resend is a Duplicate and gets the first answer (BR-007) | the answer stays true: nothing was promised |

**Entity model delta** (for `docs/entity_model.md`, `Raised by: design 005`): ORDER_RECEIPT.waiting_reason,
ORDER_RECEIPT.last_check_at, PICK_LIST.last_failure, NOTIFICATION.last_failure, NOTIFICATION.last_attempt_at. Each is
nullable, holds no personal data, and is deleted with its row after 90 days (C-022).

### Assumptions (non-interactive run)

- A-1: the recommended answers of §7 are taken.
- A-2: the pipeline ID `005-hardening` was given; no `camel-kit nextId` was run.
- A-3: the runs used the jar built from `7f37e31` and Testcontainers. The lab is not committed; its observations are
  in the evidence file.
- A-4: D6 and M3 (an `Error` in the pick-list and mail pollers) were not run by themselves. They are taken from the
  same sql-consumer mechanism as B2, and T7 runs them.
- A-5 (amendment for review decision 1): the fatal exit code is 70 (`EX_SOFTWARE`), so it differs from the fault
  hook's 137 and from Spring Boot's 1 for a failed start. The review asks only for a non-zero code; T7 checks non-zero,
  T3 checks 70. X6 was not run by itself; it goes through the same unhandled-`Error` mechanism as runs A, B and B2.

### Design review decisions (`design-review.md`)

| # | Decision | Where applied |
|---|---|---|
| 1 | Not every `Error` is contained: a `VirtualMachineError` logs one ERROR line and stops the process with a non-zero exit code, without a graceful shutdown; T7 gains the `OutOfMemoryError` case | Not Doing, §3.1 cases and X6, §3.2 H1, §3.8, §3.9 fault hook, T3, T7, §4 Security, OQ-084, A-5 |
| 2 | Red before green: each test is ordered before its change, run against the code without it, and its failure recorded | §3.9 "Red before green", "Required in the execution report" |
| 3 | Flyway runs with its own connection settings, without the 5 s socket timeout, and a test shows which settings each uses | §3.3, T12, §6 |
| 4 | The README gains a section for the Integration Team: for each end state, the query that finds it and the step that gets it moving; the statements are checked by a test | §3.6, §3.8, T13, §6 |
| 5 | Build time is measured: `verify` and `-DskipITs` before and after, each new IT; over 15 minutes, name the costliest ITs and keep them in `verify` | §4 Performance, "Required in the execution report" |

Everything else, A-1 to A-4 and the answers taken for OQ-075 (b) and OQ-079 to OQ-086, was accepted as written,
OQ-084 as changed by decision 1. The review confirmed that no circuit breaker is added, for the reason in Not Doing.

### Required in the execution report

- For every test of §3.9 and §3.10: its failure against the code without its change (test, assertion, message), and its
  pass after the change (decision 2).
- The measured duration of `./mvnw verify` and of `./mvnw verify -DskipITs`, before and after, and the time of each
  new IT. If `verify` takes more than 15 minutes, the ITs that cost the most (decision 5).
- The mechanism chosen for Flyway's own connection, and T12's result (decision 3).
