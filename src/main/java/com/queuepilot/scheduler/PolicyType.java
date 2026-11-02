package com.queuepilot.scheduler;

public enum PolicyType {
    FCFS("First Come, First Served"),
    SJF("Shortest Job First"),
    PRIORITY_AGING("Priority Scheduling with Aging"),
    MLFQ("Multi-Level Feedback Queue");

    private final String description;

    PolicyType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
