# Simulating 50 Jobs (successes and failures)

`scripts/simulate_jobs.py` submits a fixed, repeatable mix of 50 jobs and checks that each one ends the way it should. It needs only Python 3 (standard library) and a running QueuePilot (`scripts/start-demo.ps1`).

```bash
python scripts/simulate_jobs.py            # submit, wait, print a report
python scripts/simulate_jobs.py --chaos    # also interrupt a busy worker after 20 s
python scripts/simulate_jobs.py --no-wait  # submit and watch on the Live page instead
```

## The mix

| Group | Jobs | Expected | Why |
|---|---|---|---|
| Monte Carlo pi | 10 | SUCCEEDED | 10M-150M samples, random priorities |
| Sorting | 8 | SUCCEEDED | 0.5M-4M numbers, merge or heap sort |
| Text analysis | 4 | SUCCEEDED | inline text of growing size |
| Sorting without data | 5 | **FAILED** `MISSING_DATA` | input is valid by schema but unusable, a permanent failure with no retry |
| N-Queens n = 4..12 | 11 | SUCCEEDED | solution counts 2 … 14,200 |
| N-Queens n = 2, 3 with `requireSolution` | 2 | **FAILED** `NO_SOLUTION` | unsolvable boards |
| Workflow "pipeline-ok" | 5 | SUCCEEDED | sort → two pi estimates in parallel → sort → report |
| Workflow "pipeline-broken" | 5 | 1 **FAILED**, 4 **SKIPPED** | first step fails, so the dependants are skipped |
| **Total** | **50** | 38 succeeded, 8 failed, 4 skipped | |

Two more requests are deliberately invalid (unknown task type, value out of range). The API refuses them with HTTP 400, so they never become jobs.

If the N-Queens task is not available (no worker has the example plugin jar), the script registers the manifest when it can, and otherwise replaces those 13 jobs with extra pi jobs.

With `--chaos`, the jobs of one busy worker are interrupted. They are recorded as `INTERRUPTED`, requeued, and still finish as SUCCEEDED, so the report shows them as "(retried)".

The script exits with code 0 when every job ended as expected, and 1 otherwise. That makes it usable as a smoke test.

## The N-Queens task

`n-queens@1.0.0` lives in `queuepilot-example-plugin` (`com.example.nqueens.NQueensTask`). It was added without changing the coordinator, the core or the worker:

1. Implement `TaskExecutor`. Counting uses bitmask backtracking, the search is split into one branch per placement of the first two queens, and progress is reported per branch.
2. Add it to `META-INF/services/com.queuepilot.task.TaskExecutor`, and add the manifest `META-INF/queuepilot/tasks/n-queens.json`.
3. Build the jar, copy it into a worker's plugins folder (`data/plugins-worker-1`) and restart that worker. It then advertises `n-queens@1.0.0`.
4. On the **Tasks** page, paste the manifest under *Register a new task type*, open the new task and click **Enable**.
5. Submit it from **Submit**: `n = 8` returns 92 solutions and the first board; `n = 3` with `requireSolution` fails with `NO_SOLUTION`.

Unit tests check the counts against the known sequence for n = 1…12 (OEIS A000170), check that the first solution is valid, and check the failure case.
