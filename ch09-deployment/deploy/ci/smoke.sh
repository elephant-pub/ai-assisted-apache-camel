#!/usr/bin/env bash
# Design 006 §3.15 job 4, §3.14 T12: the smoke test of the release in a kind cluster (CI) or the lab (trailhead-lab).
#
#   deploy/ci/smoke.sh            every check below, then the integration's objects are removed (not the systems)
#   deploy/ci/smoke.sh secrets    step 1 only: create the Secrets (the lab's after-runs use it before lab.sh app-sts)
#
# Environment:
#   KUBE           the kubectl command: `kubectl` in CI, `deploy/local/kube.sh` in the lab (word-split, no quotes)
#   NS             the integration's namespace (trailhead-ci, trailhead-lab)
#   SYSTEMS_NS     the systems' namespace (default: NS; trailhead-ci-systems in CI)
#   APPLY_DIR      the kustomize directory applied with `apply -k` (deploy/k8s/ci, deploy/local/lab/k8s/release);
#                  empty: nothing is applied (the red run: no release, no pod)
#   RELEASE_IMAGE  the image the release runs (trailhead/order-integration:ci, :006)
#   FAULT_IMAGE    deploy/ci/fault.Dockerfile built FROM RELEASE_IMAGE (never published)
#   SECRETS_DIR    where the generated certificates, keys and passwords are kept (default target/smoke/<NS>, never
#                  committed, never printed). Files already there are reused: the lab passes target/lab/secrets, the
#                  files its running systems were started with
#   READY_TIMEOUT  seconds to wait for order-integration-0 ready (default 300)
#   KEEP_RELEASE   1: leave the integration's objects in place after a green run (the lab's after-runs)
#
# Each check prints `ok <check>: ...` or `FAILED <check>: expected <x>, got <y>`, then the pod's last 50 log lines,
# and exits 1. On a failure nothing is removed, so `kind export logs` still sees the pods. Logs are read by order
# number only (NFR-011); no secret value is ever printed.
set -euo pipefail
: "${KUBE:?KUBE: the kubectl command}" "${NS:?NS: the integration namespace}" "${RELEASE_IMAGE:?RELEASE_IMAGE}"
mode=${1:-all}
case "$mode" in all|secrets) ;; *) echo "usage: smoke.sh [secrets]" >&2; exit 2 ;; esac
[ "$mode" = secrets ] || : "${FAULT_IMAGE:?FAULT_IMAGE}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
sys_ns=${SYSTEMS_NS:-$NS}
apply_dir=${APPLY_DIR:-}
secrets_dir=${SECRETS_DIR:-$root/target/smoke/$NS}
ready_timeout=${READY_TIMEOUT:-300}
pod=order-integration-0
sts=statefulset/order-integration
selector=app.kubernetes.io/name=order-integration
k() { $KUBE -n "$NS" "$@"; }
ks() { $KUBE -n "$sys_ns" "$@"; }
t0=$(date +%s)
log() { echo "$(date -u +%T) [+$(( $(date +%s) - t0 ))s] $*"; }

fail() {
  echo "FAILED $1: expected $2, got $3"
  echo "--- last 50 log lines of $pod"
  k logs "$pod" --tail=50 2>&1 || true
  if [ "$(k get pod "$pod" -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo 0)" != 0 ]; then
    echo "--- last 50 log lines of $pod before its last restart"
    k logs "$pod" --previous --tail=50 2>&1 || true
  fi
  exit 1
}
ok() { log "ok $*"; }

