# Decisions on the validation report of 004 (integration architect, 2026-11-13)

1. **S-1 to S-4, carried over from 003 (fail).** Re-accepted for 004 with the reasons of the 003 decisions 2 and 3:
   the development passwords and the dev partner key belong to throwaway local services and an invented partner, and
   nothing under `config/` is packaged; Kafka TLS and HTTPS to the catalog wait for OQ-038 and OQ-039. This pipeline
   changed nothing under `src/main` or `config/`.

2. **F8 passes with `-DskipITs` (warn, the most important finding).** Without Docker, `./mvnw verify -DskipITs`
   builds a jar with `json-schema-validator` 3.0.4, and nothing fails. A developer without Docker can ship the broken
   jar. Accepted as a must-fix for the next pipeline: a fast guard (a Maven Enforcer rule or a unit test that reads the
   resolved runtime version) so that the pin holds in every build.

3. **F5, F6, F7 (warn).** Two layers close them:
   - In the spec: two proposed test cases for the spec owner, a delivery retried after the Warehouse moved the Pick
     List to `processed/` (F5), and a rejection with two Rejection Reasons in check order (F6).
   - Below the spec: the kill test in the build (F7), and fast tests where the spec tests are slow. The developer
     checks didn't name the files the faults change; F6 is in `notification-select.sql` (`ORDER BY rr.position`),
     not in `OrderChecks`, so a test for it reads the notification text, not the checks' result.
   Chapter 8 (hardening) starts from these and from decision 5.

4. **Test harness (warn).** For the next pipeline that touches the spec tests: Kafka topic creation needs a broker
   readiness check and a retry (two of eleven fault runs failed there); the cleanup message "touched by the cleanup"
   names the wrong cause when registration failed; the matrix should reject split-item IDs that don't exist in the
   design; the `C-alert` item for TC-004 (UC-006) goes into the design, the plan and `Tc004…IT.ITEMS`.

5. **Errors that are not exceptions (warn).** The routes catch `java.lang.Exception`; an `Error` such as F8's
   `NoSuchMethodError` leaves the exchange in flight with no answer to the partner. Chapter 8.

Pipeline 004 is accepted.
