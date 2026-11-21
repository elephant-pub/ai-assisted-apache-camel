# Developer checks after execution (2026-11-13)

The execution report left three checks open. Run by the developer:

1. **Kill test** (`src/test/scripts/kill-test.sh`, real `kill -9`, run 133407): exit 0.

   ```text
   killed with 11 of 50 received; committed offset moved by 10
   ok   orders recorded: 50
   ok   orders recorded twice: 0
   ok   history entries: 100
   ok   Duplicates skipped after the restart: 1
   logs in target/kill-test/133407; result in target/kill-test/133407/result.txt
   ```

2. **Prototype guard:** `bash prototype/run.sh` refuses with the REFUSED message and starts nothing.

3. **Scenario 7, catalog outage**, with the jar on the `dev` profile: with the stub stopped, PM-67007 stayed
   `Received` / waiting for `Check` and logged "waits for Product Catalog" on each poll; after the stub started it was
   `Recorded`. Scenarios 8 to 10 were not repeated: their routes and SQL are unchanged from the prototype, where they
   passed.

The refusal-to-start gap (`DB_*` variables not named, execution report item 1) goes to the next pipeline.
