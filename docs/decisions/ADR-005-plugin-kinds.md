# ADR-005: Two plugin kinds — trusted Java executors and sandboxed container packages

Status: Accepted (2026-11-03)

## Context
New task types must be addable without touching the scheduler. Some future tasks (compilation, scripts, video tools) are not Java and must not be trusted.

## Options
1. Upload jars at runtime into the coordinator — rejected: arbitrary code in the coordinator.
2. Java plugins loaded by workers from a local `plugins/` folder via `ServiceLoader`, installed by the admin (trusted).
3. Container-packaged runtime plugins started by the worker in a locked-down sandbox (untrusted-capable).

## Decision
Implement 2 in Phase 5 (all built-ins use it). Implement 3 in Phase 9 as a stretch goal. Uploading executable code through the dashboard is not supported in v1.0; the dashboard registers **manifests**, not code.

## Consequences
Clear trust boundary: the coordinator never executes task code; Java plugins are as trusted as the worker; container plugins are trusted only as far as the sandbox has been tested. Comparison table in `docs/architecture/task-plugin-contract.md`.
