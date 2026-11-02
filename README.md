# QueuePilot

An extensible job scheduling and execution platform, built for the CS1207 Object Oriented Programming Lab by Om Kumar, Abhinav Singh Saini, Samyak Jain and Subhamjyoti Maharana.

QueuePilot accepts jobs for **registered task types**, schedules them with FCFS, Priority with Aging or SJF, and dispatches them to **independent worker processes** that execute the matching task plugin. It tracks every attempt, detects dead workers through heartbeats and leases, retries safely, runs dependency workflows, and shows everything on a web dashboard. The scheduler never knows what a task actually does.

> **Status: re-architecture in progress.** Phases 0–1 (audit + architecture) are done; implementation starts at Phase 2. The original single-module prototype is preserved at git tag `v0-legacy` and its docs are in [`docs/legacy/`](docs/legacy/). See [PROJECT_PROGRESS.md](PROJECT_PROGRESS.md).

## Planned features (v1.0)

- Task registry with versioned task definitions and a documented plugin contract
- Job submission with schema validation, priorities, scheduled starts and deadlines
- Three scheduling policies, queue round-robin fairness, per-client limits
- Independent worker processes with capabilities, concurrency limits and real progress
- Heartbeats, leases, fencing tokens, bounded retries with backoff, timeouts
- DAG workflows with cycle rejection and failure propagation
- File inputs/outputs through an artifact storage abstraction
- Dashboard: overview, task registry, submission, jobs, scheduler, workers, workflows, benchmarks
- Separate `queuepilot-benchmarks` module (simulated policy comparison + measured execution)
- Stretch: sandboxed container execution for non-Java task packages

## Technology

Java 21 · Spring Boot 3 · PostgreSQL · Spring Data JPA + Flyway · plain HTML/CSS/JS · Maven (multi-module) · JUnit 5 · Docker (Phase 9 only)

## Documentation

| Topic | Location |
|---|---|
| Phase 0 audit | [docs/requirements/phase0-audit.md](docs/requirements/phase0-audit.md) |
| Revised requirements | [docs/requirements/requirements.md](docs/requirements/requirements.md) |
| Architecture & diagrams | [docs/architecture/architecture.md](docs/architecture/architecture.md) |
| Database model | [docs/architecture/database.md](docs/architecture/database.md) |
| Coordinator ↔ worker protocol | [docs/architecture/coordinator-worker-protocol.md](docs/architecture/coordinator-worker-protocol.md) |
| Task plugin contract | [docs/architecture/task-plugin-contract.md](docs/architecture/task-plugin-contract.md) |
| Architecture decisions | [docs/decisions/](docs/decisions/) |
| Original SRS | [QueuePilot_SRS_Official_Template.pdf](QueuePilot_SRS_Official_Template.pdf) |

Setup, running and testing instructions will be added as each phase lands.
