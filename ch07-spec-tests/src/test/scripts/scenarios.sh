#!/bin/bash
# ============================================
# Acceptance scenarios 1-6 (pipeline 001 design §4) against the Spring Boot application
# (design 002 §4 "Acceptance criteria and scenario approach").
#
# Precondition: the Product Catalog stub (prototype/stubs/product-catalog/run.sh, 127.0.0.1:8091) runs, the
# application runs with --spring.profiles.active=dev (started by whoever runs this script; this script starts nothing),
# and the Order Database does not hold PM-50117, PM-50118 or PM-50119 yet (checked; exit 2 otherwise).
# Reading the new message through the API marks it read in Mailpit; nothing is deleted.
#
# It only reads and publishes through the containers `camel infra` already runs (kafka-console-producer.sh in
# camel-kafka, psql in camel-postgres), and reads Mailpit's API on localhost:8025. It never starts or stops a
# container and never deletes a Mailpit message (Mailpit is shared): scenario 3 counts messages before and after.
# Scenarios 7-12 are run by hand. Prints PASS/FAIL per scenario and exits 1 when any scenario fails.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$PROJECT_DIR"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-camel-kafka}"
PG_CONTAINER="${PG_CONTAINER:-camel-postgres}"
TOPIC="${PEAKMART_TOPIC:-peakmart.orders.v2}"
MAILPIT_API="${MAILPIT_API:-http://localhost:8025/api/v1}"
CATALOG_URL="${CATALOG_URL:-http://127.0.0.1:8091}"
EXAMPLES="docs/messages/examples/peakmart-order-placed"
NOTIFICATION_EXAMPLE="docs/messages/examples/order-desk-notification/pm-50118.json"
OUTBOX="target/outbox"
WAIT_SECONDS="${WAIT_SECONDS:-12}"     # > order-check / notification poll interval (5 s) plus catalog time
MAIL_WAIT_SECONDS="${MAIL_WAIT_SECONDS:-60}"
# The dev profile's orderdesk.mailbox and orderdesk.sender (src/main/resources/application-dev.properties)
EXPECTED_TO="${EXPECTED_TO:-order-desk@trailhead.example}"
EXPECTED_FROM="${EXPECTED_FROM:-order-integration@trailhead.example}"
SUBJECT="Peakmart order PM-50118 rejected"

die() { echo "ERROR: $*" >&2; exit 2; }
failures=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }

