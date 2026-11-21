#!/bin/bash
# ============================================
# Partner scenarios P-1 to P-8 and P-11 (design 003 §4 "Acceptance criteria and scenario approach"; TC-001 steps 1-5).
#
# Precondition: the application runs with --spring.profiles.active=dev on localhost:8082 (started by whoever runs this
# script; this script starts and stops nothing), the Product Catalog stub runs on 127.0.0.1:8091 in normal mode, and
# the `camel infra` services run (PostgreSQL in camel-postgres, Mailpit on localhost:8025, SFTP on localhost:2222).
#
# Reads only: psql in camel-postgres (SELECT only), Mailpit's API (searched, never cleared), the SFTP server (get and
# ls only). The dev API key is read from config/dev/secrets and never printed. Each scenario uses its own order number
# from the dev range 1000-1999; a scenario that needs a fresh number takes the lowest unused one of its block, so reruns
# do not collide (the 002 rerun guard). TG-1060 (TC-001) is the fixed P-1 number: on a rerun it is a resend, and P-1
# checks the existing order and file. P-9, P-10, P-17, P-18 are run by hand.
# Prints PASS/FAIL per scenario and exits 1 when any scenario fails, 2 when a precondition fails.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$PROJECT_DIR"

API_URL="${API_URL:-http://localhost:8082/orders}"
PG_CONTAINER="${PG_CONTAINER:-camel-postgres}"
MAILPIT_API="${MAILPIT_API:-http://localhost:8025/api/v1}"
KEY_FILE="config/dev/secrets/partner-api-key-summit-outfitters"
DEV_PROPERTIES="config/application-dev.properties"
SAMPLE="docs/messages/examples/partner-order/partner-order-sample.json"
INVALID="docs/messages/examples/partner-order/invalid"
PICK_LIST_EXAMPLE="docs/messages/examples/pick-list/pl-tg-1060.json"
ORDER_CONTACT="orders@summit-outfitters.example.com"
PICK_LIST_WAIT_SECONDS="${PICK_LIST_WAIT_SECONDS:-90}"
MAIL_WAIT_SECONDS="${MAIL_WAIT_SECONDS:-60}"

die() { echo "ERROR: $*" >&2; exit 2; }
failures=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }

for tool in curl jq docker sftp; do command -v "$tool" >/dev/null 2>&1 || die "$tool not found"; done
[[ -s "$KEY_FILE" ]] || die "dev API key file $KEY_FILE missing"
API_KEY="$(tr -d '\r\n' < "$KEY_FILE")"
prop() { grep -E "^$1=" "$DEV_PROPERTIES" | tail -1 | cut -d= -f2-; }
MAIL_SENDER="$(prop partner.mail-sender)"
SFTP_HOST="$(prop warehouse.sftp.host)"
SFTP_PORT="$(prop warehouse.sftp.port)"
SFTP_USER="$(prop warehouse.sftp.username)"
SFTP_PASSWORD="$(prop warehouse.sftp.password)"
KNOWN_HOSTS="$(prop warehouse.sftp.known-hosts)"
INBOUND="$(prop warehouse.sftp.inbound-directory)"
PROCESSED="$(prop warehouse.sftp.processed-directory)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/partner-scenarios.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# --- helpers -----------------------------------------------------------------------------------------------------

# One value, unaligned, no header (read-only queries)
q() { docker exec "$PG_CONTAINER" psql -U test -d postgres -tAX -v ON_ERROR_STOP=1 -c "$1"; }

