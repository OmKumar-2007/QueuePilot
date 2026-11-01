package com.queuepilot.dto;

import com.queuepilot.model.Worker;

import java.time.Instant;
import java.util.Set;

public record WorkerResponse(
        String id,
        String name,
        String status,
        Set<String> capabilities,
        String currentJobId,
        Instant lastHeartbeat,
        Instant registeredAt,
        int totalCompleted,
        int totalFailed,
        boolean healthy
) {
    public static WorkerResponse from(Worker worker) {
        if (worker == null) return null;
        return new WorkerResponse(
                worker.getId(),
                worker.getName(),
                worker.getStatus().name(),
                worker.getCapabilities(),
                worker.getCurrentJobId(),
                worker.getLastHeartbeat(),
                worker.getRegisteredAt(),
                worker.getTotalJobsCompleted(),
                worker.getTotalJobsFailed(),
                worker.getStatus().isHealthy()
        );
    }
}
