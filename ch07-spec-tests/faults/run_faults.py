"""Runs ./mvnw verify on a clean copy of the project once without a fault and once per fault; prints which tests fail.
   python3 run_faults.py <project> [fault ...]"""
import glob, json, os, re, shutil, subprocess, sys, time
from faults import FAULTS

here = os.path.dirname(os.path.abspath(__file__))
project = os.path.abspath(sys.argv[1])
names = sys.argv[2:] or ["baseline"] + list(FAULTS)
results = {}
for name in names:
    work = os.path.join(here, "work", name)
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(project, work, ignore=shutil.ignore_patterns(".git", "target", "sessions"))
    if name != "baseline":
        path, old, new, _ = FAULTS[name]
        text = open(os.path.join(work, path)).read()
        assert text.count(old) == 1, name
        open(os.path.join(work, path), "w").write(text.replace(old, new))
    start = time.time()
    run = subprocess.run(["./mvnw", "-q", "-B", "verify", "-Dmaven.test.failure.ignore=true"], cwd=work, capture_output=True, text=True)
    failed = set()
    for report in glob.glob(os.path.join(work, "target", "*-reports", "*.txt")):
        body = open(report).read()
        if re.search(r"(Failures|Errors): [1-9]", body):
            failed.add(os.path.basename(report)[:-4].split(".")[-1])
    results[name] = {"exit": run.returncode, "failed": sorted(failed), "seconds": round(time.time() - start)}
    open(os.path.join(work, "verify.log"), "w").write(run.stdout + run.stderr)
    print(name, json.dumps(results[name]), flush=True)
json.dump(results, open(os.path.join(here, "results-%d.json" % int(time.time())), "w"), indent=1)
