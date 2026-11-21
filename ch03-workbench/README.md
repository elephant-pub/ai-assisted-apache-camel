# Chapter 3: the AI workbench

Three things live here.

## catalog-check (this Maven project)

Checks YAML routes against the Camel 4.22.2 catalog: the YAML DSL schema and the component names, endpoint options and
declared values in the endpoint shapes collected by this project. It is a focused build check, not a complete semantic
analysis of every YAML DSL construct.

The project requires JDK 25, the baseline used throughout the book. Camel Kit 0.4.1 itself requires Java 17 or later;
that minimum does not change this project's compiler target.

```bash
./mvnw verify                                          # 12 tests
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp target/classes:$(cat target/cp.txt) com.trailhead.catalogcheck.RouteCatalogCheck path/to/*.camel.yaml
```

## workbench/

`setup.sh` creates a Camel Kit project for Claude Code (`workbench/trailhead-orders`, not committed), pinned to
Camel 4.22.2, with the Camel MCP server at the read-only access level. It needs JDK 25, Python 3, JBang, Camel JBang
4.22.2 and Camel Kit 0.4.1. Read the script and inspect the generated project before trusting it in Claude Code.
Install the AIUP core plugin by hand inside Claude Code afterwards.

## measure/

The memory-versus-catalog measurement: 12 tasks (`tasks.json`), a reference route for each (`reference/`), and the
scripts that ran them.

```bash
./measure/run-all.sh        # 72 paid Claude Code sessions, 4 at a time
python3 measure/score.py    # results/summary.md and results/runs.csv
```

The 72 scored sessions recorded $6.86 in model charges. Allow extra budget for failed or repeated runs, and expect
prices to change. Do not rerun the measurement unless a changed model or Camel release makes a new result useful.

`results/` holds every route and session transcript from the run described in the chapter (home folder paths replaced
with `~`), plus `timeout-test.txt` and `connect-timeout-test.txt` from `probes/`, which run the task-08 routes
against a slow server and an address that never answers.

