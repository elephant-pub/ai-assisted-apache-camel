#!/bin/bash
# ============================================
# P-19 (design 003 §3 carried item 3): the startup check names every missing variable.
# Starts target/order-integration-0.1.0.jar without a profile:
#   1. with all 26 variables set (as --NAME=value): the run gets past the check (it then fails later on purpose: the
#      values point nowhere, so nothing connects and no port stays open);
#   2. with none set: one failure naming all 26;
#   3. 26 runs, each with one variable left out: the failure names exactly that one.
# Values are passed as command-line arguments, which Spring resolves like environment variables (as in 002).
# Starts no service. Prints PASS/FAIL per run; exits 1 on any failure.
# ============================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../.."
JAR="target/order-integration-0.1.0.jar"
[[ -f "$JAR" ]] || { echo "ERROR: build $JAR first" >&2; exit 2; }
LOGS="target/required-variables"; mkdir -p "$LOGS"

VARS=(KAFKA_BROKERS DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD DB_SSL_ROOT_CERT CATALOG_HOST CATALOG_PORT SMTP_HOST
      SMTP_PORT SMTP_USERNAME SMTP_PASSWORD ORDERDESK_MAILBOX ORDERDESK_SENDER PARTNER_HTTP_PORT PARTNER_TLS_CERTIFICATE
      PARTNER_TLS_PRIVATE_KEY PARTNER_CONFIGURATION SECRET_STORE_DIR PARTNER_MAIL_SENDER WAREHOUSE_SFTP_HOST
      WAREHOUSE_SFTP_PORT WAREHOUSE_SFTP_USERNAME WAREHOUSE_SFTP_PASSWORD WAREHOUSE_SFTP_KNOWN_HOSTS)
value() { case "$1" in
  *_PORT) echo 1 ;;                       # nothing listens on port 1; PARTNER_HTTP_PORT 1 cannot be bound either
  *_HOST|KAFKA_BROKERS) echo 127.0.0.1 ;;
  *MAILBOX|*SENDER) echo nobody@example.invalid ;;
  *) echo "/nonexistent/p19-$1" ;;
esac; }

failures=0
run() { # $1 = name, $2.. = variables to set
  local name="$1"; shift
  local args=() v
  for v in "$@"; do args+=("--$v=$(value "$v")"); done
  # perl's alarm stands in for timeout(1), which macOS lacks: no run may hang
  perl -e 'alarm shift; exec @ARGV' 90 java -jar "$JAR" ${args[@]+"${args[@]}"} > "$LOGS/$name.log" 2>&1 || true
  grep -ho 'Missing environment variables: [A-Z_, ]*' "$LOGS/$name.log" | head -1
}
without() { local skip="$1" v; for v in "${VARS[@]}"; do [[ "$v" == "$skip" ]] || echo "$v"; done; }

echo "== all 26 set"
line="$(run all-set "${VARS[@]}")"
if [[ -z "$line" ]] && grep -qE 'Application run failed|Error starting ApplicationContext' "$LOGS/all-set.log"; then echo "PASS all set: past the check ($(grep -m1 -oE '(Application run failed|Error starting ApplicationContext)' "$LOGS/all-set.log" || echo 'later failure'))"
else echo "FAIL all set: $line"; failures=$((failures + 1)); fi

echo "== none set"
expected="Missing environment variables: $(printf '%s\n' "${VARS[@]}" | sort | paste -sd, - | sed 's/,/, /g')"
line="$(run none-set)"
if [[ "$line" == "$expected" ]]; then echo "PASS none set: one failure naming all 26"
else echo "FAIL none set: '$line'"; failures=$((failures + 1)); fi

echo "== each one left out"
for v in "${VARS[@]}"; do
  # shellcheck disable=SC2046
  line="$(run "without-$v" $(without "$v"))"
  if [[ "$line" == "Missing environment variables: $v" ]]; then echo "PASS without $v"
  else echo "FAIL without $v: '$line'"; failures=$((failures + 1)); fi
done

echo
[[ "$failures" == 0 ]] && { echo "P-19 PASSED (28 runs)"; exit 0; }
echo "P-19: $failures run(s) FAILED"; exit 1
