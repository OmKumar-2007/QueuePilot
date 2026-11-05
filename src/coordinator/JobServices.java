package coordinator;

import core.Jobs.DependencyGraph;
import core.Jobs.JobState;
import core.Validation.InputSchema;
import core.Validation.InputValidator;
import core.Validation.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Job submission, queries and cancellation. A submission is acknowledged only after its
 * transaction commits, so an accepted job is never lost.
 */
@Service
class JobService {

    private static final String DEFAULT_QUEUE = "default";
    /** Inputs larger than this must be uploaded as a file artifact instead. */
    static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_DEPENDENCIES = 50;

    private final JobRepository jobs;
    private final TaskRegistryService registry;
    private final DependencyService dependencies;
    private final ArtifactService artifacts;
    private final InputValidator validator;
    private final JsonCodec json;
    private final EventLog events;
    private final Clock clock;

    public JobService(JobRepository jobs, TaskRegistryService registry, DependencyService dependencies,
                      ArtifactService artifacts, InputValidator validator, JsonCodec json, EventLog events, Clock clock) {
        this.jobs = jobs;
        this.registry = registry;
        this.dependencies = dependencies;
        this.artifacts = artifacts;
        this.validator = validator;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public JobEntity submit(SubmitJobRequest request) {
        return create(request, null, null);
    }

    /** Creates one job; also used by workflow submission inside its own transaction. */
    @Transactional
    public JobEntity create(SubmitJobRequest request, String workflowId, String workflowStep) {
        Instant now = clock.instant();
        List<ValidationError> errors = new ArrayList<>();

        TaskDefinitionEntity task = registry.resolve(request.taskType());
        InputSchema schema = registry.inputSchema(task);
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        String inputJson = json.write(input);
        if (inputJson.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new ValidationException("input", "must be at most " + MAX_INPUT_BYTES
                    + " bytes; upload large data as a file artifact");
        }
        errors.addAll(validator.validate(input, schema));
        List<String> artifactIds = validator.referencedArtifactIds(input, schema);
        if (errors.isEmpty()) {
            errors.addAll(artifacts.checkLinkable(artifactIds));
        }

        int priority = request.priority() == null ? task.getDefaultPriority() : request.priority();
        if (priority < 1 || priority > 10) {
            errors.add(new ValidationError("priority", "must be 1..10"));
        }
        long estimatedMs = request.estimatedMs() == null ? task.getDefaultEstimatedMs() : request.estimatedMs();
        if (estimatedMs < 1 || estimatedMs > Duration.ofDays(1).toMillis()) {
            errors.add(new ValidationError("estimatedMs", "must be 1..86400000"));
        }
        String queue = request.queue() == null || request.queue().isBlank() ? DEFAULT_QUEUE : request.queue();
        if (!NAME_TOKEN.matcher(queue).matches()) {
            errors.add(new ValidationError("queue", "must be 1-64 characters from A-Z a-z 0-9 . _ -"));
        }
        Set<String> prerequisites = new LinkedHashSet<>(request.dependsOn() == null ? List.of() : request.dependsOn());
        if (prerequisites.size() > MAX_DEPENDENCIES) {
            errors.add(new ValidationError("dependsOn", "at most " + MAX_DEPENDENCIES + " prerequisites"));
        }
        for (String prerequisite : prerequisites) {
            if (prerequisite == null || !jobs.existsById(prerequisite)) {
                errors.add(new ValidationError("dependsOn", "job " + prerequisite + " does not exist"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        JobState initial = initialState(prerequisites);
        JobEntity job = new JobEntity(UUID.randomUUID().toString(),
                new JobEntity.NewJob(task.getTaskType(), queue, priority, estimatedMs, inputJson, prerequisites,
                        workflowId, workflowStep, initial),
                now);
        jobs.save(job);
        artifacts.link(artifactIds, job.getId());
        events.record(EventType.JOB_SUBMITTED, job.getId(), null, null, "Job " + job.label() + " submitted -> " + initial);
        return job;
    }

    private JobState initialState(Set<String> prerequisites) {
        return switch (dependencies.readinessOf(prerequisites)) {
            case BROKEN -> JobState.SKIPPED;
            case READY -> JobState.QUEUED;
            case WAITING -> JobState.BLOCKED;
        };
    }

    @Transactional
    public JobEntity cancel(String jobId) {
        JobEntity job = get(jobId);
        Instant now = clock.instant();
        job.cancel(now);
        events.record(EventType.JOB_CANCELLED, jobId, null, null, "Job " + job.label() + " cancelled by client");
        dependencies.onJobFinished(job, now);
        return job;
    }

    @Transactional(readOnly = true)
    public JobEntity get(String jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new NotFoundException("No job " + jobId));
    }

    @Transactional(readOnly = true)
    public List<JobEntity> list(String state, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 1000)));
        if (state == null || state.isBlank()) {
            return jobs.findAllByOrderBySubmittedAtDesc(page);
        }
        JobState parsed = Arrays.stream(JobState.values()).filter(s -> s.name().equalsIgnoreCase(state)).findFirst()
                .orElseThrow(() -> new ValidationException("state", "unknown state " + state));
        return jobs.findByStateOrderBySubmittedAtDesc(parsed, page);
    }

    public Map<String, Object> inputOf(JobEntity job) {
        return json.readMap(job.getInputJson());
    }

    public Map<String, Object> outputOf(JobEntity job) {
        return job.getOutputJson() == null ? null : json.readMap(job.getOutputJson());
    }
}

/**
 * Workflow readiness rules (F12, F19): releases dependants when a prerequisite succeeds and skips
 * them, transitively, when a prerequisite fails, is cancelled or is skipped.
 */
@Service
class DependencyService {

