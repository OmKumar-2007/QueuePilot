package com.queuepilot.core.job;

/** Lifecycle states of a job. Allowed transitions are defined in {@link JobStateMachine}. */
public enum JobState {
    /** Start time is in the future. */
    SCHEDULED,
    /** Waiting for prerequisite jobs to succeed. */
    BLOCKED,
    /** Ready; may be claimed once {@code readyAt} has passed (retry delay). */
    QUEUED,
    /** An attempt is executing on a worker. */
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    /** Not executed because a prerequisite did not succeed. */
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED;
    }

    /** Cancellation is only guaranteed before execution starts (SRS U4). */
    public boolean isCancellable() {
        return this == SCHEDULED || this == BLOCKED || this == QUEUED;
    }

    /** States counted against a client's active-job quota. */
    public boolean isActive() {
        return !isTerminal();
    }
}
