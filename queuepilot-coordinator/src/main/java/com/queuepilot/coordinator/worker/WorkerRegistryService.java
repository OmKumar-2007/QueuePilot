package com.queuepilot.coordinator.worker;

import com.queuepilot.coordinator.attempt.AttemptRepository;
import com.queuepilot.coordinator.attempt.AttemptService;
import com.queuepilot.coordinator.config.QueuePilotProperties;
import com.queuepilot.coordinator.error.Errors.ConflictException;
import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.core.job.AttemptState;
import com.queuepilot.core.job.WorkerStatus;
import com.queuepilot.core.protocol.WorkerProtocol;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterRequest;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterResponse;
import com.queuepilot.core.schema.ValidationError;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Worker registration, health transitions and administrative controls (F6, F15). */
@Service
public class WorkerRegistryService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TASK_TYPE = Pattern.compile("[a-z][a-z0-9-]{1,63}");

    private final WorkerRepository workers;
    private final AttemptRepository attempts;
    private final AttemptService attemptService;
    private final EventLog events;
    private final QueuePilotProperties properties;
    private final Clock clock;

    public WorkerRegistryService(WorkerRepository workers, AttemptRepository attempts, @Lazy AttemptService attemptService,
                                 EventLog events, QueuePilotProperties properties, Clock clock) {
        this.workers = workers;
        this.attempts = attempts;
        this.attemptService = attemptService;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        if (request.name() == null || !NAME.matcher(request.name()).matches()) {
            errors.add(new ValidationError("name", "must be 1-64 characters from A-Z a-z 0-9 . _ -"));
        }
        if (request.concurrencyLimit() < 1 || request.concurrencyLimit() > 64) {
            errors.add(new ValidationError("concurrencyLimit", "must be 1..64"));
        }
        if (request.demoPaceMs() < 0 || request.demoPaceMs() > 10_000) {
            errors.add(new ValidationError("demoPaceMs", "must be 0..10000"));
        }
        Set<String> tasks = checkedSet(request.supportedTasks(), TASK_TYPE, "supportedTasks", errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        WorkerEntity worker = new WorkerEntity("w-" + UUID.randomUUID().toString().substring(0, 8), request.name(),
                request.concurrencyLimit(), tasks, request.demoPaceMs(), clock.instant());
        workers.save(worker);
        events.record(EventType.WORKER_REGISTERED, null, worker.getId(), null, "Worker " + worker.getName() + " ("
                + worker.getId() + ") registered with " + request.concurrencyLimit() + " slots, tasks " + tasks
                + (request.demoPaceMs() > 0 ? ", demo pacing " + request.demoPaceMs() + " ms per percent" : ""));
        return new RegisterResponse(worker.getId(), properties.heartbeatIntervalMs());
    }

    @Transactional(readOnly = true)
    public WorkerEntity get(String workerId) {
        return workers.findById(workerId).orElseThrow(() -> new NotFoundException("No worker " + workerId));
    }

    @Transactional
    public void deregister(String workerId) {
        WorkerEntity worker = get(workerId);
        worker.changeStatus(WorkerStatus.OFFLINE);
        events.record(EventType.WORKER_OFFLINE, null, workerId, null, "Worker " + worker.getName() + " deregistered");
        attemptService.abandonAttemptsOf(workerId, "Worker " + worker.getName() + " shut down");
    }

    /** Recovery: no heartbeat within the timeout. */
    @Transactional
    public void markOfflineIfSilent(String workerId) {
        WorkerEntity worker = get(workerId);
        Instant cutoff = clock.instant().minusMillis(properties.workerTimeoutMs());
        boolean watched = worker.getStatus() == WorkerStatus.ONLINE || worker.getStatus() == WorkerStatus.DRAINING
                || worker.getStatus() == WorkerStatus.PAUSED;
        if (!watched || !worker.isSilentSince(cutoff)) {
            return;
        }
        worker.changeStatus(WorkerStatus.OFFLINE);
        events.record(EventType.WORKER_OFFLINE, null, workerId, null, "Worker " + worker.getName()
                + " missed heartbeats for " + properties.workerTimeoutMs() + " ms and is now OFFLINE");
        attemptService.abandonAttemptsOf(workerId, "Worker " + worker.getName() + " stopped sending heartbeats");
    }

    /** Admin actions: drain, pause, resume, interrupt, disable, enable. */
    @Transactional
    public WorkerEntity changeStatus(String workerId, String action) {
        WorkerEntity worker = get(workerId);
        WorkerStatus current = worker.getStatus();
        if (action.equals("interrupt")) {
            requireFrom(current, current, WorkerStatus.ONLINE, WorkerStatus.DRAINING, WorkerStatus.PAUSED, WorkerStatus.DISABLED);
            worker.queueCommand(WorkerProtocol.COMMAND_INTERRUPT);
            events.record(EventType.WORKER_STATUS_CHANGED, null, workerId, null,
                    "Administrator interrupted the running jobs of worker " + worker.getName() + "; they will be retried");
            return worker;
        }
        WorkerStatus target = switch (action) {
            case "drain" -> requireFrom(current, WorkerStatus.DRAINING, WorkerStatus.ONLINE);
            case "pause" -> requireFrom(current, WorkerStatus.PAUSED, WorkerStatus.ONLINE, WorkerStatus.DRAINING);
            case "resume" -> requireFrom(current, WorkerStatus.ONLINE, WorkerStatus.DRAINING, WorkerStatus.PAUSED);
            case "disable" -> requireFrom(current, WorkerStatus.DISABLED, WorkerStatus.ONLINE, WorkerStatus.DRAINING, WorkerStatus.PAUSED);
            case "enable" -> requireFrom(current, WorkerStatus.ONLINE, WorkerStatus.DISABLED);
            default -> throw new NotFoundException("Unknown action " + action);
        };
        if (target == WorkerStatus.PAUSED) {
            worker.queueCommand(WorkerProtocol.COMMAND_PAUSE);
        } else if (current == WorkerStatus.PAUSED) {
            worker.queueCommand(WorkerProtocol.COMMAND_RESUME);
        }
        worker.changeStatus(target);
        events.record(EventType.WORKER_STATUS_CHANGED, null, workerId, null,
                "Administrator changed worker " + worker.getName() + " from " + current + " to " + target);
        return worker;
    }

    /**
     * Failure simulation (F15). KILL makes the worker process exit abruptly on its next heartbeat
     * without reporting anything; FREEZE makes it stop heartbeating while it keeps computing.
     */
    @Transactional
    public WorkerEntity simulateFailure(String workerId, String kind) {
        WorkerEntity worker = get(workerId);
        if (worker.getStatus() == WorkerStatus.OFFLINE) {
            throw new ConflictException("Worker " + worker.getName() + " is already offline");
        }
        String command = switch (kind) {
            case "kill" -> WorkerProtocol.COMMAND_KILL;
            case "freeze" -> WorkerProtocol.COMMAND_FREEZE;
            default -> throw new NotFoundException("Unknown simulation " + kind);
        };
        worker.queueCommand(command);
        events.record(EventType.FAILURE_SIMULATED, null, workerId, null,
                "Administrator requested " + command + " for worker " + worker.getName());
        return worker;
    }

    @Transactional(readOnly = true)
    public List<WorkerView> list() {
        Map<String, Map<AttemptState, Long>> counts = new HashMap<>();
        for (Object[] row : attempts.countByWorkerAndState()) {
            counts.computeIfAbsent((String) row[0], id -> new HashMap<>()).put((AttemptState) row[1], (Long) row[2]);
        }
        return workers.findAllByOrderByNameAsc().stream()
                .map(w -> WorkerView.from(w, counts.getOrDefault(w.getId(), Map.of()),
                        attempts.findByWorkerIdAndState(w.getId(), AttemptState.RUNNING)))
                .toList();
    }

    private static WorkerStatus requireFrom(WorkerStatus current, WorkerStatus target, WorkerStatus... allowed) {
        for (WorkerStatus status : allowed) {
            if (status == current) {
                return target;
            }
        }
        throw new ConflictException("Worker is " + current + "; cannot change to " + target);
    }

    private static Set<String> checkedSet(List<String> values, Pattern pattern, String field, List<ValidationError> errors) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        if (values.size() > 100) {
            errors.add(new ValidationError(field, "at most 100 entries"));
            return result;
        }
        for (String value : values) {
            if (value == null || !pattern.matcher(value).matches()) {
                errors.add(new ValidationError(field, "'" + value + "' is not valid"));
            } else {
                result.add(value);
            }
        }
        return result;
    }
}
