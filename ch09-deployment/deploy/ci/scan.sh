#!/usr/bin/env bash
# Design 006 D2 / T3: the vulnerability gate. Fails on any HIGH or CRITICAL finding that has a fixed version.
#   deploy/ci/scan.sh jar   target/order-integration-0.1.0.jar      [--baseline <report.json>]
#   deploy/ci/scan.sh image target/image.tar                          [--baseline <report.json>]   (tar from docker save)
#   deploy/ci/scan.sh sbom  target/image.tar > target/scan/sbom.cdx.json   the image's SBOM (CycloneDX JSON) on stdout
# Trivy runs through deploy/local/tool.sh (pinned digest, the project mounted read-only at /repo). The container writes
# nothing under /repo: every report reaches target/scan/ through a stdout redirect of this script.
#   target/scan/<mode>-report.json   full report: all severities, fixed and unfixed, without the ignore file (baseline)
#   target/scan/<mode>-gate.json     the gate's findings: --severity HIGH,CRITICAL --ignore-unfixed, .trivyignore.yaml applied
# Exit 0: no fixable HIGH/CRITICAL. Exit 1: findings, or an ignore entry that breaks the rule of .trivyignore.yaml.
# Exit 2: wrong arguments, or Trivy itself failed (no valid gate report).
# --baseline <report.json>: a report of the last green run; each gate finding that was not a fixable finding there is
# marked NEW (review 8).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
tool="$root/deploy/local/tool.sh"
ignorefile="$root/.trivyignore.yaml"

usage() { echo "usage: scan.sh jar <jar> | image <tar> [--baseline <report.json>] | sbom <tar>" >&2; exit 2; }
[ $# -ge 2 ] || usage
mode=$1; input=$2
shift 2
case "$mode" in jar|image) ;; sbom) [ $# -eq 0 ] || usage ;; *) usage ;; esac
baseline=""
while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) [ $# -ge 2 ] || usage; baseline=$2; shift 2 ;;
    *) usage ;;
  esac
done
[ -f "$input" ] || { echo "scan.sh: $input not found" >&2; exit 2; }
mkdir -p "$root/target/scan"
if [ -n "$baseline" ]; then
  [ -f "$baseline" ] || { echo "scan.sh: baseline $baseline not found" >&2; exit 2; }
  # Copied first: the baseline may be the report this run is about to write (a restored CI artifact)
  cp "$baseline" "$root/target/scan/$mode-baseline.json"
  baseline="$root/target/scan/$mode-baseline.json"
fi

