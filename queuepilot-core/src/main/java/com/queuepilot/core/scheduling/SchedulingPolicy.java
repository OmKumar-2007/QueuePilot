package com.queuepilot.core.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Strategy for ordering runnable jobs. Implementations are pure functions of their input and
 * {@code now}: no database, no clock, no side effects. That lets the coordinator and the
 * benchmark simulator use exactly the same code.
 */
public interface SchedulingPolicy {

    PolicyType type();

    /** Returns the jobs in the order they should be dispatched (first = next). */
    List<SchedulableJob> order(Collection<SchedulableJob> jobs, Instant now);
}
