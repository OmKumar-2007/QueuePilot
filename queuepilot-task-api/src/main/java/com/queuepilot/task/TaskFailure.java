package com.queuepilot.task;

/**
 * Signals that a task could not complete.
 *
 * <p>Retryable failures (e.g. a transient I/O error) let the coordinator schedule another
 * attempt; permanent failures (e.g. input that is valid by schema but unusable) fail the job
 * immediately.</p>
 */
public class TaskFailure extends Exception {

    public static final String CANCELLED = "CANCELLED";

    private final String code;
    private final boolean retryable;

    public TaskFailure(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public TaskFailure(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public static TaskFailure permanent(String code, String message) {
        return new TaskFailure(code, message, false);
    }

    public static TaskFailure retryable(String code, String message) {
        return new TaskFailure(code, message, true);
    }

    public static TaskFailure cancelled() {
        return new TaskFailure(CANCELLED, "Execution was cancelled", true);
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
