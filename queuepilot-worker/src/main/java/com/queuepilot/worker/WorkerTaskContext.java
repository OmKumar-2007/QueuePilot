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
 * The {@link TaskContext} handed to a plugin for one attempt. Throttles progress reports (at most
 * one network call per {@value #MIN_REPORT_INTERVAL_MS} ms, plus the final 100 %), and tracks why
 * an attempt was cancelled.
 */
public class WorkerTaskContext implements TaskContext {

    private static final Logger log = LoggerFactory.getLogger(WorkerTaskContext.class);
    static final long MIN_REPORT_INTERVAL_MS = 500;

    /** Why an attempt must stop. */
    public enum CancelReason { REVOKED, TIMED_OUT, SHUTDOWN }

    private final CoordinatorApi api;
    private final Assignment assignment;
    private final Path workDir;
    private final AtomicReference<CancelReason> cancelReason = new AtomicReference<>();
    private final Map<String, Path> downloaded = new ConcurrentHashMap<>();
    private long lastReportMillis;

    public WorkerTaskContext(CoordinatorApi api, Assignment assignment, Path workDir) {
        this.api = api;
        this.assignment = assignment;
        this.workDir = workDir;
    }

    @Override
    public synchronized void reportProgress(int percent, String message) {
        long now = System.currentTimeMillis();
        if (isCancelled() || (percent < 100 && now - lastReportMillis < MIN_REPORT_INTERVAL_MS)) {
            return;
        }
        lastReportMillis = now;
        try {
            boolean accepted = api.progress(assignment.attemptId(),
                    new ProgressReport(assignment.fencingToken(), percent, message));
            if (!accepted) {
                cancel(CancelReason.REVOKED);
            }
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Progress report for attempt {} failed: {}", assignment.attemptId(), e.getMessage());
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
        if (!assignment.inputArtifactIds().contains(artifactId)) {
            throw new IOException("Artifact " + artifactId + " is not an input of this job");
        }
        Path cached = downloaded.get(artifactId);
        if (cached != null) {
            return cached;
        }
        Path target = Files.createDirectories(workDir.resolve("inputs")).resolve(artifactId);
        api.downloadInput(assignment.attemptId(), assignment.fencingToken(), artifactId, target);
        downloaded.put(artifactId, target);
        return target;
    }

    @Override
    public String saveOutputFile(Path file, String contentType) throws IOException {
        return api.uploadOutput(assignment.attemptId(), assignment.fencingToken(), file, contentType);
    }
}
