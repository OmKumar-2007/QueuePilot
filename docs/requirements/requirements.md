# QueuePilot v1.0 — Revised Requirements

This document revises the original SRS v1.0 (CS1207, Om Kumar, Abhinav Singh Saini, Samyak Jain, Subhamjyoti Maharana) for the extensible-platform vision. The original PDF remains in the repository root (`QueuePilot_SRS_Official_Template.pdf`). Requirement IDs `F1`–`F15` keep their original meaning; new requirements start at `F16`. "Shall" marks a required behaviour.

## 1. Product scope (v1.0)

QueuePilot is a single-coordinator job scheduling and execution platform that runs on one development machine. Clients submit jobs for **registered task types**; the coordinator validates, persists and schedules them; **independent worker processes** claim, execute and report them. The scheduling engine knows nothing about what a task does.

**In scope for v1.0:** task registry, Java executor plugins, job lifecycle, three scheduling policies, queue fairness, per-client limits, worker processes with capability matching, leases/heartbeats/retries/fencing, DAG workflows, file artifacts, dashboard, separate benchmark module.

**Stretch for v1.0 (Phase 9):** isolated container execution of packaged runtime plugins.

**Out of scope:** multiple coordinators, leader election, consensus, multi-machine claims, exactly-once execution, ML-based scheduling, Kafka/Redis/Kubernetes, executing un-isolated user code.

## 2. Actors

| Actor | Description | Authentication (v1.0) |
|---|---|---|
| Client | Submits and inspects jobs | None; identifies itself with `X-Client-Id` for quotas (documented limitation) |
| Administrator | Manages task registry, policies, workers, failure simulation | HTTP Basic, credential from environment |
| Worker process | Executes tasks | Enrollment secret → per-worker bearer token |
| Benchmark runner | Separate program that drives the API | Uses client / admin credentials like any other caller |

## 3. Functional requirements

### Job management
- **F1** The system shall expose a REST API to submit a job referencing a registered, enabled task type (optionally a version) with input data.
- **F2** The system shall validate the submission against the task definition's input schema, reject invalid fields with a structured error, and assign a unique ID to each accepted job.
- **F3** The system shall persist job metadata, state, timestamps, attempts, output and errors **before** acknowledging acceptance; clients shall query status/results and cancel a job before execution starts.

### Scheduling
- **F4** The system shall support FCFS, Priority with Aging and SJF, with the active policy selected by an administrator.
- **F5** The scheduler shall consider only runnable jobs: state `QUEUED`, scheduled start reached, all prerequisites succeeded, retry delay elapsed, queue rules satisfied.
- **F14** The scheduler shall rotate round-robin across eligible queues, and the API shall enforce a configurable maximum number of active jobs per client.

### Workers
- **F6** The system shall maintain a worker registry: identity, capabilities, supported task types, health, concurrency limit, current assignments, counters.
- **F7** A job shall be assigned only to a healthy worker with free capacity whose capabilities satisfy the task definition; otherwise it stays queued.
- **F9** Workers shall report progress, completion, output and errors; progress shall originate from the executing task, never from a timer.
- **F10** Each assignment shall carry an attempt ID, a fencing token and a lease; workers shall heartbeat while active.
- **F11** On heartbeat/lease expiry the system shall mark the worker unavailable and retry eligible work up to the task's retry limit with a delay; updates from a superseded attempt shall be rejected.

### Workflows
- **F12** Jobs may depend on prerequisite jobs; cycles shall be rejected; dependants stay `BLOCKED` until all prerequisites succeed.
- **F13** Jobs may have a future start time and a deadline; the dashboard shall flag jobs near or past their deadline.
- **F19** When a prerequisite ends `FAILED` or `CANCELLED`, every transitive dependant shall become `SKIPPED` with the upstream job named as the reason.
- **F20** The system shall accept a workflow (a set of jobs with local references between them) in one request and report its aggregate progress.

