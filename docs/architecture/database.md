# Database Model

PostgreSQL is the **source of truth** (ADR-004). Schema is created by Flyway SQL migrations (`db/migration/V1__init.sql` …), never by Hibernate `ddl-auto`. JSON payloads are stored as `TEXT` so the same SQL runs on H2 (PostgreSQL mode) in tests.

```mermaid
erDiagram
    TASK_DEFINITION ||--o{ JOB : "pins version"
    CLIENT_QUEUE ||--o{ JOB : contains
    WORKFLOW |o--o{ JOB : groups
    JOB ||--o{ JOB_DEPENDENCY : "depends on"
    JOB ||--o{ ATTEMPT : has
    WORKER ||--o{ ATTEMPT : runs
    WORKER ||--o{ WORKER_CAPABILITY : advertises
    JOB }o--o{ ARTIFACT : "input / output"
    JOB ||--o{ SYSTEM_EVENT : "described by"
    BENCHMARK_REPORT

    TASK_DEFINITION {
        uuid id PK
        varchar task_type
        varchar version
        varchar executor_kind "JAVA_PLUGIN | CONTAINER"
        text required_capabilities "comma list"
        text input_schema "JSON"
        text output_schema "JSON"
        int cpu_millis
        int memory_mb
        int timeout_seconds
        int max_retries
        int default_priority
        bigint default_estimated_ms
        varchar container_image "digest, CONTAINER only"
        boolean enabled
        timestamp created_at
    }
    CLIENT_QUEUE {
        varchar name PK
        int weight
        boolean paused
    }
    WORKFLOW {
        uuid id PK
        varchar name
        varchar client_id
        timestamp created_at
    }
    JOB {
        uuid id PK
        uuid task_definition_id FK
        uuid workflow_id FK
        varchar queue_name FK
        varchar client_id
        varchar state
        int priority
        bigint estimated_ms
        text input "JSON"
        text output "JSON"
        varchar error_code
        text error_message
        int progress_percent
        varchar progress_message
        uuid current_attempt_id
        int attempts_used
        timestamp submitted_at
        timestamp start_at
        timestamp ready_at
        timestamp deadline_at
        timestamp started_at
        timestamp finished_at
        bigint version "optimistic lock"
    }
    JOB_DEPENDENCY {
        uuid job_id PK,FK
        uuid prerequisite_id PK,FK
    }
    ATTEMPT {
        uuid id PK
        uuid job_id FK
        varchar worker_id FK
        int attempt_number
        varchar fencing_token_hash
        varchar state
        timestamp started_at
        timestamp lease_expires_at
        timestamp last_heartbeat_at
        timestamp finished_at
        text error_message
    }
    WORKER {
        varchar id PK
        varchar name
        varchar status
        int concurrency_limit
        int active_attempts
        text supported_tasks "type@version list"
        varchar token_hash
        bigint completed_count
        bigint failed_count
        timestamp registered_at
        timestamp last_heartbeat_at
    }
    WORKER_CAPABILITY {
        varchar worker_id PK,FK
        varchar capability PK
    }
    ARTIFACT {
        uuid id PK
        uuid job_id FK "null until linked"
        varchar role "INPUT | OUTPUT"
        varchar original_name
        varchar content_type
        bigint size_bytes
        varchar sha256
        varchar storage_key
        timestamp created_at
    }
    SYSTEM_EVENT {
        bigint id PK
        timestamp occurred_at
        varchar type
        uuid job_id
        varchar worker_id
        uuid attempt_id
        text message
    }
    BENCHMARK_REPORT {
        uuid id PK
        varchar kind "SIMULATED | MEASURED"
        varchar name
        text config "JSON"
        text environment "JSON"
        text summary "JSON"
        timestamp created_at
    }
```

## Notes

- **Indexes:** `job(state, ready_at)`, `job(client_id, state)`, `attempt(state, lease_expires_at)`, `worker(status, last_heartbeat_at)`, unique `task_definition(task_type, version)`.
- **Tokens are hashed** (SHA-256) — a database leak does not let anyone impersonate a worker or attempt.
- **Optimistic locking:** `job.version` is incremented by every update; a claim that loses a race gets an `OptimisticLockException` and the scheduler moves to the next candidate.
- **History:** the full story of a job is `attempt` rows + `system_event` rows; nothing is deleted during the project demonstration.
- **Artifacts** hold metadata only; bytes live under `./data/artifacts/<storage_key>` where `storage_key` is a generated UUID, never a user-supplied name.
- **Benchmark reports** are imported from files produced by `queuepilot-benchmarks`; the raw CSV stays on disk and is referenced, not copied into the DB.
