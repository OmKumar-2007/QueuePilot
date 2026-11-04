# Benchmark Methodology

Benchmarks live in the separate `queuepilot-benchmarks` module. The coordinator never runs them, and the dashboard does not show them; results are read from the output folder. `--publish` still stores a report through the coordinator API. Each run writes a folder under `benchmark-results/` with `config.json`, `environment.json`, `summary.json`, `raw.csv` and `summary.md`.

## A. Scheduling algorithm benchmark (SIMULATED)

```bash
java -jar queuepilot-benchmarks/target/queuepilot-benchmarks-1.0.0.jar simulate --jobs 200 --workers 4 --seed 42 --seeds 5
```

- **Engine:** discrete-event simulation with a virtual clock and N identical, non-preemptive workers. At every decision the ready jobs are ordered by **the same `SchedulingPolicy` classes the coordinator uses**.
- **Workloads:** generated from a seed, so every policy sees exactly the same jobs.
  - `MIXED`: Poisson arrivals (mean 600 ms), 75 % short (0.2-1.5 s), 25 % long (4-15 s), priorities 1-10.
  - `LONG_FIRST`: long jobs arrive just before many short ones (convoy effect).
  - `PRIORITY_SKEW`: overloaded; a few early low-priority long jobs, a steady stream of high-priority short jobs (starvation).
- **Repetitions:** several seeds per scenario; the summary reports means across seeds (plus the spread of average wait).
- **Metrics:** average waiting time, average turnaround, p95 wait, **max wait (starvation indicator)**, makespan, throughput, utilization. Formulas are shared with the dashboard (`MetricsCalculator`).
- **Limitation:** durations are declared inputs, so this compares *ordering decisions*, not real execution speed. Reports are labelled SIMULATED.

Example result on this machine (200 jobs, 4 workers, 5 seeds, `PRIORITY_SKEW`): SJF had the lowest average wait (≈ 6.2 s vs ≈ 19.6 s for Priority+Aging) but the highest max wait (≈ 59 s vs ≈ 31 s): exactly the textbook trade-off.

## B. Real execution benchmark (MEASURED)

```bash
java -jar queuepilot-benchmarks/target/queuepilot-benchmarks-1.0.0.jar execute \
     --worker-jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar \
     --worker-counts 1,2,4 --slots 1 \
     --workloads "monte-carlo-pi:samples=40000000;sorting:count=2000000" \
     --jobs 8 --warmup 1 --reps 3 --publish
```

- Starts the requested number of real worker processes per configuration (or uses existing ones when `--worker-jar` is omitted; other online workers should be drained first so they do not take benchmark jobs).
- For each workload: one warm-up batch (discarded, lets the JIT compile), then the measured repetitions. Each batch uses its own queue.
- Timestamps come from the coordinator (submitted, started, finished), so execution time includes only the task, while turnaround includes queueing and dispatch.
- Reported: median and p95 of per-run medians, throughput, utilization (busy time ÷ slots × makespan), success rate, retries, machine-wide CPU load and memory (sampled every 250 ms with `OperatingSystemMXBean`, because all processes are local).

Results from this machine (Windows 11, 4 logical CPUs, Java 21.0.13), 8 jobs per run, 3 runs:

| Workers × slots | monte-carlo-pi 40M: median exec | throughput | sorting 2M: median exec | throughput |
|---|---|---|---|---|
| 1 × 1 | 501 ms | 1.57 jobs/s | 780 ms | 1.05 jobs/s |
| 2 × 1 | 590 ms | 2.38 jobs/s | 857 ms | 1.79 jobs/s |
| 4 × 1 | 712 ms | 3.42 jobs/s | 1201 ms | 2.30 jobs/s |

Throughput grows with workers while per-job execution gets slower: four CPU-bound processes compete for four logical CPUs (and the coordinator and benchmark share the machine). These are single-machine numbers; they say nothing about multi-machine scaling.

## Rules we follow

- Never invent or hand-edit results; every number comes from `raw.csv`.
- Always keep simulated and measured results apart and labelled.
- Record environment and configuration with every report.
