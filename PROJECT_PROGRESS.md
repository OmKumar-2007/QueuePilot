# Project Progress

## Roadmap

| # | Phase | Status |
|---|---|---|
| 0 | Repository and requirements audit | ✅ Done (2026-11-03) |
| 1 | System architecture and design | ✅ Proposed (2026-11-03) — awaiting team review |
| 2 | Project foundation: multi-module Maven, Spring Boot, PostgreSQL + Flyway, health API, config, logging, security skeleton | Next |
| 3 | Core job management: task definitions, jobs, validation, persistence, REST | Planned |
| 4 | Scheduling engine: policies, readiness, fairness, quotas, claim logic | Planned |
| 5 | Task plugin architecture: `task-api`, built-in plugins, manifests, "add a task without touching the scheduler" demo | Planned |
| 6 | Worker processes: registration, claiming, thread pool, progress, results, artifacts | Planned |
| 7 | Reliability: heartbeats, leases, timeouts, retries/backoff, fencing, failure simulation | Planned |
| 8 | Workflows: DAG submission, cycle detection, blocked/skipped, progress | Planned |
| 9 | Sandboxed container execution (stretch) | Planned |
| 10 | Dashboard (all pages) | Planned — simple pages are added alongside Phases 3–8 so each phase is demoable |
| 11 | `queuepilot-benchmarks` | Planned |
| 12 | Integration, failure testing, security review, demo prep | Planned |

Order change vs. the original roadmap: minimal dashboard views are built with each backend phase instead of all at the end, so the team can demo progress weekly. Phase 10 then polishes and completes them.

## Current state

- Original prototype preserved: git tag `v0-legacy`; its 26 tests pass on JDK 21 via `./mvnw test`.
- New documentation: requirements, audit, architecture, database, protocol, plugin contract, 10 ADRs.
- No new application code yet.

## Known issues / risks

- PostgreSQL and Docker are not installed on the development machine. PostgreSQL is needed from Phase 2; Docker only for Phase 9.
- Maven is only available through the wrapper (`mvnw`), which is fine.
- Client authentication is out of scope for v1.0 (ADR-008).

## Next steps

1. Team reviews `docs/architecture/architecture.md` and the ADRs.
2. Install PostgreSQL 16+ locally.
3. Start Phase 2.
