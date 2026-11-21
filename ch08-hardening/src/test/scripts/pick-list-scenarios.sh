#!/bin/bash
# ============================================
# Pick-list scenarios P-12 (TC-005), P-13, P-14 and P-16 (design 003 §4 "Acceptance criteria and scenario approach").
#
# Usage:
#   pick-list-scenarios.sh [all]          P-13 (file already in inbound, then in processed), P-14 (leftover .tmp), P-16
#   pick-list-scenarios.sh before-restart [log] P-12 part 1: the application runs with warehouse.sftp.port pointed at a closed
#                                         port; records an order and checks that its pick list stays Pending while the
#                                         attempts grow (and, with APP_LOG=<log file>, that WARN lines appear)
#   pick-list-scenarios.sh after-restart  P-12 part 2: the application was restarted with port 2222; exactly one file
#                                         arrives, the order is Sent to Warehouse, no .tmp is left
#
# Precondition: the application runs with the dev profile on localhost:8082 (this script never starts or stops the
# application or any service; the verifier restarts the application around P-12), the stub and the `camel infra`
# services run. Nothing does what the Warehouse does, so this script moves pick lists to processed itself (sftp rename
# as the dev user). Database writes are limited to setting a pick_list row back to Pending (P-13, the crash after the
# rename). The dev API key is read from config/dev/secrets and never printed; no personal data is printed.
# Prints PASS/FAIL per scenario; exits 1 when any scenario fails, 2 when a precondition fails.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$PROJECT_DIR"

MODE="${1:-all}"
API_URL="${API_URL:-http://localhost:8082/orders}"
PG_CONTAINER="${PG_CONTAINER:-camel-postgres}"
KEY_FILE="config/dev/secrets/partner-api-key-summit-outfitters"
DEV_PROPERTIES="config/application-dev.properties"
SAMPLE="docs/messages/examples/partner-order/partner-order-sample.json"
POLL_WAIT_SECONDS="${POLL_WAIT_SECONDS:-75}"       # > two pick-list polls (30 s each)
STATE_FILE="target/pick-list-scenarios.p12"
APP_LOG="${APP_LOG:-${2:-}}"   # before-restart: the application's log file, as APP_LOG or the second argument

die() { echo "ERROR: $*" >&2; exit 2; }
failures=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }

for tool in curl jq docker sftp shasum; do command -v "$tool" >/dev/null 2>&1 || die "$tool not found"; done
prop() { grep -E "^$1=" "$DEV_PROPERTIES" | tail -1 | cut -d= -f2-; }
SFTP_HOST="$(prop warehouse.sftp.host)"
SFTP_PORT="${SFTP_PORT:-$(prop warehouse.sftp.port)}"
SFTP_USER="$(prop warehouse.sftp.username)"
SFTP_PASSWORD="$(prop warehouse.sftp.password)"
KNOWN_HOSTS="$(prop warehouse.sftp.known-hosts)"
INBOUND="$(prop warehouse.sftp.inbound-directory)"
PROCESSED="$(prop warehouse.sftp.processed-directory)"
[[ -n "$INBOUND" ]] || INBOUND="$(grep -E '^warehouse.sftp.inbound-directory=' src/main/resources/application.properties | cut -d= -f2-)"
[[ -n "$PROCESSED" ]] || PROCESSED="$(grep -E '^warehouse.sftp.processed-directory=' src/main/resources/application.properties | cut -d= -f2-)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/pick-list-scenarios.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# --- helpers -----------------------------------------------------------------------------------------------------
q() { docker exec "$PG_CONTAINER" psql -U test -d postgres -tAX -v ON_ERROR_STOP=1 -c "$1"; }

KEY_HEADER="$WORK/key.header"
[[ -s "$KEY_FILE" ]] || die "dev API key file $KEY_FILE missing"
printf 'X-API-Key: %s\n' "$(tr -d '\r\n' < "$KEY_FILE")" > "$KEY_HEADER"