# --- the settings in use --------------------------------------------------------------------------------------------
# setting <KEY>: its value in ConfigMap order-integration-settings as APPLY_DIR renders it; without APPLY_DIR, as the
# cluster holds it in NS; empty when neither has it (the red run)
setting() {
  if [ -n "$apply_dir" ]; then
    # Per rendered object (kustomize sorts the fields, so data: comes before kind: and metadata:)
    $KUBE kustomize "$apply_dir" | awk -v key="$1" '
      function done() { if (cm && named && found) out = v; cm = named = found = 0 }
      /^---$/ { done(); next }
      /^kind: ConfigMap$/ { cm = 1 }
      /^  name: order-integration-settings$/ { named = 1 }
      /^data:$/ { indata = 1; next }
      /^[^ ]/ { indata = 0 }
      indata && $1 == key ":" { v = $0; sub(/^[^:]*: */, "", v); gsub(/^["\047]|["\047]$/, "", v); found = 1 }
      END { done(); print out }'
  else
    k get configmap order-integration-settings -o "jsonpath={.data.$1}" 2>/dev/null || true
  fi
}
# CI-1: the integration checks the SFTP server's key against the known_hosts entry of WAREHOUSE_SFTP_HOST exactly as
# set (`[host]:port` when the port is not 22, deploy/README.md), so known_hosts must name it that way
sftp_host=$(setting WAREHOUSE_SFTP_HOST)
sftp_port=$(setting WAREHOUSE_SFTP_PORT)
case "${sftp_port:-22}" in 22) known_host=${sftp_host:-sftp} ;; *) known_host="[${sftp_host:-sftp}]:$sftp_port" ;; esac
# check_known_hosts: before any Secret is created or the release applied. Without settings in use (the red run)
# there is nothing to compare
check_known_hosts() {
  if [ -z "$sftp_host" ]; then
    log "known-hosts: no WAREHOUSE_SFTP_HOST in use (nothing applied, none in $NS), not compared"
    return
  fi
  local names
  names=$(awk '!/^[[:space:]]*(#|$)/ { printf "%s%s", sep, $1; sep = " " }' "$secrets_dir/known_hosts")
  # A line may list several names separated by commas; one of them must be the setting's
  tr ', ' '\n\n' <<< "$names" | grep -qxF -- "$known_host" || fail known-hosts "$known_host" "${names:-nothing}"
  ok "known-hosts: names $known_host, as WAREHOUSE_SFTP_HOST${sftp_port:+ and WAREHOUSE_SFTP_PORT} in use"
}

