package com.queuepilot.scheduler;

public enum PolicyType {
    FCFS("First Come, First Served"),
    SJF("Shortest Job First"),
    PRIORITY_AGING("Priority Scheduling with Aging");

    private final String description;

    PolicyType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