# POST a body file. $1 = body file, $2 = name for the response files, $3.. = extra curl arguments.
# Writes $WORK/$2.status, $WORK/$2.type, $WORK/$2.body
post() {
  local body="$1" name="$2"; shift 2
  curl -sS -o "$WORK/$name.body" -D "$WORK/$name.headers" -w '%{http_code}' -X POST "$API_URL" \
    -H 'Content-Type: application/json' "$@" --data-binary "@$body" > "$WORK/$name.status" || true
  grep -i '^content-type:' "$WORK/$name.headers" | tail -1 | cut -d: -f2- | tr -d ' \r' > "$WORK/$name.type" || true
}
# POST with the dev API key, passed through a header file so it never appears in a process list or the output
KEY_HEADER="$WORK/key.header"
printf 'X-API-Key: %s\n' "$API_KEY" > "$KEY_HEADER"
post_with_key() { local body="$1" name="$2"; shift 2; post "$body" "$name" -H "@$KEY_HEADER" "$@"; }
status() { cat "$WORK/$1.status"; }
ctype() { cat "$WORK/$1.type"; }
field() { jq -r "$2 // empty" "$WORK/$1.body" 2>/dev/null || echo "(not JSON)"; }
# The Order Checks of the answer's reasons, comma-separated; never stops the run on a body that is not JSON
checks_of() { jq -r '[.rejectionReasons[]?.orderCheck] | join(",")' "$WORK/$1.body" 2>/dev/null || echo "(not JSON)"; }

# A Partner Order from the sample with order number $1, written to $WORK/$2.json; $3 = optional jq filter
order_file() { jq --arg n "$1" ".orderId = \$n ${3:+| $3}" "$SAMPLE" > "$WORK/$2.json"; echo "$WORK/$2.json"; }

# The lowest TG number in [$1, $2] that has no receipt yet
fresh_number() {
  local n
  for ((n = $1; n <= $2; n++)); do
    [[ "$(q "SELECT count(*) FROM order_receipt WHERE order_number = 'TG-$n'")" == 0 ]] && { echo "TG-$n"; return; }
  done
  die "no unused order number left in TG-$1..TG-$2"
}

# Mailpit: number of messages with exactly this subject (searched, never deleted)
mail_count() {
  curl -fsS -G "$MAILPIT_API/search" --data-urlencode "query=subject:\"$1\"" --data-urlencode "limit=1000" \
    | jq --arg s "$1" '[.messages[]? | select(.Subject == $s)] | length'
}
newest_mail_id() {
  curl -fsS -G "$MAILPIT_API/search" --data-urlencode "query=subject:\"$1\"" --data-urlencode "limit=1000" \
    | jq -r --arg s "$1" '[.messages[]? | select(.Subject == $s)] | sort_by(.Created) | last | .ID // empty'
}

# SFTP as the dev user, host key verified against the dev known-hosts file; the password comes through SSH_ASKPASS.
ASKPASS="$WORK/askpass.sh"
printf '#!/bin/sh\nprintf "%%s\\n" "$SFTP_SCENARIO_PASSWORD"\n' > "$ASKPASS"
chmod 700 "$ASKPASS"
sftp_batch() { # $1 = batch file
  SFTP_SCENARIO_PASSWORD="$SFTP_PASSWORD" SSH_ASKPASS="$ASKPASS" SSH_ASKPASS_REQUIRE=force DISPLAY="${DISPLAY:-:0}" \
    sftp -q -o BatchMode=no -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$KNOWN_HOSTS" \
    -o PubkeyAuthentication=no -o PreferredAuthentications=password -o NumberOfPasswordPrompts=1 \
    -P "$SFTP_PORT" -b "$1" "$SFTP_USER@$SFTP_HOST" < /dev/null
}
# Downloads $1 (a file name) from inbound or processed into $WORK/$1; prints the folder or nothing
sftp_fetch() {
  local file="$1" folder
  for folder in "$INBOUND" "$PROCESSED"; do
    printf 'get %s/%s %s/%s\n' "$folder" "$file" "$WORK" "$file" > "$WORK/get.batch"
    if sftp_batch "$WORK/get.batch" >/dev/null 2>&1; then echo "$folder"; return 0; fi
  done
  return 0
}
# Lists the .tmp files in inbound whose name starts with $1
sftp_tmp_files() {
  printf 'ls -1 %s\n' "$INBOUND" > "$WORK/ls.batch"
  sftp_batch "$WORK/ls.batch" 2>/dev/null | tr -d '\r' | sed 's#.*/##' | grep -E "^$1.*\.tmp$" || true
}

# --- preconditions -------------------------------------------------------------------------------------------------