# Records a new partner order $1 (TG-number) from the sample; prints the HTTP status
record_order() {
  jq --arg n "$1" '.orderId = $n' "$SAMPLE" > "$WORK/$1.json"
  curl -sS -o "$WORK/$1.answer" -w '%{http_code}' -X POST "$API_URL" -H 'Content-Type: application/json' \
    -H "@$KEY_HEADER" --data-binary "@$WORK/$1.json" || true
}
fresh_number() {
  local n
  for ((n = $1; n <= $2; n++)); do
    [[ "$(q "SELECT count(*) FROM order_receipt WHERE order_number = 'TG-$n'")" == 0 ]] && { echo "TG-$n"; return; }
  done
  die "no unused order number left in TG-$1..TG-$2"
}
pick_state() { q "SELECT delivery_state || ':' || attempts FROM pick_list WHERE file_name = 'PL-$1.csv'"; }
order_state() { q "SELECT r.order_state FROM order_receipt r JOIN customer_order c ON c.order_receipt_id = r.id WHERE c.order_number = '$1'"; }
wait_delivered() { # $1 = order number; waits up to POLL_WAIT_SECONDS
  local i
  for ((i = 0; i < POLL_WAIT_SECONDS; i += 5)); do
    [[ "$(pick_state "$1")" == Delivered:* ]] && return 0
    sleep 5
  done
  return 1
}

# SFTP as the dev user, host key verified against the dev warehouse_known_hosts file; password through SSH_ASKPASS
ASKPASS="$WORK/askpass.sh"
printf '#!/bin/sh\nprintf "%%s\\n" "$SFTP_SCENARIO_PASSWORD"\n' > "$ASKPASS"
chmod 700 "$ASKPASS"
sftp_batch() { # $1 = batch file
  SFTP_SCENARIO_PASSWORD="$SFTP_PASSWORD" SSH_ASKPASS="$ASKPASS" SSH_ASKPASS_REQUIRE=force DISPLAY="${DISPLAY:-:0}" \
    sftp -q -o BatchMode=no -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$KNOWN_HOSTS" \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password -o NumberOfPasswordPrompts=1 \
    -P "$SFTP_PORT" -b "$1" "$SFTP_USER@$SFTP_HOST" < /dev/null
}
sftp_cmd() { printf '%s\n' "$@" > "$WORK/cmd.batch"; sftp_batch "$WORK/cmd.batch"; }
# Names of the files in a folder (no paths)
sftp_list() { sftp_cmd "ls -1 $1" 2>/dev/null | tr -d '\r' | sed 's#.*/##' | grep -v '^$' || true; }
# Checksum of a remote file, or empty when it does not exist
sftp_sum() {
  rm -f "$WORK/fetched"
  sftp_cmd "get $1 $WORK/fetched" >/dev/null 2>&1 || true
  [[ -f "$WORK/fetched" ]] && shasum -a 256 "$WORK/fetched" | cut -d' ' -f1 || true
}
# Moves a pick list from inbound to processed, as the Warehouse does after pickup
move_to_processed() { sftp_cmd "rename $INBOUND/$1 $PROCESSED/$1" >/dev/null; }
# How many copies of a file exist in inbound and processed together
copies() { { sftp_list "$INBOUND"; sftp_list "$PROCESSED"; } | grep -cx "$1" || true; }

# --- preconditions -------------------------------------------------------------------------------------------------
q "SELECT 1" >/dev/null || die "PostgreSQL in container '$PG_CONTAINER' cannot be reached"
curl -sS -o /dev/null -X OPTIONS "$API_URL" 2>/dev/null || die "the application does not answer on $API_URL"
sftp_cmd "ls $INBOUND" >/dev/null 2>&1 || die "the SFTP server $SFTP_HOST:$SFTP_PORT cannot be reached as $SFTP_USER"

