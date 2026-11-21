#!/bin/bash
# ============================================
# Helper for the manual partner scenarios (design 003 §4 P-9, P-10, P-17, P-18): POST one Partner Order to the dev
# endpoint with an API key read from a file (default: the dev key), and print the status and the answer body.
# The key is passed through a header file, never on the command line, and never printed.
#
# Usage: partner-post.sh <order-number> [key-file] [partner-id] [copies]
#   The body is docs/messages/examples/partner-order/partner-order-sample.json with orderId (and partnerId) replaced.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../.."

ORDER_NUMBER="${1:?order number, e.g. TG-1600}"
KEY_FILE="${2:-config/dev/secrets/partner-api-key-summit-outfitters}"
PARTNER_ID="${3:-summit-outfitters}"
API_URL="${API_URL:-http://localhost:8082/orders}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/partner-post.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
printf 'X-API-Key: %s\n' "$(tr -d '\r\n' < "$KEY_FILE")" > "$WORK/key.header"
jq --arg n "$ORDER_NUMBER" --arg p "$PARTNER_ID" '.orderId = $n | .partnerId = $p' \
  docs/messages/examples/partner-order/partner-order-sample.json > "$WORK/body.json"
send() { # $1 = copy number
  local status
  status="$(curl -sS -o "$WORK/answer.$1" -w '%{http_code}' -X POST "$API_URL" -H 'Content-Type: application/json' \
    -H "@$WORK/key.header" --data-binary "@$WORK/body.json")"
  echo "$ORDER_NUMBER $status $(tr -d '\n' < "$WORK/answer.$1")"
}
# Optional 4th argument: send that many identical requests at the same time (P-9)
COPIES="${4:-1}"
for ((i = 1; i <= COPIES; i++)); do send "$i" & done
wait
