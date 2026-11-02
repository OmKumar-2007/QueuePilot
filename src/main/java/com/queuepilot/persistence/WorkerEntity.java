package com.queuepilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workers")
public class WorkerEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "capabilities", length = 512)
    private String capabilities;

    @Column(name = "current_job_id", length = 64)
    private String currentJobId;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "total_completed")
    private int totalCompleted;

    @Column(name = "total_failed")
    private int totalFailed;

    public WorkerEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public String getCurrentJobId() { return currentJobId; }
    public void setCurrentJobId(String currentJobId) { this.currentJobId = currentJobId; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public int getTotalCompleted() { return totalCompleted; }
    public void setTotalCompleted(int totalCompleted) { this.totalCompleted = totalCompleted; }
    public int getTotalFailed() { return totalFailed; }
    public void setTotalFailed(int totalFailed) { this.totalFailed = totalFailed; }
}
