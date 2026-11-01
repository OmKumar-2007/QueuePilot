package com.queuepilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_attempts")
public class JobAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", length = 64, nullable = false)
    private String jobId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", length = 64, nullable = false)
    private String workerId;

    @Column(name = "attempt_token", length = 64, nullable = false)
    private String attemptToken;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "notes", length = 1024)
    private String notes;

    public JobAttemptEntity() {}

    public JobAttemptEntity(String jobId, int attemptNumber, String workerId, String attemptToken, Instant startedAt, String status) {
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.workerId = workerId;
        this.attemptToken = attemptToken;
        this.startedAt = startedAt;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getAttemptToken() { return attemptToken; }
    public void setAttemptToken(String attemptToken) { this.attemptToken = attemptToken; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