# JSON helpers (isolated Python, reads only the given file or stdin)
json_field() { python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."): d=d.get(k) if isinstance(d,dict) else None
print("" if d is None else d)' "$1" "$2"; }

command -v docker >/dev/null 2>&1 || die "docker CLI not found"
command -v curl >/dev/null 2>&1 || die "curl not found"
command -v python3 >/dev/null 2>&1 || die "python3 not found"
docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 \
  || die "Kafka in container '$KAFKA_CONTAINER' (localhost:9092) cannot be reached"
docker exec "$PG_CONTAINER" psql -U test -d postgres -tAc "SELECT 1" >/dev/null 2>&1 \
  || die "PostgreSQL in container '$PG_CONTAINER' (localhost:15432) cannot be reached"
curl -fsS -o /dev/null "$MAILPIT_API/info" || die "Mailpit API $MAILPIT_API cannot be reached"
curl -sS -o /dev/null "$CATALOG_URL/products/TENT-2P" || die "Product Catalog stub $CATALOG_URL cannot be reached"

publish_file() {
  local file="$1"
  [[ -f "$file" ]] || die "example not found: $file"
  echo ">> publish $(basename "$file")"
  tr -d '\n\r' < "$file" | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null
}

publish_text() {
  echo ">> publish literal text record"
  printf '%s\n' "$1" | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null
}

# One value, unaligned, no header
q() { docker exec "$PG_CONTAINER" psql -U test -d postgres -tAX -v ON_ERROR_STOP=1 -c "$1"; }

pause() { echo "   ... waiting ${WAIT_SECONDS}s"; sleep "$WAIT_SECONDS"; }

# Number of Mailpit messages whose subject is exactly $SUBJECT
mail_count() {
  curl -fsS -G "$MAILPIT_API/search" --data-urlencode "query=subject:\"$SUBJECT\"" --data-urlencode "limit=1000" \
    | python3 -I -c 'import json,sys; s=sys.argv[1]
print(sum(1 for m in json.load(sys.stdin).get("messages") or [] if m.get("Subject")==s))' "$SUBJECT"
}

# ID of the newest Mailpit message whose subject is exactly $SUBJECT
newest_mail_id() {
  curl -fsS -G "$MAILPIT_API/search" --data-urlencode "query=subject:\"$SUBJECT\"" --data-urlencode "limit=1000" \
    | python3 -I -c 'import json,sys; s=sys.argv[1]
ms=[m for m in json.load(sys.stdin).get("messages") or [] if m.get("Subject")==s]
ms.sort(key=lambda m: m.get("Created",""))
print(ms[-1]["ID"] if ms else "")' "$SUBJECT"
}

# Scenario 3 expects exactly one new email; a second run would publish PM-50118 as a Duplicate (no new email)
[[ "$(q "SELECT count(*) FROM order_receipt WHERE order_number IN ('PM-50117', 'PM-50118', 'PM-50119')")" == 0 ]] \
  || die "the Order Database already holds PM-50117/PM-50118/PM-50119; scenarios 1-6 need an Order Database without them"

mails_before=$(mail_count)
echo "Mailpit messages with subject '$SUBJECT' before: $mails_before"

# --- Publish (in this order, waiting between dependent steps) ---
publish_file "$EXAMPLES/peakmart-order-placed-sample.json"               # 1
pause
publish_file "$EXAMPLES/pm-50117-redelivered.json"                       # 2 (depends on 1)
publish_file "$EXAMPLES/pm-50118-inactive-sku.json"                      # 3
publish_file "$EXAMPLES/invalid/other-event-type.json"                   # 4
publish_file "$EXAMPLES/invalid/pm-50119-buyer-email-not-an-email.json"  # 5
publish_file "$EXAMPLES/invalid/price-as-number.json"                    # 6a
publish_file "$EXAMPLES/invalid/event-id-missing.json"                   # 6b
publish_text "this record is not JSON"                                   # 6c
pause
pause

echo
# --- Scenario 1: PM-50117 recorded ---
s1_orders=$(q "SELECT count(*) FROM customer_order WHERE order_number = 'PM-50117'")
s1_street=$(q "SELECT street_line FROM customer_order WHERE order_number = 'PM-50117'")
s1_lines=$(q "SELECT string_agg(l.sku || ' ' || l.quantity || ' ' || l.unit_price || ' ' || l.product_name || ' ' || l.unit_weight_grams, '; ' ORDER BY l.line_number)
               FROM order_line l JOIN customer_order c ON c.id = l.customer_order_id WHERE c.order_number = 'PM-50117'")
s1_history=$(q "SELECT string_agg(s.order_state, ', ' ORDER BY s.changed_at, s.id) FROM order_state_change s
                 JOIN order_receipt r ON r.id = s.order_receipt_id WHERE r.order_number = 'PM-50117'")
if [[ "$s1_orders" == 1 && "$s1_street" == "2240 Pearl Street, Apt 4" && "$s1_history" == "Received, Recorded" \
      && "$s1_lines" == *"229.00 Ridgeline 2-Person Tent 2150"* && "$s1_lines" == *"12.50 Aluminium Tent Stakes 8-Pack 120"* ]]; then
  pass "Scenario 1: PM-50117 recorded (2 lines, history Received, Recorded)"
else
  fail "Scenario 1" "orders=$s1_orders history='$s1_history' lines='$s1_lines'"
fi

# --- Scenario 2: PM-50117 redelivered with a new event id ---
first_event_id=$(json_field "$EXAMPLES/peakmart-order-placed-sample.json" event_id)
s2_receipts=$(q "SELECT count(*) FROM order_receipt WHERE order_number = 'PM-50117'")
s2_event=$(q "SELECT event_id FROM order_receipt WHERE order_number = 'PM-50117'")
if [[ "$s2_receipts" == 1 && "$s2_event" == "$first_event_id" ]]; then
  pass "Scenario 2: still exactly one receipt for PM-50117, with the first event id"
else
  fail "Scenario 2" "receipts=$s2_receipts"
fi

# --- Scenario 3: PM-50118 rejected; Order Desk email in Mailpit ---
expected_reasons=$(python3 -I -c 'import json,sys
for r in json.load(open(sys.argv[1]))["rejectionReasons"]: print(r["text"])' "$NOTIFICATION_EXAMPLE")
s3_state=$(q "SELECT order_state FROM order_receipt WHERE order_number = 'PM-50118'")
s3_reasons=$(q "SELECT string_agg(rr.reason_text, E'\n' ORDER BY rr.position) FROM rejection_reason rr
                 JOIN order_receipt r ON r.id = rr.order_receipt_id WHERE r.order_number = 'PM-50118'")
s3_sent=$(q "SELECT count(*) FROM notification n JOIN order_receipt r ON r.id = n.order_receipt_id
              WHERE r.order_number = 'PM-50118' AND n.sent_at IS NOT NULL")
s3_ok=1; s3_why=""
[[ "$s3_state" == "Rejected" ]] || { s3_ok=0; s3_why+=" state=$s3_state"; }
[[ "$s3_reasons" == "$expected_reasons" ]] || { s3_ok=0; s3_why+=" reasons differ from $NOTIFICATION_EXAMPLE"; }
[[ "$s3_sent" == 1 ]] || { s3_ok=0; s3_why+=" notification sent rows=$s3_sent"; }

mails_after=$mails_before; waited=0
until [[ "$mails_after" -ge $((mails_before + 1)) || "$waited" -ge "$MAIL_WAIT_SECONDS" ]]; do
  sleep 2; waited=$((waited + 2)); mails_after=$(mail_count || echo "$mails_after")
done
sleep 2; mails_after=$(mail_count || echo "$mails_after")   # catch a second (unwanted) message arriving right after the first
if [[ "$mails_after" -ne $((mails_before + 1)) ]]; then
  s3_ok=0; s3_why+=" Mailpit count $mails_before -> $mails_after, expected +1"
else
  mail_id=$(newest_mail_id)
  mail_file="$(mktemp)"; headers_file="$(mktemp)"
  curl -fsS "$MAILPIT_API/message/$mail_id" > "$mail_file"
  curl -fsS "$MAILPIT_API/message/$mail_id/headers" > "$headers_file"
  # Checks the message without printing its content; prints only the names of failed checks
  mail_problems=$(python3 -I - "$mail_file" "$headers_file" "$EXAMPLES/pm-50118-inactive-sku.json" \
      "$SUBJECT" "$EXPECTED_TO" "$EXPECTED_FROM" <<'PY'
import json, sys
msg = json.load(open(sys.argv[1])); headers = json.load(open(sys.argv[2])); event = json.load(open(sys.argv[3]))
subject, to, sender = sys.argv[4], sys.argv[5], sys.argv[6]
problems = []
if msg.get("Subject") != subject: problems.append("subject")
if [a.get("Address") for a in msg.get("To") or []] != [to]: problems.append("to")
if (msg.get("From") or {}).get("Address") != sender: problems.append("from")
if not any(v.lower().startswith("text/plain") for v in headers.get("Content-Type", [])): problems.append("content-type")
text = msg.get("Text") or ""
if "Order number: PM-50118" not in text: problems.append("order-number-line")
personal = [event.get("buyer", {}).get("email"), *(event.get("ship_to", {}).get(k) for k in ("full_name", "address1", "address2", "city", "zip"))]
if any(p and p.lower() in text.lower() for p in personal): problems.append("personal-data")
if "@" in text: problems.append("email-address-in-body")
print(" ".join(problems))
PY
)
  while IFS= read -r reason; do
    [[ -z "$reason" ]] && continue
    python3 -I -c 'import json,sys; t=json.load(open(sys.argv[1])).get("Text") or ""; sys.exit(0 if ("- "+sys.argv[2]) in t else 1)' \
      "$mail_file" "$reason" || mail_problems+=" reason-text"
  done <<< "$expected_reasons"
  rm -f "$mail_file" "$headers_file"
  [[ -z "${mail_problems// /}" ]] || { s3_ok=0; s3_why+=" email checks failed:$mail_problems"; }
fi
if [[ -d "$OUTBOX" ]] && [[ -n "$(find "$OUTBOX" -type f 2>/dev/null | head -1)" ]]; then
  s3_ok=0; s3_why+=" a file exists under $OUTBOX"
fi
if [[ "$s3_ok" == 1 ]]; then
  pass "Scenario 3: PM-50118 rejected, one Order Desk email in Mailpit, no personal data, no outbox file"
else
  fail "Scenario 3" "$s3_why"
fi

# --- Scenario 4: other event type skipped ---
# order.cancelled reuses the sample's event id and PM-50117 (as does price-as-number), so the check is the prototype's:
# no receipt beyond PM-50117/PM-50118, PM-50117 still once, and no set-aside row holding an order.cancelled event.
s4_other=$(q "SELECT count(*) FROM order_receipt WHERE order_number NOT IN ('PM-50117', 'PM-50118')")
s4_pm50117=$(q "SELECT count(*) FROM order_receipt WHERE order_number = 'PM-50117'")
s4_set_aside=$(q "SELECT count(*) FROM set_aside_order_event WHERE event_content LIKE '%\"order.cancelled\"%'")
if [[ "$s4_other" == 0 && "$s4_pm50117" == 1 && "$s4_set_aside" == 0 ]]; then
  pass "Scenario 4: other event type skipped (no receipt, not set aside)"
else
  fail "Scenario 4" "other receipts=$s4_other PM-50117 receipts=$s4_pm50117 order.cancelled set aside=$s4_set_aside"
fi

# --- Scenario 5: PM-50119 set aside for its buyer email ---
s5_set_aside=$(q "SELECT count(*) FROM set_aside_order_event WHERE order_number = 'PM-50119' AND cannot_read_reason = '\$.buyer.email: format'")
s5_receipts=$(q "SELECT count(*) FROM order_receipt WHERE order_number = 'PM-50119'")
if [[ "$s5_set_aside" -ge 1 && "$s5_receipts" == 0 ]]; then
  pass "Scenario 5: PM-50119 set aside with '\$.buyer.email: format', no receipt"
else
  fail "Scenario 5" "set_aside=$s5_set_aside receipts=$s5_receipts"
fi

# --- Scenario 6: events that cannot be read are set aside with '<path>: <keyword>' or 'not JSON' ---
s6a_event=$(json_field "$EXAMPLES/invalid/price-as-number.json" event_id)
s6a=$(q "SELECT count(*) FROM set_aside_order_event WHERE event_id = '$s6a_event' AND cannot_read_reason ~ '^\\\$[^ ]*: type\$'")
s6b=$(q "SELECT count(*) FROM set_aside_order_event WHERE event_id LIKE 'kafka-%' AND cannot_read_reason ~ ': required\$'")
s6c=$(q "SELECT count(*) FROM set_aside_order_event WHERE event_id LIKE 'kafka-%' AND cannot_read_reason = 'not JSON'")
s6_msg=$(q "SELECT count(*) FROM set_aside_order_event WHERE cannot_read_reason !~ '^(\\\$[^ ]*: [A-Za-z]+|not JSON|too large)\$'")
if [[ "$s6a" -ge 1 && "$s6b" -ge 1 && "$s6c" -ge 1 && "$s6_msg" == 0 ]]; then
  pass "Scenario 6: price-as-number (type), event-id-missing (required), non-JSON ('not JSON') set aside; no validator message"
else
  fail "Scenario 6" "price-as-number=$s6a event-id-missing=$s6b not-json=$s6c other-reasons=$s6_msg"
fi

echo
if [[ "$failures" == 0 ]]; then
  echo "ALL SCENARIOS 1-6 PASSED"
else
  echo "$failures SCENARIO(S) FAILED"
  exit 1
fi
