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

import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /** Registers a manifest. New definitions start disabled so an administrator can review them first. */
    @PostMapping("/api/admin/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDefinitionView register(@RequestBody TaskManifest manifest) {
        return toView(registry.register(manifest, false));
    }

    @PostMapping("/api/admin/tasks/{id}/enable")
    public TaskDefinitionView enable(@PathVariable String id) {
        return toView(registry.setEnabled(id, true));
    }

    @PostMapping("/api/admin/tasks/{id}/disable")
    public TaskDefinitionView disable(@PathVariable String id) {
        return toView(registry.setEnabled(id, false));
    }

    private TaskDefinitionView toView(TaskDefinitionEntity e) {
        return new TaskDefinitionView(e.getId(), e.getTaskType(), e.getVersion(), e.identifier(), e.getDisplayName(),
                e.getDescription(), e.getExecutorKind(), e.capabilitySet(),
                json.read(e.getInputSchema(), InputSchema.class), json.read(e.getOutputSchema(), InputSchema.class),
                e.getCpuMillis(), e.getMemoryMb(), e.getTimeoutSeconds(), e.getMaxRetries(), e.getDefaultPriority(),
                e.getDefaultEstimatedMs(), e.getContainerImage(), e.isEnabled(), e.getCreatedAt());
    }

    public record TaskDefinitionView(String id, String taskType, String version, String identifier, String displayName,
                                     String description, ExecutorKind executorKind, Set<String> requiredCapabilities,
                                     InputSchema inputSchema, InputSchema outputSchema, int cpuMillis, int memoryMb,
                                     int timeoutSeconds, int maxRetries, int defaultPriority, long defaultEstimatedMs,
                                     String containerImage, boolean enabled, Instant createdAt) {
    }
}
