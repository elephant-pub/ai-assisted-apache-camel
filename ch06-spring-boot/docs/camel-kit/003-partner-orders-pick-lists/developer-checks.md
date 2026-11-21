# Developer checks after execution (2026-11-13)

1. **Contract $refs (execution report, needs a decision).** The OpenAPI parser can't resolve the contract's relative
   `../messages/*.schema.json` $refs on the classpath and logged five ERROR lines at every start. Decision (first version, replaced after validation, see `validation-decisions.md`): the build
   bundles `docs/api/partner-orders.openapi.yaml` with `openapi-generator-maven-plugin` 7.14.0 (generator
   `openapi-yaml`) into one file whose $refs are all internal, and copies only that file to `contract/api/`. `docs/`
   stays the single source. After the change the start logs no ERROR line, and `POST /orders` without a key answers 401.

2. **Kill test.** `kill-test.sh` waited for `Recorded`, but since this pipeline a recorded order goes on to its pick
   list. It now waits for `Sent to Warehouse`, expects 3 history entries per order and one delivered pick list per
   order. Run 151115, real `kill -9`:

   ```text
   killed with 11 of 50 received; committed offset moved by 10
   ok   orders recorded: 50
   ok   orders recorded twice: 0
   ok   history entries: 150
   ok   pick lists delivered: 50
   ok   Duplicates skipped after the restart: 1
   ```

   The SFTP folder holds 50 files `PL-PM-151115*.csv` and no `.tmp` file.

3. **`scenarios.sh` of 002** refuses to run on a database that already holds its order numbers; that is its guard
   working. Its scenarios were covered by the execution report's runs with fresh numbers.
