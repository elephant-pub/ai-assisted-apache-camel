#!/usr/bin/env bash
# Deploy lab helpers (design 006). Only the kind cluster trailhead-lab, only through deploy/local/kube.sh.
#   lab.sh up                      systems + secrets (namespace trailhead-lab)
#   lab.sh app <manifest>          apply an integration manifest (k8s/today.yaml) and wait for its pods to run
#   lab.sh app-sts <dir>           apply the release (kustomize dir k8s/release) and wait for statefulset/order-integration
#   lab.sh smoke [red|secrets|keep] deploy/ci/smoke.sh against this lab
# health and state select the release's pods (label app.kubernetes.io/name=order-integration)
#   lab.sh post <order-number>     one Partner Order from the client pod; prints status, seconds and the answer
#   lab.sh posts <from> <n>        n Partner Orders TG-<from>.. in sequence, one line each (status, seconds)
#   lab.sh event <order-number>    one Peakmart order.placed event with a fresh event id
#   lab.sh events <from> <n>       n events PM-<from>..
#   lab.sh sql "<statement>"       psql in the postgres pod
#   lab.sh mails                   number of messages in Mailpit
#   lab.sh files                   pick-list files on the SFTP server
#   lab.sh state                   pods with restarts, last state, exit code and reason
#   lab.sh down                    delete namespace trailhead-lab
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
kube="$root/deploy/local/kube.sh"
ns=trailhead-lab
k() { "$kube" -n $ns "$@"; }
cmd=${1:?command}; shift
case "$cmd" in
  up)
    "$root/deploy/local/lab/secrets.sh"
    k apply -f "$root/deploy/local/lab/k8s/systems.yaml"
    k rollout status deploy/postgres deploy/kafka deploy/sftp deploy/mail deploy/catalog --timeout=180s
    k wait --for=condition=Ready pod/client --timeout=120s
    k exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
      --topic peakmart.orders.v2 --partitions 2 --replication-factor 1 ;;
  app)
    k apply -f "${1:?manifest}"
    k rollout status deploy/order-integration --timeout=300s ;;
  app-sts)
    # app-sts <kustomize dir>: apply the release StatefulSet (k8s/release) and wait for its rollout
    k apply -k "${1:?kustomize dir}"
    k rollout status statefulset/order-integration --timeout=300s ;;
  smoke)
    # smoke [red|secrets|keep]: deploy/ci/smoke.sh (T12) against this lab, with the Secrets of the running systems
    # (target/lab/secrets). red: nothing applied, 20 s for the pod; secrets: step 1 only; keep: the release stays
    what=${1:-green}; dir="$root/deploy/local/lab/k8s/release"; mode=all; keep=0; wait=300
    case "$what" in red) dir=""; wait=20 ;; secrets) mode=secrets ;; keep) keep=1 ;; green) ;;
      *) echo "smoke: red, secrets, keep or nothing" >&2; exit 2 ;; esac
    KUBE="$kube" NS=$ns APPLY_DIR="$dir" RELEASE_IMAGE=trailhead/order-integration:006 \
      FAULT_IMAGE=trailhead/order-integration:006-fault SECRETS_DIR="$root/target/lab/secrets" READY_TIMEOUT=$wait \
      KEEP_RELEASE=$keep "$root/deploy/ci/smoke.sh" $([ "$mode" = secrets ] && echo secrets) ;;
  post)
    n=${1:?order number}
    body=$(jq -c --arg n "$n" '.orderId = $n' "$root/docs/messages/examples/partner-order/partner-order-sample.json")
    k exec client -- sh -c 'curl -sS --max-time 40 --cacert /ca/ca.crt -o /tmp/a -w "%{http_code} %{time_total}" \
      -X POST https://order-integration:8443/orders -H "Content-Type: application/json" \
      -H "X-API-Key: $(cat /key/partner-api-key-summit-outfitters)" --data-binary "$1"; echo " $(cat /tmp/a 2>/dev/null | head -c 300)"' _ "$body" ;;
  posts)
    from=${1:?first number}; count=${2:?count}
    for ((i = 0; i < count; i++)); do
      printf '%s TG-%s ' "$(date +%T)" "$((from + i))"; "$0" post "TG-$((from + i))" | cut -c1-120 || echo "curl-failed"
    done ;;
  stream)
    # stream <from> <seconds> [interval]: new Partner Orders TG-<from>, TG-<from+1>, ... from the client pod, one every
    # interval seconds (default 0.25); one line per request: time, order, HTTP status (000 = no connection), seconds
    from=${1:?first number}; secs=${2:?seconds}; gap=${3:-0.25}
    body=$(jq -c '.orderId = "@N@"' "$root/docs/messages/examples/partner-order/partner-order-sample.json")
    k exec client -- sh -c '
      n=$1; end=$(( $(date +%s) + $2 )); key=$(cat /key/partner-api-key-summit-outfitters)
      while [ "$(date +%s)" -lt "$end" ]; do
        b=$(printf %s "$4" | sed "s/@N@/TG-$n/")
        r=$(curl -s --max-time 15 --cacert /ca/ca.crt -o /dev/null -w "%{http_code} %{time_total}" -X POST https://order-integration:8443/orders \
             -H "Content-Type: application/json" -H "X-API-Key: $key" --data-binary "$b")
        echo "$(date +%T) TG-$n $r"; n=$((n + 1)); sleep $3
      done' _ "$from" "$secs" "$gap" "$body" ;;
  summary)
    # summary <stream output file>: count per HTTP status
    awk '{c[$3]++} END {for (s in c) printf "%s=%d ", s, c[s]; print ""}' "${1:?file}" ;;
  watch)
    # watch <order-number> <seconds>: the same Partner Order once a second (a resend is answered from the receipt)
    n=${1:?order number}; secs=${2:?seconds}
    for ((i = 0; i < secs; i++)); do
      printf '%s ' "$(date +%T)"; "$0" post "$n" 2>&1 | cut -c1-110 || true; sleep 1
    done ;;
  deploy-watch)
    # deploy-watch <manifest> <order-number> <seconds>: apply, then watch the endpoint from the first second
    k apply -f "${1:?manifest}"; date +%T; "$0" watch "${2:?order}" "${3:?seconds}" ;;
  event)
    n=${1:?order number}
    ev=$(jq -c --arg n "$n" --arg id "$(uuidgen | tr A-Z a-z)" '.order_ref = $n | .event_id = $id' \
      "$root/docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json")
    printf '%s\n' "$ev" | k exec -i deploy/kafka -- /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic peakmart.orders.v2 >/dev/null ;;
  events)
    from=${1:?first number}; count=${2:?count}
    for ((i = 0; i < count; i++)); do
      jq -c --arg n "PM-$((from + i))" --arg id "$(uuidgen | tr A-Z a-z)" '.order_ref = $n | .event_id = $id' \
        "$root/docs/messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json"
    done | k exec -i deploy/kafka -- /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic peakmart.orders.v2 >/dev/null ;;
  health)
    # health: status of /actuator/health, its readiness and liveness groups, and the DOWN components (actuator patch)
    ip=$(k get pod -l app.kubernetes.io/name=order-integration -o jsonpath='{.items[0].status.podIP}')
    for p in health health/readiness health/liveness; do
      printf '%s %-17s ' "$(date +%T)" "$p"
      k exec client -- curl --silent --max-time 20 -w ' http=%{http_code} %{time_total}s' "http://$ip:9090/actuator/$p" \
        | python3 -I -c 'import sys,json; t=sys.stdin.read(); b,_,m=t.rpartition(" http="); d=json.loads(b) if b.strip().startswith("{") else {}; c=d.get("components",{}); print(d.get("status"), "http="+m, "down:", ",".join(k for k,v in c.items() if v.get("status")!="UP") or "-")'
    done ;;
  rejected-events)
    # rejected-events <from> <n>: Peakmart events with an inactive SKU (each one a Rejected Order and a notification)
    from=${1:?first number}; count=${2:?count}
    for ((i = 0; i < count; i++)); do
      jq -c --arg n "PM-$((from + i))" --arg id "$(uuidgen | tr A-Z a-z)" '.order_ref = $n | .event_id = $id' \
        "$root/docs/messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json"
    done | k exec -i deploy/kafka -- /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic peakmart.orders.v2 >/dev/null ;;
  mail-reset)
    k exec client -- curl --silent -X DELETE http://mail:8025/api/v1/messages >/dev/null; "$0" mails ;;
  mail-subjects)
    # mail-subjects: subjects in Mailpit with their count, the ones sent more than once first
    k exec client -- curl --silent "http://mail:8025/api/v1/messages?limit=1000" \
      | jq -r '.messages[].Subject' | sort | uniq -c | sort -rn | awk '{c[$1>1?"twice-or-more":"once"]++} $1>1 {print} END {for (x in c) print x"="c[x]}' ;;
  warehouse)
    # warehouse <seconds>: the Warehouse's pickup, once a second: every final file in inbound moved to processed/ under
    # its own name (C-011); a second file of a name already picked up is kept as processed/<name>.again.<n>
    k exec deploy/sftp -- sh -c 'end=$(( $(date +%s) + $1 )); cd /home/admin/inbound/picklists
      while [ "$(date +%s)" -lt "$end" ]; do for f in *.csv; do [ -f "$f" ] || continue
        if [ -e "processed/$f" ]; then mv "$f" "processed/$f.again.$(date +%s%N)"; else mv "$f" "processed/$f"; fi; done; sleep 1; done' _ "${1:?seconds}" ;;
  dup-files)
    # dup-files: pick lists the Warehouse received more than once
    k exec deploy/sftp -- sh -c 'cd /home/admin/inbound/picklists; echo "picked up: $(ls processed | grep -c "csv$"), received again: $(ls processed | grep -c again), in inbound: $(ls *.csv 2>/dev/null | wc -l)"; ls processed | grep again | head -5' ;;
  tls)
    # tls: which TLS versions the partner endpoint accepts (NFR-010), and plain HTTP on the same port
    for v in 1.1 1.2 1.3; do
      printf 'TLS %s: ' "$v"
      k exec client -- curl --silent --show-error --max-time 10 --cacert /ca/ca.crt --tlsv$v --tls-max $v -o /dev/null \
        -w '%{http_code}\n' https://order-integration:8443/orders 2>&1 | tr '\n' ' ' || true; echo
    done
    printf 'plain HTTP: '; k exec client -- curl --silent --max-time 10 -o /dev/null -w '%{http_code}\n' http://order-integration:8443/orders || true ;;
  sql)
    k exec deploy/postgres -- psql -U trailhead -d orders -At -F ' | ' -c "${1:?statement}" ;;
  mails)
    k exec client -- curl --silent http://mail:8025/api/v1/messages?limit=1 | jq -r '"messages=\(.messages_count)"' ;;
  files)
    k exec deploy/sftp -- sh -c 'ls -la /home/admin/inbound/picklists /home/admin/inbound/picklists/processed' ;;
  state)
    k get pods -o custom-columns='POD:.metadata.name,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,STATE:.status.containerStatuses[0].state,LAST:.status.containerStatuses[0].lastState.terminated.reason,EXIT:.status.containerStatuses[0].lastState.terminated.exitCode' -l app.kubernetes.io/name=order-integration ;;
  down)
    "$kube" delete namespace $ns --wait=true ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
