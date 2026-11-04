package com.queuepilot.coordinator.task;

import com.queuepilot.core.schema.InputSchema;

/**
 * The JSON manifest describing a task type (docs/architecture/task-plugin-contract.md).
 * Used both for registration requests and for the seed files of built-in tasks.
 */
public record TaskManifest(String taskType,
                           String displayName,
                           String description,
                           InputSchema inputSchema,
                           Integer timeoutSeconds,
                           Integer maxRetries,
                           Integer defaultPriority,
                           Long defaultEstimatedMs) {
}
