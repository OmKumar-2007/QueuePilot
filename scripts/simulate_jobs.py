#!/usr/bin/env python3
"""Submit a realistic mix of 50 jobs to QueuePilot and report what happened.

The mix contains jobs that should succeed and jobs that should fail, so the dashboard shows both:

  * successes: Monte Carlo pi, sorting, text analysis, N-Queens (if registered), and a 5-step workflow
  * failures:  sorting without data (MISSING_DATA), N-Queens on an unsolvable board (NO_SOLUTION),
               and a 5-step workflow whose first step fails, so the remaining steps are SKIPPED
  * rejected:  two invalid requests that the API refuses before they become jobs (not counted in the 50)

Optional --chaos interrupts a busy worker part-way through: its jobs are retried and still succeed.

Only the Python standard library is used. Usage (from the repository root, with QueuePilot running):

    python scripts/simulate_jobs.py
    python scripts/simulate_jobs.py --chaos --seed 7
    python scripts/simulate_jobs.py --no-wait
"""
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

TERMINAL = {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"}
FAILED_STATES = {"FAILED", "SKIPPED", "CANCELLED"}
NQUEENS_MANIFEST = Path(__file__).resolve().parent.parent / \
    "queuepilot-example-plugin/src/main/resources/META-INF/queuepilot/tasks/n-queens.json"


class Api:
    def __init__(self, base):
        # On Windows "localhost" resolves to IPv6 first; the coordinator listens on IPv4 127.0.0.1 only,
        # so every request would first wait for the IPv6 attempt to fail (about 2 s).
        self.base = base.rstrip("/").replace("://localhost", "://127.0.0.1")

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                text = response.read()
                return response.status, json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            text = error.read()
            return error.code, json.loads(text) if text else None


def ensure_nqueens(api):
    """Registers and enables n-queens if its manifest is available and a worker can run it."""
    _, tasks = api.call("GET", "/api/tasks")
    task = next((t for t in tasks if t["taskType"] == "n-queens"), None)
    if task is None and NQUEENS_MANIFEST.exists():
        status, task = api.call("POST", "/api/admin/tasks", json.loads(NQUEENS_MANIFEST.read_text(encoding="utf-8")))
        if status != 201:
            return False
    if task is None:
        return False
    if not task["enabled"]:
        api.call("POST", f"/api/admin/tasks/{task['id']}/enable")
    _, workers = api.call("GET", "/api/workers")
    return any(w["status"] == "ONLINE" and "n-queens@1.0.0" in w["supportedTasks"] for w in workers)


def single_jobs(rng, with_nqueens):
    """40 independent jobs: (name, request, expected state, note)."""
    jobs = []
    words = "QueuePilot schedules jobs. Workers execute jobs! Do workers fail? Recovery retries jobs on another worker."
    for i in range(10):
        samples = rng.choice([10, 20, 40, 80, 150]) * 1_000_000
        jobs.append((f"pi-{i + 1}", {"taskType": "monte-carlo-pi", "input": {"samples": samples, "seed": i},
                                     "priority": rng.randint(1, 10), "queue": "analytics", "estimatedMs": samples // 10_000},
                     "SUCCEEDED", f"{samples // 1_000_000}M samples"))
    for i in range(8):
        count = rng.choice([500_000, 1_000_000, 2_000_000, 4_000_000])
        algorithm = rng.choice(["MERGE", "HEAP"])
        jobs.append((f"sort-{i + 1}", {"taskType": "sorting", "input": {"count": count, "algorithm": algorithm},
                                       "priority": rng.randint(1, 10), "queue": "batch", "estimatedMs": count // 400},
                     "SUCCEEDED", f"{count:,} numbers, {algorithm}"))
    for i in range(4):
        jobs.append((f"text-{i + 1}", {"taskType": "text-analysis", "input": {"text": (words + " ") * (i + 1) * 20, "topN": 3},
                                       "priority": rng.randint(1, 10), "queue": "batch"},
                     "SUCCEEDED", "inline text"))
    # Deliberate failures: input that passes the schema but cannot be processed (permanent, no retry).
    for i in range(5):
        jobs.append((f"sort-missing-data-{i + 1}", {"taskType": "sorting", "input": {}, "priority": rng.randint(1, 10), "queue": "batch"},
                     "FAILED", "no numbers and no count -> MISSING_DATA"))
    if with_nqueens:
        for n in [4, 5, 6, 7, 8, 9, 10, 11, 12, 6, 8]:
            jobs.append((f"queens-{n}", {"taskType": "n-queens", "input": {"n": n}, "priority": rng.randint(1, 10), "queue": "puzzles"},
                         "SUCCEEDED", f"{n}x{n} board"))
        for n in [2, 3]:
            jobs.append((f"queens-{n}-unsolvable", {"taskType": "n-queens", "input": {"n": n, "requireSolution": True}, "queue": "puzzles"},
                         "FAILED", "no solution exists -> NO_SOLUTION"))
    else:
        for i in range(13):
            jobs.append((f"pi-extra-{i + 1}", {"taskType": "monte-carlo-pi", "input": {"samples": 20_000_000}, "queue": "analytics"},
                         "SUCCEEDED", "20M samples"))
    return jobs


def workflow(name, failing):
    """Five steps: a -> (b, c) -> d -> e. If the first step fails, the four others are SKIPPED."""
    first_input = {} if failing else {"count": 1_000_000}
    steps = [
        {"key": "ingest", "taskType": "sorting", "input": first_input, "dependsOn": []},
        {"key": "estimate_a", "taskType": "monte-carlo-pi", "input": {"samples": 30_000_000, "seed": 1}, "dependsOn": ["ingest"]},
        {"key": "estimate_b", "taskType": "monte-carlo-pi", "input": {"samples": 30_000_000, "seed": 2}, "dependsOn": ["ingest"]},
        {"key": "combine", "taskType": "sorting", "input": {"count": 500_000}, "dependsOn": ["estimate_a", "estimate_b"]},
        {"key": "report", "taskType": "text-analysis", "input": {"text": "Pipeline finished. Results combined."}, "dependsOn": ["combine"]},
    ]
    expected = {"ingest": "FAILED" if failing else "SUCCEEDED"}
    for step in steps[1:]:
        expected[step["key"]] = "SKIPPED" if failing else "SUCCEEDED"
    return {"name": name, "queue": "pipelines", "steps": steps}, expected


def submit_all(api, with_nqueens, rng):
    tracked = []  # dicts: id, name, task, expected, note
    for name, request, expected, note in single_jobs(rng, with_nqueens):
        status, body = api.call("POST", "/api/jobs", request)
        if status != 201:
            print(f"  ! {name} was rejected: {status} {body}")
            continue
        tracked.append({"id": body["id"], "name": name, "task": request["taskType"], "expected": expected, "note": note})
    for wf_name, failing in [("pipeline-ok", False), ("pipeline-broken", True)]:
        request, expected = workflow(wf_name, failing)
        status, body = api.call("POST", "/api/workflows", request)
        if status != 201:
            print(f"  ! workflow {wf_name} was rejected: {status} {body}")
            continue
        for node in body["nodes"]:
            note = "first step fails -> later steps skipped" if failing else "workflow step"
            tracked.append({"id": node["jobId"], "name": f"{wf_name}/{node['step']}", "task": node["task"],
                            "expected": expected[node["step"]], "note": note})
    # Two requests the API must refuse before they become jobs.
    rejected = [
        ("unknown task type", {"taskType": "teleport", "input": {}}),
        ("value out of range", {"taskType": "monte-carlo-pi", "input": {"samples": 5}}),
    ]
    rejections = []
    for label, request in rejected:
        status, body = api.call("POST", "/api/jobs", request)
        detail = (body or {}).get("fieldErrors") or [{"field": "-", "message": (body or {}).get("message")}]
        rejections.append((label, status, f"{detail[0]['field']}: {detail[0]['message']}"))
    return tracked, rejections


def interrupt_busy_worker(api):
    _, workers = api.call("GET", "/api/workers")
    busy = [w for w in workers if w["status"] == "ONLINE" and w["runningAttempts"] > 0]
    if not busy:
        return None
    worker = max(busy, key=lambda w: w["runningAttempts"])
    api.call("POST", f"/api/admin/workers/{worker['id']}/interrupt")
    return worker["name"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--coordinator", default="http://127.0.0.1:8080")
    parser.add_argument("--seed", type=int, default=42, help="makes the job mix repeatable")
    parser.add_argument("--chaos", action="store_true", help="interrupt a busy worker after --chaos-after seconds")
    parser.add_argument("--chaos-after", type=float, default=20)
    parser.add_argument("--timeout", type=float, default=900, help="seconds to wait for all jobs")
    parser.add_argument("--no-wait", action="store_true", help="submit and exit without waiting")
    args = parser.parse_args()

    api = Api(args.coordinator)
    try:
        api.call("GET", "/api/health")
    except OSError as error:
        sys.exit(f"Coordinator not reachable at {args.coordinator}: {error}")
    _, workers = api.call("GET", "/api/workers")
    online = [w for w in workers if w["status"] == "ONLINE"]
    if not online:
        sys.exit("No worker is online. Start some (scripts/start-demo.ps1) or click '+ Add worker'.")
    with_nqueens = ensure_nqueens(api)
    print(f"{len(online)} workers online ({sum(w['concurrencyLimit'] for w in online)} slots); "
          f"n-queens {'available' if with_nqueens else 'not available (using extra pi jobs instead)'}")

    tracked, rejections = submit_all(api, with_nqueens, random.Random(args.seed))
    print(f"Submitted {len(tracked)} jobs; {len(rejections)} invalid requests were refused as expected:")
    for label, status, detail in rejections:
        print(f"  - {label}: HTTP {status} ({detail})")
    if args.no_wait:
        print("Not waiting. Watch the Live page.")
        return

    started = time.time()
    interrupted = None
    jobs = {}
    while True:
        jobs = {t["id"]: api.call("GET", f"/api/jobs/{t['id']}")[1] for t in tracked}
        done = sum(1 for j in jobs.values() if j["state"] in TERMINAL)
        running = sum(1 for j in jobs.values() if j["state"] == "RUNNING")
        elapsed = time.time() - started
        if args.chaos and interrupted is None and elapsed >= args.chaos_after:
            interrupted = interrupt_busy_worker(api)
            if interrupted:
                print(f"\n  >> chaos: interrupted the running jobs of {interrupted}; they will be retried")
            else:
                print("\n  >> chaos: no busy worker right now, trying again in 2 s")
        print(f"\r  {elapsed:5.0f}s  finished {done}/{len(tracked)}  running {running}   ", end="", flush=True)
        if done == len(tracked) or elapsed > args.timeout:
            break
        time.sleep(2)
    print()

    _, all_workers = api.call("GET", "/api/workers")
    worker_names = {w["id"]: w["name"] for w in all_workers}
    mismatches = 0
    print(f"\n{'job':30} {'task':15} {'expected':10} {'actual':10} {'tries':>5}  detail")
    print("-" * 100)
    for t in tracked:
        job = jobs[t["id"]]
        actual = job["state"]
        ok = actual == t["expected"]
        mismatches += 0 if ok else 1
        detail = (job.get("error") or "").split(":")[0] if actual in FAILED_STATES else summarize(job)
        retried = " (retried)" if job["attemptsUsed"] > 1 else ""
        print(f"{t['name'][:30]:30} {t['task'][:15]:15} {t['expected']:10} {actual:10}{'' if ok else ' !'} "
              f"{job['attemptsUsed']:>4}  {detail or ''}{retried}")

    counts = {}
    for job in jobs.values():
        counts[job["state"]] = counts.get(job["state"], 0) + 1
    retried_jobs = sum(1 for j in jobs.values() if j["attemptsUsed"] > 1)
    print("-" * 100)
    print("Outcome: " + ", ".join(f"{state} {n}" for state, n in sorted(counts.items())) +
          f" | retried {retried_jobs} | took {time.time() - started:.0f}s")
    print("All jobs ended as expected." if mismatches == 0 else f"{mismatches} job(s) did not end as expected.")
    sys.exit(0 if mismatches == 0 else 1)


def summarize(job):
    out = job.get("output") or {}
    if "estimate" in out:
        return f"pi ~ {out['estimate']:.5f}"
    if "solutionCount" in out:
        return f"{out['solutionCount']} solutions"
    if "verifiedSorted" in out:
        return f"sorted {out['count']:,}"
    if "wordCount" in out:
        return f"{out['wordCount']} words"
    return ""


if __name__ == "__main__":
    main()
