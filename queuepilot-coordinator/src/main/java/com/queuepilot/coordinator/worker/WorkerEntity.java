package com.queuepilot.coordinator.worker;

import com.queuepilot.coordinator.job.JobEntity;
import com.queuepilot.coordinator.task.TaskDefinitionEntity;
import com.queuepilot.core.job.WorkerStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Set;

/** A registered worker process. Running-attempt counts are derived from the attempt table, not stored here. */
@Entity
@Table(name = "worker")
@DynamicUpdate
public class WorkerEntity {

    @Id
    private String id;
    private String name;
    @Enumerated(EnumType.STRING)
    private WorkerStatus status;
    private int concurrencyLimit;
    private String supportedTasks;
    private String pendingCommand;
    private long demoPaceMs;
    private Instant lastHeartbeatAt;

    protected WorkerEntity() {
    }

    public WorkerEntity(String id, String name, int concurrencyLimit, Set<String> supportedTasks, long demoPaceMs,
                        Instant now) {
        this.id = id;
        this.name = name;
        this.status = WorkerStatus.ONLINE;
        this.concurrencyLimit = concurrencyLimit;
        this.supportedTasks = String.join(",", supportedTasks);
        this.demoPaceMs = demoPaceMs;
        this.lastHeartbeatAt = now;
    }

    /** A worker can run a job if it has a plugin for the job's task type. */
    public boolean canRun(JobEntity job) {
        return supportedTaskSet().contains(job.getTaskType());
    }

    public void heartbeat(Instant now) {
        lastHeartbeatAt = now;
    }

    public void changeStatus(WorkerStatus newStatus) {
        status = newStatus;
    }

    /** Adds a command for the next heartbeat; several commands are delivered in order, comma-separated. */
    public void queueCommand(String command) {
        pendingCommand = pendingCommand == null ? command : pendingCommand + "," + command;
    }

    /** Returns and clears the commands waiting for the next heartbeat. */
    public String takeCommand() {
        String command = pendingCommand;
        pendingCommand = null;
        return command;
    }

    public boolean isSilentSince(Instant cutoff) {
        return lastHeartbeatAt.isBefore(cutoff);
    }

    public Set<String> supportedTaskSet() {
        return TaskDefinitionEntity.splitCsv(supportedTasks);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public WorkerStatus getStatus() { return status; }
    public int getConcurrencyLimit() { return concurrencyLimit; }
    public String getPendingCommand() { return pendingCommand; }
    public long getDemoPaceMs() { return demoPaceMs; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
}
