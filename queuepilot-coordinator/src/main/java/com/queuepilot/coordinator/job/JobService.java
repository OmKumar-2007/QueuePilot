package com.queuepilot.coordinator.job;

import com.queuepilot.coordinator.artifact.ArtifactService;
import com.queuepilot.coordinator.config.JsonCodec;
import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.coordinator.task.TaskDefinitionEntity;
import com.queuepilot.coordinator.task.TaskRegistryService;
import com.queuepilot.core.job.JobState;
import com.queuepilot.core.schema.InputSchema;
import com.queuepilot.core.schema.InputValidator;
import com.queuepilot.core.schema.ValidationError;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Job submission, queries and cancellation. A submission is acknowledged only after its
 * transaction commits, so an accepted job is never lost.
 */
@Service
public class JobService {

    public static final String DEFAULT_QUEUE = "default";
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
