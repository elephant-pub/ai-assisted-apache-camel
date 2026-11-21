# Kill test results (developer, 2026-11-13)

## First version: kill by receipt count

Three runs of 200 orders killed after 64, 101 and 104 receipts. Every order was recorded once after the restart, but in
every run the committed offset matched the receipts: the kill never landed between a receipt insert and its commit,
so the runs didn't test what the design asks. The validation report (first run) found that, and eight weaknesses of the
script. This version is replaced.

## Second version: commit held back by the test hook

`DB_PASSWORD=test KILL_AT=<k> ./kill-test.sh`, 50 orders, `COMMIT_DELAY_MS=500` for the first start only, the Product
Catalog stub running. The script checks its own numbers.

| Run | Killed with | Committed | In the gap | After restart | Recorded twice | Duplicates skipped | Result |
|---|---|---|---|---|---|---|---|
| 115014, KILL_AT=10 | 11 received | 10 | 1 | 50 Recorded | 0 | 1 | pass |
| 115232, KILL_AT=5 | 6 received | 6 | 0 | 50 Recorded | 0 | 0 | inconclusive (kill after a commit) |
| 115441, KILL_AT=25 | 26 received | 25 | 1 | 50 Recorded | 0 | 1 | pass |

An earlier run, 114703, timed out before the kill because of a bug in the script itself (its wait condition was
evaluated once); it is not counted.

## Final version

The second validation found two more weaknesses: `started()` matched the schema-init run, which shares the context name,
and the runs above were made before the last edit of the script. The final script waits for the intake route and keeps
its output with the logs. One run with it:

| Run | Killed with | Committed | In the gap | After restart | Recorded twice | Duplicates skipped | Result |
|---|---|---|---|---|---|---|---|
| 120414, KILL_AT=15 | 16 received | 15 | 1 | 50 Recorded | 0 | 1 | pass, exit 0 |

Its output is `kill-test-120414.txt`. Logs are in `target/kill-test/<run>/`, not committed.
