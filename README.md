# QueuePilot

An extensible job scheduling and execution platform, built for the CS1207 Object Oriented Programming Lab by Om Kumar, Abhinav Singh Saini, Samyak Jain and Subhamjyoti Maharana.

Clients submit jobs for **registered task types**. A **coordinator** validates and stores them, orders them with FCFS, Priority with Aging or SJF, and hands them to **independent worker processes** that run the matching **task plugin**. Every attempt is tracked; dead workers are detected through heartbeats and leases; jobs are retried with backoff; late results from superseded attempts are rejected; dependency workflows run in order; and a web dashboard shows all of it.

**The rule everything follows: the scheduler never knows what a task does.** A new task type is a plugin jar plus a manifest, with no change to the coordinator or scheduler (see [Adding a task type](docs/guides/adding-a-task-type.md)).

## Features

| Area | What exists |
|---|---|
| Task registry | Versioned task definitions (schema, capabilities, limits, timeout, retries); immutable versions; enable/disable |
| Plugins | `TaskExecutor` contract; built-ins: sorting, text analysis, Monte Carlo pi, file checksum; example third-party plugin (prime sieve) |
| Jobs | Schema validation, priorities, queues, scheduled start, deadlines with at-risk/overdue status, cancel-before-start, per-client limits |
| Scheduling | FCFS, Priority with Aging, SJF (switchable live); round-robin fairness across queues; capability + capacity matching |
| Workers | Separate JVM processes; concurrency slots; real progress; input/output files; local timeouts |
| Reliability | Heartbeats, leases, fencing tokens, bounded retry with exponential backoff, task timeouts, offline detection, failure simulation (kill / freeze) |
| Workflows | DAG submission, cycle rejection, parallel branches, skip-on-failure propagation, graph view |
| Isolation (stretch) | Container runner with no network, read-only FS, CPU/memory/PID limits, non-root (not verified on this machine: Docker absent) |
| Dashboard | Overview, submit, jobs, workflows, task registry, scheduler, workers, benchmarks |
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

Open http://localhost:8080. On first start the coordinator writes a random admin password to `data/admin-password.txt` and a worker enrollment secret to `data/worker-secret.txt` (both git-ignored). Set `QP_ADMIN_PASSWORD` / `QP_WORKER_SECRET` to choose them yourself.

Start workers in other terminals:

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0.jar --name worker-1 --slots 2
```

```bash
java -jar queuepilot-worker/target/queuepilot-worker-1.0.0.jar --name worker-2 --slots 2 --capabilities cpu,io,text
```

Or start everything at once with `scripts/start-demo.ps1` (Windows) / `scripts/start-demo.sh`. Then use **Submit job → Submit demo batch**, and watch Jobs, Workers and Scheduler. The [demo guide](docs/guides/running-the-demo.md) walks through crash recovery, workflows and benchmarks.

## Tests

```bash
./mvnw verify
```

85 tests, all passing: unit tests for policies, aging, fairness, DAG, schemas, retry and metrics; plugin tests; worker runtime tests (concurrency, timeouts, revocation, plugin loading); coordinator integration tests over the real HTTP API (validation, quotas, capability matching, concurrent claims, lease expiry, fencing, retry limits, timeouts, workflows, artifacts, security); one test against a real PostgreSQL server (embedded binaries). Container isolation tests run only when Docker is available; without Docker the class is aborted by a JUnit assumption and contributes 0 tests (they are not counted as passed). See [testing strategy](docs/testing/testing-strategy.md).

## Documentation

| Topic | Where |
|---|---|
| Requirements, audit | [docs/requirements](docs/requirements) |
| Architecture, diagrams, database, protocol, plugin contract | [docs/architecture](docs/architecture) |
| Design notes (scheduling, workers, retries/leases, workflows) | [docs/design](docs/design) |
| Decisions (ADRs) | [docs/decisions](docs/decisions) |
| Testing and benchmark methodology | [docs/testing](docs/testing) |
| How-to guides | [docs/guides](docs/guides) |
| OOP concept mapping | [docs/oop-mapping.md](docs/oop-mapping.md) |
| Status and known limitations | [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md) |

## Honest limitations

- Workers are separate **processes on one machine**; nothing here is tested across machines.
- Delivery is **at-least-once** with bounded retries; a job can execute more than once. No exactly-once claim.
- One coordinator; no high availability.
- Clients are not authenticated (`X-Client-Id` only drives quotas).
- Container isolation is implemented but **not verified here** because Docker is not installed on the development machine.