# --- step 1: Secrets ------------------------------------------------------------------------------------------------
# The logic of deploy/local/lab/secrets.sh: a CA for this run, server certificates for the systems (in SYSTEMS_NS) and
# the partner endpoint (in NS), an SFTP host key (known_hosts names the host as WAREHOUSE_SFTP_HOST in use does),
# random passwords and a random partner API key.
make_secrets() {
  mkdir -p "$secrets_dir"; chmod 700 "$secrets_dir"
  (
    cd "$secrets_dir"
    umask 077
    if [ ! -f ca.crt ]; then
      openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=trailhead-smoke-ca" -keyout ca.key -out ca.crt 2>/dev/null
      for pair in "postgres:$sys_ns" "mail:$sys_ns" "order-integration:$NS"; do
        host=${pair%%:*}; ns=${pair#*:}
        san="DNS:$host,DNS:$host.$ns,DNS:$host.$ns.svc,DNS:$host.$ns.svc.cluster.local"
        openssl req -newkey rsa:2048 -nodes -subj "/CN=$host.$ns.svc" -keyout "$host.key" -out "$host.csr" 2>/dev/null
        printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$san" > "$host.ext"
        openssl x509 -req -in "$host.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile "$host.ext" \
          -out "$host.crt" 2>/dev/null
      done
      # The truststore holds this run's CA only (the stand-in mail server's STARTTLS, W4-F2); its password is not a
      # secret (a truststore holds no key) and is the JVM default the overlays name
      rm -f truststore.p12
      keytool -importcert -noprompt -alias trailhead-smoke-ca -file ca.crt -keystore truststore.p12 -storetype PKCS12 \
        -storepass changeit >/dev/null
      ssh-keygen -q -t ed25519 -N '' -f sftp_host_ed25519_key
      printf '%s %s\n' "$known_host" "$(cut -d' ' -f1,2 sftp_host_ed25519_key.pub)" > known_hosts
      openssl rand -hex 16 > db-password
      openssl rand -hex 16 > sftp-password
      openssl rand -hex 16 > smtp-password
      openssl rand -hex 24 > partner-api-key-summit-outfitters
    fi
    # Mailpit accepts any login; older lab directories have no smtp-password file
    [ -f smtp-password ] || openssl rand -hex 16 > smtp-password
  )
  check_known_hosts
  for ns in "$sys_ns" "$NS"; do
    $KUBE get namespace "$ns" >/dev/null 2>&1 || $KUBE create namespace "$ns" >/dev/null
  done
  local d=$secrets_dir
  secret() { local ns=$1; shift; $KUBE -n "$ns" create secret generic "$@" --dry-run=client -o yaml | $KUBE apply -f - >/dev/null; }
  # The systems' four Secrets and the catalog stub's examples
  secret "$sys_ns" postgres-tls --from-file=tls.crt="$d/postgres.crt" --from-file=tls.key="$d/postgres.key"
  secret "$sys_ns" mail-tls --from-file=tls.crt="$d/mail.crt" --from-file=tls.key="$d/mail.key"
  secret "$sys_ns" sftp-host-key --from-file=ssh_host_ed25519_key="$d/sftp_host_ed25519_key"
  secret "$sys_ns" lab-passwords --from-file=db-password="$d/db-password" --from-file=sftp-password="$d/sftp-password"
  ks create configmap product-examples --from-file="$root/docs/messages/examples/product" --dry-run=client -o yaml \
    | $KUBE apply -f - >/dev/null
  # The integration's three Secrets (deploy/k8s/base/kustomization.yaml, design §3.9). The settings Secret from an env
  # file (mode 600): no value on a command line, and no trailing newline in a value (as --from-file would keep)
  (umask 077; printf 'DB_PASSWORD=%s\nWAREHOUSE_SFTP_PASSWORD=%s\nSMTP_USERNAME=order-integration\nSMTP_PASSWORD=%s\n' \
    "$(cat "$d/db-password")" "$(cat "$d/sftp-password")" "$(cat "$d/smtp-password")" > "$d/order-integration-secrets.env")
  secret "$NS" order-integration-secrets --from-env-file="$d/order-integration-secrets.env"
  secret "$NS" order-integration-files \
    --from-file=partner-tls.crt="$d/order-integration.crt" --from-file=partner-tls.key="$d/order-integration.key" \
    --from-file=db-ca.crt="$d/ca.crt" --from-file=warehouse_known_hosts="$d/known_hosts" \
    --from-file=truststore.p12="$d/truststore.p12"
  secret "$NS" order-integration-partner-api-keys --from-file=partner-api-key-summit-outfitters="$d/partner-api-key-summit-outfitters"
  ok "secrets: 4 in $sys_ns, 3 in $NS (values under ${secrets_dir#"$root"/}, not printed)"
}

# --- helpers --------------------------------------------------------------------------------------------------------
sql() { ks exec deploy/postgres -- psql -U trailhead -d orders -At -F ' ' -c "$1"; }
# post <orderId>: one Partner Order from the client pod; prints "<status> <body>"
post() {
  local body
  body=$(jq -c --arg n "$1" '.orderId = $n' "$root/docs/messages/examples/partner-order/partner-order-sample.json")
  # The API key goes to curl in a header file (-H @file), never on its command line
  k exec client -- sh -c 'umask 077; printf "X-API-Key: %s\n" "$(cat /key/partner-api-key-summit-outfitters)" > /tmp/key-header
    curl -s --max-time 40 --cacert /ca/ca.crt -o /tmp/answer -w "%{http_code}" \
    -X POST https://order-integration:8443/orders -H "Content-Type: application/json" \
    -H @/tmp/key-header --data-binary "$1"; printf " "; cat /tmp/answer 2>/dev/null' _ "$body"
}
# events <first> <count>: Peakmart order.placed events PM-<first>.. with fresh event ids
events() {
  local i
  for ((i = 0; i < $2; i++)); do
    jq -c --arg n "PM-$(($1 + i))" --arg id "$(uuidgen | tr 'A-Z' 'a-z')" '.order_ref = $n | .event_id = $id' \
      "$root/docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json"
  done | ks exec -i deploy/kafka -- /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic peakmart.orders.v2 >/dev/null
}
# until_true <seconds> <command...>: retry every 2 s
until_true() {
  local end=$(( $(date +%s) + $1 )); shift
  until "$@" >/dev/null 2>&1; do [ "$(date +%s)" -lt "$end" ] || return 1; sleep 2; done
}
pod_ready() { [ "$(k get pod "$pod" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" = True ]; }
pod_image() { k get pod "$pod" -o jsonpath='{.spec.containers[0].image}' 2>/dev/null; }
pod_on_update_revision() {
  [ "$(k get pod "$pod" -o jsonpath='{.metadata.labels.controller-revision-hash}' 2>/dev/null)" = \
    "$(k get $sts -o jsonpath='{.status.updateRevision}')" ]
}
# rolled <image>: the pod runs the StatefulSet's current template and is ready. With OrderedReady a pod that never
# became ready is not replaced when the template changes back (developer check 10, W4-F5): after 90 s on the old
# revision it is deleted, as the runbook's rollback step says.
rolled() {
  if ! until_true 90 pod_on_update_revision; then
    log "pod $pod is still on the previous revision after 90 s: deleting it (runbook rollback step)"
    k delete pod "$pod" --wait=true --ignore-not-found >/dev/null
    until_true 120 pod_on_update_revision || fail rollout "$pod on the update revision" "$(k get pod "$pod" -o jsonpath='{.metadata.labels.controller-revision-hash}' 2>&1)"
  fi
  until_true "$ready_timeout" pod_ready || fail ready "$pod Ready with $1" "$(k get pod "$pod" --no-headers 2>&1)"
  [ "$(pod_image)" = "$1" ] || fail image "$1" "$(pod_image)"
}
# picklists <file>: how often the Warehouse SFTP server holds the file (inbound folder or the processed folder)
picklists() {
  ks exec deploy/sftp -- sh -c 'cd /home/admin/inbound/picklists && { ls -1; ls -1 processed 2>/dev/null; } | grep -cx "$1" || true' _ "$1"
}
# recorded <first> <count>: Peakmart orders of PM-<first>.. recorded (or sent to the Warehouse), receipts, pick lists
# delivered, set aside
counts() {
  sql "select
    (select count(*) from order_receipt where channel = 'Peakmart' and order_number = any(a) and order_state in ('Recorded', 'Sent to Warehouse')),
    (select count(*) from order_receipt where channel = 'Peakmart' and order_number = any(a)),
    (select count(*) from pick_list p join customer_order c on c.id = p.customer_order_id where c.order_number = any(a) and p.delivery_state = 'Delivered'),
    (select count(*) from set_aside_order_event where order_number = any(a))
    from (select array(select 'PM-' || g from generate_series($1, $1 + $2 - 1) g) a) s"
}

make_secrets
[ "$mode" = secrets ] && exit 0

# --- step 2: apply, wait for order-integration-0 ready ---------------------------------------------------------------
if [ -n "$apply_dir" ]; then
  # No -n: the rendered objects carry their own namespaces (in CI the systems' differ from NS, and kubectl refuses an
  # object whose namespace does not match -n)
  $KUBE apply -k "$apply_dir" >/dev/null
  log "applied $apply_dir"
  for d in postgres kafka sftp mail catalog; do ks rollout status "deploy/$d" --timeout=300s >/dev/null; done
  ks exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic peakmart.orders.v2 --partitions 2 --replication-factor 1 >/dev/null
  k wait --for=condition=Ready pod/client --timeout=120s >/dev/null
fi
until_true "$ready_timeout" pod_ready || fail ready "$pod Ready within ${ready_timeout} s" "$(k get pod "$pod" --no-headers 2>&1 || true)"
[ "$(pod_image)" = "$RELEASE_IMAGE" ] || fail image "$RELEASE_IMAGE" "$(pod_image)"
ok "ready: $pod with $RELEASE_IMAGE"

# --- step 3: a partner order accepted, its resend answered as before, a rejected one 422 -----------------------------
# The first order number of the partner's range (1000-1999) not used yet in this Order Database
n=$(sql "select min(g) from generate_series(1000, 1999) g where not exists (select 1 from order_receipt where order_number = 'TG-' || g)")
[ -n "$n" ] || fail partner-order "a free order number TG-1000..TG-1999" "none left in this Order Database"
first=$(post "TG-$n"); [ "${first%% *}" = 200 ] || fail partner-accepted "200 for TG-$n" "${first%% *}"
again=$(post "TG-$n"); [ "$again" = "$first" ] || fail partner-resend "the same answer as the first (200 and body)" "${again%% *} (body $([ "${again#* }" = "${first#* }" ] && echo same || echo different))"
rej=$(post "TG-$((n + 9000))"); [ "${rej%% *}" = 422 ] || fail partner-rejected "422 for TG-$((n + 9000)) (outside the range)" "${rej%% *}"
ok "partner: TG-$n 200, resend same answer, TG-$((n + 9000)) 422"

# --- step 4: a Peakmart event recorded -------------------------------------------------------------------------------
pm=$(( $(date +%s) % 800000 + 100000 ))
events "$pm" 1
is_recorded() { [ "$(counts "$pm" 1 | cut -d' ' -f1)" = 1 ]; }
until_true 90 is_recorded || fail peakmart-recorded "PM-$pm recorded once" "$(counts "$pm" 1) (recorded receipts delivered set-aside)"
ok "peakmart: PM-$pm recorded"

# --- step 5: both pick lists on the SFTP server, once each -----------------------------------------------------------
for f in "PL-TG-$n.csv" "PL-PM-$pm.csv"; do
  once() { [ "$(picklists "$1")" = 1 ]; }
  until_true 120 once "$f" || fail pick-list "$f on the SFTP server once" "$(picklists "$f") time(s)"
done
ok "pick lists: PL-TG-$n.csv and PL-PM-$pm.csv, once each"

# --- step 6: TLS 1.2 and 1.3 accepted, 1.1 refused -------------------------------------------------------------------
# tls <version>: the HTTP status of a GET over exactly that TLS version (000: no handshake), then curl's error if any
tls() {
  k exec client -- curl -sS -o /dev/null -w '%{http_code}' --max-time 10 --cacert /ca/ca.crt --tlsv"$1" --tls-max "$1" \
    https://order-integration:8443/orders 2>&1 | tr '\n' ' ' || true
}
for v in 1.2 1.3; do
  r=$(tls $v); case "$r" in [1-5][0-9][0-9]*) ;; *) fail "tls-$v" "an HTTP answer over TLS $v" "${r:-nothing}" ;; esac
