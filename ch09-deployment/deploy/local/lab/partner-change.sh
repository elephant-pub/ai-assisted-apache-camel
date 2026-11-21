#!/usr/bin/env bash
# Deploy lab (design 006 §3.1 C4, after-run A8): a released change of the Partner Configuration (NFR-012) reaches the
# running release how? A lab copy of the released file (deploy/k8s/base/partner-configuration.json) with a changed
# Order Contact replaces the generated ConfigMap order-integration-partners (kustomize behavior: replace, so the content
# hash in its name changes and the StatefulSet's volume follows it); applied as a release is (apply -k). Expected: a new
# ConfigMap name, the pod rolled, and the registration of the changed partner in the log and the partner row.
#   partner-change.sh [contact]   change and check
#   partner-change.sh --restore   the lab overlay as written again (deploy/local/lab/k8s/release)
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
lab="$root/deploy/local/lab/lab.sh"
k() { "$root/deploy/local/kube.sh" -n trailhead-lab "$@"; }
release="$root/deploy/local/lab/k8s/release"
sts=statefulset/order-integration
pod=order-integration-0
row() { echo "$(date +%T) $1: $("$lab" sql "select partner_code, order_contact_email, registered_at from partner where partner_code = 'summit-outfitters'")"; }
state() {
  echo "$(date +%T) $1: ConfigMap $(k get $sts -o jsonpath='{.spec.template.spec.volumes[?(@.name=="partners")].configMap.name}'), pod $(k get pod $pod -o jsonpath='uid {.metadata.uid} started {.status.containerStatuses[0].state.running.startedAt}' 2>&1)"
}
roll() { k apply -k "$1" >/dev/null; k rollout status $sts --timeout=200s >/dev/null; }
if [ "${1:-}" = --restore ]; then
  roll "$release"; state "restored"; row "restored"; exit 0
fi
contact=${1:-orders-desk@summit-outfitters.example.com}
dir="$root/target/lab/partner-change"; rm -rf "$dir"; mkdir -p "$dir"
jq --arg c "$contact" '.partners[0].orderContact = $c' "$root/deploy/k8s/base/partner-configuration.json" > "$dir/partner-configuration.json"
printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - %s\nconfigMapGenerator:\n  - name: order-integration-partners\n    behavior: replace\n    files:\n      - partner-configuration.json\n' \
  "$(python3 -I -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$release" "$dir")" > "$dir/kustomization.yaml"
state before; row before
roll "$dir"
echo "$(date +%T) applied: lab overlay with the changed Partner Configuration (orderContact changed)"
state after; row after
echo "mounted file: orderContact $(k exec $pod -- cat /etc/trailhead/partners/partner-configuration.json | jq -r '.partners[0].orderContact')"
k logs $pod | grep -E "Partner Configuration" | cut -c1-200
