package com.queuepilot.core.job;

/** Outcome of one execution attempt of a job on a worker. */
public enum AttemptState {
    RUNNING,
    SUCCEEDED,
    /** The worker reported an error. */
    FAILED,
    /** Ran longer than the task definition's timeout. */
    TIMED_OUT,
    /** No heartbeat renewed the lease in time. */
    LEASE_EXPIRED,
    /** The worker was declared offline, deregistered or was killed by failure simulation. */
    ABANDONED
}