done
# TLS 1.1 must end in a TLS handshake error (curl exit 35), not in an answer, a DNS error or a timeout. Limit: the
# client's OpenSSL 3 may refuse to offer TLS 1.1 itself ("no protocols available"); then this shows only that no TLS
# 1.1 session is possible between the two, and the server's refusal rests on server.ssl.enabled-protocols (W5-F1)
r=$(tls 1.1)
case "$r" in
  [1-5][0-9][0-9]*) fail tls-1.1 "no handshake over TLS 1.1" "HTTP $r" ;;
  *"curl: (35)"*) ;;
  *) fail tls-1.1 "a TLS handshake error over TLS 1.1 (curl exit 35)" "${r:-nothing}" ;;
esac
case "$r" in *"no protocols available"*) side="refused by the client's OpenSSL, the server not reached (W5-F1)" ;; *) side="refused in the handshake" ;; esac
ok "tls: 1.2 and 1.3 answered; 1.1 $side"

# --- step 7: restart after exit 70 -----------------------------------------------------------------------------------
k patch $sts --type strategic -p "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"order-integration\",\"image\":\"$FAULT_IMAGE\",\"env\":[{\"name\":\"FAULT_POINTS\",\"value\":\"to-validate-order-placed:oom:1\"}]}]}}}}" >/dev/null
log "image switched to $FAULT_IMAGE, FAULT_POINTS=to-validate-order-placed:oom:1"
rolled "$FAULT_IMAGE"
f70=$(( pm + 10 ))
events "$f70" 20
log "20 events published PM-$f70..PM-$((f70 + 19))"
exited70() {
  [ "$(k get pod "$pod" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}')" = 70 ] &&
    [ "$(k get pod "$pod" -o jsonpath='{.status.containerStatuses[0].restartCount}')" -ge 1 ]
}
until_true 180 exited70 || fail exit-70 "lastState.terminated.exitCode 70 and restartCount >= 1" \
  "$(k get pod "$pod" -o jsonpath='exit {.status.containerStatuses[0].lastState.terminated.exitCode}, restarts {.status.containerStatuses[0].restartCount}')"
