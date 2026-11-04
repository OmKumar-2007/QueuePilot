package com.queuepilot.coordinator.workflow;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Groups jobs submitted together as a dependency graph. The jobs carry the actual state. */
@Entity
@Table(name = "workflow")
public class WorkflowEntity {

    @Id
    private String id;
    private String name;
    private Instant createdAt;

    protected WorkflowEntity() {
    }

    public WorkflowEntity(String id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
