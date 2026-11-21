#!/usr/bin/env bash
# Runs every task 3 times in both conditions, 4 sessions at a time.
cd "$(dirname "$0")"
for rep in 1 2 3; do
  for id in $(python3 -c "import json; print(' '.join(t['id'] for t in json.load(open('tasks.json'))))"); do
    for cond in memory mcp; do echo "$cond $id $rep"; done
  done
done | xargs -P 4 -L 1 ./run-one.sh