ok "exit 70: $(k get pod "$pod" -o jsonpath='restartCount {.status.containerStatuses[0].restartCount}, last exit {.status.containerStatuses[0].lastState.terminated.exitCode} at {.status.containerStatuses[0].lastState.terminated.finishedAt}')"
# Back to the release: the image and no FAULT_POINTS
idx=$(k get $sts -o json | jq '.spec.template.spec.containers[0].env | map(.name) | index("FAULT_POINTS")')
[ "$idx" != null ] || fail fault-template "FAULT_POINTS in the StatefulSet before switching back" "not found"
k patch $sts --type json -p "[{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/image\",\"value\":\"$RELEASE_IMAGE\"},{\"op\":\"test\",\"path\":\"/spec/template/spec/containers/0/env/$idx/name\",\"value\":\"FAULT_POINTS\"},{\"op\":\"remove\",\"path\":\"/spec/template/spec/containers/0/env/$idx\"}]" >/dev/null
log "image switched back to $RELEASE_IMAGE"
rolled "$RELEASE_IMAGE"
all20() { [ "$(counts "$f70" 20)" = "20 20 20 0" ]; }
until_true 180 all20 || fail after-exit-70 "20 recorded, 20 receipts, 20 pick lists delivered, 0 set aside" "$(counts "$f70" 20)"
started=$(k get pod "$pod" -o jsonpath='{.status.containerStatuses[0].state.running.startedAt}')
[ -n "$started" ] || fail running "$pod running with $RELEASE_IMAGE" "$(k get pod "$pod" --no-headers 2>&1)"
first_s=$(sql "select round(extract(epoch from min(received_at) - '$started'::timestamptz)::numeric, 1) from order_receipt where channel = 'Peakmart' and order_number in (select 'PM-' || g from generate_series($f70, $((f70 + 19))) g) and received_at > '$started'::timestamptz")
ok "after exit 70: 20 of 20 recorded once, 20 pick lists, 0 set aside; release container start -> first receipt ${first_s:-n/a} s"
[ "$(k get $sts -o json | jq '[.spec.template.spec.containers[0].env[].name] | index("FAULT_POINTS")')" = null ] || \
  fail release-template "no FAULT_POINTS in the StatefulSet" "FAULT_POINTS still set"