curl -sS -o /dev/null "$API_URL" -X OPTIONS 2>/dev/null || die "the application does not answer on $API_URL"
q "SELECT 1" >/dev/null || die "PostgreSQL in container '$PG_CONTAINER' cannot be reached"
curl -fsS -o /dev/null "$MAILPIT_API/info" || die "Mailpit API $MAILPIT_API cannot be reached"
printf 'ls %s\n' "$INBOUND" > "$WORK/ping.batch"
sftp_batch "$WORK/ping.batch" >/dev/null 2>&1 || die "the SFTP server $SFTP_HOST:$SFTP_PORT cannot be reached as $SFTP_USER"

# --- P-1: TC-001 steps 1-5 -------------------------------------------------------------------------------------------
echo "== P-1 accepted order TG-1060 (TC-001)"
P1_SUBJECT="Order TG-1060 rejected"
p1_mail_before="$(mail_count "$P1_SUBJECT")"
p1_existing="$(q "SELECT count(*) FROM order_receipt WHERE order_number = 'TG-1060' AND order_state IN ('Recorded','Sent to Warehouse')")"
[[ "$p1_existing" != 0 ]] && echo "   (rerun guard: TG-1060 is already recorded; this request is a resend and the existing order is checked)"
post_with_key "$SAMPLE" p1
if [[ "$(status p1)" == 200 && "$(ctype p1)" == application/json* && "$(field p1 .result)" == accepted \
      && "$(field p1 .orderNumber)" == TG-1060 ]]; then
  orders="$(q "SELECT count(*) FROM customer_order WHERE order_number = 'TG-1060'")"
  prices="$(q "SELECT string_agg(l.unit_price::text, ',' ORDER BY l.line_number) FROM order_line l JOIN customer_order c ON c.id = l.customer_order_id WHERE c.order_number = 'TG-1060'")"
  # Wait for the pick list (poll every 30 s)
  state=""
  for ((i = 0; i < PICK_LIST_WAIT_SECONDS; i += 5)); do
    state="$(q "SELECT r.order_state FROM order_receipt r JOIN customer_order c ON c.order_receipt_id = r.id WHERE c.order_number = 'TG-1060'")"
    [[ "$state" == "Sent to Warehouse" ]] && break
    sleep 5
  done
  history="$(q "SELECT string_agg(s.order_state, '>' ORDER BY s.changed_at, s.id) FROM order_state_change s JOIN customer_order c ON c.order_receipt_id = s.order_receipt_id WHERE c.order_number = 'TG-1060'")"
  pick_lists="$(q "SELECT count(*) FROM pick_list p JOIN customer_order c ON c.id = p.customer_order_id WHERE c.order_number = 'TG-1060'")"
  # Expected CSV: the header, then each example row's values in column order, CRLF line endings
  header="order_no,line_no,sku,description,qty,unit_weight_g,ship_name,ship_street,ship_city,ship_postcode,ship_country,total_weight_g"
  { printf '%s\r\n' "$header"
    jq -r --arg h "$header" '.[] as $r | ($h | split(",")) | map($r[.] | tostring) | join(",")' "$PICK_LIST_EXAMPLE" \
      | while IFS= read -r line; do printf '%s\r\n' "$line"; done; } > "$WORK/expected-PL-TG-1060.csv"
  folder="$(sftp_fetch PL-TG-1060.csv)"
  tmp_left="$(sftp_tmp_files PL-TG-1060)"
  p1_mail_after="$(mail_count "$P1_SUBJECT")"
  if [[ "$orders" != 1 ]]; then fail P-1 "customer_order count $orders, expected 1"
  elif [[ "$prices" != "249.00,39.95" ]]; then fail P-1 "unit prices $prices, expected 249.00,39.95"
  elif [[ "$state" != "Sent to Warehouse" ]]; then fail P-1 "order state '$state' after ${PICK_LIST_WAIT_SECONDS}s"
  elif [[ "$history" != "Received>Recorded>Sent to Warehouse" ]]; then fail P-1 "history $history"
  elif [[ "$pick_lists" != 1 ]]; then fail P-1 "pick_list count $pick_lists"
  elif [[ -z "$folder" ]]; then fail P-1 "PL-TG-1060.csv not found on the SFTP server"
  elif ! cmp -s "$WORK/expected-PL-TG-1060.csv" "$WORK/PL-TG-1060.csv"; then fail P-1 "PL-TG-1060.csv differs from the expected CSV"
  elif [[ -n "$tmp_left" ]]; then fail P-1 ".tmp file left in inbound"
  elif [[ "$p1_mail_after" != "$p1_mail_before" ]]; then fail P-1 "a rejection email was sent"
  else pass "P-1 (200 accepted, prices 249.00/39.95, Received>Recorded>Sent to Warehouse, PL-TG-1060.csv byte-equal in $folder, no .tmp, no email)"
  fi
