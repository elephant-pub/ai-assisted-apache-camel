# Build times — 006 deployment (design §3.15, 005 decision 6)

Local machine: macOS 26.6.2, Apple silicon (aarch64), Temurin 25.0.3 (Maven 3.9.9 via `./mvnw`), Docker for
Testcontainers. The whole `./mvnw -B verify` is measured, without `clean`. Wall time is Maven's own `Total time`
(the run had no permission for `/usr/bin/time`); per-IT times are the `Time elapsed` failsafe reports for each class
(the same value as `time` in `target/failsafe-reports/TEST-*.xml`).

## Before (code `a1b55cf`)

Measured 2026-11-16, Task 1 (wave 1), on the unchanged code (`af64292` = `a1b55cf` plus the plan only), `java.version`
21 compiled on JDK 25.

| Run | Exit | Wall time | Unit tests | Spec ITs | Failure ITs |
|---|---|---|---|---|---|
| `./mvnw -B verify` | 0 (`BUILD SUCCESS`) | 16:19 min (979 s) | 175 run, 0 failed, 0 skipped | 5 run, 0 failed | 26 run, 0 failed, 0 skipped |

| IT class | Tests | Time (s) |
|---|---|---|
| spec.Tc001PartnerOrderToWarehouseIT | 1 | 48.83 |
| spec.Tc002PeakmartRedeliveryShippedOnceIT | 1 | 48.88 |
| spec.Tc003PeakmartOrderRejectedIT | 1 | 44.70 |
| spec.Tc004SetAsideOrderEventReachesWarehouseIT | 1 | 24.39 |
| spec.Tc005PickListOnceAfterRestartIT | 1 | 42.61 |
| failure.ConnectionSettingsIT | 1 | 4.495 |
| failure.DatabaseFrozenIT | 1 | 96.55 |
| failure.DeliveryFailureIT | 2 | 40.68 |
| failure.EndStateQueriesIT | 2 | 1.885 |
| **failure.KillIT** | 7 | **382.8** |
| failure.NotificationTextIT | 1 | 1.692 |
| **failure.UnexpectedErrorIT** | 9 | **186.3** |
| failure.UnstorableOrderIT | 2 | 36.82 |
| failure.WaitingSelectIT | 1 | 2.354 |

KillIT: 382.8 s. UnexpectedErrorIT: 186.3 s. Together 569 s, 58 % of the build.

## After waves 1–2 (Task 16)

Taken from the two wave gates (`execution-report.md`, wave 1 and wave 2 handoffs; logs
`target/006-wave1-logs/`, `target/006-wave2-verify.log`), not re-run. The wave 2 gate is the "mid" point: Java 25,
D2, D11 (wave 1) and D16, D17, A-9 with T13 and T14 (wave 2), before D3 and D10.

| Run | Exit | Wall time | Unit tests | Spec ITs | Failure ITs |
|---|---|---|---|---|---|
| wave 1 gate `./mvnw -B verify` | 0 (`BUILD SUCCESS`) | 16:15 min (975 s) | 183 run, 0 failed, 0 skipped | 5 run, 0 failed | 26 run, 0 failed, 0 skipped |
| **wave 2 gate `./mvnw -B verify`** | 0 (`BUILD SUCCESS`) | **19:38 min (1178 s)** | 189 run, 0 failed, 0 skipped | 5 run, 0 failed | 29 run, 0 failed, 0 skipped |

