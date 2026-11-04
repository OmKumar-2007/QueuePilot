package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.CompletionReport;
import com.queuepilot.core.protocol.WorkerProtocol.FailureReport;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import com.queuepilot.worker.WorkerTaskContext.CancelReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Executes one assignment on a pool thread: picks the executor, enforces the timeout, reports the
 * outcome and always cleans up. The outcome reported depends on why the task stopped:
 * <ul>
 *   <li>finished normally -> complete</li>
 *   <li>threw {@link TaskFailure} -> fail (retryable flag from the task)</li>
 *   <li>local timeout -> fail with TIMEOUT (retryable)</li>
 *   <li>interrupted by an administrator -> fail with INTERRUPTED (retryable, so it is requeued)</li>
 *   <li>revoked by the coordinator or shutdown -> report nothing; the attempt is no longer ours</li>
 * </ul>
 */
public class AttemptRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AttemptRunner.class);

    private final Assignment assignment;
    private final TaskExecutorRegistry registry;
    private final CoordinatorApi api;
    private final ActiveAttempts active;
    private final ScheduledExecutorService timer;
    private final Path workRoot;
    private final long paceMsPerPercent;
    private final PauseGate pauseGate;
    private final Runnable onFinished;

    public AttemptRunner(Assignment assignment, TaskExecutorRegistry registry, CoordinatorApi api, ActiveAttempts active, ScheduledExecutorService timer, Path workRoot,
                         long paceMsPerPercent, PauseGate pauseGate, Runnable onFinished) {
        this.assignment = assignment;
        this.registry = registry;
        this.api = api;
        this.active = active;
        this.timer = timer;
        this.workRoot = workRoot;
        this.paceMsPerPercent = paceMsPerPercent;
        this.pauseGate = pauseGate;
        this.onFinished = onFinished;
    }

    @Override
    public void run() {
        Thread thread = Thread.currentThread();
        Path workDir = workRoot.resolve(assignment.attemptId());
        WorkerTaskContext context = new WorkerTaskContext(api, assignment, workDir, paceMsPerPercent, pauseGate);
        active.add(assignment.attemptId(), context);
        ScheduledFuture<?> timeout = timer.schedule(() -> {
            context.cancel(CancelReason.TIMED_OUT);
            thread.interrupt();
        }, assignment.timeoutSeconds(), TimeUnit.SECONDS);

        log.info("Attempt {} of job {} ({}) started", assignment.attemptNumber(), assignment.jobId(), assignment.taskType());
        try {
            Files.createDirectories(workDir);
            TaskOutput output = execute(context);
            context.reportProgress(100, "Finishing");
            if (context.isCancelled()) {
                reportStopped(context, "Task returned after being cancelled");
            } else {
                Map<String, Object> values = new LinkedHashMap<>(output.values());
                if (paceMsPerPercent > 0) {
                    // Make paced (demo) runs recognisable in the job output.
                    values.put("demoPacingMsPerPercent", paceMsPerPercent);
                }
                boolean accepted = api.complete(assignment.attemptId(), new CompletionReport(values, output.artifactIds()));
                log.info("Attempt {} of job {} completed{}", assignment.attemptNumber(), assignment.jobId(),
                        accepted ? "" : " but the coordinator rejected the result (stale attempt)");
            }
        } catch (TaskFailure failure) {
            if (context.isCancelled()) {
                reportStopped(context, failure.getMessage());
            } else {
                report(failure.getCode(), failure.getMessage(), failure.isRetryable());
            }
        } catch (OutOfMemoryError e) {
            report("OUT_OF_MEMORY", "Task ran out of memory", true);
        } catch (Exception e) {
            if (context.isCancelled()) {
                reportStopped(context, e.toString());
            } else {
                log.warn("Attempt {} crashed", assignment.attemptId(), e);
                report("EXECUTION_ERROR", e.toString(), true);
            }
        } finally {
            timeout.cancel(false);
            Thread.interrupted();
            active.remove(assignment.attemptId());
            deleteQuietly(workDir);
            onFinished.run();
        }
    }

    private TaskOutput execute(WorkerTaskContext context) throws TaskFailure {
        TaskExecutor executor = registry.find(assignment.taskType()).orElseThrow(() ->
                TaskFailure.retryable("NO_EXECUTOR", "Worker has no implementation of " + assignment.taskType()));
        return executor.execute(new TaskInput(assignment.input()), context);
    }

    private void reportStopped(WorkerTaskContext context, String detail) {
        if (context.cancelReason() == CancelReason.TIMED_OUT) {
            report("TIMEOUT", "Exceeded " + assignment.timeoutSeconds() + "s", true);
        } else if (context.cancelReason() == CancelReason.INTERRUPTED) {
            report("INTERRUPTED", "Interrupted by an administrator", true);
        } else {
            log.info("Attempt {} stopped ({}): {}", assignment.attemptId(), context.cancelReason(), detail);
        }
    }

    private void report(String code, String message, boolean retryable) {
        try {
            api.fail(assignment.attemptId(), new FailureReport(code, message, retryable));
            log.info("Attempt {} of job {} failed: {} {}", assignment.attemptNumber(), assignment.jobId(), code, message);
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Could not report failure of attempt {}: {}", assignment.attemptId(), e.getMessage());
        }
    }

    static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete {}", path);
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean {}", dir);
        }
    }
}