else
  fail P-1 "status $(status p1), type $(ctype p1), result $(field p1 .result)"
fi

# --- P-2: resend of P-1 ------------------------------------------------------------------------------------------------
echo "== P-2 resend of TG-1060"
before="$(q "SELECT (SELECT count(*) FROM order_receipt WHERE order_number='TG-1060') || '/' || (SELECT count(*) FROM order_state_change s JOIN order_receipt r ON r.id=s.order_receipt_id WHERE r.order_number='TG-1060') || '/' || (SELECT count(*) FROM pick_list WHERE file_name='PL-TG-1060.csv')")"
# The same JSON value, reformatted: the content hash ignores whitespace and key order (C-6)
jq -S . "$SAMPLE" > "$WORK/p2.json"
post_with_key "$WORK/p2.json" p2
after="$(q "SELECT (SELECT count(*) FROM order_receipt WHERE order_number='TG-1060') || '/' || (SELECT count(*) FROM order_state_change s JOIN order_receipt r ON r.id=s.order_receipt_id WHERE r.order_number='TG-1060') || '/' || (SELECT count(*) FROM pick_list WHERE file_name='PL-TG-1060.csv')")"
if [[ "$(status p2)" == 200 && "$(field p2 .result)" == accepted && "$before" == "$after" ]]; then
  pass "P-2 (200 again; receipts/history/pick lists unchanged $after)"
else
  fail P-2 "status $(status p2), counts before $before after $after"
fi

# --- P-3: rejected, email, resend ----------------------------------------------------------------------------------
P3="$(fresh_number 1100 1199)"
echo "== P-3 rejected order $P3"
P3_SUBJECT="Order $P3 rejected"
p3_mail_before="$(mail_count "$P3_SUBJECT")"
p3_body="$(order_file "$P3" p3 '.lines[1].quantity = 25 | .shipTo.country = "DE"')"
post_with_key "$p3_body" p3
p3_checks="$(checks_of p3)"
mail_id=""
for ((i = 0; i < MAIL_WAIT_SECONDS; i += 3)); do
  [[ "$(mail_count "$P3_SUBJECT")" -gt "$p3_mail_before" ]] && { mail_id="$(newest_mail_id "$P3_SUBJECT")"; break; }
  sleep 3
done
if [[ "$(status p3)" != 422 || "$(ctype p3)" != application/problem+json* || "$(field p3 .result)" != rejected ]]; then
  fail P-3 "status $(status p3), type $(ctype p3), result $(field p3 .result)"
elif [[ "$p3_checks" != "quantity,destination-country" ]]; then
  fail P-3 "reasons $p3_checks, expected quantity,destination-country"
elif [[ -z "$mail_id" ]]; then
  fail P-3 "no Mailpit message '$P3_SUBJECT' within ${MAIL_WAIT_SECONDS}s"
