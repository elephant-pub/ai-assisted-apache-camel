# Chapter 7: testing against the spec

The chapter 6 order integration with the spec's five approved test cases (`docs/test_cases/TC-001` to `TC-005`) as
executable tests, built by the Camel Kit pipeline `004-spec-tests`. Each test starts the packaged jar as a process and
drives it from the outside: Citrus plays the partner, Peakmart, the Product Catalog and the mailboxes; Testcontainers
starts Kafka, PostgreSQL 16 and the warehouse SFTP server. Spring Boot 4.1.1, Camel 4.22.2, Citrus 5.0.1.

| Folder | What it holds |
|---|---|
| `src/main/` | The integration, unchanged from chapter 6 |
| `src/test/java/.../spec/` | The five spec tests (`Tc001…IT` to `Tc005…IT`), `SpecTraceabilityTest`, `SpecMatrix`, the document parser |
| `src/test/java/.../spec/support/` | The harness: the integration process, the roles, the containers, the result files |
| `src/test/` (rest) | The 34 unit tests of chapter 6, the kill-test hook, the scenario and kill-test scripts |
| `config/` | Development settings, as in chapter 6 (not used by the spec tests) |
| `docs/` | The spec, and `camel-kit/001…004/`: design specs, reviews, plans, execution and validation reports, decisions |
| `faults/` | The eight faults of the chapter, the script that runs the build once per fault, and the results |

## Build

```bash
./mvnw verify            # 54 unit tests and the five spec tests; needs Docker; about 3 minutes
./mvnw verify -DskipITs  # unit tests only, without Docker
```

Nothing has to be running before, and no port is fixed. The container images are pinned by digest in
`src/test/resources/spec-test/spec-test.properties`. Without Docker the spec tests fail rather than skip.

Every build writes `target/spec-tests/traceability-matrix.md` (and `.json`): use case → test case step → test, every
item of every test case, and the items still *not yet testable* because UC-005, UC-006 or UC-008 isn't built. The
integration's logs are in `target/spec-tests/logs/`, the per-test results in `target/spec-tests/results/`.

`-DskipITs` doesn't run the spec tests, so it doesn't catch what only they catch. With `-DskipITs`, a build without the
`json-schema-validator` pin in `pom.xml` passes and ships a broken jar (validation decision 2 of 004).

## Fault injection

```bash
cd faults && python3 run_faults.py ..                      # baseline and all eight faults, about 35 minutes
cd faults && python3 run_faults.py .. F6-reason-order      # one fault
```

Each run copies the project to `faults/work/<fault>`, applies one exact text change, runs `./mvnw verify` with test
failures ignored and prints the test classes that failed. `results.txt` holds the chapter's runs: five of eight faults
caught. Two F7 runs failed while starting Kafka, before any test step; the third ran clean.

## Run it in development

As in chapter 6; see `../ch06-spring-boot/README.md`.

