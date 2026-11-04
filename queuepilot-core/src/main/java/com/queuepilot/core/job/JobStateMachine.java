package com.queuepilot.core.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.queuepilot.core.job.JobState.BLOCKED;
import static com.queuepilot.core.job.JobState.CANCELLED;
import static com.queuepilot.core.job.JobState.FAILED;
import static com.queuepilot.core.job.JobState.QUEUED;
import static com.queuepilot.core.job.JobState.RUNNING;
import static com.queuepilot.core.job.JobState.SKIPPED;
import static com.queuepilot.core.job.JobState.SUCCEEDED;

/**
 * The single place that defines which job state changes are legal.
 * See the state diagram in docs/architecture/architecture.md §7.
 */
public final class JobStateMachine {

    private static final Map<JobState, Set<JobState>> ALLOWED = new EnumMap<>(JobState.class);

    static {
        ALLOWED.put(BLOCKED, EnumSet.of(QUEUED, SKIPPED, CANCELLED));
        ALLOWED.put(QUEUED, EnumSet.of(RUNNING, CANCELLED));
        // RUNNING -> QUEUED is a retry after a failed, expired or timed-out attempt.
        ALLOWED.put(RUNNING, EnumSet.of(SUCCEEDED, FAILED, QUEUED));
        for (JobState terminal : EnumSet.of(SUCCEEDED, FAILED, CANCELLED, SKIPPED)) {
            ALLOWED.put(terminal, EnumSet.noneOf(JobState.class));
        }
    }

    private JobStateMachine() {
    }

    public static boolean canTransition(JobState from, JobState to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void check(JobState from, JobState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException("Job cannot move from " + from + " to " + to);
        }
    }
}
