# Project Progress

## Roadmap

| # | Phase | Status | Main artefacts |
|---|---|---|---|
| 0 | Repository and requirements audit | ✅ Done | `docs/requirements/phase0-audit.md`, git tag `v0-legacy` |
| 1 | Architecture and design | ✅ Done | `docs/architecture/*`, `docs/decisions/ADR-001…010` |
| 2 | Foundation | ✅ Done | Multi-module Maven build, Spring Boot coordinator, Flyway schema, H2 (PostgreSQL mode) default + `postgres` profile, health API, security skeleton, secrets handling |
| 3 | Core job management | ✅ Done | Task definitions, job submission/validation/persistence, queries, cancel, quotas, deadlines |
| 4 | Scheduling engine | ✅ Done | FCFS, Priority+Aging, SJF, readiness, round-robin queue fairness, capability matching, race-free claims |
| 5 | Plugin architecture | ✅ Done | `queuepilot-task-api`, 4 built-in tasks, manifests, `queuepilot-example-plugin` added without core changes |
| 6 | Worker processes | ✅ Done | `queuepilot-worker`: registration, claim loop, slot semaphore, real progress, files, timeouts |
| 7 | Reliability | ✅ Done | Heartbeats, leases, fencing tokens, retry with backoff, task timeouts, offline detection, kill/freeze simulation, drain |
| 8 | Workflows | ✅ Done | DAG submission, cycle detection, release on success, transitive skip, graph view |
| 9 | Isolated execution | ⚠️ Implemented, **not verified** | `ContainerTaskRunner`, `DockerCommandBuilder` (sandbox flags unit-tested), isolation tests skip without Docker; example Python task |
| 10 | Dashboard | ✅ Done | 8 pages, admin login, live polling, DAG rendering, failure simulation controls |
| 11 | Benchmarks | ✅ Done | Separate module: simulated policy comparison, measured worker scaling, reports + publishing |
| 12 | Integration and verification | ✅ Done | Full test suite, live end-to-end run with real crash, documentation |

## Iteration 2 (2026-11-03): demo usability

- Demo pacing (`--pace-ms`) so jobs take ~15-30 s; marked in job output.
- Worker controls: pause/resume, interrupt, freeze with automatic thaw, crash, add worker from the dashboard.
- Admin password removed by default (`QP_ADMIN_AUTH=true` re-enables it).
- Demo script starts four workers.
- Dashboard redesigned around a single Live page; controls are no longer re-created on each refresh (clicks were being lost).
- Ideas taken from the original prototype's tests (github.com/SubhamJM/QueuePilot): readable `@DisplayName`s, a 4-worker concurrent run, crash/recovery on a different worker. They are now real end-to-end tests over HTTP (`RealWorkersEndToEndTest`) instead of in-memory threads.

## Iteration 3 (2026-11-04): simpler classes

Fields and features not needed for a course project were removed to keep the code easy to follow:

- **Security**: Spring Security, admin login, worker enrollment secret and bearer tokens, fencing tokens. Workers send their id in `X-Worker-Id`; stale results are still rejected because only the job's current attempt may update it.
- **Task versions, enable/disable, capabilities, resources, output schema, Docker/container tasks** (Phase 9 code deleted).
- **Clients and quotas, deadlines, scheduled start (`SCHEDULED` state), job names** (jobs show `taskType #id`).
- Error code + message merged into one `error` field on jobs and attempts; artifact `role`, `storageKey` and `clientId` removed; most settings became constants.
- New built-in task: **Custom JavaScript** (upload a `.js` file defining `run(input, progress)`).

The database schema was rewritten in `V1__init.sql` (V2 merged into it): delete `data/db` before starting an old installation.
`docs/architecture`, `docs/design` and the ADRs still describe the earlier design in places.

## Verification status (2026-11-03)

- `./mvnw verify`: 99 tests, 0 failures; container isolation tests do not run (Docker absent). See `docs/testing/testing-strategy.md` for the requirement-to-test map.
- Live run: coordinator + 2 worker processes; plugin routing, real crash (exit 137) and recovery on the other worker, file artifacts, workflow ordering, cyclic workflow rejection all confirmed.
- Benchmarks: simulated (3 scenarios × 3 policies × 5 seeds) and measured (1/2/4 workers, 2 workloads) reports produced and displayed.
- Dashboard: all pages checked in a browser; no console errors.

## Known limitations

- Container isolation untested on real Docker (Phase 9).
- Default database is embedded H2 in PostgreSQL mode; PostgreSQL is supported (`postgres` profile) and exercised by one integration test using embedded PostgreSQL binaries, but no locally installed PostgreSQL server was used.
- Single coordinator; workers are local processes; no multi-machine testing.
- Clients are not authenticated; `X-Client-Id` only drives quotas.
- The scheduler considers the 500 oldest claimable jobs per decision.
- Progress updates are throttled to two per second per attempt.
- No cancellation of running jobs (by design, SRS U4).

## Possible next steps

1. Verify Phase 9 on a machine with Docker; publish the example image to a registry and pin its digest.
2. Client authentication (API keys) so per-client quotas cannot be bypassed.
3. Server-Sent Events instead of polling for the dashboard.
4. A fourth policy (e.g. MLFQ or weighted fair queuing) to demonstrate scheduler extensibility.
