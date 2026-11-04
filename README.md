# QueuePilot

An extensible job scheduling and execution platform, built for the CS1207 Object Oriented Programming Lab by Om Kumar, Abhinav Singh Saini, Samyak Jain and Subhamjyoti Maharana.

Clients submit jobs for **registered task types**. A **coordinator** validates and stores them, orders them with FCFS, Priority with Aging or SJF, and hands them to **independent worker processes** that run the matching **task plugin**. Every attempt is tracked; dead workers are detected through heartbeats and leases; jobs are retried with backoff; late results from superseded attempts are rejected; dependency workflows run in order; and a web dashboard shows all of it.

**The rule everything follows: the scheduler never knows what a task does.** A new task type is a plugin jar plus a manifest, with no change to the coordinator or scheduler (see [Adding a task type](docs/guides/adding-a-task-type.md)).

## Features

| Area | What exists |
|---|---|
| Task registry | Task definitions by name (input schema, timeout, retries, default priority and duration) |
| Plugins | `TaskExecutor` contract; built-ins: sorting, text analysis, Monte Carlo pi, file checksum; example third-party plugin with prime sieve and an N-Queens solver; **Custom JavaScript**: upload a `.js` file defining `run(input, progress)` and the worker runs it with Node.js (sample: `examples/sample-files/fibonacci.js`; not sandboxed) |
| Jobs | Schema validation, priorities, queues, dependencies, cancel-before-start |
| Scheduling | FCFS, Priority with Aging, SJF (switchable live); round-robin fairness across queues; jobs go only to workers that have the plugin and a free slot |
| Workers | Separate JVM processes; concurrency slots; real progress; input/output files; local timeouts |
| Reliability | Heartbeats, leases, late results from old attempts rejected, bounded retry with exponential backoff, task timeouts, offline detection |
| Worker controls | Pause / resume (jobs hold in place), interrupt (jobs stop and are retried), freeze (heartbeats stop for 20 s), crash (process exits), add worker from the dashboard |
| Demo pacing | Optional `--pace-ms` per worker so jobs take ~15-30 s and can be watched; recorded in each job's output, never used for benchmarks |
| Workflows | DAG submission, cycle rejection, parallel branches, skip-on-failure propagation, graph view |
| Dashboard | Live view (workers and their running jobs, controls, up next, activity), jobs, submit, workflows, tasks, full event logs; light and dark mode |
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

Open http://localhost:8080. There is no login of any kind: the coordinator only listens on 127.0.0.1, and workers simply register and then identify themselves with their worker id (`X-Worker-Id` header).

Start workers in other terminals:

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar --name worker-1 --slots 2
```

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar --name worker-2 --slots 2
```

Or start everything at once with `scripts/start-demo.ps1` (Windows) / `scripts/start-demo.sh`: the coordinator plus four workers with 2 slots each and demo pacing. On the **Live** page click **Run demo jobs** and watch the workers; use **Pause**, **Interrupt**, **Freeze**, **Crash** and **+ Add worker**. The [demo guide](docs/guides/running-the-demo.md) walks through crash recovery, workflows and benchmarks. To load the system with a mix of 50 succeeding and failing jobs, run `python scripts/simulate_jobs.py --chaos`.

## Tests

```bash
./mvnw verify
```

108 tests, all passing: unit tests for policies, aging, fairness, DAG, schemas, retry and metrics; plugin tests (including the JavaScript task, skipped if Node.js is missing); worker runtime tests (concurrency, timeouts, revocation, plugin loading); coordinator integration tests over the real HTTP API (validation, plugin matching, concurrent claims, lease expiry, stale-attempt rejection, retry limits, timeouts, workflows, artifacts); end-to-end tests with four real worker runtimes over HTTP (concurrency, interrupt and retry, pause and resume, freeze and recovery); one test against a real PostgreSQL server (embedded binaries). See [testing strategy](docs/testing/testing-strategy.md).

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
- **No security at all**: no client, admin or worker authentication, and custom JavaScript runs unsandboxed. Fine on 127.0.0.1 for a course demo, not on a shared network.
- Demo pacing makes jobs deliberately slower; paced results show `demoPacingMsPerPercent` in their output.
