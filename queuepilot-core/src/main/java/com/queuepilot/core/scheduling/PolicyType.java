package com.queuepilot.core.scheduling;

import java.time.Duration;

public enum PolicyType {
    FCFS("First Come, First Served"),
    PRIORITY_AGING("Priority with Aging"),
    SJF("Shortest Job First");

    private final String displayName;

    PolicyType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Creates the policy; aging settings are ignored by policies that do not age. */
    public SchedulingPolicy create(Duration agingInterval, int agingStep) {
        return switch (this) {
            case FCFS -> new FcfsPolicy();
            case PRIORITY_AGING -> new PriorityAgingPolicy(agingInterval, agingStep);
            case SJF -> new SjfPolicy();
        };
    }
}
