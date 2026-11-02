package com.queuepilot.coordinator.attempt;

import com.queuepilot.coordinator.security.TokenHasher;
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
 * One execution attempt of a job on a worker. The fencing token proves that an update comes from
 * the holder of this specific attempt; only its hash is stored.
 */
@Entity
@Table(name = "attempt")
public class AttemptEntity {

    @Id
    private String id;
    private String jobId;
    private String workerId;
    private int attemptNumber;
    private String fencingTokenHash;
    @Enumerated(EnumType.STRING)
    private AttemptState state;
    private Instant startedAt;
    private Instant leaseExpiresAt;
    private Instant lastHeartbeatAt;
    private Instant finishedAt;
    private String errorCode;
    private String errorMessage;

    protected AttemptEntity() {
    }

    public AttemptEntity(String id, String jobId, String workerId, int attemptNumber, String fencingToken,
                         Instant now, Duration lease) {
        this.id = id;
        this.jobId = jobId;
        this.workerId = workerId;
        this.attemptNumber = attemptNumber;
        this.fencingTokenHash = TokenHasher.hash(fencingToken);
        this.state = AttemptState.RUNNING;
        this.startedAt = now;
        this.lastHeartbeatAt = now;
        this.leaseExpiresAt = now.plus(lease);
    }

    /** True only for the worker that owns this attempt, presenting its token, while it is running. */
    public boolean accepts(String workerId, String fencingToken) {
        return state == AttemptState.RUNNING && this.workerId.equals(workerId)
                && TokenHasher.matches(fencingToken, fencingTokenHash);
    }

    public void renewLease(Instant now, Duration lease) {
        lastHeartbeatAt = now;
        leaseExpiresAt = now.plus(lease);
    }

    public void finish(AttemptState outcome, String code, String message, Instant now) {
        if (state != AttemptState.RUNNING || outcome == AttemptState.RUNNING) {
            throw new IllegalStateTransitionException("Attempt " + id + " is already " + state);
        }
        state = outcome;
        errorCode = code;
        errorMessage = message != null && message.length() > 2000 ? message.substring(0, 2000) : message;
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
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
