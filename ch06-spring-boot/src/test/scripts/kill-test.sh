#!/bin/bash
# Kill test (design 002 §3 "Flow: kill-test support", from prototype/kill-test.sh run 120414): SIGKILL the application
# after an order's receipt is written but before its Kafka offset is committed, start it again, and check that the
# order is skipped as a Duplicate and that every order is recorded exactly once.
#
# The first start runs KillTestApplication (test classpath) with the profiles dev,kill-test and
# kill-test.commit-delay-ms=500: KillTestHook holds each commit back so the kill lands in the gap. The second start
# uses only dev, with no hook. The production jar has no hook.
# Precondition: the Product Catalog stub runs; the application does not. Reads Kafka and PostgreSQL only through
# `docker exec` into the running camel-kafka / camel-postgres containers; starts and stops no container.
# Exits 1 when a check fails, 2 when the kill missed the gap (the run proves less; run it again).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../.."                 # project root
N=${N:-50}; KILL_AT=${KILL_AT:-10}; RUN=${RUN:-$(date +%H%M%S)}
COMMIT_DELAY_MS=${COMMIT_DELAY_MS:-500}
PREFIX="PM-$RUN"                          # every run has its own order numbers: PM-<run><4 digits>
LOGS=target/kill-test/$RUN; mkdir -p "$LOGS"
exec > >(tee "$LOGS/result.txt") 2>&1     # keep what the script prints with the logs
MAIN_CLASS=com.trailheadgear.orderintegration.KillTestApplication
MATCH="order_number ~ '^$PREFIX[0-9]{4}\$'"
sql() { docker exec camel-postgres psql -U test -d postgres -tAc "$1"; }
received() { sql "select count(*) from order_receipt where $MATCH"; }
committed() { docker exec camel-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group trailhead-order-integration 2>/dev/null | awk '$2 == "peakmart.orders.v2" {s += $4} END {print s + 0}'; }
wait_for() { local what=$1 limit=$2; shift 2; local t=0
  until "$@"; do sleep 1; t=$((t+1)); [ $t -lt "$limit" ] || { echo "FAIL: timed out waiting for $what"; exit 1; }; done; }
enough_received() { [ "$(received)" -ge "$KILL_AT" ]; }
# since pipeline 003 a recorded order goes on to its pick list, so the end state is Sent to Warehouse
all_delivered() { [ "$(sql "select count(*) from order_receipt where $MATCH and order_state = 'Sent to Warehouse'")" -eq "$N" ]; }
started() { grep -q "Started peakmart-order-intake" "$1" 2>/dev/null; }          # Camel's route-started line
hook_active() { grep -q "Kill-test hook active commitDelayMs=$COMMIT_DELAY_MS" "$1" 2>/dev/null; }
# The forked application JVM only: its main class follows a space. Maven's own JVM has it after
# "-Dspring-boot.run.main-class=", so it does not match and is never killed by this script.
APP_PATTERN="java .* com\.trailheadgear\.orderintegration\.KillTestApplication( |\$)"
MAVEN_PATTERN="spring-boot\.run\.main-class=com\.trailheadgear\.orderintegration\.KillTestApplication"
app_pid() { pgrep -f "$APP_PATTERN" || true; }
app_gone() { [ -z "$(app_pid)" ] && [ -z "$(pgrep -f "$MAVEN_PATTERN" || true)" ]; }
start_app() { # $1 = profiles, $2 = log file, $3 = extra application arguments
  ./mvnw -q spring-boot:test-run -Dspring-boot.run.main-class="$MAIN_CLASS" \
    -Dspring-boot.run.profiles="$1" -Dspring-boot.run.arguments="$3" > "$2" 2>&1 &
}
cleanup() { local p; for p in $(app_pid); do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT
check() { if [ "$2" = "$3" ]; then echo "ok   $1: $2"; else echo "FAIL $1: $2, expected $3"; failed=1; fi; }
failed=0

[ -z "$(app_pid)" ] || { echo "FAIL: an application started by $MAIN_CLASS is already running"; exit 1; }
before=$(committed)
start_app dev,kill-test "$LOGS/1.log" "--kill-test.commit-delay-ms=$COMMIT_DELAY_MS"
wait_for "the application to start" 180 started "$LOGS/1.log"
wait_for "the kill-test hook" 10 hook_active "$LOGS/1.log"
python3 -I - "$N" "$PREFIX" docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json \
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
pid=$(app_pid)
case "$pid" in
  ''|*[!0-9]*) echo "FAIL: expected exactly one application JVM ($MAIN_CLASS), found: ${pid:-none}"; exit 1 ;;
esac
kill -9 "$pid"
at_kill=$(received); offset=$(committed)
echo "killed with $at_kill of $N received; committed offset moved by $((offset - before))"
gap=$((at_kill - (offset - before)))      # orders received but not committed when the kill landed
if [ "$gap" = 0 ]; then echo "inconclusive: the kill landed after a commit, not in the gap; run again"; fi
wait_for "the killed application and its Maven run to end" 30 app_gone

start_app dev "$LOGS/2.log" ""
wait_for "the application to start again" 180 started "$LOGS/2.log"
wait_for "all pick lists delivered" 300 all_delivered
check "orders recorded" "$(sql "select count(*) from customer_order where $MATCH")" "$N"
check "orders recorded twice" "$(sql "select count(*) from (select order_number from customer_order where $MATCH group by 1 having count(*) > 1) d")" 0
check "history entries" "$(sql "select count(*) from order_state_change s join order_receipt r on r.id = s.order_receipt_id where r.$MATCH")" "$((3 * N))"   # received, recorded, sent to warehouse
check "pick lists delivered" "$(sql "select count(*) from pick_list p join customer_order o on o.id = p.customer_order_id where o.$MATCH and p.delivery_state = 'Delivered'")" "$N"
check "Duplicates skipped after the restart" "$(grep -c "Duplicate Peakmart order skipped orderNumber=$PREFIX" "$LOGS/2.log" || true)" "$gap"
echo "logs in $LOGS; result in $LOGS/result.txt"
[ "$failed" = 0 ] && [ "$gap" = 0 ] && exit 2
exit $failed
