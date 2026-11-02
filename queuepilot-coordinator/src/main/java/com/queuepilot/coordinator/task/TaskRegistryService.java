package com.queuepilot.coordinator.task;

import com.queuepilot.coordinator.config.JsonCodec;
import com.queuepilot.coordinator.error.Errors.ConflictException;
import com.queuepilot.coordinator.error.Errors.NotFoundException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.core.schema.InputSchema;
import com.queuepilot.core.schema.InputValidator;
import com.queuepilot.core.schema.ValidationError;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Task Registry: validates and stores task manifests, enables/disables versions and resolves
 * which version a submission uses. It stores metadata only; it never loads task code.
 */
@Service
public class TaskRegistryService {

    /** Capability every worker that runs CONTAINER tasks must advertise. */
    public static final String DOCKER_CAPABILITY = "docker";

    private static final Pattern TASK_TYPE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern VERSION = Pattern.compile("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final TaskDefinitionRepository repository;
    private final InputValidator validator;
    private final JsonCodec json;
    private final EventLog events;
    private final Clock clock;

    public TaskRegistryService(TaskDefinitionRepository repository, InputValidator validator, JsonCodec json,
                               EventLog events, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public TaskDefinitionEntity register(TaskManifest manifest, boolean enabled) {
        List<ValidationError> errors = validate(manifest);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        if (repository.findByTaskTypeAndVersion(manifest.taskType(), manifest.version()).isPresent()) {
            throw new ConflictException("Task " + manifest.taskType() + "@" + manifest.version()
                    + " already exists; register a new version instead of changing it");
        }

        Set<String> capabilities = new LinkedHashSet<>(manifest.requiredCapabilities() == null ? List.of() : manifest.requiredCapabilities());
        if (manifest.executorKind() == ExecutorKind.CONTAINER) {
            capabilities.add(DOCKER_CAPABILITY);
        }
        String commandJson = manifest.container() == null ? null : json.write(manifest.container().command());
        TaskDefinitionEntity entity = new TaskDefinitionEntity(UUID.randomUUID().toString(), manifest, capabilities,
                json.write(schemaOrEmpty(manifest.inputSchema())), json.write(schemaOrEmpty(manifest.outputSchema())),
                commandJson, enabled, clock.instant());
        repository.save(entity);
        events.record(EventType.TASK_REGISTERED, null, null, null,
                "Registered task " + entity.identifier() + (enabled ? " (enabled)" : " (disabled until enabled by an administrator)"));
        return entity;
    }

    @Transactional
    public TaskDefinitionEntity setEnabled(String id, boolean enabled) {
        TaskDefinitionEntity entity = get(id);
        entity.setEnabled(enabled);
        events.record(EventType.TASK_STATUS_CHANGED, null, null, null,
                "Task " + entity.identifier() + (enabled ? " enabled" : " disabled"));
        return entity;
    }

    @Transactional(readOnly = true)
    public TaskDefinitionEntity get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("No task definition " + id));
    }

