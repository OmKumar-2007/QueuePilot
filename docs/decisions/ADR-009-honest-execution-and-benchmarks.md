# ADR-009: No artificial delays; benchmarks are labelled simulated or measured

Status: Accepted (2026-11-03)

## Decision
- Task executors never `sleep` to look busy. Progress is reported from real loop iterations. Demo jobs take seconds because their inputs are large (e.g. 200M Monte Carlo samples), not because of timers.
- `queuepilot-benchmarks` produces two kinds of report: `SIMULATED` (policy comparison on a virtual clock with declared durations) and `MEASURED` (real workers, real tasks, warm-up + repetitions, median/p95). Every report stores config, seed, environment and raw data. The dashboard shows the label prominently.

## Amendment (2026-11-03): opt-in demo pacing

Real tasks finished too fast to watch in a live demo. Workers can now be started with `--pace-ms N`, which waits N ms per percent of reported progress. This keeps the spirit of the rule: the work is real, the pacing is explicit (worker flag, shown on the dashboard, written into every paced job's output) and benchmarks never use it.

## Consequences
Execution durations in demos vary by machine — this is reported, not hidden.