| IT class | Before (s) | Wave 1 (s) | Wave 2 (s) |
|---|---|---|---|
| spec.Tc001PartnerOrderToWarehouseIT | 48.83 | 43.3 | 56.7 |
| spec.Tc002PeakmartRedeliveryShippedOnceIT | 48.88 | 44.1 | 43.4 |
| spec.Tc003PeakmartOrderRejectedIT | 44.70 | 43.5 | 43.6 |
| spec.Tc004SetAsideOrderEventReachesWarehouseIT | 24.39 | 22.9 | 34.1 |
| spec.Tc005PickListOnceAfterRestartIT | 42.61 | 36.7 | 55.8 |
| failure.ConnectionSettingsIT | 4.495 | 5.0 | 12.7 |
| failure.DatabaseFrozenIT | 96.55 | 99.9 | 122.4 |
| failure.DeliveryFailureIT | 40.68 | 42.5 | 51.2 |
| failure.EndStateQueriesIT | 1.885 | 4.1 | 2.0 |
| **failure.KillIT** | **382.8** | **378.0** | **394.4** |
| failure.NotificationTextIT | 1.692 | 2.0 | 6.2 |
| failure.PickListLoginDirectoryIT (T13, new) | — | — | 23.7 |
| failure.SftpSubsystemHangIT (T14, new) | — | — | 42.2 |
| **failure.UnexpectedErrorIT** | **186.3** | **193.3** | **216.3** |
| failure.UnstorableOrderIT | 36.82 | 41.7 | 35.6 |
| failure.WaitingSelectIT | 2.354 | 2.4 | 2.6 |

Wave 2: KillIT 394.4 s, UnexpectedErrorIT 216.3 s, together 611 s, 52 % of the build. Of the 3:23 min more than
wave 1, about 66 s are the two new ITs; the rest is slower spec and failure ITs on this run (same code paths).

## After D10 (Task 24)

Measured 2026-11-16, wave 3 gate: waves 1–3 (D3, D5, D10, D13, T6, T8, T9, T10, developer checks 2 and 4). Wall time is
Maven's `Total time` (`/usr/bin/time -p` was refused in this session, as in wave 1). Log
`target/006-wave3-logs/gate-verify.log`.

| Run | Exit | Wall time | Unit tests | Spec ITs | Failure ITs |
|---|---|---|---|---|---|
| `./mvnw -B verify` | 0 (`BUILD SUCCESS`) | **17:27 min (1047 s)** | 192 run, 0 failed, 0 skipped | 5 run, 0 failed | 31 run, 0 failed, 0 skipped |

| IT class | Before (s) | Wave 2 (s) | After D10 (s) |
|---|---|---|---|
| spec.Tc001PartnerOrderToWarehouseIT | 48.83 | 56.7 | 58.58 |
| spec.Tc002PeakmartRedeliveryShippedOnceIT | 48.88 | 43.4 | 44.22 |
| spec.Tc003PeakmartOrderRejectedIT | 44.70 | 43.6 | 43.71 |
| spec.Tc004SetAsideOrderEventReachesWarehouseIT | 24.39 | 34.1 | 24.33 |
| spec.Tc005PickListOnceAfterRestartIT | 42.61 | 55.8 | 46.58 |
| failure.ConnectionSettingsIT | 4.495 | 12.7 | 4.738 |
| failure.DatabaseFrozenIT | 96.55 | 122.4 | 102.62 |
| failure.DeliveryFailureIT | 40.68 | 51.2 | 49.23 |
| failure.EndStateQueriesIT | 1.885 | 2.0 | 2.823 |
| **failure.KillIT** | **382.8** | **394.4** | **296.52** |
| failure.NotificationTextIT | 1.692 | 6.2 | 2.866 |
| failure.PickListLoginDirectoryIT | — | 23.7 | 25.89 |
| failure.ProbesIT (T6, new) | — | — | 29.04 |
| failure.SftpSubsystemHangIT | — | 42.2 | 48.45 |
| **failure.UnexpectedErrorIT** | **186.3** | **216.3** | **195.93** |
| failure.UnstorableOrderIT | 36.82 | 41.7 | 43.55 |
| failure.WaitingSelectIT | 2.354 | 2.4 | 3.234 |

KillIT 296.5 s against 382.8 s before (−86 s, −23 %); UnexpectedErrorIT 195.9 s against 186.3 s before (+10 s, within
the run-to-run spread seen in waves 1–2). Together 492 s, 47 % of the build (before: 569 s, 58 %). The whole build is
17:27 min against 16:19 min before and 19:38 min after wave 2: it carries four new ITs (T13, T14, T6: about 103 s) and
T8's two extra events. In isolation (Task 20, same code) KillIT took 223.2 s and UnexpectedErrorIT 155.2 s, and the
K1 intake resumed in 1.7–2.3 s instead of 39–40 s; the 73 s between the isolated and the gate run is run-to-run
spread on this machine (not analysed further). A stale `failure.ZzScratchSftpProbeIT` report in `target/failsafe-reports`
is wave 2's deleted probe, not part of this run (31 = 29 + ProbesIT's 2).

