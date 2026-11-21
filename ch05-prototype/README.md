# Chapter 5: the Peakmart intake prototype

UC-002 Take In Peakmart Order, with the order checks of UC-003, as YAML routes run by Camel JBang 4.22.2 against Kafka
and PostgreSQL from `camel infra`. Built with Camel Kit 0.4.1 (`/camel-brainstorm`, `/camel-plan`, `/camel-execute`,
`/camel-validate`) from the chapter 4 spec, in six Claude Code sessions.

| Folder | What it holds |
|---|---|
| `docs/` | The chapter 4 spec (with OQ-075, which the prototype raised) and `camel-kit/001-peakmart-intake/`: design spec, design review, implementation plan, execution report, validation report and decisions |
| `prototype/` | The routes, SQL, two Java beans, `run.sh`, `scenarios.sh`, `kill-test.sh` and the Product Catalog stub |
| `src/test/` | Checks of the prototype: chapter 3's catalog check on every route, the pinned Camel version in every script |

## Run it

Each of the long-running commands in its own terminal:

```bash
camel infra run kafka --background
camel infra run postgres --port 15432 --background       # 5432 was taken on the book's machine

(cd prototype/stubs/product-catalog && ./run.sh)          # Product Catalog stub on 127.0.0.1:8091
DB_PASSWORD=test prototype/run.sh                         # creates the tables, then runs the integration
prototype/scenarios.sh                                    # scenarios 1 to 6
DB_PASSWORD=test prototype/kill-test.sh                   # with the integration stopped: SIGKILL and restart
```

`scenarios.sh` and `kill-test.sh` publish and query with the client tools inside the `camel-kafka` and
`camel-postgres` containers that `camel infra` starts.

## Check it

```bash
./mvnw verify          # 3 tests; uses ../ch03-workbench's catalog check
```

