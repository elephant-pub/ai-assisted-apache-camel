#!/usr/bin/env bash
# Runs each task-08 route against an address that never answers (10.255.255.1).
# The requirement says give up connecting after 2 seconds.
cd "$(dirname "$0")"
for route in ../reference/08-http-call.camel.yaml ../results/*/08-http-call-*.camel.yaml; do
  work=$(mktemp -d)
  sed -e 's#https://catalog.trailhead.example#http://10.255.255.1:18099#' -e 's#- name: https$#- name: http#' "$route" > "$work/route.camel.yaml"
  cat > "$work/trigger.camel.yaml" <<'YAML'
- route:
    from:
      uri: timer:go
      parameters:
        repeatCount: 1
      steps:
        - setHeader:
            name: sku
            constant: TENT-2P
        - log:
            message: "calling"
        - to:
            uri: direct:enrich
YAML
  log=$(cd "$work" && camel run route.camel.yaml trigger.camel.yaml --max-seconds=20 2>&1)
  t0=$(echo "$log" | grep -m1 ": calling" | awk '{print $2}')
  t1=$(echo "$log" | grep -m1 -E "ConnectTimeout|connect timed out|Connect to" | awk '{print $2}')
  secs=$(python3 -c "
from datetime import datetime as d
f='%H:%M:%S.%f'
print(round((d.strptime('$t1',f)-d.strptime('$t0',f)).total_seconds(),1) if '$t1' else 'no failure within 20 s')")
  printf '%-45s gave up connecting after %s\n' "${route#../}" "$secs"
  rm -rf "$work"
done
