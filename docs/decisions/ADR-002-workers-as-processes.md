# ADR-002: Workers are independent local JVM processes

Status: Accepted (2026-11-03)

## Context
Worker threads inside the coordinator cannot genuinely crash independently, share the coordinator's memory, and turn "failure recovery" into a simulation.

## Decision
`queuepilot-worker` is a separate executable jar. Several instances run on the same machine, each with its own name, capabilities and concurrency limit (an internal thread pool).

## Consequences
- (+) Killing a worker process is a real failure; recovery logic is exercised honestly.
- (+) Plugins run outside the coordinator.
- (−) Needs a network protocol (ADR-003) and worker authentication (ADR-008).
- (−) Workers are on one machine; we never claim multi-machine distribution.
