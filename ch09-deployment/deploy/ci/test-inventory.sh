#!/usr/bin/env bash
# Design 006 §3.14 T11 / §3.15 job 1: every test class under src/test/java ran, and ran clean.
#   deploy/ci/test-inventory.sh [reports-root]        reports-root defaults to target/ (holds surefire-reports/, failsafe-reports/)
# A test class is a file named *Test.java or *IT.java that holds @Test, @ParameterizedTest or @CitrusTest (support and
# abstract classes without one are not test classes). Each must have TEST-<fqcn>.xml in surefire-reports (*Test) or
# failsafe-reports (*IT) whose root <testsuite> says tests > 0, skipped="0", errors="0", failures="0".
# So a class that surefire/failsafe never picks up (an include pattern that misses it, -DskipITs, -Dtest=…) fails here
# although `verify` passed.
# Reports are not checked for age: run it after a build from a clean target/ (CI: a fresh checkout). Locally, reports
# left by an earlier `verify` under target/ would let a later `-DskipITs` build pass.
# Exit 0: every class has a clean report. Exit 1: one line per class "FAILED inventory: <class> <reason>". Exit 2: usage.
# Reads the root testsuite attributes with xmllint when present, else with grep on the root element.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ $# -le 1 ] || { echo "usage: test-inventory.sh [reports-root]" >&2; exit 2; }
reports=${1:-"$root/target"}
[ -d "$reports" ] || { echo "test-inventory.sh: $reports is not a directory" >&2; exit 2; }
src="$root/src/test/java"

# attr <file> <name>: the value of an attribute of the root <testsuite> element (empty when absent)
if command -v xmllint >/dev/null 2>&1; then
  attr() { xmllint --xpath "string(/testsuite/@$2)" "$1" 2>/dev/null || true; }
else
  attr() {
    grep -o -m1 '<testsuite [^>]*>' "$1" | grep -oE " $2=\"[^\"]*\"" | head -n1 | sed -E 's/^ [a-z]+="([^"]*)"$/\1/' || true
  }
fi

checked=0
failed=0
while IFS= read -r file; do
  grep -qE '@(Test|ParameterizedTest|CitrusTest)\b' "$file" || continue
  rel=${file#"$src"/}
  fqcn=${rel%.java}
  fqcn=${fqcn//\//.}
  case "$fqcn" in
    *IT) dir=failsafe-reports ;;
    *)   dir=surefire-reports ;;
  esac
  checked=$((checked + 1))
  report="$reports/$dir/TEST-$fqcn.xml"
  if [ ! -f "$report" ]; then
    echo "FAILED inventory: $fqcn no report (expected $dir/TEST-$fqcn.xml)"
    failed=$((failed + 1)); continue
  fi
  reason=""
  tests=$(attr "$report" tests)
  case "$tests" in ''|*[!0-9]*) reason="unreadable report (tests='$tests')" ;; 0) reason="no tests ran" ;; esac
  for a in skipped errors failures; do
    n=$(attr "$report" "$a")
    case "$n" in
      ''|*[!0-9]*) reason="${reason:+$reason, }unreadable report ($a='$n')" ;;
      0) ;;
      *) reason="${reason:+$reason, }$a $n" ;;
    esac
  done
  if [ -n "$reason" ]; then
    echo "FAILED inventory: $fqcn $reason"
    failed=$((failed + 1))
  fi
done < <(find "$src" -type f \( -name '*Test.java' -o -name '*IT.java' \) | LC_ALL=C sort)

if [ "$checked" -eq 0 ]; then
  echo "FAILED inventory: no test class found under src/test/java"
  exit 1
fi
if [ "$failed" -gt 0 ]; then
  echo "test inventory: $failed of $checked test classes without a clean report ($reports)"
  exit 1
fi
echo "test inventory: all $checked test classes have a report with 0 skipped, 0 errors, 0 failures"
