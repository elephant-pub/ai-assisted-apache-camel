#!/usr/bin/env bash
# Design 006 §3.15 job 1 (and T9): the build's test results as Markdown, for $GITHUB_STEP_SUMMARY.
#   deploy/ci/test-summary.sh [reports-root] >> "$GITHUB_STEP_SUMMARY"     reports-root defaults to target/
# Prints: the totals per report folder; every failed or errored test with its assertion message; the Time elapsed of
# every integration test class (failsafe), slowest first, for the build-time decision of §3.15.
# A reporting step: it always exits 0 once its arguments are valid, also when reports are missing (the build failed
# early). The gate's verdict comes from the build and from test-inventory.sh, not from here.
# Reads the reports with xmllint when present, else with grep (then test names of failures are not shown).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ $# -le 1 ] || { echo "usage: test-summary.sh [reports-root]" >&2; exit 2; }
reports=${1:-"$root/target"}
maxlen=600   # characters of one message in the table

if command -v xmllint >/dev/null 2>&1; then have_xmllint=1; else have_xmllint=0; fi

attr() {   # attr <file> <name>: an attribute of the root <testsuite>
  if [ "$have_xmllint" = 1 ]; then
    xmllint --xpath "string(/testsuite/@$2)" "$1" 2>/dev/null || true
  else
    grep -o -m1 '<testsuite [^>]*>' "$1" | grep -oE " $2=\"[^\"]*\"" | head -n1 | sed -E 's/^ [a-zA-Z]+="([^"]*)"$/\1/' || true
  fi
}
num() { case "$1" in ''|*[!0-9]*) echo 0 ;; *) echo "$1" ;; esac; }
# cell: one table cell: entities decoded (grep path), newlines and pipes made safe, cut to $maxlen
cell() {
  local s
  s=$(printf '%s' "$1" | tr '\r\n\t' '   ' | sed -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' \
        -e 's/&#10;/ /g' -e 's/&#13;/ /g' -e 's/&amp;/\&/g' -e 's/|/\\|/g' -e 's/  */ /g')
  if [ "${#s}" -gt "$maxlen" ]; then s="${s:0:$maxlen}…"; fi
  printf '%s' "$s"
}

echo "## Tests"
echo
files_found=0
for dir in surefire-reports failsafe-reports; do
  ls "$reports/$dir"/TEST-*.xml >/dev/null 2>&1 && files_found=1
done
if [ "$files_found" = 0 ]; then
  echo "No test report under \`$reports\` (surefire-reports, failsafe-reports): the build stopped before the tests."
  exit 0
fi

echo "| Reports | Classes | Tests | Failures | Errors | Skipped | Flakes |"
echo "|---|---|---|---|---|---|---|"
for dir in surefire-reports failsafe-reports; do
  c=0 t=0 f=0 e=0 s=0 k=0
  for x in "$reports/$dir"/TEST-*.xml; do
    [ -f "$x" ] || continue
    c=$((c + 1))
    t=$((t + $(num "$(attr "$x" tests)")))
    f=$((f + $(num "$(attr "$x" failures)")))
    e=$((e + $(num "$(attr "$x" errors)")))
    s=$((s + $(num "$(attr "$x" skipped)")))
    k=$((k + $(num "$(attr "$x" flakes)")))
  done
  echo "| $dir | $c | $t | $f | $e | $s | $k |"
done
echo

echo "### Failed tests"
echo
rows=""
for dir in surefire-reports failsafe-reports; do
  for x in "$reports/$dir"/TEST-*.xml; do
    [ -f "$x" ] || continue
    bad=$(( $(num "$(attr "$x" failures)") + $(num "$(attr "$x" errors)") ))
    [ "$bad" -gt 0 ] || continue
    suite=$(attr "$x" name)
    if [ "$have_xmllint" = 1 ]; then
      n=$(xmllint --xpath 'count(//testcase[failure or error])' "$x" 2>/dev/null || echo 0)
      i=1
      while [ "$i" -le "${n%.*}" ]; do
        tc="(//testcase[failure or error])[$i]"
        name=$(xmllint --xpath "string($tc/@name)" "$x" 2>/dev/null || true)
        kind=$(xmllint --xpath "local-name($tc/*[self::failure or self::error][1])" "$x" 2>/dev/null || true)
        type=$(xmllint --xpath "string($tc/*[self::failure or self::error][1]/@type)" "$x" 2>/dev/null || true)
        msg=$(xmllint --xpath "string($tc/*[self::failure or self::error][1]/@message)" "$x" 2>/dev/null || true)
        rows+="| \`${suite##*.}\` | \`$(cell "$name")\` | $kind \`$(cell "${type##*.}")\` | $(cell "$msg") |"$'\n'
        i=$((i + 1))
      done
    else
      while IFS= read -r el; do
        kind=${el%% *}; kind=${kind#<}
        type=$(printf '%s' "$el" | grep -oE ' type="[^"]*"' | sed -E 's/^ type="(.*)"$/\1/' || true)
        msg=$(printf '%s' "$el" | grep -oE ' message="[^"]*"' | sed -E 's/^ message="(.*)"$/\1/' || true)
        rows+="| \`${suite##*.}\` | (name: needs xmllint) | $kind \`$(cell "${type##*.}")\` | $(cell "$msg") |"$'\n'
      done < <(grep -oE '<(failure|error)( [^>]*)?>' "$x" || true)
    fi
  done
done
if [ -n "$rows" ]; then
  echo "| Class | Test | Kind | Message |"
  echo "|---|---|---|---|"
  printf '%s' "$rows"
  echo
  echo "The integration process's log of a failed IT is under \`target/spec-tests/\` (uploaded with the reports)."
else
  echo "None."
fi
echo

echo "### Integration tests: Time elapsed (slowest first)"
echo
its=""
for x in "$reports/failsafe-reports"/TEST-*.xml; do
  [ -f "$x" ] || continue
  its+="$(attr "$x" time) $(attr "$x" name) $(num "$(attr "$x" tests)")"$'\n'
done
if [ -n "$its" ]; then
  echo "| IT | Tests | Time elapsed (s) |"
  echo "|---|---|---|"
  printf '%s' "$its" | LC_ALL=C sort -k1,1 -g -r | while read -r time name tests; do
    echo "| \`${name##*.}\` | $tests | $time |"
  done
else
  echo "No failsafe report (\`-DskipITs\`, or the build stopped before the integration tests)."
fi
