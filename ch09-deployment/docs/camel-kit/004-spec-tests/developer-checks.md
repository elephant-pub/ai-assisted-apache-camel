# Developer checks after execution (2026-11-13)

1. **Commit.** The work after Wave 5 (the five ITs, `SpecMatrix`, `SpecTraceabilityTest`, the later helpers and the
   `pom.xml` and `README.md` changes) is committed together as `Session 05-execute-resume`.

2. **Build, run by the developer on a clean copy** (no `target/`, nothing running beforehand): `./mvnw -B verify`
   exits 0 in 189 s; surefire 54/0/0, failsafe 5/0/0. The matrix summary lines equal the execution report's.

3. **Shipped classpath.** `./mvnw dependency:list -DincludeScope=runtime` lists
   `com.networknt:json-schema-validator:jar:2.0.1:compile`, the version `camel-json-validator` 4.22.2 declares.

4. **Fault injection: do the tests notice when the integration is wrong?** Each fault is one exact text change on a
   clean copy of the project, unknown to the sessions that wrote the tests, followed by `./mvnw -B verify
   -Dmaven.test.failure.ignore=true` so that the unit tests and the spec tests both run. The baseline (no fault) passes.

   | Fault | Change | Failing tests | Caught |
   |---|---|---|---|
   | F1 | email pattern `\\s` written as `\s` (the `camel export` bug of pipeline 002) | `EmailPatternTest`; TC-001 C-setup, TC-002 S2, TC-005 C-setup | yes |
   | F2 | Peakmart address lines joined with `' '` instead of `', '` | TC-002 S4 (pick list `ship_street`) | yes, spec tests only |
   | F3 | Catalog Price rule inverted (`if (!partner)`) | `OrderChecksPartnerTest`; TC-001, TC-002, TC-005 S2 | yes |
   | F4 | Pick List total weight without quantities | TC-001 S5, TC-002 S4, TC-005 S7 (`total_weight_g`) | yes, spec tests only |
   | F5 | delivery no longer checks `processed/` before writing | none | **no** |
   | F6 | Rejection Reasons listed in reverse check order | none | **no** |
   | F7 | Kafka offset committed before the receipt is written | none | **no** |
   | F8 | the `json-schema-validator` 2.0.1 pin removed from `pom.xml` | TC-001, TC-002, TC-003, TC-005 S2, TC-004 S1 | yes, spec tests only |

   Five of eight caught: two by the unit tests, five by the spec tests. A run takes 117 to 203 s; F8 takes 387 s
   because every stuck exchange makes the integration wait at shutdown.

   Two of eleven runs (both F7) failed before any step with `Cannot create Kafka topic peakmart.orders.v2`: the Kafka
   container reported started, and the admin client was disconnected until its 120 s timeout. A third F7 run passed
   its environment and every test. That is a flaky environment start, like the Ryuk failure in the execution report.

   Notes:
   - F1: TC-001 fails in C-setup with "Partner summit-outfitters not registered". The email pattern is also the
     partner configuration's Order Contact check (`PartnerRegistration`), and `orders@summit-outfitters.example.com`
     has an `s` before the `@`. Its P-cleanup then reports "Partner summit-outfitters was touched by the cleanup",
     which is a knock-on of the failed registration, not of the cleanup.
   - F7 passes every test: an early commit loses an order only if the process dies between the commit and the
     write. The kill test (`src/test/scripts/kill-test.sh`) catches that; it is not part of `./mvnw verify`.
   - F5 and F6 pass every test. Neither is a fault of the tests as designed: no Approved test case has the Warehouse
     move a Pick List to `processed/` between two delivery attempts (F5), and no test case rejects an order for more
     than one reason (F6, TC-003 has exactly one). The gaps are in the test cases.

5. **Follow-up, not in this pipeline.** Proposed for the spec owner: a test case for a Pick List the Warehouse
   already moved to `processed/` when delivery is retried, and a TC-003 variant with two Rejection Reasons in check
   order. The design gap the execution report names for TC-004 (`C-alert`, UC-006) is accepted as recommended.