# --- P-13: crash after the rename, before the state update ---------------------------------------------------------------
p13() {
  local n file folder sum_before sum_after attempts_before
  n="$(fresh_number 1300 1399)"; file="PL-$n.csv"
  echo "== P-13 crash after the rename ($n)"
  [[ "$(record_order "$n")" == 200 ]] || { fail P-13 "order $n not accepted"; return; }
  wait_delivered "$n" || { fail P-13 "first delivery of $file did not happen"; return; }
  for folder in "$INBOUND" "$PROCESSED"; do
    [[ "$folder" == "$PROCESSED" ]] && move_to_processed "$file"
    sum_before="$(sftp_sum "$folder/$file")"
    [[ -n "$sum_before" ]] || { fail "P-13 ($folder)" "$file not found in $folder"; continue; }
    # The crash: the file is on the server, the row is still Pending
    q "UPDATE pick_list SET delivery_state = 'Pending', delivered_at = NULL WHERE file_name = '$file'" >/dev/null
    attempts_before="$(pick_state "$n" | cut -d: -f2)"
    if wait_delivered "$n"; then
      sum_after="$(sftp_sum "$folder/$file")"
      if [[ "$sum_after" == "$sum_before" && "$(copies "$file")" == 1 && "$(order_state "$n")" == "Sent to Warehouse" ]]; then
        pass "P-13 ($folder: counted as delivered, file unchanged, one copy, Sent to Warehouse, attempts $attempts_before -> $(pick_state "$n" | cut -d: -f2))"
      else
        fail "P-13 ($folder)" "checksum changed or copies $(copies "$file") or state $(order_state "$n")"
      fi
    else
      fail "P-13 ($folder)" "row still $(pick_state "$n") after ${POLL_WAIT_SECONDS}s"
    fi
  done
}

# --- P-14: a leftover .tmp from a broken attempt ------------------------------------------------------------------------
p14() {
  local n file lines
  n="$(fresh_number 1500 1599)"; file="PL-$n.csv"
  echo "== P-14 leftover $file.tmp"
  printf 'order_no,line_no,sku\r\n%s,1,TEN' "$n" > "$WORK/partial.tmp"
  sftp_cmd "put $WORK/partial.tmp $INBOUND/$file.tmp" >/dev/null
  [[ "$(record_order "$n")" == 200 ]] || { fail P-14 "order $n not accepted"; return; }
  if ! wait_delivered "$n"; then fail P-14 "row still $(pick_state "$n") after ${POLL_WAIT_SECONDS}s"; return; fi
  rm -f "$WORK/fetched"; sftp_cmd "get $INBOUND/$file $WORK/fetched" >/dev/null 2>&1 || true
  lines="$(grep -c $'\r$' "$WORK/fetched" 2>/dev/null || echo 0)"
  if [[ "$lines" == 3 ]] && head -1 "$WORK/fetched" | grep -q '^order_no,line_no,sku,description,' \
      && ! sftp_list "$INBOUND" | grep -qx "$file.tmp"; then
    pass "P-14 (.tmp replaced: final file complete with header and 2 rows, no .tmp left)"
  else
    fail P-14 "final file has $lines CRLF lines or the .tmp is still there"
  fi
}

