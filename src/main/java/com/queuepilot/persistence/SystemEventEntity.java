package com.queuepilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "system_events")
public class SystemEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(name = "message", length = 512, nullable = false)
    private String message;

    @Lob
    @Column(name = "details")
    private String details;

    public SystemEventEntity() {}

    public SystemEventEntity(String eventType, String entityId, String message, String details) {
        this.timestamp = Instant.now();
        this.eventType = eventType;
        this.entityId = entityId;
        this.message = message;
        this.details = details;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
    public String getEntityId() { return entityId; }
    public String getMessage() { return message; }
    public String getDetails() { return details; }
}
