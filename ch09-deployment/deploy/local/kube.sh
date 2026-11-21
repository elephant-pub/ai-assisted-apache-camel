#!/usr/bin/env bash
# The only way into the local lab cluster: kubectl with a kubeconfig that holds the kind cluster and nothing else.
#   deploy/local/kube.sh get pods -n trailhead
# KIND_KUBECONFIG is the file `kind create cluster --kubeconfig` wrote. Flags that pick another cluster or user are refused.
set -euo pipefail
: "${KIND_KUBECONFIG:?set KIND_KUBECONFIG to the kind cluster kubeconfig file}"
context=kind-trailhead-lab
refused='^(--kubeconfig|--context|--cluster|--server|-s$|--token|--user|--as|--certificate-authority|--client-|--insecure-skip-tls-verify)'
for a in "$@"; do
  if [[ "$a" =~ $refused ]]; then
    echo "kube.sh: $a is not allowed; this script only reaches $context" >&2
    exit 2
  fi
done
contexts=$(KUBECONFIG="$KIND_KUBECONFIG" kubectl config get-contexts -o name)
if [ "$contexts" != "$context" ]; then
  echo "kube.sh: $KIND_KUBECONFIG must hold only $context (it has: $contexts)" >&2
  exit 2
fi
exec env KUBECONFIG="$KIND_KUBECONFIG" kubectl --context "$context" "$@"
