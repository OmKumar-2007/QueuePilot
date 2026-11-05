#!/usr/bin/env python3
"""Runs one pool of real jobs through the whole backend, once per scheduling algorithm.

For each of FCFS, Priority with Aging and SJF it starts a fresh coordinator (own port, own empty
database), sets the policy, submits the pool through the HTTP API, then starts real workers, so the
whole pool is waiting before the first job runs. The pool is identical for every algorithm.

It writes raw.csv (one row per job) and summary.json to the output folder.

Only the Python standard library is used. Run from the repository root after building:

    python3 scripts/benchmark_pipeline.py --jobs 10000 --workers 4 --seed 42
"""
import argparse
import csv
import http.client
import json
import math
import random
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

COORDINATOR_JAR = "target/coordinator/queuepilot-coordinator-1.0.0.jar"
WORKER_JAR = "target/worker/queuepilot-worker-1.0.0-exec.jar"
POLICIES = ["FCFS", "PRIORITY_AGING", "SJF"]
TERMINAL = {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"}
SAMPLES_PER_ESTIMATED_MS = 100_000


class Api:
    """Small JSON client that keeps one connection open, because it makes tens of thousands of calls."""

    def __init__(self, port):
        self.port = port
        self.connection = http.client.HTTPConnection("127.0.0.1", port, timeout=60)

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body)
        try:
            self.connection.request(method, path, data, {"Content-Type": "application/json"})
            response = self.connection.getresponse()
            text = response.read()
        except (http.client.HTTPException, OSError):
            self.connection.close()
            raise
        return response.status, json.loads(text) if text else None

    def wait_until_up(self, seconds=90):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                if self.call("GET", "/api/health")[0] == 200:
                    return
            except (http.client.HTTPException, OSError):
                time.sleep(0.5)
        raise SystemExit("Coordinator did not start; see coordinator.log in the output folder")


