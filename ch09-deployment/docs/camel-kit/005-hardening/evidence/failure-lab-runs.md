# Failure-mode runs of 2026-11-13 (design 005, "what happens today")

These runs back every "today (run)" cell of the design spec's §3. The integration under test is the packaged jar
`target/order-integration-0.1.0.jar` of commit `7f37e31`, unchanged, started as the spec tests start it (profile
`spec-test`, `SpecEnvironment` containers: Kafka, PostgreSQL 16, Warehouse SFTP). Two things were added from outside the
jar:

- **A fault hook** on `loader.path` (Spring Boot `PropertiesLauncher`): a test-only `InterceptStrategy` that, before
  the node with a given id runs, throws `NoSuchMethodError` (an `Error`, as F8 did), or calls `Runtime.halt(137)` (the
  process dies like a SIGKILL at that step). The production jar has no hook.
- **A Product Catalog and an SMTP server** whose failure mode can be switched (slow, 500, 429, invalid 200, reset,
  down; SMTP 550 at RCPT, silent server).

Docker controls (`pause`, `exec chmod`) simulated a frozen Order Database, a frozen or read-only Warehouse SFTP Server
and a frozen Kafka broker. The lab is throwaway (`target/hardening-analysis/lab`, not committed). Its raw log is
`target/hardening-analysis/lab/target/results/observations.md`; the integration's logs are under
`target/hardening-analysis/lab/target/work/lab-logs/`.

## A. An `Error` on the partner path

| Run | Fault | Partner answer | Order Database | Log |
|---|---|---|---|---|
| A1 | `NoSuchMethodError` at `to-validate-product` (catalog-lookup thread) | **HTTP 503 after 30 727 ms**, Spring's generic body `{"timestamp":…,"status":503,"error":"Service Unavailable","path":"/orders"}`, not the contract's answer | receipt `Received`, being-checked mark set | **nothing** about the Error (0 ERROR lines, no `NoSuchMethodError`); only Spring's `AsyncRequestTimeoutException` WARN |
| A2 | `NoSuchMethodError` at `to-validate-partner-completed-order` (request thread) | same, 30 813 ms | same | same |
| A3 | resend of both after 32 s (fault used up) | 200 accepted | Recorded once | — |
| A stop | SIGTERM | — | — | `Waiting as there are still 4 inflight and pending exchanges` for 45 s (partner-orders-rest-route, partner-order-submit, -receive, -check); shutdown 46 s |

## B. An `Error` in the Peakmart flows

| Run | Fault | What happened |
|---|---|---|
| B | `NoSuchMethodError` **once** at `to-validate-order-placed` (intake) | The first record's exchange never ends. **The Kafka consumer thread is blocked: none of the 3 records published is taken in** (0 receipts, committed offset unchanged) for the whole run. Nothing logged but the fault line. Shutdown waits 45 s on 2 in-flight exchanges. After the next start (K1) all three were taken in: nothing lost, but nothing moved until a restart. |
| BP | the same Error on every attempt (like F8) | Same as B: the topic stops, no set-aside row, no log line, no retry. |
| B2 | `NoSuchMethodError` **once** at `to-validate-order` (order-record) | **The check poller stops**: all 3 Peakmart Orders stay `Received`/`waiting_for=Check` for 40 s and beyond, no pick list, no notification, nothing logged. Shutdown waits 45 s on `order-record = 1, peakmart-order-check-poll = 2`. The pick-list and notification pollers were not reached (no order got that far). |

## K. The process killed at a step (`Runtime.halt`, then a normal start)

| Run | Killed before node | Before restart | After restart |
|---|---|---|---|
| K1 | `commit-kafka-offset` (receipt written, offset not committed; F7) | the record in the gap was PM-90001 (left uncommitted by B) | `Duplicate Peakmart order skipped orderNumber=PM-90001`; every order recorded once; committed offset +6 |
| K2 | `to-pick-list-delivered` (file uploaded, Delivered not recorded); then the file was moved to `processed/` by hand, as the Warehouse does (F5) | `PL-PM-91002.csv` in `processed/`, pick list `Pending` | `already on the warehouse server` logged; Delivered, Sent to Warehouse; still exactly one file |
| K3 | `log-order-desk-notified` (mail server accepted, row not marked sent) | 1 email accepted, row unsent | **a second email** for PM-91003 is sent; row marked sent |
| K4 | `log-partner-order-recorded` (recorded, not answered) | connection reset at the partner; receipt Recorded | resend answered 200 accepted from the receipt; one Accepted Order |
| K5 | `to-partner-order-check` (claimed, not checked) | receipt Received, mark set | resend 5 s after the kill: 503 contract answer (A8); after the 30 s expiry: 200, recorded once |
| K6 | `to-product-lookup` (in the middle of a Peakmart check) | receipt waiting for Check | checked again, recorded once, one pick list |

## D. The systems down, slow, answering an error or something unusable

| System | Failure | Result |
|---|---|---|
| Product Catalog | 3 s answers (timeout 2 s) | partner 503 contract answer in 2 927 ms; receipt Received |
| | 500 / 429 / invalid 200 / connection reset / down | partner 503 contract answer in 15 – 1 023 ms |
| | 500 for a Peakmart Order | waits (8 WARN lines `waits for Product Catalog`), recorded and delivered once the catalog answers |
| Order Database | frozen (`docker pause`, 46 s) | **partner: HTTP 503 after 30 915 ms, Spring's generic body** (Hikari: `Connection is not available, request timed out after 30011ms`); the request went on after the answer and recorded TG-1330 once the database was back (a resend would be a Duplicate). Every poller logged `Failed polling endpoint` and resumed. A Peakmart event published during the freeze was recorded afterwards. |
| | Peakmart Order with a 150-character recipient name (passes every check; `customer_order.recipient_name` is `varchar(100)`) | **waits for Check forever**: re-checked on every poll, a WARN `stays waiting after a database failure` each time, 0 ERROR lines (OQ-075) |
| Mail server | 550 at RCPT | tried on every poll (5 attempts in 15 s), `attempts` counted, unsent; a full-stack ERROR from Camel's DefaultErrorHandler on each attempt (no address in it) |
| | accepts, never answers | each attempt waits for the 10 s timeout; unsent |
| | back to normal | sent once |
| Warehouse SFTP | inbound folder read-only | 10 attempts in 20 s, `GenericFileOperationFailedException`; delivered once it is writable again |
| | frozen | `RuntimeBeanExpressionException` (the stat check timed out); delivered after the unpause |
| | a read-only `PL-x.csv.tmp` left over | overwritten; delivered once |
| | `processed/` missing | delivered (the stat of `processed/x` is "no such file") |
| Kafka | frozen 40 s | partner unaffected (200); consumer resumed; the next event recorded |
