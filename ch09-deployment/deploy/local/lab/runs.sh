#!/usr/bin/env bash
# Deploy lab runs (design 006 §3.1, after-runs §3.14). Each run writes target/lab/runs/<run>.txt and prints a summary.
# The cases target the release: statefulset/order-integration, pod order-integration-0 (deploy/local/lab/k8s/release).
#   runs.sh rollout <from>        partner traffic for 70 s, a rollout restart at 10 s; integration pods watched
#   runs.sh sigterm <from>        200 Peakmart events and partner traffic, the pod deleted (SIGTERM) mid-way
#   runs.sh badconfig <from> <patch-file>  partner traffic, then a release with a broken setting applied (the lab
#                                 overlay plus the strategic merge patch), then rolled back by the runbook's step
#   runs.sh outages <from> [systems]  Kafka, the Order Database, the Product Catalog down in turn
#   runs.sh replicas <from> [accepted] [rejected]  the release as written (one replica, no scale command): Peakmart
#                                 orders with a rollout restart and a pod delete meanwhile; integration pods watched
#   runs.sh burst <from> [accepted] [rejected]  the L7 burst (300 + 200 Peakmart orders) with restarts and memory
#   runs.sh patched <patch-file>  the lab overlay plus a lab-only patch (k8s/patch-*.yaml), applied with apply -k
#   runs.sh release               the lab overlay as written again (the runbook's rollback)
# Partner traffic uses order numbers outside the partner's range unless stated: a served answer is the contract's 422,
# a refused connection is 000.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
lab="$root/deploy/local/lab/lab.sh"
kube="$root/deploy/local/kube.sh"
release="$root/deploy/local/lab/k8s/release"
k() { "$kube" -n trailhead-lab "$@"; }
sts=statefulset/order-integration
pod=order-integration-0
sel=app.kubernetes.io/name=order-integration
out="$root/target/lab/runs"; mkdir -p "$out"
run=${1:?run}; shift
log="$out/$run.txt"
note() { echo "$(date +%T) == $*" | tee -a "$log.events"; }
: > "$log.events"
# watch_pods <seconds>: every 0.5 s the number of integration pods and their phases, into <run>.txt.pods
watch_pods() {
  local end=$(( $(date +%s) + $1 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    echo "$(date +%T) $(k get pods -l $sel --no-headers 2>/dev/null | awk '{printf "%s:%s:%s ", $1, $2, $3}')"; sleep 0.5
  done > "$log.pods"
}
pods_summary() {
  awk '{n = NF - 1; if (n > m) m = n} END {print "integration pods at most: " m + 0}' "$log.pods" | tee -a "$log.events"
}
ready() { [ "$(k get pod $pod -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" = True ]; }
on_update_revision() {
  [ "$(k get pod $pod -o jsonpath='{.metadata.labels.controller-revision-hash}' 2>/dev/null)" = "$(k get $sts -o jsonpath='{.status.updateRevision}')" ]
}
wait_for() { local end=$(( $(date +%s) + $1 )); shift; until "$@"; do [ "$(date +%s)" -lt "$end" ] || return 1; sleep 1; done; }
case "$run" in
  rollout)
    "$lab" stream "${1:?from}" 70 > "$log" &
    sp=$!
    watch_pods 75 &
    wp=$!
    sleep 10; note "rollout restart"
    k rollout restart $sts >/dev/null
    k rollout status $sts --timeout=120s >/dev/null; note "rollout status done"
    wait $sp $wp
    pods_summary
    awk '$3 == "000" {if (!f) f = $1; l = $1} END {print "refused (000) from " (f ? f : "-") " to " (l ? l : "-")}' "$log" | tee -a "$log.events" ;;
  sigterm)
    from=${1:?from}
    "$lab" sql "select count(*) from order_receipt where channel = 'Peakmart'" | sed 's/^/peakmart receipts before: /' | tee -a "$log.events"
    "$lab" events "$from" 200; note "200 events published PM-$from.."
    "$lab" stream "$((from + 50000))" 60 > "$log" &
    sp=$!
    sleep 3
    k logs -f $pod > "$log.oldpod" 2>&1 &
    lp=$!
    note "delete pod $pod (SIGTERM, grace $(k get pod $pod -o jsonpath='{.spec.terminationGracePeriodSeconds}') s)"
    k delete pod $pod --wait=true >/dev/null; note "pod $pod gone"
    wait $lp || true
    wait_for 180 ready && note "replacement ready" || note "replacement NOT ready after 180 s"
    wait $sp
    sleep 30
    grep -E "shutdown in|Graceful shutdown|Unsubscribed|Shutting down" "$log.oldpod" | cut -c1-200 | tee -a "$log.events" || true
    "$lab" sql "select 'recorded=' || count(*) filter (where order_state in ('Recorded', 'Sent to Warehouse')) || ' receipts=' || count(*) || ' distinct=' || count(distinct order_number) from order_receipt where channel = 'Peakmart' and order_number in (select 'PM-' || g from generate_series($from, $((from + 199))) g)" | tee -a "$log.events"
    "$lab" sql "select 'pick lists=' || count(*) || ' delivered=' || count(*) filter (where delivery_state = 'Delivered') from pick_list p join customer_order c on c.id = p.customer_order_id where c.order_number in (select 'PM-' || g from generate_series($from, $((from + 199))) g)" | tee -a "$log.events" ;;
  badconfig)
    # The bad release goes in the way a release does (apply -k), so that "apply the previous objects" undoes it
    patch=${2:?patch file}; bad="$out/badconfig-release"; rm -rf "$bad"; mkdir -p "$bad"
    # The lab patch files hold only `spec:`; kustomize's strategic merge patch also needs the object's identity
    { printf 'apiVersion: apps/v1\nkind: StatefulSet\nmetadata:\n  name: order-integration\n'; grep -v '^#' "$patch"; } > "$bad/patch.yaml"
    printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - %s\npatches:\n  - path: patch.yaml\n' \
      "$(python3 -I -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$release" "$bad")" > "$bad/kustomization.yaml"
    "$lab" stream "${1:?from}" 150 > "$log" &
    sp=$!
    watch_pods 155 &
    wp=$!
    sleep 10; note "bad release applied: lab overlay + $(basename "$patch")"
    k apply -k "$bad" >/dev/null
    sleep 60; note "after 60 s"; "$lab" state | tee -a "$log.events"
    k logs $pod --previous --tail=200 2>/dev/null | grep -E "Caused by|FATAL|Flyway" | tail -3 | cut -c1-200 | sed 's/^/last log: /' | tee -a "$log.events" || true
    note "rollback: apply the previous objects (deploy/local/lab/k8s/release)"
    t_rb=$(date +%s)
    k apply -k "$release" >/dev/null
    if ! wait_for 30 on_update_revision; then
      note "pod $pod still on the bad revision after 30 s (never ready): delete it (runbook step, developer check 10)"
      k delete pod $pod --wait=false >/dev/null
    fi
    wait_for 240 ready && note "ready again $(( $(date +%s) - t_rb )) s after the rollback" || note "NOT ready 240 s after the rollback"
    wait $sp $wp
    pods_summary ;;
  outages)
    # outages <from>: for Kafka, the Order Database and the Product Catalog in turn: down 30 s, what health and a
    # partner request see, then back. Readiness of the integration pod watched the whole time
    n=${1:?from}; shift; systems=${*:-kafka postgres catalog}
    k get pod $pod -o jsonpath='restartCount before: {.status.containerStatuses[0].restartCount}{"\n"}' | tee -a "$log.events"
    k get pod $pod -w -o jsonpath='{.metadata.name} ready={.status.conditions[?(@.type=="Ready")].status} restarts={.status.containerStatuses[0].restartCount}{"\n"}' > "$log.podwatch" 2>&1 &
    wp=$!
    for sys in $systems; do
      note "$sys down"; k scale deploy/$sys --replicas=0 >/dev/null; k wait --for=delete pod -l app=$sys --timeout=60s >/dev/null 2>&1 || true
      sleep 30
      "$lab" health | tee -a "$log.events"
      echo "partner TG-$n: $("$lab" post TG-$n | cut -c1-160)" | tee -a "$log.events"; n=$((n + 1))
      "$lab" state | tail -1 | tee -a "$log.events"
      note "$sys back"; k scale deploy/$sys --replicas=1 >/dev/null; k rollout status deploy/$sys --timeout=120s >/dev/null
      sleep 20
      "$lab" health | tee -a "$log.events"
    done
    kill $wp 2>/dev/null || true
    k get pod $pod -o jsonpath='restartCount after: {.status.containerStatuses[0].restartCount}{"\n"}' | tee -a "$log.events"
    echo "pod watch: $(grep -c 'ready=False' "$log.podwatch" || true) line(s) with ready=False of $(wc -l < "$log.podwatch" | tr -d ' ')" | tee -a "$log.events"
    : > "$log" ;;
  replicas)
    # replicas <from> [accepted] [rejected]: the release as written (one replica; no scale command). Accepted and
    # rejected Peakmart orders; meanwhile a rollout restart and a pod delete; the Warehouse picks up every second
    from=${1:?from}; acc=${2:-100}; rej=${3:-200}; last=$((from + acc + rej - 1))
    note "replicas in the StatefulSet: $(k get $sts -o jsonpath='{.spec.replicas}')"
    "$lab" mail-reset >/dev/null
    "$lab" warehouse 240 &
    whp=$!
    watch_pods 240 &
    wp=$!
    "$lab" events "$from" "$acc"; "$lab" rejected-events "$((from + acc))" "$rej"; note "$acc accepted and $rej rejected events published PM-$from..PM-$last"
    sleep 5; note "rollout restart"; k rollout restart $sts >/dev/null; k rollout status $sts --timeout=180s >/dev/null; note "rollout done"
    sleep 15; note "delete pod $pod"; k delete pod $pod --wait=true >/dev/null; wait_for 180 ready && note "replacement ready" || note "replacement NOT ready after 180 s"
    wait $whp; note "warehouse pickup stopped"
    wait $wp
    pods_summary
    "$lab" dup-files | tee -a "$log.events"
    "$lab" mail-subjects | tail -5 | tee -a "$log.events"
    "$lab" sql "select 'notifications=' || count(*) || ' sent=' || count(sent_at) from notification n join order_receipt r on r.id = n.order_receipt_id where r.order_number in (select 'PM-' || g from generate_series($from, $last) g)" | tee -a "$log.events"
    "$lab" sql "select 'pick lists=' || count(*) || ' delivered=' || count(*) filter (where delivery_state = 'Delivered') || ' attempts>1=' || count(*) filter (where attempts > 1) || ' failures=' || coalesce(string_agg(distinct last_failure, ','), '-') from pick_list p join customer_order c on c.id = p.customer_order_id where c.order_number in (select 'PM-' || g from generate_series($from, $last) g)" | tee -a "$log.events"
    : > "$log" ;;
  patched)
    # patched <patch-file>: the lab overlay plus a lab-only strategic merge patch, applied as a release is (apply -k)
    patch=${1:?patch file}; dir="$out/patched-release"; rm -rf "$dir"; mkdir -p "$dir"
    { printf 'apiVersion: apps/v1\nkind: StatefulSet\nmetadata:\n  name: order-integration\n'; grep -v '^#' "$patch"; } > "$dir/patch.yaml"
    printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - %s\npatches:\n  - path: patch.yaml\n' \
      "$(python3 -I -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$release" "$dir")" > "$dir/kustomization.yaml"
    k apply -k "$dir" >/dev/null; note "applied lab overlay + $(basename "$patch")"
    wait_for 120 on_update_revision || note "pod not on the new revision after 120 s"
    wait_for 240 ready && note "ready" || note "NOT ready after 240 s"
    "$lab" state | tee -a "$log.events"
    : > "$log" ;;
  release)
    # release: back to the lab overlay as written (the runbook's rollback: apply the previous objects)
    k apply -k "$release" >/dev/null; note "applied the lab overlay"
    if ! wait_for 60 on_update_revision; then note "pod still on the old revision after 60 s: delete it (runbook step)"; k delete pod $pod --wait=false >/dev/null; fi
    wait_for 240 ready && note "ready" || note "NOT ready after 240 s"
    "$lab" state | tee -a "$log.events"
    : > "$log" ;;
  burst)
    # burst <from> [accepted] [rejected]: the L7 burst (300 accepted, 200 rejected Peakmart orders) and partner
    # traffic; restarts, last state and memory (kube.sh top pod, when the lab has metrics-server) every 5 s
    from=${1:?from}; acc=${2:-300}; rej=${3:-200}; last=$((from + acc + rej - 1))
    : > "$log.top"
    "$lab" state | sed 's/^/before: /' | tee -a "$log.events"
    "$lab" stream "$((from + 70000))" 120 > "$log" &
    sp=$!
    "$lab" events "$from" "$acc"; "$lab" rejected-events "$((from + acc))" "$rej"; note "$acc accepted and $rej rejected events published PM-$from..PM-$last"
    end=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "$end" ]; do
      echo "$(date +%T) $(k top pod $pod --no-headers 2>&1 | tr -s ' ' | cut -c1-80) | cgroup $(k exec $pod -- cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak 2>/dev/null | awk '{printf("%s=%dMiB ", (NR == 1 ? "current" : "peak"), $1 / 1048576)}') | $(k get pod $pod -o jsonpath='restarts={.status.containerStatuses[0].restartCount} last={.status.containerStatuses[0].lastState.terminated.reason}/{.status.containerStatuses[0].lastState.terminated.exitCode}' 2>&1)" >> "$log.top"
      sleep 5
    done
    wait $sp
    tail -3 "$log.top" | tee -a "$log.events"
    awk '{for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+Mi$/) {v = $i + 0; if (v > m) m = v}} END {print "peak memory (top): " (m ? m "Mi" : "not measured")}' "$log.top" | tee -a "$log.events"
    awk '{for (i = 1; i <= NF; i++) if ($i ~ /^peak=[0-9]+MiB$/) {v = substr($i, 6) + 0; if (v > m) m = v}} END {print "peak memory (cgroup memory.peak of the running container): " (m ? m " MiB" : "not measured")}' "$log.top" | tee -a "$log.events"
    "$lab" state | sed 's/^/after: /' | tee -a "$log.events"
    "$lab" sql "select 'receipts=' || count(*) || ' distinct=' || count(distinct order_number) || ' recorded=' || count(*) filter (where order_state in ('Recorded', 'Sent to Warehouse')) || ' rejected=' || count(*) filter (where order_state = 'Rejected') from order_receipt where channel = 'Peakmart' and order_number in (select 'PM-' || g from generate_series($from, $last) g)" | tee -a "$log.events"
    "$lab" sql "select 'set aside=' || count(*) from set_aside_order_event where order_number in (select 'PM-' || g from generate_series($from, $last) g)" | tee -a "$log.events" ;;
  *) echo "unknown run $run" >&2; exit 2 ;;
esac
echo "statuses: $("$lab" summary "$log")" | tee -a "$log.events"
grep -v -E ' (200|422) ' "$log" | head -40 || true
cat "$log.events"
