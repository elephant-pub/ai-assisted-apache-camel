#!/usr/bin/env bash
# Deploy lab (design 006, §3.1 "built"): Trivy findings of an image tarball or of the project's dependencies, counted.
#   deploy/local/lab/scan-summary.sh image target/lab/image-today.tar
#   deploy/local/lab/scan-summary.sh fs
# Writes the JSON report next to the input (target/lab/) and prints counts per target, severity and fixable.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
mode=${1:?usage: scan-summary.sh image <tar> | fs}
mkdir -p "$root/target/lab"
case "$mode" in
  image) tar=${2:?tar path relative to the project}; out="$root/target/lab/$(basename "$tar" .tar)-trivy.json"
         "$root/deploy/local/tool.sh" trivy image --input "/repo/$tar" --scanners vuln --format json --quiet > "$out" ;;
  fs)    out="$root/target/lab/fs-trivy.json"
         "$root/deploy/local/tool.sh" trivy fs --scanners vuln --format json --quiet /repo/pom.xml > "$out" ;;
  jar)   out="$root/target/lab/jar-trivy.json"
         "$root/deploy/local/tool.sh" trivy rootfs --offline-scan --scanners vuln --format json --quiet /repo/target/order-integration-0.1.0.jar > "$out" ;;
  *) echo "unknown mode $mode" >&2; exit 2 ;;
esac
python3 -I - "$out" <<'PY'
import json, sys, collections
r = json.load(open(sys.argv[1]))
print("report:", sys.argv[1])
md = r.get("Metadata", {})
print("os:", md.get("OS"))
tot = collections.Counter()
for res in r.get("Results", []) or []:
    c = collections.Counter()
    for v in res.get("Vulnerabilities", []) or []:
        fixed = "fixable" if v.get("FixedVersion") else "nofix"
        c[(v["Severity"], fixed)] += 1
        tot[(v["Severity"], fixed)] += 1
    if c:
        print(f'{res.get("Type")}\t{res.get("Target")[:70]}\t' + " ".join(f"{s}/{f}={n}" for (s, f), n in sorted(c.items())))
print("TOTAL", " ".join(f"{s}/{f}={n}" for (s, f), n in sorted(tot.items())))
pk = collections.defaultdict(set)
for res in r.get("Results", []) or []:
    for v in res.get("Vulnerabilities", []) or []:
        if v["Severity"] in ("HIGH", "CRITICAL"):
            pk[(v["PkgName"], v.get("InstalledVersion"), v.get("FixedVersion") or "-")].add(v["Severity"][0] + ":" + v["VulnerabilityID"])
print("HIGH/CRITICAL packages:")
for (p, i, f), ids in sorted(pk.items()):
    print(f"  {p} {i} -> {f}  {' '.join(sorted(ids))}")
PY