    /** Readiness of a job's prerequisites. */
    public enum Readiness { READY, WAITING, BROKEN }

    private final JobRepository jobs;
    private final EventLog events;

    public DependencyService(JobRepository jobs, EventLog events) {
        this.jobs = jobs;
        this.events = events;
    }

    public Readiness readinessOf(Collection<String> prerequisiteIds) {
        if (prerequisiteIds.isEmpty()) {
            return Readiness.READY;
        }
        if (jobs.countBrokenAmong(prerequisiteIds) > 0) {
            return Readiness.BROKEN;
        }
        long succeeded = jobs.countByIdInAndState(prerequisiteIds, JobState.SUCCEEDED);
        return succeeded == prerequisiteIds.size() ? Readiness.READY : Readiness.WAITING;
    }

    /** Must run inside the transaction that finished {@code job}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onJobFinished(JobEntity job, Instant now) {
        jobs.flush();
        for (JobEntity dependant : jobs.findDependants(job.getId())) {
            if (job.getState() == JobState.SUCCEEDED) {
                releaseIfReady(dependant, now);
            } else if (dependant.getState() == JobState.BLOCKED) {
                dependant.skip("Prerequisite " + job.label() + " ended " + job.getState(), now);
                events.record(EventType.JOB_SKIPPED, dependant.getId(), null, null,
                        "Job " + dependant.label() + " skipped because prerequisite " + job.label() + " ended " + job.getState());
                onJobFinished(dependant, now);
            }
        }
    }

    private void releaseIfReady(JobEntity dependant, Instant now) {
        if (dependant.getState() != JobState.BLOCKED) {
            return;
        }
        if (readinessOf(dependant.getPrerequisiteIds()) == Readiness.READY) {
            dependant.release(now);
            events.record(EventType.JOB_RELEASED, dependant.getId(), null, null,
                    "Job " + dependant.label() + " unblocked: all prerequisites succeeded");
        }
    }
}

/**
 * Submits a whole dependency graph in one transaction (F12, F20): steps reference each other by
 * key, the graph is checked for cycles, and jobs are created in topological order so each step's
 * prerequisites already exist when it is created.
 */
@Service
class WorkflowService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_STEPS = 100;

    private final WorkflowRepository workflows;
    private final JobRepository jobs;
    private final JobService jobService;
    private final EventLog events;
    private final Clock clock;

    public WorkflowService(WorkflowRepository workflows, JobRepository jobs, JobService jobService, EventLog events, Clock clock) {
        this.workflows = workflows;
        this.jobs = jobs;
        this.jobService = jobService;
        this.events = events;
        this.clock = clock;
    }