# --- step 8: rollout with partner traffic: never two integration pods, no 404 ---------------------------------------
stream_out=$(mktemp); pods_out=$(mktemp)
streamer=""; watcher=""
# On any exit the streamer and the pod watcher are stopped before their files are removed
trap 'for p in $streamer $watcher; do kill "$p" 2>/dev/null || true; done; rm -f "$stream_out" "$pods_out"' EXIT
body=$(jq -c '.orderId = "@N@"' "$root/docs/messages/examples/partner-order/partner-order-sample.json")
# Order numbers outside the partner's range: every served answer is the contract's 422, a refused connection is 000
k exec client -- sh -c '
  n=$1; end=$(( $(date +%s) + $2 ))
  umask 077; printf "X-API-Key: %s\n" "$(cat /key/partner-api-key-summit-outfitters)" > /tmp/key-header
  while [ "$(date +%s)" -lt "$end" ]; do
    b=$(printf %s "$3" | sed "s/@N@/TG-$n/")
    r=$(curl -s --max-time 15 --cacert /ca/ca.crt -o /dev/null -w "%{http_code}" -X POST https://order-integration:8443/orders \
         -H "Content-Type: application/json" -H @/tmp/key-header --data-binary "$b")
    echo "$(date +%T) TG-$n $r"; n=$((n + 1)); sleep 0.25
  done' _ "$(( (pm % 10000) + 20000 ))" 75 "$body" > "$stream_out" &
