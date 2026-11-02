# ADR-004: PostgreSQL is the source of truth; Flyway migrations; optimistic locking

Status: Accepted (2026-11-03)

## Decision
- Every state change is a DB transaction; an API success response is sent only after commit.
- Schema managed by Flyway SQL migrations (readable, versioned SQL the team can explain) instead of `ddl-auto=update`.
- Concurrent claims are protected by optimistic locking (`@Version` on `job`). With one coordinator this is enough; `SELECT … FOR UPDATE SKIP LOCKED` is not needed.
- JSON stored as `TEXT` for portability; unit/slice tests use H2 in PostgreSQL mode; an integration profile runs against real PostgreSQL.
- Spring Data JPA for entities; plain `JdbcTemplate` only for metrics aggregate queries where JPQL would be awkward.

## Consequences
Coordinator restarts lose nothing. Slightly more DB traffic than an in-memory queue — irrelevant at our scale (hundreds of jobs). PostgreSQL must be installed locally (not present on the development machine today).
