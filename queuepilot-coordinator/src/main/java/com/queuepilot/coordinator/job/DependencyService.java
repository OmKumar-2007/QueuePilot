package com.queuepilot.coordinator.job;

import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.core.job.JobState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

/**
 * Workflow readiness rules (F12, F19): releases dependants when a prerequisite succeeds and skips
 * them, transitively, when a prerequisite fails, is cancelled or is skipped.
 */
@Service
public class DependencyService {

    /** Readiness of a job's prerequisites. */
    public enum Readiness { READY, WAITING, BROKEN }

    private final JobRepository jobs;
    private final EventLog events;

    public DependencyService(JobRepository jobs, EventLog events) {
        this.jobs = jobs;
        this.events = events;
    }

    public Readiness readinessOf(Collection<String> prerequisiteIds) {
        if (prerequisiteIds.isEmpty()) {
            return Readiness.READY;
        }
        if (jobs.countBrokenAmong(prerequisiteIds) > 0) {
            return Readiness.BROKEN;
        }
        long succeeded = jobs.countByIdInAndState(prerequisiteIds, JobState.SUCCEEDED);
        return succeeded == prerequisiteIds.size() ? Readiness.READY : Readiness.WAITING;
    }

    /** Must run inside the transaction that finished {@code job}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onJobFinished(JobEntity job, Instant now) {
        jobs.flush();
        for (JobEntity dependant : jobs.findDependants(job.getId())) {
            if (job.getState() == JobState.SUCCEEDED) {
                releaseIfReady(dependant, now);
            } else if (dependant.getState() == JobState.BLOCKED) {
                dependant.skip("Prerequisite " + job.label() + " ended " + job.getState(), now);
                events.record(EventType.JOB_SKIPPED, dependant.getId(), null, null,
                        "Job " + dependant.label() + " skipped because prerequisite " + job.label() + " ended " + job.getState());
                onJobFinished(dependant, now);
            }
        }
    }

    private void releaseIfReady(JobEntity dependant, Instant now) {
        if (dependant.getState() != JobState.BLOCKED) {
            return;
        }
        if (readinessOf(dependant.getPrerequisiteIds()) == Readiness.READY) {
            dependant.release(now);
            events.record(EventType.JOB_RELEASED, dependant.getId(), null, null,
                    "Job " + dependant.label() + " unblocked: all prerequisites succeeded");
        }
    }
}
