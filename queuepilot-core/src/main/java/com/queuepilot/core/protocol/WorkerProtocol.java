package com.queuepilot.core.protocol;

import java.util.List;
import java.util.Map;

/**
 * JSON messages exchanged between workers and the coordinator
 * (see docs/architecture/coordinator-worker-protocol.md). Grouped in one file because they only
 * make sense together. Times are plain milliseconds so no date handling is needed on the wire.
 */
public final class WorkerProtocol {

    private WorkerProtocol() {
    }

    /** Commands a coordinator can piggy-back on a heartbeat response. */
    public static final String COMMAND_KILL = "KILL";
    public static final String COMMAND_FREEZE = "FREEZE";
    public static final String COMMAND_REREGISTER = "REREGISTER";
    /** Stop claiming and hold running tasks at their next progress checkpoint (heartbeats continue). */
    public static final String COMMAND_PAUSE = "PAUSE";
    public static final String COMMAND_RESUME = "RESUME";
    /** Stop all running attempts now; they are reported as failed (retryable) and requeued. */
    public static final String COMMAND_INTERRUPT = "INTERRUPT";

    /** {@code supportedTasks} are the task types this worker has a plugin for. */
    public record RegisterRequest(String name,
                                  int concurrencyLimit,
                                  List<String> supportedTasks,
                                  long demoPaceMs) {
    }

    /** After registering, the worker identifies itself with {@code workerId} in the X-Worker-Id header. */
    public record RegisterResponse(String workerId, long heartbeatIntervalMs) {
    }

    public record Assignment(String attemptId,
                             int attemptNumber,
                             String jobId,
                             String taskType,
                             Map<String, Object> input,
                             int timeoutSeconds) {
    }

    public record HeartbeatRequest(List<String> activeAttemptIds) {
    }

    /**
     * {@code revoked} attempts must be stopped by the worker. {@code command} may be null or hold
     * several commands separated by commas, in the order they were issued.
     */
    public record HeartbeatResponse(List<String> renewed, List<String> revoked, String command) {
    }

    public record ProgressReport(int percent, String message) {
    }

    public record CompletionReport(Map<String, Object> output, List<String> outputArtifactIds) {
    }

    public record FailureReport(String errorCode, String message, boolean retryable) {
    }
}