streamer=$!
( end=$(( $(date +%s) + 75 )); while [ "$(date +%s)" -lt "$end" ]; do
    echo "$(date +%T) $(k get pods -l "$selector" --no-headers 2>/dev/null | wc -l | tr -d ' ')"; sleep 0.5; done ) > "$pods_out" &
watcher=$!
sleep 10
k rollout restart $sts >/dev/null; log "rollout restart"
k rollout status $sts --timeout=240s >/dev/null || fail rollout "statefulset rollout complete within 240 s" "$(k get pod "$pod" --no-headers 2>&1)"
log "rollout status done"
wait "$streamer" || fail rollout-traffic "the partner traffic stream to run to its end" "exit $? from the client"
wait "$watcher" || true
streamer=""; watcher=""
# The checks below mean something only if traffic and pods were actually seen
lines=$(wc -l < "$stream_out" | tr -d ' ')
[ "$lines" -ge 100 ] || fail rollout-traffic "at least 100 partner requests during the rollout" "$lines"
grep -q ' 422$' "$stream_out" || fail rollout-traffic "served answers (422) during the rollout" "none"
grep -q ' 1$' "$pods_out" || fail rollout-one-pod "the integration pod seen by the watcher" "never 1 pod"
max_pods=$(awk '$2 > m {m = $2} END {print m + 0}' "$pods_out")
summary=$(awk '{c[$3]++} END {for (s in c) printf "%s=%d ", s, c[s]}' "$stream_out")
refused=$(awk '$3 == "000"' "$stream_out" | sed -n '1p;$p' | awk '{print $1}' | paste -sd- -)
[ "$max_pods" -le 1 ] || fail rollout-one-pod "at most 1 integration pod at any time" "$max_pods"
! awk '$3 == "404"' "$stream_out" | grep -q . || fail rollout-no-404 "0 answers 404" "$(awk '$3 == "404"' "$stream_out" | wc -l | tr -d ' ')"
pod_ready || fail ready "$pod Ready after the rollout" "$(k get pod "$pod" --no-headers 2>&1)"
ok "rollout: at most $max_pods pod, statuses ${summary}(000 ${refused:-none}), 0 x 404"

# --- end: remove the integration's objects (the systems and the client stay) ----------------------------------------
# The Secrets stay: the client pod (a system) mounts order-integration-files and the API key; the next run rewrites them.
if [ "${KEEP_RELEASE:-0}" != 1 ]; then
  k delete statefulset,service,networkpolicy,serviceaccount,configmap -l app.kubernetes.io/part-of=trailhead-order-integration \
    --wait=true >/dev/null
  k delete configmap order-integration-settings --ignore-not-found >/dev/null
  log "integration objects removed from $NS (the Secrets and the systems stay)"
fi
log "smoke green"
