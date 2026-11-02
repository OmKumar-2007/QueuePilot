package com.queuepilot.model;

public enum WorkerStatus {
    IDLE("Idle and ready for tasks"),
    BUSY("Executing an assigned task"),
    UNRESPONSIVE("Missed heartbeat deadlines"),
    DEAD("Terminated or crashed");

    private final String description;

    WorkerStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isHealthy() {
        return this == IDLE || this == BUSY;
    }
}
