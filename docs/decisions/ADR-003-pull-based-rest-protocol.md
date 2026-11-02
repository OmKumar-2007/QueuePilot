# ADR-003: Pull-based REST protocol between workers and coordinator

Status: Accepted (2026-11-03)

## Options
| Option | Pros | Cons |
|---|---|---|
| **Workers poll `POST /claim` (pull)** | Simple; coordinator needs no connection to workers; natural back-pressure (workers claim only with free slots); easy to test with MockMvc | ≤ 1 s idle latency; some idle requests |
| Coordinator pushes to worker HTTP servers | Lower latency | Workers need a server + port; coordinator must track addresses; harder failure handling |
| WebSocket / gRPC streams | Low latency, bidirectional | More moving parts to learn and debug |
| Message broker (RabbitMQ/Kafka) | Industrial pattern | New infrastructure; excluded without a concrete need |

## Decision
Pull over REST with JSON. Heartbeats are batched per worker (one call renews all its leases). Claim latency of ~1 s is irrelevant for jobs lasting seconds.

## Consequences
Scheduling happens *at claim time* for the claiming worker, which naturally satisfies capability and capacity matching (F7). See `docs/architecture/coordinator-worker-protocol.md`.
