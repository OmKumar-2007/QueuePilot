package com.queuepilot.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Domain entity representing an execution worker in QueuePilot.
 */
public class Worker implements Serializable {

    private final String id;
    private String name;
    private volatile WorkerStatus status;
    private final Set<String> capabilities = new HashSet<>();
    private volatile String currentJobId;
    private volatile Instant lastHeartbeat;
    private final Instant registeredAt;

    private int totalJobsCompleted = 0;
    private int totalJobsFailed = 0;

    public Worker(String id, String name, Set<String> capabilities) {
        this.id = id;
        this.name = name;
        this.status = WorkerStatus.IDLE;
        if (capabilities != null) {
            this.capabilities.addAll(capabilities);
        }
        this.registeredAt = Instant.now();
        this.lastHeartbeat = Instant.now();
    }

    public boolean canExecute(Job job) {
        if (job == null || !status.isHealthy()) {
            return false;
        }
        // Worker must satisfy all required capability tags of the job
        return capabilities.containsAll(job.getRequiredCapabilities());
    }

    public synchronized void recordHeartbeat() {
        this.lastHeartbeat = Instant.now();
        if (this.status == WorkerStatus.UNRESPONSIVE) {
            this.status = (this.currentJobId != null) ? WorkerStatus.BUSY : WorkerStatus.IDLE;
        }
    }

    public synchronized void assignJob(String jobId) {
        this.currentJobId = jobId;
        this.status = WorkerStatus.BUSY;
        this.lastHeartbeat = Instant.now();
    }

    public synchronized void releaseJob(boolean success) {
        this.currentJobId = null;
        if (this.status != WorkerStatus.DEAD) {
            this.status = WorkerStatus.IDLE;
        }
        if (success) {
            this.totalJobsCompleted++;
        } else {
            this.totalJobsFailed++;
        }
        this.lastHeartbeat = Instant.now();
    }

    public synchronized void markUnresponsive() {
        if (this.status != WorkerStatus.DEAD) {
            this.status = WorkerStatus.UNRESPONSIVE;
        }
    }

    public synchronized void markDead() {
        this.status = WorkerStatus.DEAD;
        this.currentJobId = null;
    }

    public synchronized void markAlive() {
        this.status = (this.currentJobId != null) ? WorkerStatus.BUSY : WorkerStatus.IDLE;
        this.lastHeartbeat = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public WorkerStatus getStatus() { return status; }
    public Set<String> getCapabilities() { return Collections.unmodifiableSet(capabilities); }
    public String getCurrentJobId() { return currentJobId; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public Instant getRegisteredAt() { return registeredAt; }
    public int getTotalJobsCompleted() { return totalJobsCompleted; }
    public int getTotalJobsFailed() { return totalJobsFailed; }

    @Override
    public String toString() {
        return "Worker{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", status=" + status +
                ", currentJobId='" + currentJobId + '\'' +
                '}';
    }
}
