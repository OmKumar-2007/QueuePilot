package com.queuepilot.core.job;

/** Health / availability of a registered worker. */
public enum WorkerStatus {
    /** Healthy and accepting new work. */
    ONLINE,
    /** Healthy, finishing current attempts, accepting nothing new. */
    DRAINING,
    /** Healthy and heartbeating, but told by an administrator to hold all work. */
    PAUSED,
    /** Heartbeats stopped or the worker deregistered; must re-register. */
    OFFLINE,
    /** Switched off by an administrator. */
    DISABLED;

    public boolean acceptsWork() {
        return this == ONLINE;
    }
}
