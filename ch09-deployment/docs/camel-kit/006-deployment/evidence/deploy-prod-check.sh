#!/usr/bin/env bash
# The developer's deployment check on trailhead-prod (runbook "Deploy a release" step 4, validation decisions 4 and 5):
# steps 3-6 of deploy/ci/smoke.sh against the released image, run from the client pod in trailhead-systems.
# No fault image and no restart test here: those ran in CI; this is the release as a person deployed it.
set -euo pipefail
P="$(cd "$(dirname "$0")" && pwd)"; WS=${WS:?project}; export KUBECONFIG=$P/kubeconfig.yaml
k()  { kubectl --context kind-trailhead-prod -n trailhead "$@"; }
ks() { kubectl --context kind-trailhead-prod -n trailhead-systems "$@"; }
url=https://order-integration.trailhead.svc:8443/orders
ok() { echo "OK   $*"; }; bad() { echo "FAIL $*"; exit 1; }
sql() { ks exec deploy/postgres -- psql -U trailhead -d orders -At -F ' ' -c "$1"; }
post() {
  local body; body=$(jq -c --arg n "$1" '.orderId = $n' "$WS/docs/messages/examples/partner-order/partner-order-sample.json")
  ks exec client -- sh -c 'umask 077; printf "X-API-Key: %s\n" "$(cat /key/partner-api-key-summit-outfitters)" > /tmp/key-header
    curl -s --max-time 40 --cacert /ca/ca.crt -o /tmp/answer -w "%{http_code}" -X POST "$2" -H "Content-Type: application/json" \
      -H @/tmp/key-header --data-binary "$1"; printf " "; cat /tmp/answer 2>/dev/null' _ "$body" "$url"
}
until_true() { local end=$(( $(date +%s) + $1 )); shift; until "$@" >/dev/null 2>&1; do [ "$(date +%s)" -lt "$end" ] || return 1; sleep 2; done; }
picklists() { ks exec deploy/sftp -- sh -c 'cd /home/admin/inbound/picklists && { ls -1; ls -1 processed 2>/dev/null; } | grep -cx "$1" || true' _ "$1"; }

echo "image: $(k get pod order-integration-0 -o jsonpath='{.status.containerStatuses[0].imageID}')"
echo "ready: $(k get pod order-integration-0 -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'), restarts $(k get pod order-integration-0 -o jsonpath='{.status.containerStatuses[0].restartCount}'), arch $(k exec order-integration-0 -- uname -m)"

n=$(sql "select min(g) from generate_series(1000, 1999) g where not exists (select 1 from order_receipt where order_number = 'TG-' || g)")
first=$(post "TG-$n"); [ "${first%% *}" = 200 ] || bad "partner TG-$n: ${first%% *}"
again=$(post "TG-$n"); [ "$again" = "$first" ] || bad "resend TG-$n: ${again%% *}"
rej=$(post "TG-$((n + 9000))"); [ "${rej%% *}" = 422 ] || bad "TG-$((n + 9000)): ${rej%% *}"
ok "partner: TG-$n 200, resend same answer, TG-$((n + 9000)) 422"

pm=$(( $(date +%s) % 800000 + 100000 ))
jq -c --arg n "PM-$pm" --arg id "$(uuidgen | tr 'A-Z' 'a-z')" '.order_ref = $n | .event_id = $id' \
  "$WS/docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json" |
  ks exec -i deploy/kafka -- /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic peakmart.orders.v2 >/dev/null
recorded() { [ "$(sql "select count(*) from order_receipt where channel = 'Peakmart' and order_number = 'PM-$pm' and order_state in ('Recorded', 'Sent to Warehouse')")" = 1 ]; }
until_true 90 recorded || bad "PM-$pm not recorded"
ok "peakmart: PM-$pm recorded"

for f in "PL-TG-$n.csv" "PL-PM-$pm.csv"; do
  once() { [ "$(picklists "$1")" = 1 ]; }
  until_true 120 once "$f" || bad "$f on SFTP $(picklists "$f") time(s)"
done
ok "pick lists: PL-TG-$n.csv and PL-PM-$pm.csv, once each"

mails=$(ks exec deploy/mail -- wget -qO- http://localhost:8025/api/v1/messages 2>/dev/null | jq -r --arg a "TG-$n" '[.messages[] | select(.Subject | contains($a))] | length' 2>/dev/null || echo "n/a")
echo "info order desk mails naming TG-$n: $mails"

tls() { ks exec client -- curl -sS -o /dev/null -w '%{http_code}' --max-time 10 --cacert /ca/ca.crt --tlsv"$1" --tls-max "$1" "$url" 2>&1 | tr '\n' ' ' || true; }
for v in 1.2 1.3; do r=$(tls $v); case "$r" in [1-5][0-9][0-9]*) ok "tls $v: HTTP $r";; *) bad "tls $v: $r";; esac; done
r=$(tls 1.1); echo "info tls 1.1 from the client pod's curl: $r"
# From this machine, OpenSSL 3.6.2 offering exactly TLS 1.1 (seclevel 0 so that the client itself allows it):
k port-forward pod/order-integration-0 18443:8443 >/dev/null 2>&1 & pf=$!; trap 'kill $pf 2>/dev/null || true' EXIT; sleep 3
for v in tls1_1 tls1_2 tls1_3; do
  out=$(openssl s_client -connect 127.0.0.1:18443 -servername order-integration.trailhead.svc -$v -cipher 'DEFAULT:@SECLEVEL=0' </dev/null 2>&1 || true)
  proto=$(printf '%s\n' "$out" | sed -n 's/^ *Protocol *: *//p' | head -1)
  alert=$(printf '%s\n' "$out" | grep -oE 'alert [a-z ]+|no protocols available|unsupported protocol|wrong version number' | head -1 || true)
  echo "info openssl -$v: ${proto:-no session}${alert:+ ($alert)}"
done
