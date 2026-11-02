# Phase 0 — Repository and Requirements Audit

Date: 2026-11-03
Baseline: git tag `v0-legacy` (commit `07db4ad`) preserves the original prototype exactly as received.

## 1. What exists today

| Area | Current implementation | Assessment |
|---|---|---|
| Build | Single Maven module, Spring Boot 3.3.4, Java 21, Maven wrapper | Good base. Needs multi-module layout (workers + benchmarks must be separate). |
| Job model | Abstract `Job` + `SortingJob`, `TextAnalysisJob`, `MathComputeJob`; `JobType` **enum** | Blocks extensibility: a new task type needs a new enum value, a new subclass, DTO and controller changes. |
| Source of truth | `ConcurrentHashMap` inside `JobQueueManager` | Restarting the app loses every job. Database is only a write-behind audit log. |
| Persistence | JPA entities + `AuditService`; default DB is **H2 in-memory**; save errors are caught and logged | Violates SRS U1 exception: "do not report a job as accepted unless its record was persisted". |
| Scheduling | `SchedulingPolicy` interface with FCFS, SJF, Priority+Aging, **MLFQ** | Algorithms are reusable. MLFQ is not in the SRS; policies are coupled to the in-memory `Job`. |
| DAG | `DependencyGraph` with 3-colour DFS cycle detection | Reusable logic. No failure propagation (dependants of a failed job stay BLOCKED forever). |
| Workers | `WorkerPool` of 4 **threads inside the coordinator** | Prompt requires independent worker **processes**. "Crash" = thread interrupt, not a real failure. |
| Executors | Three executors | Each executor pads its run with `Thread.sleep(...)` to last 10–30 s. Progress is tied to the timer, not to real work. Violates "never substitute fake execution with timers". |
| Leases / fencing | UUID attempt token, lease renewal on heartbeat, watchdog thread | Correct idea. `failJob` checks the token but not the state; cancel is allowed while RUNNING, contrary to SRS U4. |
| Benchmarks | `BenchmarkService` inside the operational app (discrete-event simulation) | Must move to a separate `queuepilot-benchmarks` module. No real execution benchmark. |
| Fairness / quotas (F14) | Not implemented | Missing. |
| Admin security (NFR 4.2) | None — anyone can crash workers or switch policy | Missing. |
| Task registry | Not present (executors hard-wired in `ExecutorRegistry` constructor) | Missing — core of the new vision. |
| File handling | None | Missing. |
| Dashboard | Single static page (`index.html`, `app.js`, `style.css`) polling every 800 ms | Usable as visual reference; will be rebuilt against the new API. |
| Tests | 26 tests across 8 classes | Reusable where they test algorithms (policies, DAG). Others test the thread-based design. |
| Docs | `README.md`, `ARCHITECTURE.md`, `OOP_CONCEPTS_AND_DESIGN.md` | Over-claim ("production-grade", "zero-trust", "modelled after Kubernetes"). Moved to `docs/legacy/` for reference. |
| Version control | Not a git repository | Fixed: initialised, baseline committed and tagged. |

### Environment found on the development machine

| Tool | Status |
|---|---|
| JDK | Temurin 21.0.13 — OK |
| Maven | Not on PATH; `mvnw` wrapper works |
| PostgreSQL | **Not installed** |
| Docker | **Not installed** (needed only for Phase 9) |

## 2. Requirements to preserve (from SRS v1.0)

All functional requirements F1–F15 remain valid in intent. Specifically kept as-is:

- REST submission, validation, unique IDs, status/result queries, cancel-before-start (F1–F3).
- FCFS, Priority with Aging, SJF with an admin-selected active policy (F4).
- Readiness rules: state, scheduled start, dependencies, retry delay, queue rules (F5).
- Worker registry with capabilities, health, concurrency limit, assignments (F6–F7).
- Progress / result / error reporting (F9), leases + heartbeats (F10), bounded retry and stale-update rejection (F11).
- DAG dependencies with cycle rejection (F12), scheduled starts and deadline warnings (F13).
- Queue fairness and per-client limits (F14), dashboard + failure simulation + metrics (F15).
- All NFRs in SRS §4 and the scope boundary in §5 (no consensus, no exactly-once, no arbitrary code without isolation).

## 3. Requirements that need revision

| SRS item | Revision | Reason |
|---|---|---|
| §1.1 "worker threads; processes are an extension" | Workers become **independent local processes** in v1.0. | Master prompt §4.5. |
| F8 "defined set of built-in executors" | Replaced by a **Task Registry** (versioned task definitions) + **plugin contract**. Built-ins become ordinary plugins. | Extensibility is the core principle. |
| F15 "benchmark comparisons in the dashboard" | Benchmarks run from a **separate module**; the dashboard only *displays* stored reports. | Master prompt §6. |
| Built-in job durations | Durations come from real work sized by input, never from sleeps. | Honest execution. |
| Persistence | Database becomes the **source of truth**; in-memory state is only a cache. | SRS U1/U3/U5 exception clauses. |
| New | Artifact (file) storage abstraction for inputs/outputs. | Master prompt §4.6. |
| New | Isolated container execution for packaged runtime plugins (Phase 9, optional for the core demo). | Master prompt §4.3. |
| MLFQ | Removed from the operational engine. | Not in SRS; simplicity rule. It can be re-added later as a 4th policy to *demonstrate* extensibility. |

## 4. Recommendation: restructure with salvage

Evolving the current code in place would mean rewriting most of it anyway (job model, store, workers, executors). The recommended path:

1. Keep the original in git history (`v0-legacy`).
2. Create the new multi-module layout.
3. Port the parts that are sound: `DependencyGraph` algorithm, the three scheduling policies' ordering rules, the real computation inside the three executors (minus the sleeps), and the algorithmic tests.
4. Rebuild job model, persistence, worker processes, recovery and the dashboard against the new architecture.
