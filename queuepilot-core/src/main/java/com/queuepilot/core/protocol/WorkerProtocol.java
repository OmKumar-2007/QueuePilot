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

    public static final String KIND_JAVA_PLUGIN = "JAVA_PLUGIN";
    public static final String KIND_CONTAINER = "CONTAINER";

    /** Commands a coordinator can piggy-back on a heartbeat response. */
    public static final String COMMAND_KILL = "KILL";
    public static final String COMMAND_FREEZE = "FREEZE";
    public static final String COMMAND_REREGISTER = "REREGISTER";
    /** Stop claiming and hold running tasks at their next progress checkpoint (heartbeats continue). */
    public static final String COMMAND_PAUSE = "PAUSE";
    public static final String COMMAND_RESUME = "RESUME";
    /** Stop all running attempts now; they are reported as failed (retryable) and requeued. */
    public static final String COMMAND_INTERRUPT = "INTERRUPT";

    public record RegisterRequest(String name,
                                  int concurrencyLimit,
                                  List<String> capabilities,
                                  List<String> supportedTasks,
                                  String enrollSecret,
                                  long demoPaceMs) {
    }

    public record RegisterResponse(String workerId,
                                   String workerToken,
                                   long heartbeatIntervalMs,
                                   long leaseDurationMs,
                                   long claimPollMs) {
    }

    public record ClaimRequest(int freeSlots) {
    }

    public record ContainerSpec(String image, List<String> command, int cpuMillis, int memoryMb) {
    }

    public record Assignment(String attemptId,
                             String fencingToken,
                             int attemptNumber,
                             String jobId,
                             String taskType,
                             String taskVersion,
                             String executorKind,
                             Map<String, Object> input,
                             List<String> inputArtifactIds,
                             long leaseDurationMs,
                             int timeoutSeconds,
                             ContainerSpec container) {

        public String taskIdentifier() {
            return taskType + "@" + taskVersion;
        }
    }

    public record AttemptRef(String attemptId, String fencingToken) {
    }

    public record HeartbeatRequest(List<AttemptRef> activeAttempts) {
    }

    /**
     * {@code revoked} attempts must be stopped by the worker. {@code command} may be null or hold
     * several commands separated by commas, in the order they were issued.
     */
    public record HeartbeatResponse(List<String> renewed, List<String> revoked, String command) {
    }

    public record ProgressReport(String fencingToken, int percent, String message) {
    }

    public record CompletionReport(String fencingToken, Map<String, Object> output, List<String> outputArtifactIds) {
    }

    public record FailureReport(String fencingToken, String errorCode, String message, boolean retryable) {
    }
}
