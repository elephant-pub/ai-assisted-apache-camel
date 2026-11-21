# Developer checks after execution (2026-11-14)

1. **Plan accepted unchanged.** I read the 38 tasks before execute and changed nothing. The three things the design
   asked the plan to settle (the intake commits once after a set-aside, the interceptor order, the split item IDs) are
   answered in the execution report, and T7 TC-912 checks the first at runtime.

2. **Commit.** Execute made no commits; its whole working tree is committed as `Session 04-execute`.

3. **Build, run by the developer on a clean copy** (`./mvnw clean`, nothing running beforehand):
   - `./mvnw -B clean verify` exits 0 in 1,075 s (17:55). Unit tests 170/0, spec ITs 5/0, failure ITs 23/0, matrix
     written. Before this pipeline: 189 s.
   - `./mvnw -B clean verify -DskipITs` exits 0 in 33 s (before: 4.6 s without `clean`).
   - The build now takes more than the 15 minutes of design decision 5. The costliest ITs are named in
     `evidence/build-times.md` (KillIT 514 s, UnexpectedErrorIT 202 s). None leaves `verify`, as decided. Making them
     cheaper (the 40 s Kafka session timeout after every halt) goes to the pipeline that sets up CI (chapter 9).

4. **Shipped artifact.**
   - `dependency:list -DincludeScope=runtime` lists 202 entries, the same as pipeline 003, with
     `json-schema-validator:2.0.1`.
   - `target/order-integration-0.1.0.jar` contains no fault hook, kill-test or test class. The hook is in the separate
     `order-integration-0.1.0-fault-hook.jar` (test-jar, used only by the failure ITs).

5. **Red before green (decision 2).** `evidence/red-green-runs.md` has a red and a green row for every test except T9,
   T10 and T11, whose red runs fell in the Docker disk-full window. I filled the gap with fault runs (item 6):
   - T9 `DatabaseFrozenIT`: R9 (H3 removed) **does not make it fail**. See finding 7a.
   - T10 `UnstorableOrderIT`: F15 (H5's park not written) makes it fail.
   - T11 `DeliveryFailureIT`: R11 (H6's `last_failure` not written) makes it fail.

6. **Fault injection.** Each fault is one exact text change on a clean copy of the final code, followed by
   `./mvnw -B verify -Dmaven.test.failure.ignore=true`.
   - F5 to F8 are carried over from pipeline 004; decision 3 of 004 named their gaps.
   - F9 to F13 were written on 2026-11-13, before any session of this pipeline, and never shown to one.
   - F14 to F16, R9 and R11 were written after execute, against its code.
   - The harness is `faults/faults.py` and `faults/run_faults.py` in the book's code project.

   | Fault | Change | Failing tests | Caught |
   |---|---|---|---|
   | F5 | delivery no longer checks `processed/` | `WarehouseSftpLookupTest`, `KillIT` | yes (missed in 004) |
   | F6 | rejection reasons in reverse check order | `NotificationTextIT` | yes (missed in 004) |
   | F7 | Kafka offset committed before the receipt | `KillIT`, `UnexpectedErrorIT` (the fatal case) | yes (missed in 004) |
   | F8 | `json-schema-validator` pin removed | `JsonValidatorPinTest` (also with `-DskipITs`), all five spec ITs, six failure ITs | yes |
   | F9 | intake database failure `handled: true` | `WaitingSelectIT`, but with an environment error (Flyway could not connect), not because of the fault | **no** |
   | F10 | pick-list SFTP `fileExist: Fail` → `Override` | none | **no** |
   | F11 | catalog timeout 2,000 → 60,000 ms | none | **no** |
   | F12 | the shared catalog throttle removed | none | **no** |
   | F13 | partner catalog outage answered 400, not 503 | `UnexpectedErrorIT` (partner case) | yes |
   | F14 | `errorContainment` no longer a bean (H1 off) | `UnexpectedErrorIT`, all six cases | yes |
   | F15 | `order-park.sql` leaves `waiting_for = 'Check'` | `UnstorableOrderIT` | yes |
   | F16 | Order Desk mail send retried 3 times inside one attempt | `UnexpectedErrorIT` (notification case) | yes |
   | R9 | H3's three Order Database time limits removed | `ConnectionSettingsIT` only | settings only |
   | R11 | `pick-list-failed.sql` without `last_failure` | `DeliveryFailureIT`, `UnexpectedErrorIT` | yes |

   All four of 004's misses from decision 3 (F5–F8) are now caught, F8 in the fast build too. Of the five hidden faults
   written before the sessions (F9–F13), one is caught. A run takes 14 to 21 minutes; F8 takes 35, because every
   stuck partner exchange waits at shutdown. One run in fifteen hit an environment error (F9's `WaitingSelectIT`).

7. **Findings.**
   - a. **The 9 s safety net hides the time limits behind it.** `spring.mvc.async.request-timeout=9s` (H2) answers the
     partner 503 within 10 s whatever holds the request. So `DatabaseFrozenIT` passes without H3's database limits (R9),
     and with a 60 s catalog timeout (F11). Both are still wrong:
     - Without H3, the request goes on after its answer and holds a connection for 30 s.
     - With F11, a slow catalog holds every Peakmart check for a minute per line.
     
     Only `ConnectionSettingsIT` guards H3, by reading the settings, and nothing guards the catalog timeout. A test
     that measures the partner answer can't see either; one that measures the time the request itself takes (or the
     check poll) can.
   - b. **F9: the test's outage is shorter than the retries.** `DatabaseFrozenIT` freezes the database for 15 s. The
     intake redelivers a database failure 3 times with back-off (about 35 s with the 7 s time limits), so the event is
     written before the retries run out, with the fault or without it. I ran the same test with a 60 s freeze:
     - With F9, it fails. The handled failure skips the event without a log line; its offset stays uncommitted, so it
       is taken in only after a restart (the test's drain start picked it up). A later record's commit would have lost
       it for good.
     - Without F9, it passes (96 s).
     
     The freeze must outlast the retries.
   - c. **Nothing measures C-010.** No test counts catalog calls per second, so F12 passes. A test with a 25-line order
     against a counting catalog would see it.
   - d. **F10 is a second guard.** `fileExist: Fail` only matters when the first guard (the existence check) is wrong
     or loses a race. A test that removes the first guard as a fixture could see it. Low priority.
   - e. **Failure IT reports don't say why.** A failing Citrus test reports `CitrusRuntimeException: Test failed`, with
     no assertion message in the surefire report or the XML. The reason is only in the test's log lines and the
     integration's log. Chapter 7's spec tests print the failed item; the failure ITs should too.

8. **Not run as planned.** I meant to run the failure ITs against chapter 7's code and against a one-line "harden the
   routes" session's code (`00-harden-one-line`) for a before-and-after. They read V3's columns (`waiting_reason`,
   `last_failure`), so on the older code they fail on the schema, not the behavior. The before picture is the design's
   lab runs (`evidence/failure-lab-runs.md`, against the chapter 7 jar) and T7's red run of 6/6 against the code before
   H1–H6.