    public record StepRequest(String key, String taskType, Map<String, Object> input, Integer priority, Long estimatedMs,
                              List<String> dependsOn) {
    }

    public record SubmitWorkflowRequest(String name, String queue, List<StepRequest> steps) {
    }

    @Transactional
    public WorkflowView submit(SubmitWorkflowRequest request) {
        List<StepRequest> steps = request.steps() == null ? List.of() : request.steps();
        List<ValidationError> errors = new ArrayList<>();
        String name = request.name() == null || request.name().isBlank() ? "workflow" : request.name().trim();
        if (name.length() > 128) {
            errors.add(new ValidationError("name", "must be at most 128 characters"));
        }
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            errors.add(new ValidationError("steps", "must contain 1.." + MAX_STEPS + " steps"));
        }
        Map<String, StepRequest> byKey = new HashMap<>();
        DependencyGraph graph = new DependencyGraph();
        for (StepRequest step : steps) {
            if (step.key() == null || !KEY.matcher(step.key()).matches()) {
                errors.add(new ValidationError("steps", "step key '" + step.key() + "' is invalid"));
            } else if (byKey.put(step.key(), step) != null) {
                errors.add(new ValidationError("steps", "step key '" + step.key() + "' is used twice"));
            } else {
                graph.addNode(step.key());
            }
        }
        for (StepRequest step : steps) {
            for (String dependency : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
                if (!byKey.containsKey(dependency)) {
                    errors.add(new ValidationError("steps." + step.key() + ".dependsOn", "unknown step '" + dependency + "'"));
                } else {
                    graph.addDependency(step.key(), dependency);
                }
            }
        }
        if (errors.isEmpty()) {
            graph.findCycle().ifPresent(cycle ->
                    errors.add(new ValidationError("steps", "dependency cycle: " + String.join(" -> ", cycle))));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        WorkflowEntity workflow = workflows.save(new WorkflowEntity(UUID.randomUUID().toString(), name, clock.instant()));
        Map<String, String> jobIdByKey = new HashMap<>();
        for (String key : graph.topologicalOrder()) {
            StepRequest step = byKey.get(key);
            List<String> prerequisiteIds = graph.prerequisitesOf(key).stream().map(jobIdByKey::get).toList();
            SubmitJobRequest jobRequest = new SubmitJobRequest(step.taskType(), step.input(), step.priority(),
                    step.estimatedMs(), request.queue(), prerequisiteIds);
            try {
                jobIdByKey.put(key, jobService.create(jobRequest, workflow.getId(), key).getId());
            } catch (ValidationException e) {
                throw new ValidationException(e.errors().stream()
                        .map(err -> new ValidationError("steps." + key + "." + err.field(), err.message())).toList());
            }
        }
        events.record(EventType.WORKFLOW_SUBMITTED, null, null, null,
                "Workflow " + name + " submitted with " + steps.size() + " steps");
        return view(workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowView get(String id) {
        return view(workflows.findById(id).orElseThrow(() -> new NotFoundException("No workflow " + id)));
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(int limit) {
        return workflows.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
                .stream().map(this::view).toList();
    }

    private WorkflowView view(WorkflowEntity workflow) {
        List<JobEntity> members = jobs.findByWorkflowIdOrderBySubmittedAtAsc(workflow.getId());
        Set<String> memberIds = new HashSet<>();
        members.forEach(job -> memberIds.add(job.getId()));
        Map<JobState, Integer> counts = new EnumMap<>(JobState.class);
        List<WorkflowView.Node> nodes = new ArrayList<>();
        List<WorkflowView.Edge> edges = new ArrayList<>();
        int terminal = 0;
        for (JobEntity job : members) {
            counts.merge(job.getState(), 1, Integer::sum);
            if (job.getState().isTerminal()) {
                terminal++;
            }
            nodes.add(new WorkflowView.Node(job.getId(), job.getWorkflowStep(), job.getTaskType(),
                    job.getState(), job.getProgressPercent()));
            for (String prerequisite : job.getPrerequisiteIds()) {
                if (memberIds.contains(prerequisite)) {
                    edges.add(new WorkflowView.Edge(prerequisite, job.getId()));
                }
            }
        }
        int percent = members.isEmpty() ? 0 : 100 * terminal / members.size();
        return new WorkflowView(workflow.getId(), workflow.getName(), workflow.getCreatedAt(),
                WorkflowView.overallState(counts, members.size()), percent, counts, nodes, edges);
    }
}

/**
 * The Task Registry: validates and stores task manifests and looks task types up by name.
 * It stores metadata only; it never loads task code.
 */
@Service
class TaskRegistryService {

    private static final Pattern TASK_TYPE = Pattern.compile("[a-z][a-z0-9-]{1,63}");

    private final TaskDefinitionRepository repository;
    private final InputValidator validator;
    private final JsonCodec json;
    private final EventLog events;

    public TaskRegistryService(TaskDefinitionRepository repository, InputValidator validator, JsonCodec json, EventLog events) {
        this.repository = repository;
        this.validator = validator;
        this.json = json;
        this.events = events;
    }

    @Transactional
    public TaskDefinitionEntity register(TaskManifest manifest) {
        List<ValidationError> errors = validate(manifest);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        if (repository.findByTaskType(manifest.taskType()).isPresent()) {
            throw new ConflictException("Task type " + manifest.taskType() + " already exists");
        }
        InputSchema schema = manifest.inputSchema() == null ? InputSchema.empty() : manifest.inputSchema();
        TaskDefinitionEntity entity = new TaskDefinitionEntity(UUID.randomUUID().toString(), manifest, json.write(schema));
        repository.save(entity);
        events.record(EventType.TASK_REGISTERED, null, null, null, "Registered task " + entity.getTaskType());
        return entity;
    }

    @Transactional(readOnly = true)
    public TaskDefinitionEntity get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("No task definition " + id));
    }

    @Transactional(readOnly = true)
    public List<TaskDefinitionEntity> list() {
        return repository.findAllByOrderByTaskTypeAsc();
    }

    @Transactional(readOnly = true)
    public Optional<TaskDefinitionEntity> find(String taskType) {
        return repository.findByTaskType(taskType);
    }

    /** The task definition a submission refers to; a validation error if there is none. */
    @Transactional(readOnly = true)
    public TaskDefinitionEntity resolve(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new ValidationException("taskType", "is required");
        }
        return repository.findByTaskType(taskType)
                .orElseThrow(() -> new ValidationException("taskType", "no task type '" + taskType + "'"));
    }

