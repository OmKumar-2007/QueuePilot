package com.queuepilot.coordinator.task;

import com.queuepilot.coordinator.config.JsonCodec;
import com.queuepilot.core.schema.InputSchema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TaskDefinitionController {

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