    @Transactional(readOnly = true)
    public List<TaskDefinitionEntity> list() {
        return repository.findAllByOrderByTaskTypeAscCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<TaskDefinitionEntity> find(String taskType, String version) {
        return repository.findByTaskTypeAndVersion(taskType, version);
    }

    /** The exact version if given, otherwise the highest enabled version of the type. */
    @Transactional(readOnly = true)
    public TaskDefinitionEntity resolveForSubmission(String taskType, String version) {
        if (taskType == null || taskType.isBlank()) {
            throw new ValidationException("taskType", "is required");
        }
        TaskDefinitionEntity definition;
        if (version != null && !version.isBlank()) {
            definition = repository.findByTaskTypeAndVersion(taskType, version)
                    .orElseThrow(() -> new ValidationException("taskVersion", "task " + taskType + "@" + version + " is not registered"));
        } else {
            definition = repository.findByTaskType(taskType).stream()
                    .filter(TaskDefinitionEntity::isEnabled)
                    .max(Comparator.comparing(TaskDefinitionEntity::getVersion, TaskDefinitionEntity::compareVersions))
                    .orElseThrow(() -> new ValidationException("taskType", "no enabled task type '" + taskType + "'"));
        }
        if (!definition.isEnabled()) {
            throw new ValidationException("taskType", "task " + definition.identifier() + " is disabled");
        }
        return definition;
    }

    public InputSchema inputSchema(TaskDefinitionEntity definition) {
        return json.read(definition.getInputSchema(), InputSchema.class);
    }

    private List<ValidationError> validate(TaskManifest m) {
        List<ValidationError> errors = new ArrayList<>();
        if (m.taskType() == null || !TASK_TYPE.matcher(m.taskType()).matches()) {
            errors.add(new ValidationError("taskType", "must be lowercase letters, digits and dashes (2-64 chars)"));
        }
        if (m.version() == null || !VERSION.matcher(m.version()).matches()) {
            errors.add(new ValidationError("version", "must look like 1.0.0"));
        }
        if (m.displayName() == null || m.displayName().isBlank() || m.displayName().length() > 128) {
            errors.add(new ValidationError("displayName", "is required (max 128 chars)"));
        }
        if (m.description() != null && m.description().length() > 1000) {
            errors.add(new ValidationError("description", "must be at most 1000 chars"));
        }
        if (m.executorKind() == null) {
            errors.add(new ValidationError("executorKind", "must be JAVA_PLUGIN or CONTAINER"));
        }
        if (m.requiredCapabilities() != null) {
            for (String capability : m.requiredCapabilities()) {
                if (capability == null || !CAPABILITY.matcher(capability).matches()) {
                    errors.add(new ValidationError("requiredCapabilities", "'" + capability + "' is not a valid capability name"));
                }
            }
        }
        errors.addAll(validator.checkSchema(schemaOrEmpty(m.inputSchema()), "inputSchema"));
        errors.addAll(validator.checkSchema(schemaOrEmpty(m.outputSchema()), "outputSchema"));
        if (m.resources() == null || m.resources().cpuMillis() < 100 || m.resources().memoryMb() < 16
                || m.resources().cpuMillis() > 16000 || m.resources().memoryMb() > 16384) {
            errors.add(new ValidationError("resources", "cpuMillis must be 100..16000 and memoryMb 16..16384"));
        }
        checkRange(errors, "timeoutSeconds", m.timeoutSeconds(), 1, 86_400);
        checkRange(errors, "maxRetries", m.maxRetries(), 0, 10);
        checkRange(errors, "defaultPriority", m.defaultPriority(), 1, 10);
        if (m.defaultEstimatedMs() == null || m.defaultEstimatedMs() < 1 || m.defaultEstimatedMs() > 86_400_000L) {
            errors.add(new ValidationError("defaultEstimatedMs", "must be 1..86400000"));
        }
        if (m.executorKind() == ExecutorKind.CONTAINER) {
            if (m.container() == null || m.container().image() == null || !m.container().image().contains("@sha256:")) {
                errors.add(new ValidationError("container.image", "CONTAINER tasks need an image pinned by digest (name@sha256:...)"));
            }
            if (m.container() == null || m.container().command() == null || m.container().command().isEmpty()) {
                errors.add(new ValidationError("container.command", "is required for CONTAINER tasks"));
            }
        } else if (m.container() != null) {
            errors.add(new ValidationError("container", "is only allowed for CONTAINER tasks"));
        }
        return errors;
    }

    private static void checkRange(List<ValidationError> errors, String field, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            errors.add(new ValidationError(field, "must be " + min + ".." + max));
        }
    }

    private static InputSchema schemaOrEmpty(InputSchema schema) {
        return schema == null ? InputSchema.empty() : schema;
    }
}
