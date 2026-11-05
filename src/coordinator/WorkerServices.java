package coordinator;

import core.Jobs.AttemptState;
import core.Jobs.JobState;
import core.Jobs.RetryPolicy;
import core.Jobs.WorkerStatus;
import core.Scheduling.PolicyType;
import core.Scheduling.PriorityAgingPolicy;
import core.Scheduling.QueueRotation;
import core.Scheduling.SchedulableJob;
import core.Scheduling.SchedulingPolicy;
import core.Validation.ValidationError;
import core.WorkerProtocol.Assignment;
import core.WorkerProtocol.CompletionReport;
import core.WorkerProtocol.FailureReport;
import core.WorkerProtocol.HeartbeatResponse;
import core.WorkerProtocol.ProgressReport;
import core.WorkerProtocol.RegisterRequest;
import core.WorkerProtocol.RegisterResponse;
import core.WorkerProtocol;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Worker registration, health transitions and administrative controls (F6, F15). */
@Service
class WorkerRegistryService {

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

/**
 * Starts additional worker <b>processes</b> on this machine for the dashboard's "Add worker" button.
 *
 * <p>It only ever runs the QueuePilot worker jar from configuration, never anything a client sends.
 * Launched workers are ordinary independent processes (they register like any other) and use demo
 * pacing so their jobs are slow enough to watch. They are stopped when the coordinator shuts down.</p>
 */
@Component
class WorkerLauncher {

    private static final Logger log = LoggerFactory.getLogger(WorkerLauncher.class);
    private static final Pattern NUMBERED = Pattern.compile("worker-(\\d+)");

    private final QueuePilotProperties properties;
    private final WorkerRepository workers;
    private final EventLog events;
    private final Environment environment;
    private final List<Process> launched = new ArrayList<>();

    public WorkerLauncher(QueuePilotProperties properties, WorkerRepository workers,
                          EventLog events, Environment environment) {
        this.properties = properties;
        this.workers = workers;
        this.events = events;
        this.environment = environment;
    }

    public record Launched(String name, long pid, int slots, long paceMsPerPercent) {
    }

    @Transactional
    public synchronized Launched launch(int slots) {
        if (slots < 1 || slots > 8) {
            throw new ValidationException("slots", "must be 1..8");
        }
        Path jar = properties.workerJar().toAbsolutePath();
        if (!Files.isRegularFile(jar)) {
            throw new ConflictException("Worker jar not found at " + jar + "; build it with mvnw install");
        }
        String name = "worker-" + nextNumber();
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String java = ProcessHandle.current().info().command().orElse("java");
        Path logs = properties.dataDir().resolve("logs");
        try {
            Files.createDirectories(logs);
            ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString(),
                    "--coordinator", "http://127.0.0.1:" + port, "--name", name, "--slots", String.valueOf(slots),
                    "--pace-ms", String.valueOf(properties.launchedWorkerPaceMs()),
                    "--work-dir", properties.dataDir().resolve("worker-work").resolve(name).toString())
                    .redirectErrorStream(true)
                    .redirectOutput(logs.resolve(name + ".log").toFile());
            Process process = builder.start();
            launched.add(process);
            events.record(EventType.WORKER_STATUS_CHANGED, null, null, null,
                    "Started worker process " + name + " (pid " + process.pid() + ", " + slots + " slots)");
            log.info("Launched {} as pid {}", name, process.pid());
            return new Launched(name, process.pid(), slots, properties.launchedWorkerPaceMs());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start a worker process", e);
        }
    }

