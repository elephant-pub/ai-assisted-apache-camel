"""Scores the measurement runs: catalog problems, required options, cost and time. Run after run-all.sh."""
import csv, json, re, subprocess, sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
TASKS = {t["id"]: t for t in json.load(open(HERE / "tasks.json"))}
CP = f"{ROOT}/target/classes:{(ROOT / 'target/cp.txt').read_text().strip()}"


def catalog_problems(files):
    out = subprocess.run(["java", "-cp", CP, "com.trailhead.catalogcheck.RouteCatalogCheck", *map(str, files)],
                         capture_output=True, text=True).stdout
    found = defaultdict(list)
    for line in out.splitlines():
        m = re.match(r"(\S+\.camel\.yaml): (\S+) (.*)", line)
        if m:
            found[m.group(1)].append((m.group(2), m.group(3)))
    return found


def session(jsonl):
    tools, result = [], {}
    for line in open(jsonl):
        m = json.loads(line)
        if m.get("type") == "assistant":
            tools += [c["name"] for c in m["message"]["content"] if c.get("type") == "tool_use"]
        if m.get("type") == "result":
            result = m
    return tools, result


rows = []
for cond in ["memory", "mcp"]:
    d = HERE / "results" / cond
    runs = sorted(d.glob("*.jsonl"))
    problems = catalog_problems(sorted(d.glob("*.camel.yaml")))
    for run in runs:
        name = run.name.removesuffix(".jsonl")
        task_id = name.rsplit("-", 1)[0]
        route = d / f"{name}.camel.yaml"
        text = route.read_text() if route.exists() else ""
        p = problems.get(route.name, [])
        missing = [g[0] for g in TASKS[task_id]["expect"]
                   if not any(re.search(rf"(?<![\w.]){re.escape(o)}\s*[:=]", text) for o in g)]
        tools, res = session(run)
        rows.append({
            "condition": cond, "run": name, "task": task_id, "written": bool(text),
            "invented": sum(k in ("unknown-option", "unknown-component") for k, _ in p),
            "bad_value": sum(k.startswith("invalid-") for k, _ in p),
            "schema": sum(k == "schema" for k, _ in p),
            "missing_required": ";".join(missing),
            "mcp_calls": sum(t.startswith("mcp__") for t in tools),
            "turns": res.get("num_turns", 0), "cost_usd": round(res.get("total_cost_usd", 0), 4),
            "seconds": round(res.get("duration_ms", 0) / 1000),
            "problems": " | ".join(f"{k} {w}" for k, w in p),
        })

with open(HERE / "results" / "runs.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0]))
    w.writeheader()
    w.writerows(rows)

lines = ["| | Memory only | With Camel MCP |", "|---|---|---|"]
def stat(label, fn):
    vals = []
    for cond in ["memory", "mcp"]:
        r = [x for x in rows if x["condition"] == cond]
        vals.append(fn(r))
    lines.append(f"| {label} | {vals[0]} | {vals[1]} |")
stat("Routes written", lambda r: f"{sum(x['written'] for x in r)} of {len(r)}")
stat("Routes with no catalog problems", lambda r: f"{sum(x['written'] and not (x['invented'] or x['bad_value'] or x['schema']) for x in r)}")
stat("Invented options or components", lambda r: sum(x["invented"] for x in r))
stat("Invalid option values", lambda r: sum(x["bad_value"] for x in r))
stat("YAML schema errors", lambda r: sum(x["schema"] for x in r))
stat("Routes missing a required option", lambda r: sum(bool(x["missing_required"]) for x in r))
stat("Routes correct on both counts", lambda r: sum(x["written"] and not (x["invented"] or x["bad_value"] or x["schema"] or x["missing_required"]) for x in r))
stat("Median time per route (s)", lambda r: sorted(x["seconds"] for x in r)[len(r) // 2])
stat("Total cost (USD)", lambda r: f"{sum(x['cost_usd'] for x in r):.2f}")
stat("MCP tool calls per route (mean)", lambda r: f"{sum(x['mcp_calls'] for x in r) / len(r):.1f}")
(HERE / "results" / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
print()
for x in rows:
    if x["problems"] or x["missing_required"]:
        print(x["condition"], x["run"], "|", x["problems"], "| missing:", x["missing_required"])