### Task registry and plugins
- **F8** Task execution shall go through one plugin contract (`TaskExecutor`); the scheduler shall contain no task-specific logic.
- **F16** Administrators shall register task definitions: type, version, executor kind, required capabilities, input/output schema, resource limits, timeout, max retries, default priority, default estimated duration, enabled flag. `(type, version)` is unique and immutable once jobs reference it.
- **F17** A new task type shall be addable by packaging a plugin and registering its manifest, **without modifying coordinator or scheduler code**.
- **F18** Each attempt shall be cut off when it exceeds the task definition's timeout, and treated as a retryable failure.

### Files
- **F21** Clients shall upload input files and download output files; files shall be stored through a storage abstraction (local filesystem in v1.0) with size limits, and referenced from jobs by artifact ID.

### Monitoring and benchmarks
- **F15** The dashboard shall display jobs, workers, history and live metrics, and provide controlled failure simulation (admin only).
- **F22** A separate `queuepilot-benchmarks` module shall (a) compare the three policies on identical simulated workloads, and (b) measure real worker execution; it shall save raw data, configuration and environment, and the dashboard shall display imported reports clearly labelled *simulated* or *measured*.

### Isolated execution (Phase 9)
- **F23** Packaged runtime plugins shall run in containers with CPU, memory, PID and time limits, no network, read-only root FS, non-root user, and a bounded scratch directory.

## 4. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR1 Performance | Submission or validation error responds within 2 s for payloads ≤ 64 KB (excluding execution). |
| NFR2 Freshness | With 100 jobs and 4 worker slots, the dashboard reflects persisted changes within 2 s. |
| NFR3 Detection | Worker failure is detected within heartbeat timeout + one recovery interval. |
| NFR4 Durability | A coordinator restart loses no accepted job; running attempts are recovered through lease expiry. |
| NFR5 Security | No arbitrary code in the coordinator; inputs bounded; admin endpoints authenticated; secrets not in git or logs; errors without stack traces. |
| NFR6 Guarantees | At-least-once attempt delivery, bounded retries, first valid completion from the *current* attempt wins. No exactly-once claim. |
| NFR7 Maintainability | Clear module boundaries; scheduler and core logic testable without Spring or a database. |
| NFR8 Reproducibility | Benchmarks with the same seed and configuration produce comparable results. |

## 5. Use cases

The five SRS use cases (U1 Submit and manage jobs, U2 Manage scheduler and monitor, U3 Schedule and dispatch, U4 Execute assigned job, U5 Detect failure and recover) remain valid with these changes:

- **U3** is triggered by a worker *claim* request (pull model): the scheduler picks the best runnable job for that worker.
- **U4** runs inside a separate worker process, through the `TaskExecutor` plugin selected by task type and version.
- **New U6 — Register a task type:** admin uploads/enters a manifest → system validates schema and uniqueness → definition stored (disabled) → admin enables it → workers advertising the type become eligible.
- **New U7 — Run a benchmark:** operator runs `queuepilot-benchmarks` → report files written → report imported and shown on the benchmark page.

## 6. Acceptance criteria (summary)

| Req | Acceptance test |
|---|---|
| F2 | Submitting a missing required field returns 400 with the field name; no job row exists. |
| F3 | Kill the coordinator after a 201 response, restart → job still present. |
| F4/F5 | Same queued set produces FCFS/SJF/Aging orders asserted in unit tests; jobs with future start, unmet dependency or retry delay are never claimed. |
| F7 | A worker without capability `X` never receives a job requiring `X`. |
| F11 | Kill a worker process mid-job → attempt becomes `LEASE_EXPIRED`, job retried on another worker; after `maxRetries` → `FAILED`. A late completion with the old token returns 409 and changes nothing. |
| F12/F19 | A→B→A is rejected; failure of A marks B and C `SKIPPED`. |
| F14 | Client over its active-job limit gets 429. |
| F17 | Adding a new plugin jar + manifest makes a new task type runnable with zero changes to `queuepilot-coordinator` or `queuepilot-core`. |
| F18 | A task exceeding its timeout is stopped and its attempt marked `TIMED_OUT`. |
| F22 | Benchmark run writes `raw.csv`, `summary.json`, `environment.json`; repeated runs with same seed give identical simulation output. |
