# Adding a Task Type

This is exactly how the example plugin in `src/plugins/ExamplePlugin.java` (prime sieve) was added. No file in the coordinator, core or worker changes.

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

- `resources/plugins/META-INF/services/taskapi.TaskExecutor` containing `plugins.ExamplePlugin$PrimeSieveTask` (a nested class is written with `$`; a normal top-level class is just `mypackage.MyTask`)
- `resources/plugins/META-INF/queuepilot/tasks/prime-sieve.json`: the manifest (schema, capabilities, timeout, retries, …)

## 4. Build and install on a worker

```bash
./mvnw -pl src/plugins -am package
```

Copy `target/plugins/queuepilot-example-plugin-1.0.0.jar` into a plugins folder and (re)start a worker with `--plugins <folder>`. Its log shows `Loaded task implementations: [..., prime-sieve@1.0.0, ...]`.

## 5. Register

The dashboard only lists task types, so register a new one through the API:

```bash
curl -H "Content-Type: application/json" \
     --data @resources/plugins/META-INF/queuepilot/tasks/prime-sieve.json \
     http://localhost:8080/api/admin/tasks
```

## 6. Submit

Submit job → *Prime sieve* → limit 50000000. Only workers that advertise `prime-sieve@1.0.0` will receive it.

To ship it as a built-in instead, add the class to `src/tasks/BuiltinTasks.java`, list it in `resources/tasks/META-INF/services/taskapi.TaskExecutor`, and put the manifest in `resources/tasks/META-INF/queuepilot/tasks/`; the coordinator seeds it at startup.

## Non-Java tasks (containers, Phase 9)

See [examples/container-tasks/python-word-stats](../../examples/container-tasks/python-word-stats/README.md). The worker must run with `--containers` and Docker available.
