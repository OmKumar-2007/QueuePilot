package com.queuepilot.task;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Services a worker offers to a running task. One context exists per execution attempt.
 */
public interface TaskContext {

    /**
     * Reports real progress from the task's own work loop. The worker throttles network calls,
     * so tasks may call this often.
     */
    void reportProgress(int percent, String message);

    /**
     * True when the attempt was revoked, timed out or the worker is shutting down.
     * Long loops must check this (or use {@link ProgressTracker}) and stop early.
     */
    boolean isCancelled();

    /** A private, empty directory for this attempt; deleted after the attempt ends. */
    Path workDirectory();

    /** Downloads (once) and returns a local copy of an input artifact referenced by the job input. */
    Path inputFile(String artifactId) throws IOException;

    /** Uploads a file as an output artifact of the job and returns its artifact id. */
    String saveOutputFile(Path file, String contentType) throws IOException;
}
