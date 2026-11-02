package com.queuepilot.coordinator.event;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only history entry shown in the dashboard and in a job's history. */
@Entity
@Table(name = "system_event")
public class SystemEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant occurredAt;
    @Enumerated(EnumType.STRING)
    private EventType type;
    private String jobId;
    private String workerId;
    private String attemptId;
    private String message;

    protected SystemEventEntity() {
    }

    public SystemEventEntity(Instant occurredAt, EventType type, String jobId, String workerId, String attemptId, String message) {
        this.occurredAt = occurredAt;
        this.type = type;
        this.jobId = jobId;
        this.workerId = workerId;
        this.attemptId = attemptId;
        this.message = message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public EventType getType() { return type; }
    public String getJobId() { return jobId; }
    public String getWorkerId() { return workerId; }
    public String getAttemptId() { return attemptId; }
    public String getMessage() { return message; }
}
