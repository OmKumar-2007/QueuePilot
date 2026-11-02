# Running the Demo

Prerequisite: `./mvnw install -DskipTests`, then `scripts/start-demo.ps1` (Windows) or `scripts/start-demo.sh`. This starts the coordinator and two workers; `worker-1` also has the prime-sieve plugin. Log in as admin (top right) with the password from `data/admin-password.txt`.

| Step | Do | Observe |
|---|---|---|
| 1. Platform | Open **Workers** | Two separate processes with slots, capabilities and supported task versions |
| 2. Submit | **Submit job → Submit demo batch** | Eight real jobs across two queues; progress comes from the computation |
| 3. Policies | **Scheduler** → switch FCFS / Priority+Aging / SJF while jobs queue | The dispatch order table changes; with aging, effective priority grows |
| 4. Crash | While a big job runs, **Workers → Simulate crash** on its worker | The worker's terminal exits (code 137); within ~11 s it shows OFFLINE, the job's history shows attempt 1 ABANDONED and attempt 2 on the other worker |
| 5. Fencing | **Freeze heartbeats** on a busy worker | Lease expires, job retried elsewhere; when the frozen worker finishes, its result is rejected (event `STALE_UPDATE_REJECTED`) |
| 6. Restart | Start the crashed worker again (`java -jar queuepilot-worker/target/queuepilot-worker-1.0.0.jar --name worker-2`) | It registers as a new worker id and takes work |
| 7. Extensibility | **Task registry** → register the prime-sieve manifest → enable → submit a prime-sieve job | Only worker-1 (which has the jar) runs it |
| 8. Workflow | **Workflows → Submit workflow** (template prefilled) | Graph runs ingest → validate → 3 parallel simulations → combine → verify |
| 9. Failure propagation | Submit a workflow whose first step has an impossible input, e.g. sorting with `{}` | First step FAILED, every later step SKIPPED |
| 10. Files | Submit *file-checksum* or *text-analysis* with an uploaded file | Output digest / CSV artifact downloadable from the job drawer |
| 11. Benchmarks | Run the two commands in [benchmark-methodology.md](../testing/benchmark-methodology.md) with `--publish` | **Benchmarks** page shows SIMULATED and MEASURED reports separately |

Stop: close the windows, or `pkill -f queuepilot-` (Git Bash/Linux). Delete `data/` to reset everything.
