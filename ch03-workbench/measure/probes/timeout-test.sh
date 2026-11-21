#!/usr/bin/env bash
# Runs each task-08 route against a catalog that answers after 8 seconds.
# The requirement says give up after 5, so a correct route times out.
cd "$(dirname "$0")"
python3 slow_catalog.py 2>/dev/null & SERVER=$!
trap 'kill $SERVER' EXIT
sleep 1
for route in ../reference/08-http-call.camel.yaml ../results/*/08-http-call-*.camel.yaml; do
  work=$(mktemp -d)
  # the test server is plain HTTP: point the route at it, and at the http component if it configures https
  sed -e 's#https://catalog.trailhead.example#http://127.0.0.1:18099#' -e 's#- name: https$#- name: http#' "$route" > "$work/route.camel.yaml"
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
        - log:
            message: "answered"
YAML
  log=$(cd "$work" && camel run route.camel.yaml trigger.camel.yaml --max-seconds=15 2>&1)
  start=$(echo "$log" | grep -m1 ": calling" | cut -c12-19)
  if echo "$log" | grep -q "Read timed out"; then verdict="timed out (correct)"
  elif echo "$log" | grep -q ": answered"; then verdict="waited for the slow answer (timeout ignored)"
  else verdict="other: $(echo "$log" | grep -m1 -E 'ERROR|Exception' | cut -c1-120)"; fi
  printf '%-45s %s\n' "${route#../}" "$verdict"
  rm -rf "$work"
done
