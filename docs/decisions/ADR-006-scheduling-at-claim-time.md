# ADR-006: Scheduling decisions are made at claim time; policies are pure functions

Status: Accepted (2026-11-03)

## Decision
`SchedulingPolicy.order(List<SchedulableJob>, Instant now)` is a pure function in `queuepilot-core` with no Spring or DB access. On each claim, `SchedulerService`:
1. loads `QUEUED` jobs with `ready_at <= now` (start time, dependencies and retry delay are encoded in state + `ready_at`),
2. keeps those whose capabilities the worker satisfies,
3. applies `QueueRotation` (round-robin across queues, F14),
4. orders within the chosen queue with the active policy,
5. tries to claim candidates in order until one succeeds.

Priority aging is computed from `now - submittedAt` while ordering; nothing is written to the DB to "age" a job.

## Consequences
The benchmark simulator reuses exactly the same policy code with a virtual clock (fair comparison). Policies are trivially unit-testable. MLFQ is dropped from v1.0 (not in the SRS, and it needs time slices/preemption that we do not have).
