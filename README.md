# QueuePilot

An extensible job scheduling and execution platform, built for the CS1207 Object Oriented Programming Lab by Om Kumar, Abhinav Singh Saini, Samyak Jain and Subhamjyoti Maharana.

Clients submit jobs for **registered task types**. A **coordinator** validates and stores them, orders them with FCFS, Priority with Aging or SJF, and hands them to **independent worker processes** that run the matching **task plugin**. Every attempt is tracked; dead workers are detected through heartbeats and leases; jobs are retried with backoff; late results from superseded attempts are rejected; dependency workflows run in order; and a web dashboard shows all of it.

**The rule everything follows: the scheduler never knows what a task does.** A new task type is a plugin jar plus a manifest, with no change to the coordinator or scheduler (see [Adding a task type](docs/guides/adding-a-task-type.md)).

## Features

| Area | What exists |
|---|---|
| Task registry | Versioned task definitions (schema, capabilities, limits, timeout, retries); immutable versions; enable/disable |
| Plugins | `TaskExecutor` contract; built-ins: sorting, text analysis, Monte Carlo pi, file checksum; example third-party plugin with prime sieve and an N-Queens solver |
| Jobs | Schema validation, priorities, queues, scheduled start, deadlines with at-risk/overdue status, cancel-before-start, per-client limits |
| Scheduling | FCFS, Priority with Aging, SJF (switchable live); round-robin fairness across queues; capability + capacity matching |
| Workers | Separate JVM processes; concurrency slots; real progress; input/output files; local timeouts |
| Reliability | Heartbeats, leases, fencing tokens, bounded retry with exponential backoff, task timeouts, offline detection |
| Worker controls | Pause / resume (jobs hold in place), interrupt (jobs stop and are retried), freeze (heartbeats stop for 20 s), crash (process exits), add worker from the dashboard |
| Demo pacing | Optional `--pace-ms` per worker so jobs take ~15-30 s and can be watched; recorded in each job's output, never used for benchmarks |
| Workflows | DAG submission, cycle rejection, parallel branches, skip-on-failure propagation, graph view |
| Isolation (stretch) | Container runner with no network, read-only FS, CPU/memory/PID limits, non-root (not verified on this machine: Docker absent) |
| Dashboard | Live view (workers and their running jobs, controls, up next, activity), jobs, submit, workflows, scheduler, tasks, benchmarks |
| Benchmarks | Separate module: simulated policy comparison + measured execution with worker scaling |

## Technology

Java 21 · Spring Boot 3.3 · Spring Data JPA + Flyway · PostgreSQL (H2 in PostgreSQL mode by default) · plain HTML/CSS/JS · Maven multi-module · JUnit 5.

## Modules

```
queuepilot-task-api        plugin contract (no dependencies)
queuepilot-core            scheduling policies, DAG, state machine, schemas, protocol, metrics (no Spring)
queuepilot-tasks-builtin   built-in task plugins + manifests
queuepilot-example-plugin  a third-party plugin built only against task-api
queuepilot-coordinator     Spring Boot REST API, persistence, scheduler, recovery, dashboard
queuepilot-worker          standalone worker process
queuepilot-benchmarks      standalone benchmark runner
```

## Quick start

Prerequisites: JDK 21. Maven is included (`mvnw`). PostgreSQL is optional (see [database setup](docs/guides/database-setup.md)).

Run all commands from the repository root:

```bash
./mvnw install -DskipTests
```

```bash
java -jar queuepilot-coordinator/target/queuepilot-coordinator-1.0.0.jar
```

Open http://localhost:8080. There is no admin login: the coordinator only listens on 127.0.0.1, and administrator actions are open for the local demo (set `QP_ADMIN_AUTH=true` and `QP_ADMIN_PASSWORD` to require HTTP Basic). Workers enrol with a secret the coordinator writes to `data/worker-secret.txt` (git-ignored), or `QP_WORKER_SECRET`.

Start workers in other terminals:

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar --name worker-1 --slots 2
```

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar --name worker-2 --slots 2 --capabilities cpu,io,text
```

Or start everything at once with `scripts/start-demo.ps1` (Windows) / `scripts/start-demo.sh`: the coordinator plus four workers with 2 slots each and demo pacing. On the **Live** page click **Run demo jobs** and watch the workers; use **Pause**, **Interrupt**, **Freeze**, **Crash** and **+ Add worker**. The [demo guide](docs/guides/running-the-demo.md) walks through crash recovery, workflows and benchmarks. To load the system with a mix of 50 succeeding and failing jobs, run `python scripts/simulate_jobs.py --chaos`.

## Tests

```bash
./mvnw verify
```

99 tests, all passing: unit tests for policies, aging, fairness, DAG, schemas, retry and metrics; plugin tests; worker runtime tests (concurrency, timeouts, revocation, plugin loading); coordinator integration tests over the real HTTP API (validation, quotas, capability matching, concurrent claims, lease expiry, fencing, retry limits, timeouts, workflows, artifacts, security); end-to-end tests with four real worker runtimes over HTTP (concurrency, interrupt and retry, pause and resume, freeze and recovery); one test against a real PostgreSQL server (embedded binaries). Container isolation tests run only when Docker is available; without Docker the class is aborted by a JUnit assumption and contributes 0 tests (they are not counted as passed). See [testing strategy](docs/testing/testing-strategy.md).

## Documentation

| Topic | Where |
|---|---|
| Requirements, audit | [docs/requirements](docs/requirements) |
| Architecture, diagrams, database, protocol, plugin contract | [docs/architecture](docs/architecture) |
| Design notes (scheduling, workers, retries/leases, workflows) | [docs/design](docs/design) |
| Decisions (ADRs) | [docs/decisions](docs/decisions) |
| Testing and benchmark methodology | [docs/testing](docs/testing) |
| How-to guides | [docs/guides](docs/guides), including [simulating 50 jobs](docs/guides/simulating-50-jobs.md) |
| OOP concept mapping | [docs/oop-mapping.md](docs/oop-mapping.md) |
| Status and known limitations | [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md) |

## Honest limitations

- Workers are separate **processes on one machine**; nothing here is tested across machines.
- Delivery is **at-least-once** with bounded retries; a job can execute more than once. No exactly-once claim.
- One coordinator; no high availability.
- Clients are not authenticated (`X-Client-Id` only drives quotas), and by default neither are administrator actions: fine on 127.0.0.1, not on a shared network.
- Demo pacing makes jobs deliberately slower; paced results show `demoPacingMsPerPercent` in their output.
- Container isolation is implemented but **not verified here** because Docker is not installed on the development machine.
