package com.queuepilot.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates the output of a job execution attempt.
 */
public class JobResult implements Serializable {
    private final boolean success;
    private final String message;
    private final Map<String, Object> outputData;
    private final long executionDurationMs;
    private final Instant finishedAt;
    private final String errorMessage;

    private JobResult(Builder builder) {
        this.success = builder.success;
        this.message = builder.message;
        this.outputData = builder.outputData != null ? Collections.unmodifiableMap(builder.outputData) : Collections.emptyMap();
        this.executionDurationMs = builder.executionDurationMs;
        this.finishedAt = builder.finishedAt != null ? builder.finishedAt : Instant.now();
        this.errorMessage = builder.errorMessage;
    }

    public static Builder success(String message, long executionDurationMs) {
        return new Builder().success(true).message(message).executionDurationMs(executionDurationMs);
    }

    public static Builder failure(String errorMessage, long executionDurationMs) {
        return new Builder().success(false).message("Execution failed").errorMessage(errorMessage).executionDurationMs(executionDurationMs);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getOutputData() {
        return outputData;
    }

    public long getExecutionDurationMs() {
        return executionDurationMs;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "JobResult{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", durationMs=" + executionDurationMs +
                ", error='" + errorMessage + '\'' +
                '}';
    }

    public static class Builder {
        private boolean success;
        private String message;
        private Map<String, Object> outputData;
        private long executionDurationMs;
        private Instant finishedAt;
        private String errorMessage;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder outputData(Map<String, Object> outputData) {
            this.outputData = outputData;
            return this;
        }

        public Builder executionDurationMs(long durationMs) {
            this.executionDurationMs = durationMs;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public JobResult build() {
            return new JobResult(this);
        }
    }
}
