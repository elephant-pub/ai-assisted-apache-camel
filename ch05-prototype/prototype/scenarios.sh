#!/bin/bash
# ============================================
# Acceptance scenarios 1-6 of the Peakmart intake prototype (design §4, design review item 5)
#
# How it publishes and queries (assumption A-15): it uses the client tools inside the containers that
# `camel infra` is ALREADY running -- kafka-console-producer.sh in `camel-kafka` (broker localhost:9092) and psql in
# `camel-postgres` (Order Database localhost:15432, db postgres, user test). It only runs `docker exec`; it never
# starts or stops a container and uses no port 8080, 8081 or 5432 on the host.
#
# Precondition: the Product Catalog stub (stubs/product-catalog/run.sh) and the integration (run.sh) are running.
# Scenarios 7-10 need the stub stopped or the integration restarted and are run by hand.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-camel-kafka}"
PG_CONTAINER="${PG_CONTAINER:-camel-postgres}"
TOPIC="${PEAKMART_TOPIC:-peakmart.orders.v2}"
EXAMPLES="../docs/messages/examples/peakmart-order-placed"
OUTBOX="target/outbox/order-desk"
WAIT_SECONDS="${WAIT_SECONDS:-12}"   # > order-check / notification poll interval (5 s) plus catalog time

die() { echo "ERROR: $*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "docker CLI not found"
docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 \
  || die "Kafka in container '$KAFKA_CONTAINER' (localhost:9092) cannot be reached"
docker exec "$PG_CONTAINER" psql -U test -d postgres -tAc "SELECT 1" >/dev/null 2>&1 \
  || die "PostgreSQL in container '$PG_CONTAINER' (localhost:15432) cannot be reached"

# Publish one record; the example file is compacted to one line because the console producer sends one record per line
publish_file() {
  local file="$1"
  [[ -f "$file" ]] || die "example not found: $file"
  echo ">> publish $(basename "$file")"
  tr -d '\n\r' < "$file" | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null
}

publish_text() {
  echo ">> publish literal text record: $1"
  printf '%s\n' "$1" | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null
}

sql() { docker exec "$PG_CONTAINER" psql -U test -d postgres -P pager=off -c "$1"; }

pause() { echo "   ... waiting ${WAIT_SECONDS}s"; sleep "$WAIT_SECONDS"; }

# --- Publish (in this order, waiting between dependent steps) ---
publish_file "$EXAMPLES/peakmart-order-placed-sample.json"        # 1
pause
publish_file "$EXAMPLES/pm-50117-redelivered.json"                # 2 (depends on 1)
publish_file "$EXAMPLES/pm-50118-inactive-sku.json"               # 3
publish_file "$EXAMPLES/invalid/other-event-type.json"            # 4
publish_file "$EXAMPLES/invalid/pm-50119-buyer-email-not-an-email.json"  # 5
publish_file "$EXAMPLES/invalid/price-as-number.json"             # 6a
publish_file "$EXAMPLES/invalid/event-id-missing.json"            # 6b
publish_text "this record is not JSON"                            # 6c
pause
pause

# --- Report (read-only queries; safe to repeat) ---
echo
echo "================ Scenario 1: PM-50117 recorded ================"
echo "EXPECTED: customer_order PM-50117, street line '2240 Pearl Street, Apt 4', 2 lines 229.00/12.50 with catalog"
echo "          names/weights (Ridgeline 2-Person Tent 2150 g, Aluminium Tent Stakes 8-Pack 120 g); history Received,"
echo "          Recorded; equals order/from-peakmart-order.json except receivedAt/correlationId"
sql "SELECT order_number, ordered_at, customer_email IS NOT NULL AS has_email, street_line, city, state_region,
            postal_code, country FROM customer_order WHERE order_number = 'PM-50117'"
sql "SELECT l.line_number, l.sku, l.product_name, l.quantity, l.unit_price, l.unit_weight_grams
       FROM order_line l JOIN customer_order c ON c.id = l.customer_order_id
      WHERE c.order_number = 'PM-50117' ORDER BY l.line_number"

echo "================ Scenario 2: PM-50117 redelivered (new event id) ================"
echo "EXPECTED: still exactly one receipt for PM-50117 (first event id 8d2f6c1e-...), no new rows; INFO duplicate log"
sql "SELECT r.order_number, r.event_id, r.order_state, r.waiting_for,
            string_agg(s.order_state, ', ' ORDER BY s.changed_at, s.id) AS history
       FROM order_receipt r LEFT JOIN order_state_change s ON s.order_receipt_id = r.id
      GROUP BY r.id ORDER BY r.id"

echo "================ Scenario 3: PM-50118 rejected ================"
echo "EXPECTED: receipt Rejected; 1 SKU reason on line 1:"
echo "          'Order Line 1: the SKU TENT-1P is not an Active Product in the Product Catalog. Replace it with an active SKU.'"
echo "          notification sent; outbox file peakmart-order-PM-50118-rejected.txt, subject 'Peakmart order PM-50118 rejected',"
echo "          no name/email/address"
sql "SELECT r.order_number, rr.position, rr.order_check, rr.line_number, rr.reason_text
       FROM rejection_reason rr JOIN order_receipt r ON r.id = rr.order_receipt_id ORDER BY r.id, rr.position"
sql "SELECT r.order_number, n.notification_type, n.recipient_email, n.sent_at IS NOT NULL AS sent, n.attempts
       FROM notification n JOIN order_receipt r ON r.id = n.order_receipt_id ORDER BY n.id"
echo "-- outbox: $OUTBOX"
ls -1 "$OUTBOX" 2>/dev/null || echo "(outbox is empty)"
if [[ -f "$OUTBOX/peakmart-order-PM-50118-rejected.txt" ]]; then
  echo "-- content of peakmart-order-PM-50118-rejected.txt:"
  cat "$OUTBOX/peakmart-order-PM-50118-rejected.txt"; echo
  if grep -qiE "Avery|Chen|avery\.chen|Mountain Avenue|Fort Collins|80521" "$OUTBOX/peakmart-order-PM-50118-rejected.txt"; then
    echo "!! personal data found in the notification"
  else
    echo "OK: no customer name, email or address in the notification"
  fi
fi

echo "================ Scenario 4: other event type ================"
echo "EXPECTED: skipped -- no receipt beyond PM-50117/PM-50118, no set-aside row for order.cancelled"
sql "SELECT count(*) AS receipts FROM order_receipt"

echo "================ Scenarios 5 and 6: set-aside events ================"
echo "EXPECTED: PM-50119 -> reason '\$.buyer.email: format' (no receipt);"
echo "          price-as-number -> '<path>: <keyword>' (e.g. '\$.items[0].price_usd: type'), key = event id 8d2f6c1e-...;"
echo "          event-id-missing -> '<path>: <keyword>' (required), key kafka-<p>-<o>;"
echo "          non-JSON text -> 'not JSON', key kafka-<p>-<o>; never the validator message"
sql "SELECT event_id, order_number, cannot_read_reason, set_aside_state, length(event_content) AS content_chars
       FROM set_aside_order_event ORDER BY id"
sql "SELECT count(*) AS pm_50119_receipts FROM order_receipt WHERE order_number = 'PM-50119'"
