#!/bin/bash
# ============================================
# Run Script for the Peakmart intake prototype
# 1. one-shot Order Database schema init (A-12), 2. the integration (all flows and both Java beans).
# Uses the Kafka (9092) and PostgreSQL (15432) that `camel infra` already runs; starts no containers.
# ============================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v jbang &>/dev/null; then
  echo "ERROR: jbang not found. Install from https://www.jbang.dev/installation/" >&2
  exit 1
fi

if [[ -z "${DB_PASSWORD:-}" ]]; then
  echo "ERROR: set DB_PASSWORD (the Order Database password; for the local camel infra PostgreSQL it is 'test')" >&2
  exit 1
fi

# Camel JBang pinned to project.camelVersion from .camel-kit/config.properties (plan review, Law 4)
echo "Initialising the Order Database schema..."
jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run \
  db/order-db-schema-init.camel.yaml \
  OrderChecks.java CannotReadReason.java \
  --properties=application.properties \
  --max-messages=1

echo "Starting peakmart-intake-prototype integration..."
jbang -Dcamel.jbang.version=4.22.2 camel@apache/camel run \
  peakmart-order-intake.camel.yaml peakmart-order-check.camel.yaml order-desk-notification.camel.yaml \
  OrderChecks.java CannotReadReason.java \
  --properties=application.properties