    /** One more than the highest "worker-N" name seen so far, so names stay readable. */
    private int nextNumber() {
        int max = 0;
        for (WorkerEntity worker : workers.findAll()) {
            Matcher matcher = NUMBERED.matcher(worker.getName());
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max + 1;
    }

    @PreDestroy
    synchronized void stopLaunched() {
        launched.forEach(Process::destroy);
    }
}

/**
 * Makes the dispatch decision when a worker asks for work (U3, ADR-006):
 * readiness filter (query) -> task-type filter -> queue rotation -> active policy -> atomic claim.
 *
 * <p>This class only orders and matches; it never looks at task inputs or task code.</p>
 */
@Service
class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    /** Upper bound of candidates considered per decision; keeps each claim cheap. */
    static final int CANDIDATE_LIMIT = 500;

    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final AttemptRepository attempts;
    private final AttemptService attemptService;
    private final SchedulerSettingsService settings;
    private final QueueRotation rotation;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SchedulerService(JobRepository jobs, WorkerRepository workers, AttemptRepository attempts,
                            AttemptService attemptService, SchedulerSettingsService settings, QueueRotation rotation,
                            TransactionTemplate transactions, Clock clock) {
        this.jobs = jobs;
        this.workers = workers;
        this.attempts = attempts;
        this.attemptService = attemptService;
        this.settings = settings;
        this.rotation = rotation;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Optional<Assignment> claim(String workerId) {
        WorkerEntity worker = workers.findById(workerId).orElse(null);
        if (worker == null || !worker.getStatus().acceptsWork()) {
            return Optional.empty();
        }
        if (attempts.countByWorkerIdAndState(workerId, AttemptState.RUNNING) >= worker.getConcurrencyLimit()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Map<String, JobEntity> eligible = new LinkedHashMap<>();
        for (JobEntity job : jobs.findClaimable(now, PageRequest.of(0, CANDIDATE_LIMIT))) {
            if (worker.canRun(job)) {
                eligible.put(job.getId(), job);
            }
        }
        List<SchedulableJob> candidates = eligible.values().stream().map(JobEntity::toSchedulable).toList();
        for (SchedulableJob candidate : rotation.arrange(candidates, settings.activePolicy(), now)) {
            Optional<Assignment> assignment = tryClaim(candidate.id(), workerId);
            if (assignment.isPresent()) {
                rotation.markServed(candidate.queue());
                return assignment;
            }
        }
        return Optional.empty();
    }

    /** Each claim attempt is its own transaction; losing a race to another worker just moves on. */
    private Optional<Assignment> tryClaim(String jobId, String workerId) {
        try {
            Optional<Assignment> result = transactions.execute(status -> attemptService.startAttempt(jobId, workerId));
            return result == null ? Optional.empty() : result;
        } catch (OptimisticLockingFailureException e) {
            log.debug("Job {} was claimed concurrently; trying the next candidate", jobId);
            return Optional.empty();
        }
    }

    /**
     * Explains the current dispatch order for the dashboard: claimable jobs in the order the next
     * decisions would take them (ignoring which worker asks), plus jobs that are not yet runnable.
     */
    public QueuePreview preview() {
        Instant now = clock.instant();
        SchedulingPolicy policy = settings.activePolicy();
        List<JobEntity> claimable = jobs.findClaimable(now, PageRequest.of(0, CANDIDATE_LIMIT));
        Map<String, JobEntity> byId = new LinkedHashMap<>();
        claimable.forEach(job -> byId.put(job.getId(), job));

        List<QueuePreview.Entry> ready = new ArrayList<>();
        int rank = 1;
        for (SchedulableJob job : rotation.arrange(claimable.stream().map(JobEntity::toSchedulable).toList(), policy, now)) {
            JobEntity entity = byId.get(job.id());
            Long effective = policy instanceof PriorityAgingPolicy aging ? aging.effectivePriority(job, now) : null;
            ready.add(new QueuePreview.Entry(rank++, job.id(), entity.label(), entity.getTaskType(), job.queue(),
                    job.priority(), effective, job.estimatedMs(), job.submittedAt(), "Ready"));
        }

        List<QueuePreview.Entry> waiting = new ArrayList<>();
        PageRequest page = PageRequest.of(0, 100);
        for (JobEntity job : jobs.findByStateOrderBySubmittedAtAsc(JobState.QUEUED, page)) {
            if (job.getReadyAt() != null && job.getReadyAt().isAfter(now)) {
                waiting.add(waitingEntry(job, "Retry delay until " + job.getReadyAt()));
            }
        }
        for (JobEntity job : jobs.findByStateOrderBySubmittedAtAsc(JobState.BLOCKED, page)) {
            waiting.add(waitingEntry(job, "Waiting for " + job.getPrerequisiteIds().size() + " prerequisite(s)"));
        }
        return new QueuePreview(settings.settings(), rotation.lastServedQueue(), ready, waiting);
    }

    private static QueuePreview.Entry waitingEntry(JobEntity job, String reason) {
        return new QueuePreview.Entry(null, job.getId(), job.label(), job.getTaskType(), job.getQueueName(),
                job.getPriority(), null, job.getEstimatedMs(), job.getSubmittedAt(), reason);
    }

    public record QueuePreview(SchedulerSettingsService.SchedulerSettings settings, String lastServedQueue,
                               List<Entry> ready, List<Entry> notReady) {
        public record Entry(Integer rank, String jobId, String name, String task, String queue, int priority,
                            Long effectivePriority, long estimatedMs, Instant submittedAt, String reason) {
        }
    }
}

/**
 * Holds the active scheduling policy. The settings live in the database; the policy object is
 * cached and rebuilt on every change so claim requests do not hit the database for it.
 */
@Service
class SchedulerSettingsService {

    private final SchedulerSettingsRepository repository;
    private final EventLog events;
    private volatile Snapshot cached;

    public SchedulerSettingsService(SchedulerSettingsRepository repository, EventLog events) {
        this.repository = repository;
        this.events = events;
    }

    public record SchedulerSettings(PolicyType activePolicy, int agingIntervalSeconds, int agingStep) {
    }

    private record Snapshot(SchedulerSettings settings, SchedulingPolicy policy) {
    }

    public SchedulerSettings settings() {
        return snapshot().settings();
    }

    public SchedulingPolicy activePolicy() {
        return snapshot().policy();
    }

    @Transactional
    public SchedulerSettings update(SchedulerSettings requested) {
        List<ValidationError> errors = new ArrayList<>();
        if (requested.activePolicy() == null) {
            errors.add(new ValidationError("activePolicy", "must be one of FCFS, PRIORITY_AGING, SJF"));
        }
        if (requested.agingIntervalSeconds() < 1 || requested.agingIntervalSeconds() > 3600) {
            errors.add(new ValidationError("agingIntervalSeconds", "must be 1..3600"));
        }
        if (requested.agingStep() < 0 || requested.agingStep() > 10) {
            errors.add(new ValidationError("agingStep", "must be 0..10"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        SchedulerSettingsEntity entity = repository.findById(SchedulerSettingsEntity.SINGLETON_ID).orElseThrow();
        entity.update(requested.activePolicy(), requested.agingIntervalSeconds(), requested.agingStep());
        events.record(EventType.SCHEDULER_UPDATED, null, null, null, "Scheduler set to " + requested.activePolicy()
                + " (aging " + requested.agingStep() + " per " + requested.agingIntervalSeconds() + "s)");
        cached = build(entity);
        return cached.settings();
    }

    private Snapshot snapshot() {
        Snapshot current = cached;
        if (current == null) {
            current = build(repository.findById(SchedulerSettingsEntity.SINGLETON_ID).orElseThrow());
            cached = current;
        }
        return current;
    }

    private static Snapshot build(SchedulerSettingsEntity e) {
        SchedulerSettings settings = new SchedulerSettings(e.getActivePolicy(), e.getAgingIntervalSeconds(), e.getAgingStep());
        return new Snapshot(settings, e.getActivePolicy().create(Duration.ofSeconds(e.getAgingIntervalSeconds()), e.getAgingStep()));
    }

    /** Forgets the cache; used by tests that reset the database. */
    public void reload() {
        cached = null;
    }
}

/**
 * Everything that happens to an attempt after a job is claimed: creation, lease renewal, progress,
 * completion, failure, expiry and timeout. Every worker update is checked: it is applied only if
 * it names the job's current attempt and comes from that attempt's worker.
 */
@Service
class AttemptService {

    /** Result of a worker update; STALE becomes HTTP 409 so the worker stops the attempt. */
    public enum UpdateResult { ACCEPTED, STALE }

    private final AttemptRepository attempts;
    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final TaskRegistryService registry;
    private final DependencyService dependencies;
    private final ArtifactService artifacts;
    private final RetryPolicy retryPolicy;
    private final JsonCodec json;
    private final EventLog events;
    private final QueuePilotProperties properties;
    private final Clock clock;

    public AttemptService(AttemptRepository attempts, JobRepository jobs, WorkerRepository workers,
                          TaskRegistryService registry, DependencyService dependencies, ArtifactService artifacts,
                          RetryPolicy retryPolicy, JsonCodec json, EventLog events, QueuePilotProperties properties,
                          Clock clock) {
        this.attempts = attempts;
        this.jobs = jobs;
        this.workers = workers;
        this.registry = registry;
        this.dependencies = dependencies;
        this.artifacts = artifacts;
        this.retryPolicy = retryPolicy;
        this.json = json;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Larger outputs must be uploaded as file artifacts. */
    static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private Duration lease() {
        return Duration.ofMillis(properties.leaseDurationMs());
    }

    /**
     * Claims {@code jobId} for {@code workerId}. Returns empty if the job is no longer QUEUED.
     * A concurrent claim of the same job fails at commit with an optimistic-lock error.
     */
    @Transactional
    public Optional<Assignment> startAttempt(String jobId, String workerId) {
        JobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getState() != JobState.QUEUED) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AttemptEntity attempt = new AttemptEntity(UUID.randomUUID().toString(), jobId, workerId,
                job.getAttemptsUsed() + 1, now, lease());
        job.start(attempt.getId(), workerId, now);
        attempts.save(attempt);
        events.record(EventType.JOB_CLAIMED, jobId, workerId, attempt.getId(),
                "Job " + job.label() + " attempt " + attempt.getAttemptNumber() + " assigned to worker " + workerId);

        return Optional.of(new Assignment(attempt.getId(), attempt.getAttemptNumber(), jobId, job.getTaskType(),
                json.readMap(job.getInputJson()), task(job).getTimeoutSeconds()));
    }

    /** The task definition of a job; timeouts and retry limits are read from it. */
    private TaskDefinitionEntity task(JobEntity job) {
        return registry.find(job.getTaskType()).orElseThrow();
    }

    /** Renews the leases of the worker's attempts that are still current; tells it which ones to stop. */
    @Transactional
    public HeartbeatResponse heartbeat(String workerId, List<String> attemptIds) {
        WorkerEntity worker = workers.findById(workerId).orElse(null);
        List<String> renewed = new ArrayList<>();
        List<String> revoked = new ArrayList<>();
        List<String> active = attemptIds == null ? List.of() : attemptIds;
        if (worker == null || worker.getStatus() == WorkerStatus.OFFLINE) {
            revoked.addAll(active);
            return new HeartbeatResponse(renewed, revoked, WorkerProtocol.COMMAND_REREGISTER);
        }
        Instant now = clock.instant();
        worker.heartbeat(now);
        for (String attemptId : active) {
            AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
            if (attempt != null && attempt.accepts(workerId)) {
                attempt.renewLease(now, lease());
                renewed.add(attemptId);
            } else {
                revoked.add(attemptId);
            }
        }
        return new HeartbeatResponse(renewed, revoked, worker.takeCommand());
    }

    @Transactional
    public UpdateResult progress(String workerId, String attemptId, ProgressReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        attempt.renewLease(clock.instant(), lease());
        String message = report.message() == null ? "" : report.message();
        jobs.updateProgress(attempt.getJobId(), attemptId, Math.max(0, Math.min(100, report.percent())),
                message.length() > 500 ? message.substring(0, 500) : message);
        return UpdateResult.ACCEPTED;
    }

    @Transactional
    public UpdateResult complete(String workerId, String attemptId, CompletionReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        Instant now = clock.instant();
        String outputJson = json.write(report.output() == null ? Map.of() : report.output());
        if (outputJson.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            endAttempt(attempt, job, AttemptState.FAILED, "OUTPUT_TOO_LARGE",
                    "Output exceeds " + MAX_OUTPUT_BYTES + " bytes", false, now);
            return UpdateResult.ACCEPTED;
        }
        List<String> outputIds = report.outputArtifactIds() == null ? List.of() : report.outputArtifactIds();
        for (String id : outputIds) {
            ArtifactEntity artifact = artifacts.get(id);
            if (!job.getId().equals(artifact.getJobId())) {
                endAttempt(attempt, job, AttemptState.FAILED, "INVALID_OUTPUT_ARTIFACT",
                        "Artifact " + id + " is not an output of this job", false, now);
                return UpdateResult.ACCEPTED;
            }
        }
        attempt.finish(AttemptState.SUCCEEDED, null, now);
        job.succeed(outputJson, String.join(",", outputIds), now);
        events.record(EventType.JOB_SUCCEEDED, job.getId(), workerId, attemptId,
                "Job " + job.label() + " succeeded on attempt " + attempt.getAttemptNumber());
        dependencies.onJobFinished(job, now);
        return UpdateResult.ACCEPTED;
    }

    @Transactional
    public UpdateResult fail(String workerId, String attemptId, FailureReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        String code = report.errorCode() == null || report.errorCode().isBlank() ? "TASK_ERROR" : report.errorCode();
        AttemptState outcome = "TIMEOUT".equals(code) ? AttemptState.TIMED_OUT : AttemptState.FAILED;
        endAttempt(attempt, job, outcome, code, report.message(), report.retryable(), clock.instant());
        return UpdateResult.ACCEPTED;
    }

    /** Recovery: the attempt's lease ran out without a heartbeat. */
    @Transactional
    public void expireLeaseIfDue(String attemptId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        Instant now = clock.instant();
        if (attempt == null || !attempt.leaseExpired(now)) {
            return;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        events.record(EventType.LEASE_EXPIRED, job.getId(), attempt.getWorkerId(), attemptId,
                "Lease of attempt " + attempt.getAttemptNumber() + " for " + job.label() + " expired");
        endAttempt(attempt, job, AttemptState.LEASE_EXPIRED, "LEASE_EXPIRED",
                "No heartbeat from worker " + attempt.getWorkerId() + " before the lease ended", true, now);
    }

    /** Recovery: the attempt ran longer than the task's timeout (F18). The worker learns this via a revoked heartbeat. */
    @Transactional
    public void timeOutIfExceeded(String attemptId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null) {
            return;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        Instant now = clock.instant();
        int timeoutSeconds = task(job).getTimeoutSeconds();
        if (attempt.exceededTimeout(now, timeoutSeconds)) {
            events.record(EventType.ATTEMPT_TIMED_OUT, job.getId(), attempt.getWorkerId(), attemptId,
                    "Attempt " + attempt.getAttemptNumber() + " of " + job.label() + " exceeded " + timeoutSeconds + "s");
            endAttempt(attempt, job, AttemptState.TIMED_OUT, "TIMEOUT",
                    "Exceeded the task timeout of " + timeoutSeconds + "s", true, now);
        }
    }

    /** Ends every running attempt of a worker that went offline, deregistered or was killed. */
    @Transactional
    public void abandonAttemptsOf(String workerId, String reason) {
        Instant now = clock.instant();
        for (AttemptEntity attempt : attempts.findByWorkerIdAndState(workerId, AttemptState.RUNNING)) {
            JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
            endAttempt(attempt, job, AttemptState.ABANDONED, "WORKER_LOST", reason, true, now);
        }
    }

    /**
     * Closes an attempt and decides the job's fate: retry after a backoff delay if the failure is
     * retryable and retries remain, otherwise FAILED (which also skips dependants).
     */
    private void endAttempt(AttemptEntity attempt, JobEntity job, AttemptState outcome, String code, String message,
                            boolean retryable, Instant now) {
        String error = message == null || message.isBlank() ? code : code + ": " + message;
        error = error.length() > 2000 ? error.substring(0, 2000) : error;
        attempt.finish(outcome, error, now);
        if (!job.isCurrentAttempt(attempt.getId())) {
            return;
        }
        int maxRetries = task(job).getMaxRetries();
        if (retryable && job.canRetry(maxRetries)) {
            Duration delay = retryPolicy.delayBefore(job.getAttemptsUsed());
            job.retryLater(now.plus(delay), error, maxRetries);
            events.record(EventType.JOB_RETRY_SCHEDULED, job.getId(), attempt.getWorkerId(), attempt.getId(),
                    "Job " + job.label() + " attempt " + attempt.getAttemptNumber() + " ended " + outcome
                            + " (" + code + "); retry " + job.getAttemptsUsed() + "/" + maxRetries
                            + " in " + delay.toMillis() + " ms");
        } else {
            job.fail(error, now);
            events.record(EventType.JOB_FAILED, job.getId(), attempt.getWorkerId(), attempt.getId(),
                    "Job " + job.label() + " failed: " + code + (retryable ? " (retries exhausted)" : " (not retryable)"));
            dependencies.onJobFinished(job, now);
        }
    }

    /** Returns the attempt only if it is the job's current attempt and belongs to this worker. */
    private AttemptEntity currentAttempt(String workerId, String attemptId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        JobEntity job = attempt == null ? null : jobs.findById(attempt.getJobId()).orElse(null);
        if (attempt == null || job == null || !attempt.accepts(workerId) || !job.isCurrentAttempt(attemptId)) {
            events.record(EventType.STALE_UPDATE_REJECTED, job == null ? null : job.getId(), workerId, attemptId,
                    "Rejected update for attempt " + attemptId + " from worker " + workerId + ": not the current attempt");
            return null;
        }
        return attempt;
    }

    /** Output upload from a worker, checked like every other attempt update. */
    @Transactional
    public ArtifactEntity uploadOutput(String workerId, String attemptId, MultipartFile file) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            throw new StaleAttemptException("Attempt " + attemptId + " is not current");
        }
        return artifacts.uploadOutput(file, attempt.getJobId());
    }

    /** File download for a worker: only files of the attempt's own job. */
    @Transactional(readOnly = true)
    public ArtifactEntity inputForAttempt(String workerId, String attemptId, String artifactId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || !attempt.accepts(workerId)) {
            throw new StaleAttemptException("Attempt " + attemptId + " is not current");
        }
        ArtifactEntity artifact = artifacts.get(artifactId);
        if (!attempt.getJobId().equals(artifact.getJobId())) {
            throw new StaleAttemptException("Artifact " + artifactId + " does not belong to this attempt's job");
        }
        return artifact;
    }
}

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
class RecoveryMonitor {

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
