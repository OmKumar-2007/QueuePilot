# Adding a Task Type

This is exactly how `queuepilot-example-plugin` (prime sieve) was added. No file in the coordinator, core or worker changes.

## 1. Create a plugin project

Depend only on the task API (scope `provided`, because the worker already has it):

```xml
<dependency>
    <groupId>com.queuepilot</groupId>
    <artifactId>queuepilot-task-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

## 2. Implement `TaskExecutor`

```java
public class PrimeSieveTask implements TaskExecutor {
    public String taskType() { return "prime-sieve"; }
    public String version()  { return "1.0.0"; }

    public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
        int limit = input.getInt("limit", 0);
        ProgressTracker progress = new ProgressTracker(context, limit);
        // ... do the real work, calling progress.advance(done, "message") in the loop ...
        return TaskOutput.create().put("primeCount", count);
    }
}
```

Rules:
- Keep per-run state in local variables; one instance serves all threads.
- Report progress from the real loop (never a timer). `ProgressTracker` also stops the task when the attempt is cancelled.
- Throw `TaskFailure.permanent(...)` for unusable input, `TaskFailure.retryable(...)` for transient problems.
- Use `context.workDirectory()`, `context.inputFile(id)`, `context.saveOutputFile(path, type)` for files.

## 3. Declare it

- `src/main/resources/META-INF/services/com.queuepilot.task.TaskExecutor` containing `com.example.primes.PrimeSieveTask`
- `src/main/resources/META-INF/queuepilot/tasks/prime-sieve.json`: the manifest (schema, capabilities, timeout, retries, …)

## 4. Build and install on a worker

```bash
./mvnw -pl queuepilot-example-plugin package
```

Copy `queuepilot-example-plugin/target/queuepilot-example-plugin-1.0.0.jar` into a plugins folder and (re)start a worker with `--plugins <folder>`. Its log shows `Loaded task implementations: [..., prime-sieve@1.0.0, ...]`.

## 5. Register and enable

Dashboard → **Task registry** → paste the manifest → *Register*, then open it and *Enable*. Or:

```bash
curl -u admin:$QP_ADMIN_PASSWORD -H "Content-Type: application/json" \
     --data @queuepilot-example-plugin/src/main/resources/META-INF/queuepilot/tasks/prime-sieve.json \
     http://localhost:8080/api/admin/tasks
```

## 6. Submit

Submit job → *Prime sieve* → limit 50000000. Only workers that advertise `prime-sieve@1.0.0` will receive it.

To ship it as a built-in instead, add the class to `queuepilot-tasks-builtin` and the manifest to its `META-INF/queuepilot/tasks/`; the coordinator seeds it at startup.

## Non-Java tasks (containers, Phase 9)

See [examples/container-tasks/python-word-stats](../../examples/container-tasks/python-word-stats/README.md). The worker must run with `--containers` and Docker available.
