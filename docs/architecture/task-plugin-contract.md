# Task Plugin Contract

A task type has two halves that are deliberately separate:

| Half | Lives in | Owned by | Contains |
|---|---|---|---|
| **Task definition** (metadata) | Coordinator database | Administrator | type, version, capabilities, schemas, limits, timeout, retries, priority, enabled |
| **Task implementation** (code) | Worker process | Plugin author | the `TaskExecutor` that does the work |

They meet through the identifier `taskType@version`. The coordinator schedules using the definition; a worker can claim a job only if it advertises an implementation for exactly that identifier.

## Two kinds of plugins (ADR-005)

| | Java executor plugin (Phases 5–6) | Packaged runtime plugin (Phase 9) |
|---|---|---|
| Form | A jar containing `TaskExecutor` implementations + manifest | A container image (e.g. Python) + manifest |
| Runs | Inside the worker JVM, on a pool thread | In a sandboxed container started by the worker |
| Trust | Trusted: installed by the admin into the worker's `plugins/` folder | Untrusted code is acceptable *only* because of the sandbox |
| Overhead | Microseconds to start | ~0.5–2 s container start |
| Isolation | None beyond the worker process | CPU / memory / PIDs / network / FS limits |
| Languages | Java | Any language the image provides |
| Good for | Built-in and team-authored tasks | Compilation & test runs, scripts, video tools |

Neither kind is ever loaded into the coordinator.

## Java contract (`queuepilot-task-api`)

```java
public interface TaskExecutor {
    String taskType();                 // "sorting"
    String version();                  // "1.0.0"
    TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure;
}

public interface TaskContext {
    void reportProgress(int percent, String message); // real progress from the work loop
    boolean isCancelled();                             // checked inside long loops
    Path workDirectory();                              // private temp dir for this attempt
    Path inputFile(String artifactId) throws IOException;   // downloaded on demand
    String saveOutputFile(Path file) throws IOException;     // uploaded, returns artifact id
}
```

- `TaskInput` wraps the validated JSON input (typed getters: `getInt`, `getString`, `getIntList`, …).
- `TaskOutput` is a small JSON object plus optional output artifact IDs.
- `TaskFailure` carries `retryable` (e.g. transient I/O) vs permanent (e.g. input that validates but cannot be processed). Any other exception is treated as retryable.
- Implementations are discovered with `java.util.ServiceLoader` via `META-INF/services/com.queuepilot.task.TaskExecutor`.

## Manifest (`queuepilot-task.json`)

```json
{
  "taskType": "prime-sieve",
  "version": "1.0.0",
  "displayName": "Prime sieve",
  "executorKind": "JAVA_PLUGIN",
  "requiredCapabilities": ["cpu"],
  "inputSchema": {
    "fields": [
      { "name": "limit", "type": "INTEGER", "required": true, "min": 10, "max": 200000000 }
    ]
  },
  "outputSchema": {
    "fields": [
      { "name": "primeCount", "type": "INTEGER" },
      { "name": "largestPrime", "type": "INTEGER" }
    ]
  },
  "resources": { "cpuMillis": 1000, "memoryMb": 512 },
  "timeoutSeconds": 120,
  "maxRetries": 3,
  "defaultPriority": 5,
  "defaultEstimatedMs": 8000
}
```

Field types supported by the built-in validator (ADR-007): `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `ENUM` (`values`), `STRING_LIST`, `INTEGER_LIST`, `NUMBER_LIST`, `FILE` (artifact ID). Constraints: `required`, `min`, `max`, `maxLength`, `maxItems`, `values`.

## Adding a new task type — the target workflow (F17)

1. Create a Maven project depending only on `queuepilot-task-api`.
2. Implement `TaskExecutor`; add the `META-INF/services` line and the manifest.
3. `mvn package` → drop the jar into `queuepilot-worker/plugins/`, restart that worker.
4. In the dashboard (Task Registry → Register), paste/upload the manifest; enable it.
5. Submit a job. **No change** to the coordinator, scheduler or core.

Phase 5 ends with a test that proves step 5 by adding a `prime-sieve` task in a separate module.

## Versioning rules

- `(taskType, version)` is immutable once any job references it; changes require a new version.
- A job pins the version resolved at submission (latest enabled if not specified).
- Disabling a version stops new submissions; queued jobs for it still run.
