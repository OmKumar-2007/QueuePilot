# Testing Strategy

Run everything with `./mvnw verify` from the repository root. Result on 2026-11-03: 99 tests, 0 failures (task-api 4, core 25, built-ins 7, example plugin 1, worker 13, coordinator 45, benchmarks 4).

## Layers

| Layer | Where | Speed | What it proves |
|---|---|---|---|
| Unit (pure Java) | `queuepilot-core`, `queuepilot-task-api` | ms | Policies, aging, queue rotation, DAG cycles/order, schema validation, state machine, retry backoff, metrics formulas |
| Plugin | `queuepilot-tasks-builtin`, `queuepilot-example-plugin` | < 1 s | Tasks compute correct results, report monotonic progress ending at 100, honour cancellation, discoverable via ServiceLoader with manifests |
| Worker runtime | `queuepilot-worker` (fake coordinator) | seconds | Slot limit respected under real concurrency, failure flags, local timeout, revoked attempts abandoned silently, plugin jar loading, sandbox command flags |
| Coordinator integration | `queuepilot-coordinator` (full Spring context, H2 PG-mode, controllable clock) | ~30 s | Every rule through the real HTTP API |
| Real workers end-to-end | `RealWorkersEndToEndTest` (coordinator on a random port + 4 real `WorkerRuntime`s over HTTP, real clock and recovery) | ~20 s | 4 jobs run concurrently on 4 workers; interrupt then retry; pause holds progress past the lease time then resume; freeze then recovery on another worker then thaw and re-register |
| Real PostgreSQL | `PostgresIntegrationTest` (embedded PostgreSQL binaries) | ~30 s | Flyway migration and a full job lifecycle on PostgreSQL |
| Container isolation | `ContainerIsolationTest` | needs Docker | No network, read-only root, non-root user, PID limit. **Does not run without Docker (0 tests reported, not counted as passed)** |
| End-to-end (manual/scripted) | live coordinator + 2 worker processes | minutes | Plugin routing, real crash recovery, files, workflows, benchmarks |

Time-dependent rules (leases, retries, timeouts, deadlines) use an injected `MutableClock`, so tests advance time instead of sleeping and are not flaky.

## Requirement coverage

| Requirement | Tests |
|---|---|
| F1-F3 submit, validate, persist, cancel | `JobSubmissionTest` |
| F4, F5 policies and readiness | `SchedulingPolicyTest`, `SchedulingTest.fcfsSjfAndPriority…`, `JobSubmissionTest.futureStartTime…` |
| F6, F7 registry, capability matching, slot limit | `SchedulingTest.jobIsOnlyAssigned…`, `workerNeverExceeds…` |
| F8, F16, F17 plugins and registry | `BuiltinTasksTest`, `PrimeSieveTaskTest`, `TaskRegistryAndArtifactTest.newTaskTypeBecomesRunnable…`, `PluginAndConfigTest` |
| F9 progress/results | `ExecutionAndRecoveryTest.progressAndCompletion…`, `WorkerRuntimeTest` |
| F10, F11 leases, retries, fencing | `ExecutionAndRecoveryTest.expiredLease…`, `retryableFailuresStop…`, `silentWorker…`, `forgedToken…` |
| F12, F19, F20 workflows | `DependencyGraphTest`, `WorkflowTest` |
| F13 schedule/deadlines | `JobSubmissionTest.futureStartTime…`, `deadlineStatusIsReported` |
| F14 fairness and quotas | `QueueRotationTest`, `SchedulingTest.queuesAreServedRoundRobin`, `JobSubmissionTest.perClientActiveJobLimit…` |
| F15 monitoring, failure simulation | `ExecutionAndRecoveryTest.failureSimulation…`, `drainedWorker…`, `TaskRegistryAndArtifactTest.overview…` |
| F18 timeouts | `ExecutionAndRecoveryTest.attemptRunningPastTheTaskTimeout…`, `WorkerRuntimeTest.localTimeout…` |
| F21 files | `TaskRegistryAndArtifactTest.inputFileFlows…` |
| F22 benchmarks | `SimulationTest` |
| F23 isolation | `PluginAndConfigTest.dockerCommandApplies…`, `ContainerIsolationTest` (needs Docker) |
| NFR5 security | admin 401 tests, enrollment 403, forged token 409, no stack traces in errors |
| Concurrency | `SchedulingTest.concurrentClaimsNeverAssignAJobTwice` |

## Live end-to-end run (2026-11-03, this machine)

Coordinator + `worker-1` (with the prime-sieve plugin jar) + `worker-2`:

1. `prime-sieve` registered and enabled at runtime; a job with limit 50,000,000 ran on worker-1 only → 3,001,134 primes (correct).
2. A 1.5-billion-sample pi job was running on worker-2 at 6 % when *Simulate crash* was used: the worker process exited with code 137, attempt 1 became ABANDONED, attempt 2 ran on worker-1 and succeeded (estimate 3.141555).
3. An uploaded 820 KB file: checksum equal to a locally computed SHA-256; text analysis produced the expected top words and a CSV output artifact.
4. A diamond workflow ran a → (b, c) → d in that order; a cyclic workflow was rejected with `x -> y -> x`.

## Live run 3 (2026-11-03): file submission, N-Queens, 50-job simulation

1. **File upload through the Submit page.** `examples/sample-files/queuepilot-notes.txt` (38,060 bytes) was attached in the browser form and submitted twice. *File checksum* returned `4a6b3354…77ff3`, the same as a locally computed SHA-256. *Text analysis* reported 408 lines, "workers" 808 times and "jobs" 800 times (as constructed), and produced a downloadable CSV.
2. **New task type N-Queens.** Built in the example plugin and installed on worker-1 only. It was registered and enabled on the Tasks page and submitted from the Submit page: `n = 8` gave 92 solutions plus the first board; `n = 3` with `requireSolution` FAILED with `NO_SOLUTION` after one attempt (permanent failures are not retried).
3. **`scripts/simulate_jobs.py --chaos`.** 50 jobs plus 2 refused requests (HTTP 400). Result: 38 SUCCEEDED, 8 FAILED (`MISSING_DATA` ×6, `NO_SOLUTION` ×2), 4 SKIPPED (`UPSTREAM_FAILED`). The interrupted worker's 2 jobs were retried and succeeded. Every job matched its expected outcome (exit code 0); the run took 152 s with 5 paced workers.

Found while testing: on Windows, `localhost` resolves to IPv6 first while the coordinator listens on IPv4 127.0.0.1, so Python clients waited about 2 s per request. The script now uses 127.0.0.1.

## Not verified

- Container isolation (Docker not installed).
- Operation across several machines (out of scope).
- Long-duration soak tests.
