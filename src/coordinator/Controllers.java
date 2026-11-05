package coordinator;

import coordinator.ArtifactController.ArtifactView;
import coordinator.AttemptService.UpdateResult;
import coordinator.EventLog.EventView;
import coordinator.SchedulerService.QueuePreview;
import coordinator.SchedulerSettingsService.SchedulerSettings;
import coordinator.WorkflowService.SubmitWorkflowRequest;
import core.Jobs.AttemptState;
import core.Jobs.JobState;
import core.Jobs.WorkerStatus;
import core.Scheduling.PolicyType;
import core.Validation.InputSchema;
import core.WorkerProtocol.Assignment;
import core.WorkerProtocol.CompletionReport;
import core.WorkerProtocol.FailureReport;
import core.WorkerProtocol.HeartbeatRequest;
import core.WorkerProtocol.HeartbeatResponse;
import core.WorkerProtocol.ProgressReport;
import core.WorkerProtocol.RegisterRequest;
import core.WorkerProtocol.RegisterResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client API for jobs. Thin: validation and rules live in {@link JobService}. */
@RestController
@RequestMapping("/api/jobs")
class JobController {

    private final JobService jobService;
    private final AttemptRepository attempts;
    private final ArtifactService artifacts;
    private final EventLog events;

    public JobController(JobService jobService, AttemptRepository attempts, ArtifactService artifacts, EventLog events) {
        this.jobService = jobService;
        this.attempts = attempts;
        this.artifacts = artifacts;
        this.events = events;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobView submit(@RequestBody SubmitJobRequest request) {
        return JobView.summary(jobService.submit(request));
    }

    @GetMapping
    public List<JobView> list(@RequestParam(required = false) String state,
                              @RequestParam(defaultValue = "200") int limit) {
        return jobService.list(state, limit).stream().map(JobView::summary).toList();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable String id) {
        JobEntity job = jobService.get(id);
        return JobView.of(job, jobService.inputOf(job), jobService.outputOf(job));
    }

    @GetMapping("/{id}/history")
    public JobHistory history(@PathVariable String id) {
        JobEntity job = jobService.get(id);
        return new JobHistory(
                JobView.of(job, jobService.inputOf(job), jobService.outputOf(job)),
                attempts.findByJobIdOrderByAttemptNumberAsc(id).stream().map(AttemptView::from).toList(),
                events.forJob(id),
                artifacts.forJob(id).stream().map(ArtifactView::from).toList());
    }

    @PostMapping("/{id}/cancel")
    public JobView cancel(@PathVariable String id) {
        return JobView.summary(jobService.cancel(id));
    }

    public record JobHistory(JobView job, List<AttemptView> attempts, List<EventView> events, List<ArtifactView> artifacts) {
    }
}

@RestController
@RequestMapping("/api/workflows")
class WorkflowController {

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowView submit(@RequestBody SubmitWorkflowRequest request) {
        return workflows.submit(request);
    }

    @GetMapping
    public List<WorkflowView> list(@RequestParam(defaultValue = "50") int limit) {
        return workflows.list(limit);
    }

    @GetMapping("/{id}")
    public WorkflowView get(@PathVariable String id) {
        return workflows.get(id);
    }
}

@RestController
class TaskDefinitionController {

    private final TaskRegistryService registry;
    private final JsonCodec json;

    public TaskDefinitionController(TaskRegistryService registry, JsonCodec json) {
        this.registry = registry;
        this.json = json;
    }

    @GetMapping("/api/tasks")
    public List<TaskDefinitionView> list() {
        return registry.list().stream().map(this::toView).toList();
    }

    @GetMapping("/api/tasks/{id}")
    public TaskDefinitionView get(@PathVariable String id) {
        return toView(registry.get(id));
    }

    /** Registers a new task type from its manifest; it can be used straight away. */
    @PostMapping("/api/admin/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDefinitionView register(@RequestBody TaskManifest manifest) {
        return toView(registry.register(manifest));
    }

    private TaskDefinitionView toView(TaskDefinitionEntity e) {
        return new TaskDefinitionView(e.getId(), e.getTaskType(), e.getDisplayName(), e.getDescription(),
                json.read(e.getInputSchema(), InputSchema.class), e.getTimeoutSeconds(), e.getMaxRetries(),
                e.getDefaultPriority(), e.getDefaultEstimatedMs());
    }

    public record TaskDefinitionView(String id, String taskType, String displayName, String description,
                                     InputSchema inputSchema, int timeoutSeconds, int maxRetries,
                                     int defaultPriority, long defaultEstimatedMs) {
    }
}

@RestController
class ArtifactController {

    private final ArtifactService artifacts;

