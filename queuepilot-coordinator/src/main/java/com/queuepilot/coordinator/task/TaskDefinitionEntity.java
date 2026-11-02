package com.queuepilot.coordinator.task;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Metadata of one version of a task type. Immutable after creation except for {@code enabled};
 * a change requires registering a new version.
 */
@Entity
@Table(name = "task_definition")
public class TaskDefinitionEntity {

    @Id
    private String id;
    private String taskType;
    private String version;
    private String displayName;
    private String description;
    @Enumerated(EnumType.STRING)
    private ExecutorKind executorKind;
    private String requiredCapabilities;
    private String inputSchema;
    private String outputSchema;
    private int cpuMillis;
    private int memoryMb;
    private int timeoutSeconds;
    private int maxRetries;
    private int defaultPriority;
    private long defaultEstimatedMs;
    private String containerImage;
    private String containerCommand;
    private boolean enabled;
    private Instant createdAt;

    protected TaskDefinitionEntity() {
    }

    public TaskDefinitionEntity(String id, TaskManifest manifest, Set<String> capabilities, String inputSchemaJson,
                                String outputSchemaJson, String containerCommandJson, boolean enabled, Instant createdAt) {
        this.id = id;
        this.taskType = manifest.taskType();
        this.version = manifest.version();
        this.displayName = manifest.displayName();
        this.description = manifest.description();
        this.executorKind = manifest.executorKind();
        this.requiredCapabilities = String.join(",", capabilities);
        this.inputSchema = inputSchemaJson;
        this.outputSchema = outputSchemaJson;
        this.cpuMillis = manifest.resources().cpuMillis();
        this.memoryMb = manifest.resources().memoryMb();
        this.timeoutSeconds = manifest.timeoutSeconds();
        this.maxRetries = manifest.maxRetries();
        this.defaultPriority = manifest.defaultPriority();
        this.defaultEstimatedMs = manifest.defaultEstimatedMs();
        this.containerImage = manifest.container() == null ? null : manifest.container().image();
        this.containerCommand = containerCommandJson;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String identifier() {
        return taskType + "@" + version;
    }

    public Set<String> capabilitySet() {
        return splitCsv(requiredCapabilities);
    }

    public static Set<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public String getTaskType() { return taskType; }
    public String getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public ExecutorKind getExecutorKind() { return executorKind; }
    public String getRequiredCapabilities() { return requiredCapabilities; }
    public String getInputSchema() { return inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public int getCpuMillis() { return cpuMillis; }
    public int getMemoryMb() { return memoryMb; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public int getDefaultPriority() { return defaultPriority; }
    public long getDefaultEstimatedMs() { return defaultEstimatedMs; }
    public String getContainerImage() { return containerImage; }
    public String getContainerCommand() { return containerCommand; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    /** Versions compare numerically part by part, so 1.10.0 is newer than 1.9.0. */
    public static int compareVersions(String a, String b) {
        List<String> left = Arrays.asList(a.split("\\."));
        List<String> right = Arrays.asList(b.split("\\."));
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            int l = i < left.size() ? Integer.parseInt(left.get(i)) : 0;
            int r = i < right.size() ? Integer.parseInt(right.get(i)) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }
}
