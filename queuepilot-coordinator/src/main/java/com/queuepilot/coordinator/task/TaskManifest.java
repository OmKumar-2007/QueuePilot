package com.queuepilot.coordinator.task;

import com.queuepilot.core.schema.InputSchema;

import java.util.List;

/**
 * The JSON manifest describing a task type (docs/architecture/task-plugin-contract.md).
 * Used both for registration requests and for the seed files of built-in tasks.
 */
public record TaskManifest(String taskType,
                           String version,
                           String displayName,
                           String description,
                           ExecutorKind executorKind,
                           List<String> requiredCapabilities,
                           InputSchema inputSchema,
                           InputSchema outputSchema,
                           Resources resources,
                           Integer timeoutSeconds,
                           Integer maxRetries,
                           Integer defaultPriority,
                           Long defaultEstimatedMs,
                           Container container) {

    public record Resources(int cpuMillis, int memoryMb) {
    }

    /** Only for CONTAINER tasks. The image must be pinned by digest. */
    public record Container(String image, List<String> command) {
    }
}