    public ArtifactController(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @PostMapping(path = "/api/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactView upload(@RequestParam("file") MultipartFile file) {
        return ArtifactView.from(artifacts.uploadInput(file));
    }

    @GetMapping("/api/artifacts/{id}")
    public ArtifactView metadata(@PathVariable String id) {
        return ArtifactView.from(artifacts.get(id));
    }

    @GetMapping("/api/artifacts/{id}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id) {
        ArtifactEntity artifact = artifacts.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.getContentType()))
                .contentLength(artifact.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.getOriginalName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(artifacts.open(artifact)));
    }

    public record ArtifactView(String id, String jobId, String originalName, String contentType,
                               long sizeBytes, String sha256, Instant createdAt) {
        public static ArtifactView from(ArtifactEntity a) {
            return new ArtifactView(a.getId(), a.getJobId(), a.getOriginalName(), a.getContentType(),
                    a.getSizeBytes(), a.getSha256(), a.getCreatedAt());
        }
    }
}

/** Read-only worker monitoring plus administrator controls. */
@RestController
class WorkerController {

    private final WorkerRegistryService registry;
    private final WorkerLauncher launcher;

    public WorkerController(WorkerRegistryService registry, WorkerLauncher launcher) {
        this.registry = registry;
        this.launcher = launcher;
    }

    /** Starts one more worker process on this machine (demo convenience). */
    @PostMapping("/api/admin/workers/launch")
    public WorkerLauncher.Launched launch(@RequestParam(defaultValue = "2") int slots) {
        return launcher.launch(slots);
    }

    @GetMapping("/api/workers")
    public List<WorkerView> list() {
        return registry.list();
    }

    /**
     * action = drain | pause | resume | interrupt | disable | enable.
     * pause holds running tasks (leases stay alive); interrupt stops running tasks and requeues them.
     */
    @PostMapping("/api/admin/workers/{id}/{action:drain|pause|resume|interrupt|disable|enable}")
    public void changeStatus(@PathVariable String id, @PathVariable String action) {
        registry.changeStatus(id, action);
    }

    /** kind = kill | freeze */
    @PostMapping("/api/admin/workers/{id}/simulate/{kind}")
    public void simulate(@PathVariable String id, @PathVariable String kind) {
        registry.simulateFailure(id, kind);
    }
}

/**
 * The worker protocol (docs/architecture/coordinator-worker-protocol.md). After registering, a worker
 * sends its id in the {@value #WORKER_HEADER} header on every call.
 */
@RestController
@RequestMapping("/api/worker")
class WorkerApiController {

    public static final String WORKER_HEADER = "X-Worker-Id";

    private final WorkerRegistryService registry;
    private final SchedulerService scheduler;
    private final AttemptService attempts;
    private final ArtifactService artifacts;

    public WorkerApiController(WorkerRegistryService registry, SchedulerService scheduler, AttemptService attempts,
                               ArtifactService artifacts) {
        this.registry = registry;
        this.scheduler = scheduler;
        this.attempts = attempts;
        this.artifacts = artifacts;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody RegisterRequest request) {
        return registry.register(request);
    }

