#!/usr/bin/env bash
# Runs one task in one condition: ./run-one.sh <memory|mcp> <task-id> <rep>
# Writes results/<condition>/<task-id>-<rep>.camel.yaml and .json (cost, turns, tools used).
set -euo pipefail
cd "$(dirname "$0")"
cond=$1; id=$2; rep=$3
task=$(python3 -c "import json,sys; print(next(t['task'] for t in json.load(open('tasks.json')) if t['id']=='$id'))")
out=results/$cond; mkdir -p "$out"
work=$(mktemp -d)
prompt="You are writing an Apache Camel 4.22 integration route.

Requirement: $task

Write the route in the Camel YAML DSL, canonical form (from/to with uri and parameters), to the file route.camel.yaml in the current directory. Use only components and endpoint options that exist in Camel 4.22. Don't create any other files."

if [ "$cond" = memory ]; then
  mcp=(--strict-mcp-config --mcp-config '{"mcpServers":{}}')
  allowed="Write"
else
  mcp=(--strict-mcp-config --mcp-config "$PWD/mcp-readonly.json")
  allowed="Write mcp__camel"
fi

(cd "$work" && claude -p "$prompt" --output-format stream-json --verbose \
   --setting-sources project --tools "Write,Read" "${mcp[@]}" \
   --allowedTools $allowed --permission-mode acceptEdits --no-session-persistence < /dev/null) > "$out/$id-$rep.jsonl" || true
cp "$work/route.camel.yaml" "$out/$id-$rep.camel.yaml" 2>/dev/null || echo "no route written" >&2
rm -rf "$work"
