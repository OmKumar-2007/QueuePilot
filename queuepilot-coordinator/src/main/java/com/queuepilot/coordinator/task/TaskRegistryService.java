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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Task Registry: validates and stores task manifests and looks task types up by name.
 * It stores metadata only; it never loads task code.
 */
@Service
public class TaskRegistryService {

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