else
  curl -fsS "$MAILPIT_API/message/$mail_id" > "$WORK/p3.mail"
  from="$(jq -r '.From.Address' "$WORK/p3.mail")"
  to="$(jq -r '[.To[].Address] | join(",")' "$WORK/p3.mail")"
  text="$(jq -r '.Text' "$WORK/p3.mail")"
  if [[ "$from" != "$MAIL_SENDER" || "$to" != "$ORDER_CONTACT" ]]; then fail P-3 "mail from $from to $to"
  elif [[ "$text" != *"$P3"* ]] || [[ "$(grep -c '^- ' <<< "$text")" != 2 ]]; then fail P-3 "mail body lacks the order number or the 2 reasons"
  elif grep -qiE 'Lee Martin|Canyon|Moab|84532|lee@example.com' <<< "$text"; then fail P-3 "mail body holds personal data"
  else
    # Resend: the same answer, no second email
    post_with_key "$p3_body" p3b
    sleep 12
    if [[ "$(status p3b)" == 422 && "$(jq -c '.rejectionReasons' "$WORK/p3b.body")" == "$(jq -c '.rejectionReasons' "$WORK/p3.body")" \
          && "$(mail_count "$P3_SUBJECT")" == $((p3_mail_before + 1)) ]]; then
      pass "P-3 (422 with quantity, destination-country; one email from $from to the Order Contact; resend 422 same reasons, no second email)"
    else
      fail P-3 "resend status $(status p3b), mails $(mail_count "$P3_SUBJECT")"
    fi
  fi
fi

# --- P-4: corrected order with P-3's number --------------------------------------------------------------------------
echo "== P-4 corrected order $P3"
p4_body="$(order_file "$P3" p4)"
post_with_key "$p4_body" p4
p4_state="$(q "SELECT r.order_state FROM order_receipt r JOIN customer_order c ON c.order_receipt_id = r.id WHERE c.order_number = '$P3'")"
p4_pick="$(q "SELECT count(*) FROM pick_list WHERE file_name = 'PL-$P3.csv'")"
if [[ "$(status p4)" == 200 && "$(field p4 .result)" == accepted && -n "$p4_state" && "$p4_pick" == 1 ]]; then
  pass "P-4 (checked anew: 200, recorded ($p4_state), one pick list)"
else
  fail P-4 "status $(status p4), state '$p4_state', pick lists $p4_pick"
fi

# --- P-5: P-1's number, different content -----------------------------------------------------------------------------
echo "== P-5 TG-1060 with different content"
p1_before="$(q "SELECT md5(string_agg(l.sku || l.quantity || l.unit_price, ',' ORDER BY l.line_number)) FROM order_line l JOIN customer_order c ON c.id = l.customer_order_id WHERE c.order_number='TG-1060'")"
p5_body="$(order_file TG-1060 p5 '.lines[0].quantity = 3')"
post_with_key "$p5_body" p5
p1_after="$(q "SELECT md5(string_agg(l.sku || l.quantity || l.unit_price, ',' ORDER BY l.line_number)) FROM order_line l JOIN customer_order c ON c.id = l.customer_order_id WHERE c.order_number='TG-1060'")"
if [[ "$(status p5)" == 422 && "$(jq -r '[.rejectionReasons[]?.orderCheck] | join(",")' "$WORK/p5.body")" == order-number-already-used \
      && "$p1_before" == "$p1_after" ]]; then
  pass "P-5 (422 order-number-already-used; TG-1060 unchanged)"
else
  fail P-5 "status $(status p5), reasons $(jq -c '.rejectionReasons' "$WORK/p5.body"), order changed: $([[ "$p1_before" == "$p1_after" ]] && echo no || echo yes)"
fi

# --- P-6: order number range and format --------------------------------------------------------------------------------
echo "== P-6 TG-2500 and TG-12"
post_with_key "$(order_file TG-2500 p6a)" p6a
post_with_key "$(order_file TG-12 p6b)" p6b
r6a="$(checks_of p6a)"
r6b="$(checks_of p6b)"
if [[ "$(status p6a)" == 422 && "$r6a" == order-number-range && "$(status p6b)" == 422 \
      && "$r6b" == "order-number-format,order-number-range" ]]; then
  pass "P-6 (TG-2500: order-number-range; TG-12: order-number-format, order-number-range)"
else
  fail P-6 "TG-2500 $(status p6a) $r6a; TG-12 $(status p6b) $r6b"
fi