    public InputSchema inputSchema(TaskDefinitionEntity definition) {
        return json.read(definition.getInputSchema(), InputSchema.class);
    }

    private List<ValidationError> validate(TaskManifest m) {
        List<ValidationError> errors = new ArrayList<>();
        if (m.taskType() == null || !TASK_TYPE.matcher(m.taskType()).matches()) {
            errors.add(new ValidationError("taskType", "must be lowercase letters, digits and dashes (2-64 chars)"));
        }
        if (m.displayName() == null || m.displayName().isBlank() || m.displayName().length() > 128) {
            errors.add(new ValidationError("displayName", "is required (max 128 chars)"));
        }
        if (m.description() != null && m.description().length() > 1000) {
            errors.add(new ValidationError("description", "must be at most 1000 chars"));
        }
        errors.addAll(validator.checkSchema(m.inputSchema() == null ? InputSchema.empty() : m.inputSchema(), "inputSchema"));
        checkRange(errors, "timeoutSeconds", m.timeoutSeconds(), 1, 86_400);
        checkRange(errors, "maxRetries", m.maxRetries(), 0, 10);
        checkRange(errors, "defaultPriority", m.defaultPriority(), 1, 10);
        if (m.defaultEstimatedMs() == null || m.defaultEstimatedMs() < 1 || m.defaultEstimatedMs() > 86_400_000L) {
            errors.add(new ValidationError("defaultEstimatedMs", "must be 1..86400000"));
        }
        return errors;
    }

    private static void checkRange(List<ValidationError> errors, String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            errors.add(new ValidationError(field, "must be " + min + ".." + max));
        }
    }
}

