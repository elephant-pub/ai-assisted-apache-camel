#!/bin/bash
# Kill test (design §3, peakmart-order-intake): SIGKILL the integration after an order's receipt is written but before
# its Kafka offset is committed, start it again, and check that the order is skipped as a Duplicate and that every
# order is recorded exactly once.
#
# COMMIT_DELAY_MS holds each commit back (a test-only hook in the intake route, 0 by default), so the kill lands in
# the gap. Precondition: the Product Catalog stub runs; the integration does not. Needs DB_PASSWORD.
# Exits 1 when a check fails, 2 when the kill missed the gap (the run proves less; run it again).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
: "${DB_PASSWORD:?set DB_PASSWORD}"
N=${N:-50}; KILL_AT=${KILL_AT:-10}; RUN=${RUN:-$(date +%H%M%S)}
PREFIX="PM-$RUN"                          # every run has its own order numbers: PM-<run><4 digits>
LOGS=target/kill-test/$RUN; mkdir -p "$LOGS"
exec > >(tee "$LOGS/result.txt") 2>&1           # keep what the script prints with the logs
MATCH="order_number ~ '^$PREFIX[0-9]{4}\$'"
sql() { docker exec camel-postgres psql -U test -d postgres -tAc "$1"; }
received() { sql "select count(*) from order_receipt where $MATCH"; }
committed() { docker exec camel-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group trailhead-order-integration 2>/dev/null | awk '$2 == "peakmart.orders.v2" {print $4}'; }
wait_for() { local what=$1 limit=$2; shift 2; local t=0
  until "$@"; do sleep 1; t=$((t+1)); [ $t -lt "$limit" ] || { echo "FAIL: timed out waiting for $what"; exit 1; }; done; }
enough_received() { [ "$(received)" -ge "$KILL_AT" ]; }
all_recorded() { [ "$(sql "select count(*) from order_receipt where $MATCH and order_state = 'Recorded'")" -eq "$N" ]; }
started() { grep -q "Started peakmart-order-intake" "$1" 2>/dev/null; }   # the integration, not the schema init
cleanup() { camel stop peakmart-intake-prototype >/dev/null 2>&1 || true; }
trap cleanup EXIT
check() { if [ "$2" = "$3" ]; then echo "ok   $1: $2"; else echo "FAIL $1: $2, expected $3"; failed=1; fi; }
failed=0

before=$(committed); before=${before:-0}
COMMIT_DELAY_MS=${COMMIT_DELAY_MS:-500} ./run.sh > "$LOGS/1.log" 2>&1 &
wait_for "the integration to start" 120 started "$LOGS/1.log"
python3 - "$N" "$PREFIX" ../docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json \
  > "$LOGS/events.txt" <<'PY'
import json, sys, uuid
n, prefix = int(sys.argv[1]), sys.argv[2]
sample = json.load(open(sys.argv[3]))
for i in range(n):
    print(json.dumps(dict(sample, event_id=str(uuid.uuid4()), order_ref=f"{prefix}{i:04d}")))
PY
docker exec -i camel-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic peakmart.orders.v2 < "$LOGS/events.txt"
wait_for "$KILL_AT receipts" 120 enough_received
kill -9 "$(pgrep -f 'java.*peakmart-order-intake.camel.yaml')"
at_kill=$(received); offset=$(committed)
echo "killed with $at_kill of $N received; committed offset moved by $((offset - before))"
gap=$((at_kill - (offset - before)))      # orders received but not committed when the kill landed
if [ "$gap" = 0 ]; then echo "inconclusive: the kill landed after a commit, not in the gap; run again"; fi

./run.sh > "$LOGS/2.log" 2>&1 &
wait_for "the integration to start again" 120 started "$LOGS/2.log"
wait_for "all orders recorded" 300 all_recorded
check "orders recorded" "$(sql "select count(*) from customer_order where $MATCH")" "$N"
check "orders recorded twice" "$(sql "select count(*) from (select order_number from customer_order where $MATCH group by 1 having count(*) > 1) d")" 0
check "history entries" "$(sql "select count(*) from order_state_change s join order_receipt r on r.id = s.order_receipt_id where r.$MATCH")" "$((2 * N))"
check "Duplicates skipped after the restart" "$(grep -c "Duplicate Peakmart order skipped orderNumber=$PREFIX" "$LOGS/2.log" || true)" "$gap"
echo "logs in $LOGS"
[ "$failed" = 0 ] && [ "$gap" = 0 ] && exit 2
exit $failed
