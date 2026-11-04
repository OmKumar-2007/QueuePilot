package com.queuepilot.coordinator.attempt;

import com.queuepilot.core.job.AttemptState;
import com.queuepilot.core.job.IllegalStateTransitionException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * One execution attempt of a job on a worker. Only the worker that owns a running attempt may
 * update it; a late update from an older attempt is rejected because the job's current attempt
 * id no longer matches (see {@code JobEntity.isCurrentAttempt}).
 */
@Entity
@Table(name = "attempt")
public class AttemptEntity {

    @Id
    private String id;
    private String jobId;
    private String workerId;
    private int attemptNumber;
    @Enumerated(EnumType.STRING)
    private AttemptState state;
    private Instant startedAt;
    private Instant leaseExpiresAt;
    private Instant finishedAt;
    private String error;

    protected AttemptEntity() {
    }

    public AttemptEntity(String id, String jobId, String workerId, int attemptNumber, Instant now, Duration lease) {
        this.id = id;
        this.jobId = jobId;
        this.workerId = workerId;
        this.attemptNumber = attemptNumber;
        this.state = AttemptState.RUNNING;
        this.startedAt = now;
        this.leaseExpiresAt = now.plus(lease);
    }

    /** True only for the worker that owns this attempt, while it is running. */
    public boolean accepts(String workerId) {
        return state == AttemptState.RUNNING && this.workerId.equals(workerId);
    }

    public void renewLease(Instant now, Duration lease) {
        leaseExpiresAt = now.plus(lease);
    }

    public void finish(AttemptState outcome, String error, Instant now) {
        if (state != AttemptState.RUNNING || outcome == AttemptState.RUNNING) {
            throw new IllegalStateTransitionException("Attempt " + id + " is already " + state);
        }
        state = outcome;
        this.error = error != null && error.length() > 2000 ? error.substring(0, 2000) : error;
        finishedAt = now;
    }

    public boolean leaseExpired(Instant now) {
        return state == AttemptState.RUNNING && leaseExpiresAt.isBefore(now);
    }

    public boolean exceededTimeout(Instant now, int timeoutSeconds) {
        return state == AttemptState.RUNNING && startedAt.plusSeconds(timeoutSeconds).isBefore(now);
    }

    public String getId() { return id; }
    public String getJobId() { return jobId; }
    public String getWorkerId() { return workerId; }
    public int getAttemptNumber() { return attemptNumber; }
    public AttemptState getState() { return state; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getError() { return error; }
}
