package com.queuepilot.model;

public enum JobState {
    SCHEDULED("Scheduled for future execution"),
    BLOCKED("Waiting for prerequisite jobs to complete"),
    QUEUED("Ready and waiting in scheduling queue"),
    RUNNING("Currently executing on a worker"),
    COMPLETED("Successfully completed"),
    FAILED("Execution failed or retries exhausted"),
    CANCELLED("Cancelled before or during execution");

    private final String description;

    JobState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean isRunnable() {
        return this == QUEUED;
    }
}
