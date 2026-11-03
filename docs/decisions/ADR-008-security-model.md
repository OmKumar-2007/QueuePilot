# ADR-008: Minimal security model for a local educational platform

Status: Accepted (2026-11-03)

## Decision
- **Admin**: HTTP Basic via Spring Security; username/password from environment variables (`QP_ADMIN_USER`, `QP_ADMIN_PASSWORD`). Required for task registry writes, policy changes, worker management and failure simulation.
- **Workers**: enrollment secret (`QP_WORKER_SECRET`) → per-worker random bearer token (stored hashed) + per-attempt fencing token.
- **Clients**: unauthenticated in v1.0; identify via `X-Client-Id`, used only for quotas. Documented as *not* a security boundary.
- Secrets are never committed (`.env` is git-ignored) or logged; error responses use a uniform `{code, message, field}` body without stack traces.
- The coordinator binds to `localhost` by default.

## Amendment (2026-11-03): admin authentication off by default

For the classroom demo the team asked to remove the admin password. Administrator endpoints are now open unless `QP_ADMIN_AUTH=true`, in which case the HTTP Basic rules above apply unchanged (the test suite runs with authentication on, plus one test with it off). This is acceptable only because the coordinator binds to 127.0.0.1; worker endpoints still require the enrollment secret and per-worker tokens.

## Consequences
Meets SRS §4.2 without OAuth/JWT complexity. Real client authentication is a documented limitation.
