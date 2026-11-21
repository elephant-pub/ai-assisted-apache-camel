"""Runs each chapter 9 fault through the real CI gate: one branch and one pull request per fault, from main.
   python3 run_faults.py push <repo-dir> <owner/repo> [fault ...]     # branches + PRs (never merged)
   python3 run_faults.py collect <owner/repo> [fault ...]             # per fault: conclusion, failed jobs, FAILED lines
A fault is caught when the gate's run fails."""
import json, subprocess, sys
from faults import FAULTS
def sh(*a, cwd=None): return subprocess.run(a, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()
cmd = sys.argv[1]
if cmd == "push":
    d, repo = sys.argv[2], sys.argv[3]; names = sys.argv[4:] or list(FAULTS)
    git = ["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential"]
    for n in names:
        path, old, new, what = FAULTS[n]
        sh("git", "checkout", "-q", "main", cwd=d); sh("git", "checkout", "-q", "-B", "fault/" + n, cwd=d)
        t = open(f"{d}/{path}").read(); assert t.count(old) == 1, n
        open(f"{d}/{path}", "w").write(t.replace(old, new))
        sh("git", "commit", "-qam", f"Fault {n}: {what}", cwd=d)
        sh(*git, "push", "-q", "-f", "origin", "fault/" + n, cwd=d)
        print(n, sh("gh", "pr", "create", "-R", repo, "--base", "main", "--head", "fault/" + n, "--title",
                    f"[fault, do not merge] {n}", "--body", f"Planted fault: {what}. Chapter 9 fault injection; never merged."))
    sh("git", "checkout", "-q", "main", cwd=d)
else:
    repo = sys.argv[2]; names = sys.argv[3:] or list(FAULTS); out = {}
    for n in names:
        runs = json.loads(sh("gh", "run", "list", "-R", repo, "--branch", "fault/" + n, "--limit", "1", "--json",
                             "databaseId,status,conclusion"))
        if not runs: out[n] = {"run": None}; continue
        r = runs[0]; jobs = json.loads(sh("gh", "run", "view", str(r["databaseId"]), "-R", repo, "--json", "jobs"))["jobs"]
        failed = [j["name"] for j in jobs if j["conclusion"] == "failure"]
        lines = []
        if failed:
            log = subprocess.run(["gh", "run", "view", str(r["databaseId"]), "-R", repo, "--log-failed"],
                                 capture_output=True, text=True).stdout
            lines = sorted({l.split("Z ", 1)[-1][:200] for l in log.splitlines()
                            if "FAILED" in l or "Tests run:" in l and ("Failures: 1" in l or "Errors: 1" in l) or "##[error]" in l})[:6]
        out[n] = {"run": r["databaseId"], "status": r["status"], "conclusion": r["conclusion"],
                  "caught": r["conclusion"] == "failure", "failed_jobs": failed, "lines": lines}
        print(n, json.dumps(out[n]))
