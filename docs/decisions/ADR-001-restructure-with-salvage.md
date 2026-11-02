# ADR-001: Restructure into a multi-module project, salvaging sound parts

Status: Accepted (2026-11-03)

## Context
The prototype keeps all state in a `ConcurrentHashMap`, hard-codes task types in an enum with one subclass per type, runs workers as threads, and pads execution with `Thread.sleep`. The new vision needs a task registry, worker processes, DB-backed state and a separate benchmark module.

## Options
1. Evolve the single module in place.
2. Rewrite from scratch, discarding the prototype.
3. **New multi-module layout, porting the sound parts** (DAG algorithm, policy ordering rules, executor computations, algorithmic tests).

## Decision
Option 3. The prototype is preserved at git tag `v0-legacy`; its docs move to `docs/legacy/`.

## Consequences
Most classes change, but nothing proven is thrown away. The team can compare old and new designs, which is useful for the lab report.