# --- P-7: refused requests -------------------------------------------------------------------------------------------
echo "== P-7 no key, unknown key, other partner"
receipts_before="$(q "SELECT count(*) FROM order_receipt")"
p7_body="$(order_file TG-1070 p7)"
post "$p7_body" p7a
post "$p7_body" p7b -H 'X-API-Key: unknown-key-for-scenario-p7'
post_with_key "$(order_file TG-1070 p7c '.partnerId = "other-partner"')" p7c
receipts_after="$(q "SELECT count(*) FROM order_receipt")"
plain() { [[ "$(ctype "$1")" == application/problem+json* && -z "$(field "$1" .result)" && -z "$(field "$1" .orderNumber)" ]]; }
if [[ "$(status p7a)" == 401 && "$(status p7b)" == 401 && "$(status p7c)" == 403 ]] && plain p7a && plain p7b && plain p7c \
    && [[ "$receipts_before" == "$receipts_after" ]]; then
  pass "P-7 (401, 401, 403 plain Problem; no receipt)"
else
  fail P-7 "statuses $(status p7a)/$(status p7b)/$(status p7c), receipts $receipts_before -> $receipts_after"
fi

# --- P-8: unreadable requests ----------------------------------------------------------------------------------------
echo "== P-8 unreadable requests"
receipts_before="$(q "SELECT count(*) FROM order_receipt")"
printf 'this is not JSON' > "$WORK/not-json.json"
order_file TG-1071 orderedat '.orderedAt = "yesterday"' > /dev/null
order_file "TG-1$(printf '0%.0s' {1..40})" long-order-id > /dev/null
p8_ok=1
for f in "$INVALID/order-id-missing.json" "$INVALID/quantity-not-integer.json" "$INVALID/unknown-field.json" \
         "$WORK/not-json.json" "$WORK/orderedat.json" "$WORK/long-order-id.json"; do
  name="p8-$(basename "$f" .json)"
  post_with_key "$f" "$name"
  reasons="$(jq -r '[.rejectionReasons[]? | select(has("orderCheck") | not)] | length' "$WORK/$name.body" 2>/dev/null || echo x)"
  all="$(jq -r '.rejectionReasons | length' "$WORK/$name.body" 2>/dev/null || echo x)"
  echo "   $(basename "$f"): $(status "$name") $(field "$name" .orderNumber) $(jq -r '.rejectionReasons[0].text // empty' "$WORK/$name.body" 2>/dev/null)"
  if [[ "$(status "$name")" != 400 || "$(field "$name" .result)" != rejected || "$reasons" != 1 || "$all" != 1 ]]; then
    p8_ok=0
  fi
done
[[ "$(field p8-order-id-missing .orderNumber)" == "" && "$(field p8-orderedat .orderNumber)" == TG-1071 ]] || p8_ok=0
receipts_after="$(q "SELECT count(*) FROM order_receipt")"
if [[ "$p8_ok" == 1 && "$receipts_before" == "$receipts_after" ]]; then
  pass "P-8 (400 with one reason without orderCheck, orderNumber when readable; nothing written)"
else
  fail P-8 "see the lines above; receipts $receipts_before -> $receipts_after"
fi

# --- P-11: header injection ----------------------------------------------------------------------------------------
P11="$(fresh_number 1200 1299)"
echo "== P-11 CamelSqlQuery header on $P11"
partner_before="$(q "SELECT count(*) || ':' || md5(string_agg(p::text, '|' ORDER BY id)) FROM partner p")"
post_with_key "$(order_file "$P11" p11)" p11 -H 'CamelSqlQuery: DELETE FROM partner'
partner_after="$(q "SELECT count(*) || ':' || md5(string_agg(p::text, '|' ORDER BY id)) FROM partner p")"
p11_recorded="$(q "SELECT count(*) FROM customer_order WHERE order_number = '$P11'")"
if [[ "$(status p11)" == 200 && "$(field p11 .result)" == accepted && "$p11_recorded" == 1 && "$partner_before" == "$partner_after" ]]; then
  pass "P-11 (200 accepted and recorded; partner table unchanged ${partner_after%%:*} rows)"
else
  fail P-11 "status $(status p11), recorded $p11_recorded, partner $partner_before -> $partner_after"
fi

echo
if [[ "$failures" -gt 0 ]]; then echo "$failures scenario(s) FAILED"; exit 1; fi
echo "All partner scenarios PASSED"
