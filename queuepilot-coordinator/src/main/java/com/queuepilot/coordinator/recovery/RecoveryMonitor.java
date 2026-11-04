package com.queuepilot.coordinator.recovery;

import com.queuepilot.coordinator.attempt.AttemptEntity;
import com.queuepilot.coordinator.attempt.AttemptRepository;
import com.queuepilot.coordinator.attempt.AttemptService;
import com.queuepilot.coordinator.config.QueuePilotProperties;
import com.queuepilot.coordinator.worker.WorkerRegistryService;
import com.queuepilot.coordinator.worker.WorkerRepository;
import com.queuepilot.core.job.AttemptState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Periodic housekeeping (U5, F10, F11, F13, F18). Each pass:
 * <ol>
 *   <li>marks workers without recent heartbeats OFFLINE and abandons their attempts,</li>
 *   <li>expires attempts whose lease ran out,</li>
 *   <li>times out attempts that run longer than their task allows.</li>
 * </ol>
 * Every item is handled in its own transaction, so one failure (e.g. a concurrent update) does not
 * block the rest; the item is simply retried on the next pass.
 */
@Component
public class RecoveryMonitor {

    private static final Logger log = LoggerFactory.getLogger(RecoveryMonitor.class);

    private final WorkerRegistryService workerRegistry;
    private final AttemptService attemptService;
    private final WorkerRepository workers;
    private final AttemptRepository attempts;
    private final QueuePilotProperties properties;
    private final Clock clock;

    public RecoveryMonitor(WorkerRegistryService workerRegistry, AttemptService attemptService,
                           WorkerRepository workers, AttemptRepository attempts, QueuePilotProperties properties, Clock clock) {
        this.workerRegistry = workerRegistry;
        this.attemptService = attemptService;
        this.workers = workers;
        this.attempts = attempts;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${queuepilot.recovery-interval-ms:1000}")
    public void scheduledPass() {
        if (properties.recoveryEnabled()) {
            runOnce();
        }
    }

    public void runOnce() {
        Instant now = clock.instant();
        forEach(workers.findSilentWorkerIds(now.minusMillis(properties.workerTimeoutMs())),
                workerRegistry::markOfflineIfSilent, "mark worker offline");
        forEach(attempts.findExpiredLeaseIds(now), attemptService::expireLeaseIfDue, "expire lease");
        List<String> running = attempts.findByState(AttemptState.RUNNING).stream().map(AttemptEntity::getId).toList();
        forEach(running, attemptService::timeOutIfExceeded, "check timeout");
    }

    private static void forEach(List<String> ids, Consumer<String> action, String description) {
        for (String id : ids) {
            try {
                action.accept(id);
            } catch (RuntimeException e) {
                log.warn("Recovery could not {} for {}: {} (will retry)", description, id, e.getMessage());
            }
        }
    }
}