# The input must lie inside the project: the container only sees the project, at /repo.
abs_input="$(cd "$(dirname "$input")" && pwd)/$(basename "$input")"
case "$abs_input" in
  "$root"/*) rel_input=${abs_input#"$root"/} ;;
  *) echo "scan.sh: $input must be inside $root" >&2; exit 2 ;;
esac

# SBOM (§3.15 job 2): the packages of the image, not a gate; the CycloneDX document goes to stdout only.
if [ "$mode" = sbom ]; then
  exec "$tool" trivy image --input "/repo/$rel_input" --format cyclonedx --quiet --config "" \
    --skip-version-check --disable-telemetry
fi

# 1. The ignore file's entry rule (§3.3): the package (purls or paths), a reason and who accepted it (statement), an
#    expiry at most 90 days out. Only the layout of the file's header is accepted; any other line fails, so that no
#    entry Trivy would apply can skip this check.
python3 -I - "$ignorefile" <<'PY'
import datetime, re, sys
path = sys.argv[1]
try:
    lines = open(path, encoding="utf-8").read().splitlines()
except FileNotFoundError:
    print(f"FAILED ignore: {path} not found"); sys.exit(1)
today = datetime.date.today()
entries, cur, in_list, bad = [], None, None, 0
for n, line in enumerate(lines, 1):
    if not line.strip() or re.match(r"^\s*#", line):
        continue
    if re.match(r"^vulnerabilities:\s*(\[\]\s*)?$", line):
        in_list = None; continue
    m = re.match(r"^  - id:\s*['\"]?([A-Za-z0-9._:-]+)['\"]?\s*$", line)
    if m:
        cur = {"id": m.group(1)}; entries.append(cur); in_list = None; continue
    m = re.match(r"^    (statement|expired_at):\s*['\"]?(.*?)['\"]?\s*$", line)
    if m and cur is not None:
        cur[m.group(1)] = m.group(2); in_list = None; continue
    m = re.match(r"^    (purls|paths):\s*(\[(.*)\])?\s*$", line)
    if m and cur is not None:
        if m.group(2) is not None and m.group(3).strip():
            cur["package"] = True; in_list = None
        else:
            in_list = m.group(1)
        continue
    if in_list and cur is not None and re.match(r"^      - \S", line):
        cur["package"] = True; continue
    print(f"FAILED ignore: {path} line {n} not recognised (use the layout of the header)"); bad += 1
for e in entries:
    exp = e.get("expired_at", "")
    try:
        date = datetime.date.fromisoformat(exp)
    except ValueError:
        print(f"FAILED ignore: {e['id']} has no valid expired_at (YYYY-MM-DD)"); bad += 1; date = None
    if date is not None and date <= today:
        print(f"FAILED ignore: {e['id']} expired {exp}"); bad += 1
    elif date is not None and date > today + datetime.timedelta(days=90):
        print(f"FAILED ignore: {e['id']} expired {exp} (more than 90 days ahead)"); bad += 1
    if not e.get("statement"):
        print(f"FAILED ignore: {e['id']} has no statement (reason and who accepted it)"); bad += 1
    if not e.get("package"):
        print(f"FAILED ignore: {e['id']} has no package (purls or paths)"); bad += 1
sys.exit(1 if bad else 0)
PY

# 2. What Trivy scans.
case "$mode" in
  jar)
    rootfs="$root/target/scan/jar-rootfs"
    rm -rf "$rootfs"
    mkdir -p "$rootfs"
    unzip -q "$abs_input" 'BOOT-INF/lib/*' -d "$rootfs"
    target=(rootfs --offline-scan /repo/target/scan/jar-rootfs) ;;
  image)
    target=(image --input "/repo/$rel_input") ;;
esac
report="$root/target/scan/$mode-report.json"
gate="$root/target/scan/$mode-gate.json"
# No trivy.yaml from the project, no version check, no telemetry: the only network use is the vulnerability database
common=(--scanners vuln --format json --quiet --config "" --skip-version-check --disable-telemetry)

# 3. Full report (all severities, fixed and unfixed), then the gate run with the threshold of D2, on the same database.
"$tool" trivy "${target[@]}" "${common[@]}" > "$report"
status=0
"$tool" trivy "${target[@]}" "${common[@]}" --skip-db-update --skip-java-db-update \
  --severity HIGH,CRITICAL --ignore-unfixed --ignorefile /repo/.trivyignore.yaml --exit-code 1 > "$gate" || status=$?

# 4. The table: id, package, installed, fixed, severity; NEW when not a fixable finding of the baseline.
python3 -I - "$mode" "$report" "$gate" "$baseline" "$status" <<'PY'
import collections, json, sys
mode, report, gate, baseline, status = sys.argv[1:6]
def load(path):
    try:
        return json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        print(f"scan.sh {mode}: trivy failed (exit code {status}), {path} is not a valid report")
        sys.exit(2)
def vulns(data):
    for res in data.get("Results") or []:
        for v in res.get("Vulnerabilities") or []:
            yield v
full, found = load(report), load(gate)
counts = collections.Counter()
for v in vulns(full):
    counts[(v["Severity"], "fixable" if v.get("FixedVersion") else "no fix")] += 1
print(f"scan.sh {mode}: all findings " + (", ".join(f"{s} {f} {n}" for (s, f), n in sorted(counts.items())) or "none"))
known = set()
if baseline:
    known = {(v["VulnerabilityID"], v["PkgName"]) for v in vulns(load(baseline)) if v.get("FixedVersion")}
rows = sorted({(v["VulnerabilityID"], v["PkgName"], v.get("InstalledVersion", ""), v.get("FixedVersion", ""), v["Severity"])
               for v in vulns(found)}, key=lambda r: (r[4] != "CRITICAL", r[1], r[0]))
if not rows:
    if status != "0":
        print(f"scan.sh {mode}: trivy exited {status} without findings"); sys.exit(2)
    print(f"scan.sh {mode}: no fixable HIGH/CRITICAL finding")
    sys.exit(0)
print(f"scan.sh {mode}: {len(rows)} fixable HIGH/CRITICAL finding(s)")
print("| id | package | installed | fixed | severity |" + (" new |" if baseline else ""))
print("|---|---|---|---|---|" + ("---|" if baseline else ""))
for r in rows:
    new = (" NEW |" if (r[0], r[1]) not in known else " |") if baseline else ""
    print("| " + " | ".join(r) + " |" + new)
sys.exit(1)
PY
