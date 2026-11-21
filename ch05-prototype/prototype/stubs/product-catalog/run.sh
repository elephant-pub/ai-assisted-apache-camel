#!/bin/bash
# ============================================
# Run Script for product-catalog-stub
# Usage: ./run.sh [normal|invalid|slow]   (default: stub.mode from application.properties)
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v jbang &>/dev/null; then
  echo "ERROR: jbang not found. Install from https://www.jbang.dev/installation/" >&2
  exit 1
fi

MODE_ARGS=()
if [[ $# -gt 0 ]]; then
  case "$1" in
    normal|invalid|slow) MODE_ARGS=("--property=stub.mode=$1") ;;
    *) echo "ERROR: unknown stub mode '$1' (use normal, invalid or slow)" >&2; exit 1 ;;
  esac
fi

echo "Starting product-catalog-stub on port 8091..."
# Camel JBang pinned to project.camelVersion from .camel-kit/config.properties (plan review, Law 4)
jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run \
  product-catalog-stub.camel.yaml \
  --properties=application.properties \
  ${MODE_ARGS[@]+"${MODE_ARGS[@]}"}