## Final (Task 38)

Measured 2026-11-16, wave 6: every change of pipeline 006 (waves 1–6; wave 6 changed no file under `src/` or `pom.xml`,
only CI scripts, the workflow and docs). Wall time is Maven's `Total time`: `/usr/bin/time -p` was refused again, as in
waves 1 and 3. Two full runs this session on the same code: the T11 green run (Task 36) and the final run after the
review's fixes. Logs `target/006-wave6-logs/t11-green-verify.log`, `final-verify.log`. After the final run,
`deploy/ci/test-inventory.sh`: all 35 test classes have a clean report. T5 (28/28 valid) and actionlint (0 errors) were
re-run this wave too.

| Run | Exit | Wall time | Unit tests | Spec ITs | Failure ITs |
|---|---|---|---|---|---|
| `./mvnw -B verify` (Task 36, T11 green) | 0 (`BUILD SUCCESS`) | 14:41 min (881 s) | 203 run, 0 failed, 0 skipped | 5 run, 0 failed | 31 run, 0 failed, 0 skipped |
| **`./mvnw -B verify` (final)** | 0 (`BUILD SUCCESS`) | **19:20 min (1160 s)** | 203 run, 0 failed, 0 skipped | 5 run, 0 failed | 31 run, 0 failed, 0 skipped |

| IT class | Before (s) | After D10 (s) | Task 36 (s) | Final (s) |
|---|---|---|---|---|
| spec.Tc001PartnerOrderToWarehouseIT | 48.83 | 58.58 | 49.17 | 50.85 |
| spec.Tc002PeakmartRedeliveryShippedOnceIT | 48.88 | 44.22 | 42.56 | 38.76 |
| spec.Tc003PeakmartOrderRejectedIT | 44.70 | 43.71 | 51.46 | 46.75 |
| spec.Tc004SetAsideOrderEventReachesWarehouseIT | 24.39 | 24.33 | 25.82 | 23.83 |
| spec.Tc005PickListOnceAfterRestartIT | 42.61 | 46.58 | 45.07 | 38.58 |
| failure.ConnectionSettingsIT | 4.495 | 4.738 | 4.414 | 4.220 |
| failure.DatabaseFrozenIT | 96.55 | 102.62 | 97.90 | 97.69 |
| failure.DeliveryFailureIT | 40.68 | 49.23 | 43.10 | 47.13 |
| failure.EndStateQueriesIT | 1.885 | 2.823 | 2.541 | 2.939 |
| **failure.KillIT** | **382.8** | **296.52** | **208.85** | **224.6** |
| failure.NotificationTextIT | 1.692 | 2.866 | 2.275 | 1.849 |
| failure.PickListLoginDirectoryIT | — | 25.89 | 28.19 | 22.78 |
| failure.ProbesIT | — | 29.04 | 29.03 | 41.27 |
| failure.SftpSubsystemHangIT | — | 48.45 | 43.26 | 74.50 |
| **failure.UnexpectedErrorIT** | **186.3** | **195.93** | **158.42** | **345.8** |
| failure.UnstorableOrderIT | 36.82 | 43.55 | 31.87 | 74.62 |
| failure.WaitingSelectIT | 2.354 | 3.234 | 1.652 | 5.096 |

The same code took 14:41 and 19:20 min an hour apart. Of the 279 s difference, 187 s are `UnexpectedErrorIT`
(158 → 346 s), with `UnstorableOrderIT`, `SftpSubsystemHangIT` and `ProbesIT` slower too. The other ITs were within a few
seconds. This is run-to-run spread on this machine (other containers run on it; not analysed further), the same kind
as wave 3's 73 s. KillIT stays well below the 382.8 s before D10 in both runs (209–225 s, −41 to −45 %). For the
build-time rule of §3.15 (split failsafe if job 1 takes more than 25 minutes after D10), only the developer's CI run
counts. Locally the whole build took 14:41 to 19:20 min.
