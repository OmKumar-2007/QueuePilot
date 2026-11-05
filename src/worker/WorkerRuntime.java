package worker;

import core.WorkerProtocol.Assignment;
import core.WorkerProtocol.CompletionReport;
import core.WorkerProtocol.FailureReport;
import core.WorkerProtocol.HeartbeatResponse;
import core.WorkerProtocol.ProgressReport;
import core.WorkerProtocol.RegisterRequest;
import core.WorkerProtocol.RegisterResponse;
import core.WorkerProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import taskapi.TaskExecutor.TaskContext;
import taskapi.TaskExecutor.TaskFailure;
import taskapi.TaskExecutor.TaskInput;
import taskapi.TaskExecutor.TaskOutput;
import taskapi.TaskExecutor;
import worker.WorkerMain.TaskExecutorRegistry;
import worker.WorkerMain.WorkerConfig;
import worker.WorkerTaskContext.CancelReason;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Wires a worker together and runs its activities:
 * <ul>
 *   <li><b>commands</b> from the coordinator (on heartbeat replies): PAUSE / RESUME, INTERRUPT,
 *       FREEZE (stop heartbeating for a while), KILL (exit abruptly), REREGISTER,</li>
 *   <li><b>claim loop</b> (one thread): asks for work whenever a slot is free,</li>
 *   <li><b>execution pool</b> ({@code slots} threads): runs {@link AttemptRunner}s,</li>
 *   <li><b>heartbeat</b> (scheduled): renews leases of all active attempts and obeys commands.</li>
 * </ul>
 * A {@link Semaphore} with one permit per slot guarantees the worker never holds more attempts than
 * it advertised.
 */