/** Upload, download and job-linking rules for artifacts (F21). */
@Service
class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    /** Largest file accepted (50 MB). */
    static final long MAX_ARTIFACT_BYTES = 50L * 1024 * 1024;

    private final ArtifactRepository repository;
    private final ArtifactStorage storage;
    private final Clock clock;

    public ArtifactService(ArtifactRepository repository, ArtifactStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public ArtifactEntity uploadInput(MultipartFile file) {
        return save(file, null);
    }

    @Transactional
    public ArtifactEntity uploadOutput(MultipartFile file, String jobId) {
        return save(file, jobId);
    }

    private ArtifactEntity save(MultipartFile file, String jobId) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "is required and must not be empty");
        }
        String name = sanitizeName(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try (InputStream in = file.getInputStream()) {
            String id = UUID.randomUUID().toString();
            ArtifactStorage.StoredFile stored = storage.store(id, in, MAX_ARTIFACT_BYTES);
            Instant now = clock.instant();
            return repository.save(new ArtifactEntity(id, jobId, name,
                    contentType.length() > 100 ? "application/octet-stream" : contentType, stored, now));
        } catch (ArtifactStorage.TooLargeException e) {
            throw new ValidationException("file", e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store artifact", e);
        }
    }

    @Transactional(readOnly = true)
    public ArtifactEntity get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("No artifact " + id));
    }

    public InputStream open(ArtifactEntity artifact) {
        try {
            return storage.open(artifact.getId());
        } catch (IOException e) {
            throw new NotFoundException("Artifact content is missing");
        }
    }

    /** Checks that input artifacts referenced by a new job exist and are not used by another job. */
    @Transactional(readOnly = true)
    public List<ValidationError> checkLinkable(List<String> ids) {
        List<ValidationError> errors = new ArrayList<>();
        for (String id : ids) {
            ArtifactEntity artifact = repository.findById(id).orElse(null);
            if (artifact == null) {
                errors.add(new ValidationError("input", "artifact " + id + " does not exist"));
            } else if (artifact.getJobId() != null) {
                errors.add(new ValidationError("input", "artifact " + id + " is already used by job " + artifact.getJobId()));
            }
        }
        return errors;
    }

    @Transactional
    public void link(List<String> ids, String jobId) {
        for (String id : ids) {
            get(id).linkTo(jobId);
        }
    }

    @Transactional(readOnly = true)
    public List<ArtifactEntity> forJob(String jobId) {
        return repository.findByJobId(jobId);
    }

    /** Best effort: a file that cannot be deleted is only wasted disk space, so it must not fail the caller. */
    public void deleteFiles(List<String> ids) {
        for (String id : ids) {
            try {
                storage.delete(id);
            } catch (IOException e) {
                log.warn("Could not delete artifact file {}: {}", id, e.getMessage());
            }
        }
    }

    private static String sanitizeName(String original) {
        if (original == null || original.isBlank()) {
            return "upload.bin";
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}

/**
 * Where artifact bytes live: files named by their artifact id (a UUID) under {@code <dataDir>/artifacts}.
 * File names never come from user input, so path traversal is impossible. Metadata stays in the
 * database ({@link ArtifactEntity}).
 */
@Component
class ArtifactStorage {

    record StoredFile(long sizeBytes, String sha256) {
    }

    static class TooLargeException extends IOException {
        TooLargeException(long maxBytes) {
            super("File exceeds the limit of " + maxBytes + " bytes");
        }
    }

    private static final Pattern KEY = Pattern.compile("[0-9a-f-]{36}");

    private final Path root;

    public ArtifactStorage(QueuePilotProperties properties) throws IOException {
        this.root = properties.dataDir().resolve("artifacts").toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    /** Stores the stream under {@code key} (the artifact id), rejecting anything larger than {@code maxBytes}. */
    public StoredFile store(String key, InputStream content, long maxBytes) throws IOException {
        Path temp = Files.createTempFile(root, "upload-", ".tmp");
        MessageDigest digest = sha256();
        long size = 0;
        try (DigestInputStream in = new DigestInputStream(content, digest);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                size += n;
                if (size > maxBytes) {
                    throw new TooLargeException(maxBytes);
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, resolve(key), StandardCopyOption.ATOMIC_MOVE);
        return new StoredFile(size, HexFormat.of().formatHex(digest.digest()));
    }

    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return root.resolve(key);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
