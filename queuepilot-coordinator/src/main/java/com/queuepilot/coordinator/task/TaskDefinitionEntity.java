package com.queuepilot.coordinator.task;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Metadata of one task type: what input it takes and its default limits. The code lives in a worker plugin. */
@Entity
@Table(name = "task_definition")
public class TaskDefinitionEntity {

    @Id
    private String id;
    private String taskType;
    private String displayName;
    private String description;
    private String inputSchema;
    private int timeoutSeconds;
    private int maxRetries;
    private int defaultPriority;
    private long defaultEstimatedMs;

    protected TaskDefinitionEntity() {
    }

    public TaskDefinitionEntity(String id, TaskManifest manifest, String inputSchemaJson) {
        this.id = id;
        this.taskType = manifest.taskType();
        this.displayName = manifest.displayName();
        this.description = manifest.description();
        this.inputSchema = inputSchemaJson;
        this.timeoutSeconds = manifest.timeoutSeconds();
        this.maxRetries = manifest.maxRetries();
        this.defaultPriority = manifest.defaultPriority();
        this.defaultEstimatedMs = manifest.defaultEstimatedMs();
    }

    /** Splits a comma-separated column into a set (empty for null or blank). */
    public static Set<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    public String getId() { return id; }
    public String getTaskType() { return taskType; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public int getDefaultPriority() { return defaultPriority; }
    public long getDefaultEstimatedMs() { return defaultEstimatedMs; }
}
