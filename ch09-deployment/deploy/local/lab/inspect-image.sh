#!/usr/bin/env bash
# Deploy lab (design 006, §3.1 "built"): what an image holds. Read-only; prints facts about one local image.
#   deploy/local/lab/inspect-image.sh trailhead/order-integration:today
set -euo pipefail
image=${1:?usage: inspect-image.sh <image>}
echo "== config"
docker image inspect "$image" --format 'user={{.Config.User}} entrypoint={{json .Config.Entrypoint}} cmd={{json .Config.Cmd}} workdir={{.Config.WorkingDir}} size={{.Size}} arch={{.Architecture}}'
docker image inspect "$image" --format '{{range .Config.Env}}{{println .}}{{end}}'
echo "== base layers"
docker history --no-trunc --format '{{.CreatedBy}}' "$image" | cut -c1-160 | head -20
echo "== inside"
docker run --rm --entrypoint sh "$image" -c '
  java -version 2>&1
  echo "id: $(id)"
  head -2 /etc/os-release
  echo "deployments:"; ls /deployments
  echo "classes:"; ls /deployments/BOOT-INF/classes 2>/dev/null || true
  echo "libs: $(ls /deployments/BOOT-INF/lib 2>/dev/null | wc -l)"
  echo "test or fault classes: $(find /deployments -path "*fault*" -o -name "*IT.class" -o -name "*Test.class" | wc -l)"
  echo "config dir: $(ls -d /deployments/config 2>/dev/null || echo none)"
  for t in curl wget bash microdnf dnf rpm apt-get python3 jcmd jfr; do command -v $t >/dev/null 2>&1 && echo "tool: $t"; done
  echo "writable by the user: $(find / -xdev -writable -type d 2>/dev/null | grep -v ^/proc | head -10 | tr "\n" " ")"
'