# --- P-16: V2 backfill -------------------------------------------------------------------------------------------------
p16() {
  echo "== P-16 V2 backfill"
  local v2 total with_one delivered missing names
  # The time V2 ran: the backfill rows carry its transaction time, the earliest created_at of any pick list
  # (flyway_schema_history.installed_on is in the client's local time and cannot be compared directly)
  v2="$(q "SELECT min(created_at) FROM pick_list")"
  [[ -n "$v2" ]] || { fail P-16 "no pick lists at all"; return; }
  total="$(q "SELECT count(*) FROM customer_order c WHERE c.recorded_at < '$v2'")"
  with_one="$(q "SELECT count(*) FROM customer_order c WHERE c.recorded_at < '$v2' AND (SELECT count(*) FROM pick_list p WHERE p.customer_order_id = c.id) = 1")"
  delivered="$(q "SELECT count(*) FROM customer_order c JOIN pick_list p ON p.customer_order_id = c.id JOIN order_receipt r ON r.id = c.order_receipt_id WHERE c.recorded_at < '$v2' AND p.delivery_state = 'Delivered' AND r.order_state = 'Sent to Warehouse'")"
  q "SELECT p.file_name FROM customer_order c JOIN pick_list p ON p.customer_order_id = c.id WHERE c.recorded_at < '$v2' ORDER BY 1" | sort > "$WORK/expected.names"
  { sftp_list "$INBOUND"; sftp_list "$PROCESSED"; } | sort > "$WORK/server.names"
  missing="$(comm -23 "$WORK/expected.names" <(sort -u "$WORK/server.names") | wc -l | tr -d ' ')"
  # Files of these orders that appear more than once across inbound and processed
  names="$(uniq -d "$WORK/server.names" | comm -12 "$WORK/expected.names" - | wc -l | tr -d ' ')"
  if [[ "$total" -gt 0 && "$total" == "$with_one" && "$total" == "$delivered" && "$missing" == 0 && "$names" == 0 ]]; then
    pass "P-16 ($total orders recorded before V2: one pick list each, all Delivered and Sent to Warehouse, each file once on the server)"
  else
    fail P-16 "orders $total, with one pick list $with_one, delivered $delivered, files missing $missing, duplicated $names"
  fi
}

# --- P-12 (TC-005): SFTP unreachable, then a restart ---------------------------------------------------------------------
before_restart() {
  local n s1 s2
  n="$(fresh_number 1400 1499)"
  echo "== P-12 before the restart ($n, SFTP unreachable)"
  [[ "$(record_order "$n")" == 200 ]] || { fail "P-12 before" "order $n not accepted"; return; }
  echo "$n" > "$STATE_FILE"
  sleep 35; s1="$(pick_state "$n")"
  sleep 35; s2="$(pick_state "$n")"
  local warn_ok=1
  if [[ -n "$APP_LOG" ]]; then
    grep -q "Pick list not delivered, tried again on the next poll orderNumber=$n" "$APP_LOG" || warn_ok=0
  fi
  if [[ "$s1" == Pending:* && "$s2" == Pending:* && "${s2#*:}" -gt "${s1#*:}" && "$warn_ok" == 1 ]]; then
    pass "P-12 before the restart (Pending, attempts ${s1#*:} -> ${s2#*:}${APP_LOG:+, WARN lines in the log})"
  else
    fail "P-12 before" "states $s1 then $s2, WARN lines found: $warn_ok"
  fi
}
after_restart() {
  [[ -s "$STATE_FILE" ]] || die "run before-restart first ($STATE_FILE missing)"
  local n file
  n="$(cat "$STATE_FILE")"; file="PL-$n.csv"
  echo "== P-12 after the restart ($n)"
  if wait_delivered "$n" && [[ "$(order_state "$n")" == "Sent to Warehouse" && "$(copies "$file")" == 1 ]] \
      && ! sftp_list "$INBOUND" | grep -qx "$file.tmp"; then
    pass "P-12 after the restart (exactly one $file, Sent to Warehouse, no .tmp)"
  else
    fail "P-12 after" "state $(pick_state "$n") / $(order_state "$n"), copies $(copies "$file")"
  fi
}

case "$MODE" in
  all) p13; p14; p16 ;;
  before-restart) before_restart ;;
  after-restart) after_restart ;;
  p13) p13 ;;
  p14) p14 ;;
  p16) p16 ;;
  *) die "unknown mode $MODE (all, before-restart, after-restart)" ;;
esac

echo
if [[ "$failures" -gt 0 ]]; then echo "$failures scenario(s) FAILED"; exit 1; fi
echo "Pick-list scenarios ($MODE) PASSED"