def build_pool(count, seed):
    """Same mix as the simulator's MIXED scenario: 75% short jobs, 25% long jobs, priorities 1-10."""
    rng = random.Random(seed)
    pool = []
    for index in range(count):
        long_job = rng.random() < 0.25
        samples = rng.randint(4_000_000, 15_000_000) if long_job else rng.randint(200_000, 1_500_000)
        pool.append({"index": index, "size": "long" if long_job else "short", "samples": samples,
                     "priority": rng.randint(1, 10), "estimatedMs": max(1, samples // SAMPLES_PER_ESTIMATED_MS)})
    return pool


def run_policy(policy, pool, args, out_dir):
    work = Path(tempfile.mkdtemp(prefix=f"queuepilot-{policy}-", dir=args.scratch))
    logs = out_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    processes = []

    def start(name, command):
        log = open(logs / f"{policy}-{name}.log", "w", encoding="utf-8")
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    try:
        start("coordinator", ["java", "-Xmx1g", "-jar", COORDINATOR_JAR, f"--server.port={args.port}",
                              f"--queuepilot.data-dir={work}"])
        api = Api(args.port)
        api.wait_until_up()

        status, settings = api.call("PUT", "/api/admin/scheduler", {
            "activePolicy": policy, "agingIntervalSeconds": args.aging_interval_seconds, "agingStep": args.aging_step})
        if status != 200:
            raise SystemExit(f"Could not set policy {policy}: HTTP {status} {settings}")

        print(f"[{policy}] submitting {len(pool)} jobs", flush=True)
        began = time.time()
        ids = []
        for job in pool:
            status, created = api.call("POST", "/api/jobs", {
                "taskType": "monte-carlo-pi", "input": {"samples": job["samples"], "seed": job["index"]},
                "priority": job["priority"], "estimatedMs": job["estimatedMs"], "queue": "benchmark"})
            if status != 201:
                raise SystemExit(f"Submit failed: HTTP {status} {created}")
            ids.append(created["id"])
        submit_seconds = time.time() - began

        print(f"[{policy}] submitted in {submit_seconds:.1f} s; starting {args.workers} workers", flush=True)
        for n in range(args.workers):
            start(f"worker-{n + 1}", ["java", "-Xmx256m", "-jar", WORKER_JAR,
                                      "--coordinator", f"http://127.0.0.1:{args.port}", "--name", f"bench-{n + 1}",
                                      "--slots", "1", "--work-dir", str(work / f"worker-{n + 1}")])

        deadline = time.time() + args.timeout_seconds
        while True:
            _, queued = api.call("GET", "/api/jobs?state=QUEUED&limit=1")
            _, running = api.call("GET", "/api/jobs?state=RUNNING&limit=1")
            if not queued and not running:
                break
            if time.time() > deadline:
                raise SystemExit(f"{policy} did not finish within {args.timeout_seconds} s")
            time.sleep(1)

        print(f"[{policy}] finished; reading {len(ids)} job records", flush=True)
        rows = []
        for job, job_id in zip(pool, ids):
            status, view = api.call("GET", f"/api/jobs/{job_id}")
            if status != 200:
                raise SystemExit(f"Could not read job {job_id}: HTTP {status}")
            rows.append({**job, "policy": policy, "jobId": job_id, "state": view["state"],
                         "attempts": view["attemptsUsed"], "submittedAt": view["submittedAt"],
                         "startedAt": view["startedAt"], "finishedAt": view["finishedAt"]})
        return rows, submit_seconds
    finally:
        for process in reversed(processes):
            process.terminate()
        for process in reversed(processes):
            try:
                process.wait(20)
            except subprocess.TimeoutExpired:
                process.kill()
        shutil.rmtree(work, ignore_errors=True)


def millis(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp() * 1000


def percentile(values, percent):
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, math.ceil(percent / 100 * len(ordered)) - 1))]


def mean(values):
    return sum(values) / len(values) if values else 0


def summarise(rows, workers):
    """Every job was already waiting when work began, so wait is measured from the first job's start."""
    ran = [r for r in rows if r["startedAt"] and r["finishedAt"]]
    start = min(millis(r["startedAt"]) for r in ran)
    for r in ran:
        r["waitMs"] = millis(r["startedAt"]) - start
        r["executionMs"] = millis(r["finishedAt"]) - millis(r["startedAt"])
        r["turnaroundMs"] = millis(r["finishedAt"]) - start
        r["slowdown"] = r["turnaroundMs"] / max(1, r["executionMs"])
    makespan = max(r["turnaroundMs"] for r in ran)
    waits = [r["waitMs"] for r in ran]

    def wait_of(keep):
        return mean([r["waitMs"] for r in ran if keep(r)])

    slowdowns = [r["slowdown"] for r in ran]
    return {
        "jobs": len(rows),
        "succeeded": sum(r["state"] == "SUCCEEDED" for r in rows),
        "retries": sum(max(0, r["attempts"] - 1) for r in rows),
        "makespanMs": makespan,
        "throughputPerSec": len(ran) / (makespan / 1000),
        "utilization": sum(r["executionMs"] for r in ran) / (workers * makespan),
        "avgWaitMs": mean(waits),
        "medianWaitMs": percentile(waits, 50),
        "p95WaitMs": percentile(waits, 95),
        "p99WaitMs": percentile(waits, 99),
        "maxWaitMs": max(waits),
        "avgTurnaroundMs": mean([r["turnaroundMs"] for r in ran]),
        "avgExecutionMs": mean([r["executionMs"] for r in ran]),
        "avgSlowdown": mean(slowdowns),
        "p95Slowdown": percentile(slowdowns, 95),
        "avgWaitShortMs": wait_of(lambda r: r["size"] == "short"),
        "avgWaitLongMs": wait_of(lambda r: r["size"] == "long"),
        "avgWaitHighPriorityMs": wait_of(lambda r: r["priority"] >= 8),
        "avgWaitLowPriorityMs": wait_of(lambda r: r["priority"] <= 3),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jobs", type=int, default=10_000)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--port", type=int, default=18090)
    parser.add_argument("--aging-interval-seconds", type=int, default=5)
    parser.add_argument("--aging-step", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=int, default=3600)
    parser.add_argument("--scratch", default=None, help="folder for the temporary databases")
    parser.add_argument("--out", default="benchmark-results/report-pipeline")
    parser.add_argument("--policies", default=",".join(POLICIES), help="comma-separated, in the order to run them")
    args = parser.parse_args()

    if not Path(COORDINATOR_JAR).exists() or not Path(WORKER_JAR).exists():
        sys.exit("Build first: ./mvnw install -DskipTests")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    pool = build_pool(args.jobs, args.seed)
    summary = {"config": vars(args), "policies": {}}
    all_rows = []
    for policy in args.policies.split(","):
        rows, submit_seconds = run_policy(policy, pool, args, out_dir)
        summary["policies"][policy] = {**summarise(rows, args.workers), "submitSeconds": submit_seconds}
        all_rows += rows
        print(json.dumps({policy: summary["policies"][policy]}, indent=2), flush=True)

    with open(out_dir / "raw.csv", "w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=list(all_rows[0].keys()), extrasaction="ignore")
        writer.writeheader()
        writer.writerows(all_rows)
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Results written to {out_dir}")


if __name__ == "__main__":
    main()
