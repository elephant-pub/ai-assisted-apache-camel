#!/usr/bin/env bash
# Sets up the chapter 3 workbench: a Camel Kit project for Claude Code, pinned to Camel 4.22.2 LTS,
# with the Camel MCP server in read-only mode. Requires JDK 25 and Python 3.
set -euo pipefail
cd "$(dirname "$0")"

CAMEL=4.22.2

if [[ -e trailhead-orders ]]; then
  echo "Refusing to overwrite workbench/trailhead-orders; inspect, rename, or remove it first." >&2
  exit 1
fi

# 1. A Camel Kit project for Claude Code: AGENTS.md, CLAUDE.md, commands, skills and .mcp.json
camel-kit init trailhead-orders --ai claude \
  -p camel.main.version=$CAMEL \
  -p camel.springboot.version=$CAMEL \
  -p springboot.bom.version=$CAMEL \
  -p camel.mcp.version=$CAMEL

cd trailhead-orders

# 2. Read-only Camel MCP server: no sending messages, stopping routes, tracing or writing files
python3 - <<'PY'
import json
from pathlib import Path

path = Path(".mcp.json")
cfg = json.loads(path.read_text(encoding="utf-8"))
args = cfg["mcpServers"]["camel"]["args"]
runner = next(a for a in args if a.endswith(":runner"))
at = args.index(runner)
security = ["-Dcamel.mcp.security.enabled=true", "-Dcamel.mcp.security.access-level=read-only"]
if not all(option in args for option in security):
    args[at:at] = security
path.write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
PY

echo "Workbench ready: $(pwd)"
echo "Review the generated files before trusting the project or creating a baseline commit."
echo "Next, in Claude Code: /plugin marketplace add ai-unified-process/marketplace, then /plugin install aiup-core"
