# Workflow (DAG) Design

**Purpose.** Run jobs that depend on other jobs, in parallel where possible, and stop sensibly when something fails (F12, F19, F20).

## Two ways to declare dependencies

1. **Single job:** `dependsOn: [existing job ids]`. A new job cannot be a prerequisite of an existing one, so a cycle is impossible here.
2. **Workflow:** `POST /api/workflows` with steps that reference each other by key. The whole graph is validated first and created in one transaction.

```json
{ "name": "pipeline", "steps": [
  { "key": "validate", "taskType": "text-analysis", "input": {"text": "..."}, "dependsOn": [] },
  { "key": "a", "taskType": "monte-carlo-pi", "input": {"samples": 10000000}, "dependsOn": ["validate"] },
  { "key": "b", "taskType": "monte-carlo-pi", "input": {"samples": 10000000}, "dependsOn": ["validate"] },
  { "key": "combine", "taskType": "sorting", "input": {"count": 100000}, "dependsOn": ["a", "b"] } ] }
```

## Algorithm

`DependencyGraph` (core) stores `node -> prerequisites`.

- **Cycle detection:** depth-first search with three colours (white = unvisited, grey = on the current path, black = done). Meeting a grey node means the path loops; the cycle is reported as a path, e.g. `x -> y -> x`.
- **Topological order:** post-order DFS, so every prerequisite is created before its dependants (the dependant's row needs the prerequisite's id).

## Readiness and propagation (`DependencyService`)

| Event | Effect on dependants |
|---|---|
| Prerequisite **SUCCEEDED** | A BLOCKED dependant becomes QUEUED when *all* its prerequisites succeeded |
| Prerequisite **FAILED / CANCELLED / SKIPPED** | BLOCKED or SCHEDULED dependants become SKIPPED, recursively |
| Submission with a prerequisite already failed | Job is created directly as SKIPPED |

A workflow's state is derived: RUNNING until every step is terminal, then SUCCEEDED only if all steps succeeded, otherwise FAILED.

## Visualisation

The dashboard places each node in a column equal to the longest path leading to it, draws edges as curves, colours nodes by state, and shows live progress. Clicking a node opens its job.

## Tests

`DependencyGraphTest` (cycles of length 1, 2, 3, diamonds, ordering) and `WorkflowTest` (cycle rejected with nothing stored, diamond release order, transitive skip, single-job dependencies).
