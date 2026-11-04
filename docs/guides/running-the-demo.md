# Running the Demo

Prerequisite: `./mvnw install -DskipTests`, then `scripts/start-demo.ps1` (Windows) or `scripts/start-demo.sh`. This starts the coordinator and four workers (2 slots each); `worker-1` also has the prime-sieve plugin. Workers run with **demo pacing** (`--pace-ms 150`): they wait 150 ms per percent of progress, so a job takes about 15-30 s. The real work is unchanged and every paced job's output contains `demoPacingMsPerPercent`. No login is needed.

| Step | Do | Observe |
|---|---|---|
| 1. Platform | Open **Live** | Four worker processes, each slot showing its current job and live progress |
| 2. Submit | **Run demo jobs** | Twelve real jobs across two queues; eight run at once, the rest appear under *Up next* |
| 3. Policies | Switch **FCFS / Priority / SJF** at the top while jobs queue | *Up next* reorders immediately |
| 4. Pause | **Pause** on a busy worker | Its jobs hold at their current percentage, still RUNNING (heartbeats keep the leases); **Resume** continues them |
| 5. Interrupt | **Interrupt** on a busy worker | Its jobs stop, are recorded as INTERRUPTED and go back to the queue; another slot picks them up (attempt 2) |
| 6. Freeze | **Freeze** on a busy worker | Heartbeats stop for 20 s: after ~10 s it is OFFLINE and its jobs move to other workers; then it thaws and re-registers by itself |
| 7. Crash | **Crash** on a busy worker | The process exits (code 137); its jobs are recovered by the other workers |
| 8. Scale | **+ Add worker** | A new worker process starts and takes work within seconds |
| 9. Extensibility | **Task registry** → register the prime-sieve manifest → enable → submit a prime-sieve job | Only worker-1 (which has the jar) runs it |
| 10. Workflow | **Workflows → Submit workflow** (template prefilled) | Graph runs ingest → validate → 3 parallel simulations → combine → verify |
| 11. Failure propagation | Submit a workflow whose first step has an impossible input, e.g. sorting with `{}` | First step FAILED, every later step SKIPPED |
| 12. Files | Submit *file-checksum* or *text-analysis* with an uploaded file | Output digest / CSV artifact downloadable from the job drawer |
| 13. Benchmarks | Run the two commands in [benchmark-methodology.md](../testing/benchmark-methodology.md) | Results are written to `benchmark-results/`; they are not shown on the dashboard |

**Load test:** `python scripts/simulate_jobs.py --chaos` submits 50 jobs (38 succeed, 8 fail, 4 are skipped) and checks every outcome. See [simulating-50-jobs.md](simulating-50-jobs.md).

**Clear history** on the **Live** page permanently deletes all logs and finished jobs, except finished jobs that unfinished jobs or workflows still need.

Stop: close the windows, or `pkill -f queuepilot-` (Git Bash/Linux). Delete `data/` to reset everything.