    @PostMapping("/claim")
    public ResponseEntity<Assignment> claim(@RequestHeader(WORKER_HEADER) String workerId) {
        return scheduler.claim(workerId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/heartbeat")
    public HeartbeatResponse heartbeat(@RequestHeader(WORKER_HEADER) String workerId, @RequestBody HeartbeatRequest request) {
        return attempts.heartbeat(workerId, request.activeAttemptIds());
    }

    @PostMapping("/attempts/{attemptId}/progress")
    public void progress(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                         @RequestBody ProgressReport report) {
        requireAccepted(attempts.progress(workerId, attemptId, report), attemptId);
    }

    @PostMapping("/attempts/{attemptId}/complete")
    public void complete(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                         @RequestBody CompletionReport report) {
        requireAccepted(attempts.complete(workerId, attemptId, report), attemptId);
    }

    @PostMapping("/attempts/{attemptId}/fail")
    public void fail(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                     @RequestBody FailureReport report) {
        requireAccepted(attempts.fail(workerId, attemptId, report), attemptId);
    }

    @GetMapping("/attempts/{attemptId}/artifacts/{artifactId}")
    public ResponseEntity<InputStreamResource> downloadInput(@RequestHeader(WORKER_HEADER) String workerId,
                                                             @PathVariable String attemptId, @PathVariable String artifactId) {
        ArtifactEntity artifact = attempts.inputForAttempt(workerId, attemptId, artifactId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(artifact.getSizeBytes())
                .body(new InputStreamResource(artifacts.open(artifact)));
    }

    @PostMapping(path = "/attempts/{attemptId}/artifacts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArtifactView uploadOutput(@RequestHeader(WORKER_HEADER) String workerId, @PathVariable String attemptId,
                                     @RequestParam("file") MultipartFile file) {
        return ArtifactView.from(attempts.uploadOutput(workerId, attemptId, file));
    }

    @PostMapping("/deregister")
    public void deregister(@RequestHeader(WORKER_HEADER) String workerId) {
        registry.deregister(workerId);
    }

    private static void requireAccepted(UpdateResult result, String attemptId) {
        if (result == UpdateResult.STALE) {
            throw new StaleAttemptException("Attempt " + attemptId + " is no longer current; stop working on it");
        }
    }
}

@RestController
class SchedulerController {

    private final SchedulerService scheduler;
    private final SchedulerSettingsService settings;

    public SchedulerController(SchedulerService scheduler, SchedulerSettingsService settings) {
        this.scheduler = scheduler;
        this.settings = settings;
    }

    @GetMapping("/api/scheduler")
    public QueuePreview preview() {
        return scheduler.preview();
    }

    @GetMapping("/api/scheduler/policies")
    public List<PolicyInfo> policies() {
        return Arrays.stream(PolicyType.values()).map(p -> new PolicyInfo(p, p.displayName())).toList();
    }

    @PutMapping("/api/admin/scheduler")
    public SchedulerSettings update(@RequestBody SchedulerSettings requested) {
        return settings.update(requested);
    }

    public record PolicyInfo(PolicyType type, String displayName) {
    }
}

@RestController
class MetricsController {

    private final MetricsService metrics;
    private final EventLog events;
    private final HistoryService history;
    private final JdbcTemplate jdbc;

    public MetricsController(MetricsService metrics, EventLog events, HistoryService history, JdbcTemplate jdbc) {
        this.metrics = metrics;
        this.events = events;
        this.history = history;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/admin/history/clear")
    public HistoryService.Cleared clearHistory() {
        return history.clear();
    }

    @GetMapping("/api/metrics/overview")
    public MetricsService.Overview overview() {
        return metrics.overview();
    }

    @GetMapping("/api/events")
    public List<EventView> events(@RequestParam(defaultValue = "100") int limit,
                                  @RequestParam(required = false) Long beforeId) {
        return events.recent(limit, beforeId);
    }

    /** Liveness plus a real database round trip. */
    @GetMapping("/api/health")
    public Health health() {
        boolean databaseUp;
        try {
            databaseUp = Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class));
        } catch (RuntimeException e) {
            databaseUp = false;
        }
        return new Health(databaseUp ? "UP" : "DEGRADED", databaseUp, "1.0.0", Instant.now());
    }

    public record Health(String status, boolean database, String version, Instant time) {
    }
}

/**
 * Stores and serves benchmark reports. The coordinator never runs benchmarks itself;
 * it only displays what queuepilot-benchmarks produced, labelled SIMULATED or MEASURED.
 */
@RestController
class BenchmarkController {

    private static final Set<String> KINDS = Set.of("SIMULATED", "MEASURED");
    private static final int MAX_JSON = 300_000;

    private final BenchmarkReportRepository reports;
    private final JsonCodec json;
    private final EventLog events;
    private final Clock clock;

    public BenchmarkController(BenchmarkReportRepository reports, JsonCodec json, EventLog events, Clock clock) {
        this.reports = reports;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    public record ReportRequest(String kind, String name, Map<String, Object> config, Map<String, Object> environment,
                                Map<String, Object> summary) {
    }

    public record ReportView(String id, String kind, String name, Map<String, Object> config,
                             Map<String, Object> environment, Map<String, Object> summary, Instant createdAt) {
    }

    @GetMapping("/api/benchmarks")
    public List<ReportView> list() {
        return reports.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    @GetMapping("/api/benchmarks/{id}")
    public ReportView get(@PathVariable String id) {
        return toView(reports.findById(id).orElseThrow(() -> new NotFoundException("No benchmark report " + id)));
    }

    @PostMapping("/api/admin/benchmarks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ReportView importReport(@RequestBody ReportRequest request) {
        if (request.kind() == null || !KINDS.contains(request.kind())) {
            throw new ValidationException("kind", "must be SIMULATED or MEASURED");
        }
        if (request.name() == null || request.name().isBlank() || request.name().length() > 128 || request.summary() == null) {
            throw new ValidationException("name", "name (max 128 chars) and summary are required");
        }
        String config = json.write(request.config() == null ? Map.of() : request.config());
        String environment = json.write(request.environment() == null ? Map.of() : request.environment());
        String summary = json.write(request.summary());
        if (config.length() > MAX_JSON || environment.length() > MAX_JSON || summary.length() > MAX_JSON) {
            throw new ValidationException("summary", "report is too large");
        }
        BenchmarkReportEntity entity = reports.save(new BenchmarkReportEntity(UUID.randomUUID().toString(),
                request.kind(), request.name(), config, environment, summary, clock.instant()));
        events.record(EventType.BENCHMARK_IMPORTED, null, null, null, request.kind() + " benchmark '" + request.name() + "' imported");
        return toView(entity);
    }

    private ReportView toView(BenchmarkReportEntity e) {
        return new ReportView(e.getId(), e.getKind(), e.getName(), json.readMap(e.getConfigJson()),
                json.readMap(e.getEnvironmentJson()), json.readMap(e.getSummaryJson()), e.getCreatedAt());
    }
}

/**
 * Body of {@code POST /api/jobs}. Only {@code taskType} is mandatory; the other settings fall back
 * to the task definition's defaults.
 *
 * @param priority  1 (lowest) .. 10 (highest)
 * @param dependsOn ids of existing jobs that must succeed first
 */
record SubmitJobRequest(String taskType,
                        Map<String, Object> input,
                        Integer priority,
                        Long estimatedMs,
                        String queue,
                        List<String> dependsOn) {
}

/** JSON representation of a job. {@code input}/{@code output} are only filled for single-job requests. */
record JobView(String id,
               String label,
               String taskType,
               String queue,
               JobState state,
               int priority,
               long estimatedMs,
               int progressPercent,
               String progressMessage,
               int attemptsUsed,
               String currentWorkerId,
               String currentAttemptId,
               Set<String> dependsOn,
               String workflowId,
               String workflowStep,
               Instant submittedAt,
               Instant readyAt,
               Instant startedAt,
               Instant finishedAt,
               String error,
               Map<String, Object> input,
               Map<String, Object> output,
               String outputArtifactIds) {

    public static JobView summary(JobEntity j) {
        return of(j, null, null);
    }

    public static JobView of(JobEntity j, Map<String, Object> input, Map<String, Object> output) {
        return new JobView(j.getId(), j.label(), j.getTaskType(), j.getQueueName(), j.getState(), j.getPriority(),
                j.getEstimatedMs(), j.getProgressPercent(), j.getProgressMessage(), j.getAttemptsUsed(),
                j.getCurrentWorkerId(), j.getCurrentAttemptId(), j.getPrerequisiteIds(), j.getWorkflowId(),
                j.getWorkflowStep(), j.getSubmittedAt(), j.getReadyAt(), j.getStartedAt(), j.getFinishedAt(),
                j.getError(), input, output, j.getOutputArtifactIds());
    }
}

record AttemptView(String id, String jobId, String workerId, int attemptNumber, AttemptState state,
                   Instant startedAt, Instant leaseExpiresAt, Instant finishedAt, String error) {

    public static AttemptView from(AttemptEntity a) {
        return new AttemptView(a.getId(), a.getJobId(), a.getWorkerId(), a.getAttemptNumber(), a.getState(),
                a.getStartedAt(), a.getLeaseExpiresAt(), a.getFinishedAt(), a.getError());
    }
}

record WorkerView(String id,
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

/** A workflow with its graph (nodes = jobs, edges = prerequisite -> dependant) and aggregate progress. */
record WorkflowView(String id,
                    String name,
                    Instant createdAt,
                    String state,
                    int percentComplete,
                    Map<JobState, Integer> stateCounts,
                    List<Node> nodes,
                    List<Edge> edges) {

    public record Node(String jobId, String step, String task, JobState state, int progressPercent) {
    }

    public record Edge(String from, String to) {
    }

    /** RUNNING while anything is unfinished; afterwards SUCCEEDED only if every step succeeded. */
    static String overallState(Map<JobState, Integer> counts, int total) {
        int succeeded = counts.getOrDefault(JobState.SUCCEEDED, 0);
        int finished = 0;
        for (Map.Entry<JobState, Integer> entry : counts.entrySet()) {
            if (entry.getKey().isTerminal()) {
                finished += entry.getValue();
            }
        }
        if (finished < total) {
            return counts.getOrDefault(JobState.RUNNING, 0) > 0 || finished > 0 ? "RUNNING" : "PENDING";
        }
        return succeeded == total ? "SUCCEEDED" : "FAILED";
    }
}

/**
 * The JSON manifest describing a task type (docs/architecture/task-plugin-contract.md).
 * Used both for registration requests and for the seed files of built-in tasks.
 */
record TaskManifest(String taskType,
                    String displayName,
                    String description,
                    InputSchema inputSchema,
                    Integer timeoutSeconds,
                    Integer maxRetries,
                    Integer defaultPriority,
                    Long defaultEstimatedMs) {
}
