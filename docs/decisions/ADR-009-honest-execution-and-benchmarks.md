# ADR-009: No artificial delays; benchmarks are labelled simulated or measured

Status: Accepted (2026-11-03)

## Decision
- Task executors never `sleep` to look busy. Progress is reported from real loop iterations. Demo jobs take seconds because their inputs are large (e.g. 200M Monte Carlo samples), not because of timers.
- `queuepilot-benchmarks` produces two kinds of report: `SIMULATED` (policy comparison on a virtual clock with declared durations) and `MEASURED` (real workers, real tasks, warm-up + repetitions, median/p95). Every report stores config, seed, environment and raw data. The dashboard shows the label prominently.

## Consequences
Execution durations in demos vary by machine — this is reported, not hidden.
