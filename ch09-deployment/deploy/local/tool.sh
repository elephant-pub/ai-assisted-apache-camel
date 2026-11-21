#!/usr/bin/env bash
# Runs a check tool from its official image, pinned by digest, with only this project mounted (read-only).
#   deploy/local/tool.sh trivy fs --scanners vuln --severity HIGH,CRITICAL /repo
#   deploy/local/tool.sh trivy image --input /repo/target/image.tar     # after: docker save -o target/image.tar <image>
#   deploy/local/tool.sh kubeconform -strict -summary /repo/deploy/k8s
#   deploy/local/tool.sh actionlint
# The project is /repo inside the container. Trivy keeps its database in the Docker volume trailhead-trivy-cache.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
tool=${1:?usage: tool.sh trivy|kubeconform|actionlint [args]}; shift
case "$tool" in
  trivy)       image=aquasec/trivy@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c   # 0.76.1
               extra=(-v trailhead-trivy-cache:/root/.cache) ;;
  kubeconform) image=ghcr.io/yannh/kubeconform@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e   # v0.8.0
               extra=() ;;
  actionlint)  image=rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667   # 1.7.12
               extra=() ;;
  *) echo "tool.sh: unknown tool $tool" >&2; exit 2 ;;
esac
exec docker run --rm -v "$root:/repo:ro" -w /repo "${extra[@]+"${extra[@]}"}" "$image" "$@"
