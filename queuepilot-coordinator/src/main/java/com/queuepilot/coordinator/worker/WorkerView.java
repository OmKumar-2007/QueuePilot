package com.queuepilot.coordinator.worker;

import com.queuepilot.coordinator.attempt.AttemptEntity;
import com.queuepilot.core.job.AttemptState;
import com.queuepilot.core.job.WorkerStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record WorkerView(String id,
                         String name,
                         WorkerStatus status,
                         int concurrencyLimit,
                         int runningAttempts,
                         int freeSlots,
                         Set<String> supportedTasks,
                         List<String> currentJobIds,
                         long succeededAttempts,
                         long failedAttempts,
                         Instant lastHeartbeatAt,
                         String pendingCommand,
                         long demoPaceMs) {

    public static WorkerView from(WorkerEntity w, Map<AttemptState, Long> counts, List<AttemptEntity> running) {
        long failed = counts.getOrDefault(AttemptState.FAILED, 0L) + counts.getOrDefault(AttemptState.TIMED_OUT, 0L)
                + counts.getOrDefault(AttemptState.LEASE_EXPIRED, 0L) + counts.getOrDefault(AttemptState.ABANDONED, 0L);
        int free = w.getStatus().acceptsWork() ? Math.max(0, w.getConcurrencyLimit() - running.size()) : 0;
        return new WorkerView(w.getId(), w.getName(), w.getStatus(), w.getConcurrencyLimit(), running.size(), free,
                w.supportedTaskSet(), running.stream().map(AttemptEntity::getJobId).toList(),
                counts.getOrDefault(AttemptState.SUCCEEDED, 0L), failed, w.getLastHeartbeatAt(),
                w.getPendingCommand(), w.getDemoPaceMs());
    }
}
