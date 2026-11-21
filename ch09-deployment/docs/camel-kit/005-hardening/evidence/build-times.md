# Build times — pipeline 005-hardening (design decision 5)

Measured, not estimated. Machine: macOS 26.6 (aarch64), JDK 25.0.3 (Temurin), Maven 3.9.9 (wrapper), Docker for
Testcontainers. Durations are Maven's own `Total time` line (`/usr/bin/time -p` was not usable in this session's shell;
Maven's wall-clock figure covers the same run). Both builds ran without `clean`, on a `target/` left by the previous
pipeline, the same way the "After" runs are made.

## Before (commit `9db4985` code)

`git status --porcelain -- src pom.xml` printed nothing (HEAD `8336eee` only adds the plan; the code is that of
`9db4985`).

| Command | Exit code | Seconds |
|---|---|---|
| `./mvnw -B verify -DskipITs` | 0 | 4.6 |
| `./mvnw -B verify` | 0 | 189 (03:09 min) |

Unit tests: 54, 0 failures. Spec ITs: 5, 0 failures; the matrix was written.

Per IT (failsafe `Time elapsed`):

| IT | Seconds |
|---|---|
| Tc001PartnerOrderToWarehouseIT | 45.23 |
| Tc002PeakmartRedeliveryShippedOnceIT | 39.08 |
| Tc003PeakmartOrderRejectedIT | 38.80 |
| Tc004SetAsideOrderEventReachesWarehouseIT | 23.37 |
| Tc005PickListOnceAfterRestartIT | 38.78 |

## After

All tasks done (uncommitted working tree on `8336eee`). Measured the same way as before (Maven's `Total time`, no
`clean`).

| Command | Exit code | Seconds |
|---|---|---|
| `./mvnw -B verify -DskipITs` | 0 | 21.1 |
| `./mvnw -B verify` | 0 | 1097 (18:17 min) |

Unit tests: 170 (before 54), 0 failures. The fast build gains about 16 s, mostly `ErrorContainmentTest` (6.7 s, the fatal
cases wait for the halt) and the unit-test fork's start. Spec ITs: 5, 0 failures; the matrix was written. Failure ITs
(second failsafe execution): 23 tests in 9 classes, 0 failures.

Per IT (failsafe `Time elapsed`):

| IT | Seconds | Before |
|---|---|---|
| Tc001PartnerOrderToWarehouseIT | 51.17 | 45.23 |
| Tc002PeakmartRedeliveryShippedOnceIT | 43.81 | 39.08 |
| Tc003PeakmartOrderRejectedIT | 44.59 | 38.80 |
| Tc004SetAsideOrderEventReachesWarehouseIT | 23.65 | 23.37 |
| Tc005PickListOnceAfterRestartIT | 36.89 | 38.78 |
| failure/ConnectionSettingsIT (T12) | 4.38 | new |
| failure/DatabaseFrozenIT (T9) | 53.10 | new |
| failure/DeliveryFailureIT (T11) | 46.21 | new |
| failure/EndStateQueriesIT (T13) | 8.54 | new |
| failure/KillIT (T8) | 514.0 | new |
| failure/NotificationTextIT (T5) | 9.29 | new |
| failure/UnexpectedErrorIT (T7) | 201.9 | new |
| failure/UnstorableOrderIT (T10) | 41.02 | new |
| failure/WaitingSelectIT (T6) | 3.99 | new |

**Over 15 minutes (decision 5): `./mvnw verify` takes 18:17.** The ITs that cost the most, in descending order:

1. KillIT, 514 s. Seven kill points, each a halted start plus a normal restart. After every Peakmart halt the new
   consumer waits about 40 s for the dead member's Kafka session to time out. Across three full runs it took 381–514 s.
2. UnexpectedErrorIT, 202 s. Six processes, including the fatal case's restart.
3. DatabaseFrozenIT, 53 s.
4. Tc001, 51 s.
5. DeliveryFailureIT, 46 s.

None was moved out of `verify` (decision 5: NFR-006/NFR-007 are proven in every build). The design's estimate (§4: "roughly
8–10 minutes" added) is close to the measured +15 min. The Kafka session timeout after a halt is the largest single
factor.