public class WorkerRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

    private final WorkerConfig config;
    private final CoordinatorApi api;
    private final TaskExecutorRegistry registry;
    private final ActiveAttempts active = new ActiveAttempts();
    private final Semaphore slots;
    private final ExecutorService pool;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean();
    private final PauseGate pauseGate = new PauseGate();
    private volatile long frozenUntilMillis;
    /** How long to wait before asking again when there was no work. */
    private static final long CLAIM_POLL_MS = 1000;
    private volatile String workerId;
    private Thread claimThread;

    public WorkerRuntime(WorkerConfig config, CoordinatorApi api, TaskExecutorRegistry registry) {
        this.config = config;
        this.api = api;
        this.registry = registry;
        this.slots = new Semaphore(config.slots());
        this.pool = Executors.newFixedThreadPool(config.slots(), runnable -> {
            Thread thread = new Thread(runnable, "qp-exec-" + config.name());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Registers (retrying until the coordinator is reachable) and starts claiming and heartbeating. */
    public void start() throws InterruptedException {
        RegisterResponse registration = registerWithRetry();
        running.set(true);
        scheduler.scheduleAtFixedRate(this::heartbeatSafely, registration.heartbeatIntervalMs(),
                registration.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        claimThread = new Thread(this::claimLoop, "qp-claim-" + config.name());
        claimThread.start();
    }

    public String workerId() {
        return workerId;
    }

    public int activeAttempts() {
        return active.size();
    }

    public boolean isPaused() {
        return pauseGate.isPaused();
    }

    public boolean isFrozen() {
        return System.currentTimeMillis() < frozenUntilMillis;
    }

    private RegisterResponse registerWithRetry() throws InterruptedException {
        RegisterRequest request = new RegisterRequest(config.name(), config.slots(), registry.taskTypes(),
                config.paceMsPerPercent());
        long backoff = 1000;
        while (true) {
            try {
                RegisterResponse response = api.register(request);
                workerId = response.workerId();
                log.info("Registered as {} ({}) with {} slots; tasks {}{}", config.name(), workerId,
                        config.slots(), registry.taskTypes(),
                        config.paceMsPerPercent() > 0 ? "; DEMO PACING " + config.paceMsPerPercent() + " ms per percent" : "");
                return response;
            } catch (CoordinatorApi.CoordinatorException e) {
                // 400 = bad settings, 409 = worker limit reached: retrying straight away would not help.
                if (e.status() == 400 || e.status() == 409) {
                    throw new IllegalStateException("Registration rejected: " + e.getMessage());
                }
                log.warn("Coordinator not reachable ({}); retrying in {} ms", e.getMessage(), backoff);
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 15_000);
            }
        }
    }

    private void claimLoop() {
        while (running.get()) {
            try {
                pauseGate.awaitOpen(() -> !running.get());
                slots.acquire();
                Optional<Assignment> assignment = claimSafely();
                if (assignment.isEmpty()) {
                    slots.release();
                    Thread.sleep(CLAIM_POLL_MS);
                    continue;
                }
                pool.submit(new AttemptRunner(assignment.get(), registry, api, active, scheduler,
                        config.workDir(), config.paceMsPerPercent(), pauseGate, slots::release));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Optional<Assignment> claimSafely() {
        try {
            return api.claim();
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Claim failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void heartbeatSafely() {
        if (isFrozen()) {
            return;
        }
        try {
            HeartbeatResponse response = api.heartbeat(active.ids());
            response.revoked().forEach(id -> {
                log.warn("Coordinator revoked attempt {}; stopping it", id);
                active.cancel(id, CancelReason.REVOKED);
            });
            handleCommand(response.command());
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected heartbeat error", e);
        }
    }

    private void handleCommand(String commands) {
        if (commands == null || commands.isBlank()) {
            return;
        }
        for (String command : commands.split(",")) {
            switch (command.trim()) {
                case WorkerProtocol.COMMAND_KILL -> {
                    log.error("KILL received (failure simulation): exiting immediately without reporting");
                    Runtime.getRuntime().halt(137);
                }
                case WorkerProtocol.COMMAND_FREEZE -> {
                    log.error("FREEZE received (failure simulation): no heartbeats for {} ms, tasks keep running", config.freezeMs());
                    frozenUntilMillis = System.currentTimeMillis() + config.freezeMs();
                }
                case WorkerProtocol.COMMAND_PAUSE -> {
                    log.warn("PAUSE received: no new work, running tasks hold at their next checkpoint");
                    pauseGate.pause();
                }
                case WorkerProtocol.COMMAND_RESUME -> {
                    log.info("RESUME received");
                    pauseGate.resume();
                }
                case WorkerProtocol.COMMAND_INTERRUPT -> {
                    log.warn("INTERRUPT received: stopping {} running attempt(s)", active.size());
                    active.cancelAll(CancelReason.INTERRUPTED);
                }
                case WorkerProtocol.COMMAND_REREGISTER -> reregister();
                default -> log.warn("Unknown command {}", command);
            }
        }
    }

    private void reregister() {
        log.warn("Coordinator no longer recognises this worker; re-registering");
        active.cancelAll(CancelReason.REVOKED);
        pauseGate.resume();
        try {
            registerWithRetry();
        } catch (IllegalStateException e) {
            // Our place may have been taken while we were offline; the next heartbeat tries again.
            log.warn("Could not re-register: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Graceful shutdown: stop claiming, cancel running attempts, deregister (their jobs are retried). */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        log.info("Shutting down worker {}", config.name());
        if (claimThread != null) {
            claimThread.interrupt();
        }
        active.cancelAll(CancelReason.SHUTDOWN);
        pauseGate.resume();
        pool.shutdownNow();
        scheduler.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
            api.deregister();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Deregistration failed: {}", e.getMessage());
        }
    }
}

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
class AttemptRunner implements Runnable {

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

/**
 * The {@link TaskContext} handed to a plugin for one attempt.
 *
 * <p>Progress reports are the task's checkpoints, so this is where the worker applies:</p>
 * <ul>
 *   <li><b>pause</b>: a paused worker holds the task here until it is resumed,</li>
 *   <li><b>demo pacing</b> (optional, {@code --pace-ms}): waits {@code paceMsPerPercent} for every
 *       percent of progress, so jobs are slow enough to watch. The work itself is unchanged and the
 *       job output records the pacing, so paced runs are never mistaken for real speed,</li>
 *   <li><b>throttling</b>: at most one progress call per {@value #MIN_REPORT_INTERVAL_MS} ms.</li>
 * </ul>
 */
class WorkerTaskContext implements TaskContext {

    private static final Logger log = LoggerFactory.getLogger(WorkerTaskContext.class);
    static final long MIN_REPORT_INTERVAL_MS = 300;
    private static final long SLEEP_SLICE_MS = 50;

    /** Why an attempt must stop. */
    public enum CancelReason { REVOKED, TIMED_OUT, INTERRUPTED, SHUTDOWN }

    private final CoordinatorApi api;
    private final Assignment assignment;
    private final Path workDir;
    private final long paceMsPerPercent;
    private final PauseGate pauseGate;
    private final AtomicReference<CancelReason> cancelReason = new AtomicReference<>();
    private final Map<String, Path> downloaded = new ConcurrentHashMap<>();
    private long lastReportMillis;
    private int pacedPercent;

    public WorkerTaskContext(CoordinatorApi api, Assignment assignment, Path workDir, long paceMsPerPercent, PauseGate pauseGate) {
        this.api = api;
        this.assignment = assignment;
        this.workDir = workDir;
        this.paceMsPerPercent = paceMsPerPercent;
        this.pauseGate = pauseGate;
    }

    @Override
    public synchronized void reportProgress(int percent, String message) {
        if (isCancelled()) {
            return;
        }
        waitWhilePaused();
        if (paceMsPerPercent <= 0) {
            send(percent, message);
            return;
        }
        for (int step = pacedPercent + 1; step <= percent && !isCancelled(); step++) {
            sleepQuietly(paceMsPerPercent);
            waitWhilePaused();
            send(step, message);
        }
        pacedPercent = Math.max(pacedPercent, percent);
    }

    private void send(int percent, String message) {
        long now = System.currentTimeMillis();
        if (isCancelled() || (percent < 100 && now - lastReportMillis < MIN_REPORT_INTERVAL_MS)) {
            return;
        }
        lastReportMillis = now;
        try {
            boolean accepted = api.progress(assignment.attemptId(), new ProgressReport(percent, message));
            if (!accepted) {
                cancel(CancelReason.REVOKED);
            }
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Progress report for attempt {} failed: {}", assignment.attemptId(), e.getMessage());
        }
    }

    private void waitWhilePaused() {
        try {
            pauseGate.awaitOpen(this::isCancelled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps in short slices so cancellation is noticed quickly. */
    private void sleepQuietly(long millis) {
        long end = System.currentTimeMillis() + millis;
        try {
            while (!isCancelled() && System.currentTimeMillis() < end) {
                Thread.sleep(Math.min(SLEEP_SLICE_MS, Math.max(1, end - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelReason.get() != null;
    }

    public void cancel(CancelReason reason) {
        cancelReason.compareAndSet(null, reason);
    }

    public CancelReason cancelReason() {
        return cancelReason.get();
    }

    @Override
    public Path workDirectory() {
        return workDir;
    }

    @Override
    public Path inputFile(String artifactId) throws IOException {
        Path cached = downloaded.get(artifactId);
        if (cached != null) {
            return cached;
        }
        Path target = Files.createDirectories(workDir.resolve("inputs")).resolve(artifactId);
        api.downloadInput(assignment.attemptId(), artifactId, target);
        downloaded.put(artifactId, target);
        return target;
    }

    @Override
    public String saveOutputFile(Path file, String contentType) throws IOException {
        return api.uploadOutput(assignment.attemptId(), file, contentType);
    }
}

/**
 * Attempts currently executing in this worker. Shared by the pool threads (add/remove), the
 * heartbeat thread (list/revoke) and shutdown, hence a concurrent map.
 */
class ActiveAttempts {

    private final Map<String, WorkerTaskContext> attempts = new ConcurrentHashMap<>();

    public void add(String attemptId, WorkerTaskContext context) {
        attempts.put(attemptId, context);
    }

    public void remove(String attemptId) {
        attempts.remove(attemptId);
    }

    public List<String> ids() {
        return List.copyOf(attempts.keySet());
    }

    public void cancel(String attemptId, WorkerTaskContext.CancelReason reason) {
        WorkerTaskContext context = attempts.get(attemptId);
        if (context != null) {
            context.cancel(reason);
        }
    }

    public void cancelAll(WorkerTaskContext.CancelReason reason) {
        attempts.values().forEach(context -> context.cancel(reason));
    }

    public int size() {
        return attempts.size();
    }
}

/**
 * Lets an administrator pause a worker. While paused, the claim loop takes no new work and running
 * tasks wait at their next progress checkpoint. Heartbeats continue, so the coordinator keeps the
 * leases alive: a paused worker is healthy, just not making progress.
 */
class PauseGate {

    private boolean paused;

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Blocks while paused, returning early as soon as {@code stopWaiting} becomes true. */
    public synchronized void awaitOpen(BooleanSupplier stopWaiting) throws InterruptedException {
        while (paused && !stopWaiting.getAsBoolean()) {
            wait(100);
        }
    }
}
