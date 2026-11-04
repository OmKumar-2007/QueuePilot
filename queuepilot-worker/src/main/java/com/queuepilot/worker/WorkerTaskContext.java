package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.ProgressReport;
import com.queuepilot.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
public class WorkerTaskContext implements TaskContext {

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

    public long paceMsPerPercent() {
        return paceMsPerPercent;
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
