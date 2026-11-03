package com.queuepilot.coordinator.worker;

import com.queuepilot.coordinator.job.JobEntity;
import com.queuepilot.coordinator.task.ExecutorKind;
import com.queuepilot.coordinator.task.TaskDefinitionEntity;
import com.queuepilot.coordinator.task.TaskRegistryService;
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
    private String capabilities;
    private String supportedTasks;
    private String tokenHash;
    private String pendingCommand;
    private long demoPaceMs;
    private Instant registeredAt;
    private Instant lastHeartbeatAt;

    protected WorkerEntity() {
    }

    public WorkerEntity(String id, String name, int concurrencyLimit, Set<String> capabilities,
                        Set<String> supportedTasks, String tokenHash, long demoPaceMs, Instant now) {
        this.id = id;
        this.name = name;
        this.status = WorkerStatus.ONLINE;
        this.concurrencyLimit = concurrencyLimit;
        this.capabilities = String.join(",", capabilities);
        this.supportedTasks = String.join(",", supportedTasks);
        this.tokenHash = tokenHash;
        this.demoPaceMs = demoPaceMs;
        this.registeredAt = now;
        this.lastHeartbeatAt = now;
    }

    /**
     * Capability matching (F7): the worker must advertise every capability the task requires and
     * must have the exact task implementation (or, for container tasks, the docker capability).
     */
    public boolean canRun(JobEntity job) {
        Set<String> mine = capabilitySet();
        if (!mine.containsAll(job.requiredCapabilitySet())) {
            return false;
        }
        if (job.getExecutorKind() == ExecutorKind.CONTAINER) {
            return mine.contains(TaskRegistryService.DOCKER_CAPABILITY);
        }
        return supportedTaskSet().contains(job.taskIdentifier());
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

    public Set<String> capabilitySet() {
        return TaskDefinitionEntity.splitCsv(capabilities);
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
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
}
