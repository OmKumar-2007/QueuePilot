package com.queuepilot.coordinator.job;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/jobs}. Only {@code taskType} is mandatory; the other settings fall back
 * to the task definition's defaults.
 *
 * @param priority  1 (lowest) .. 10 (highest)
 * @param dependsOn ids of existing jobs that must succeed first
 */
public record SubmitJobRequest(String taskType,
                               Map<String, Object> input,
                               Integer priority,
                               Long estimatedMs,
                               String queue,
                               List<String> dependsOn) {
}
