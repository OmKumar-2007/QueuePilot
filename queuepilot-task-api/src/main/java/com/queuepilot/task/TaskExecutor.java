package com.queuepilot.task;

/**
 * The single contract every task implementation fulfils.
 *
 * <p>Implementations are discovered by workers through {@link java.util.ServiceLoader}
 * (a line in {@code META-INF/services/com.queuepilot.task.TaskExecutor}). One instance is
 * shared by all worker threads, so implementations must keep per-execution state in local
 * variables, never in fields.</p>
 */
public interface TaskExecutor {

    /** Task type identifier, e.g. {@code "sorting"}. Must match the registered manifest. */
    String taskType();

    /**
     * Performs the work. Called on a worker thread.
     *
     * @param input   input already validated by the coordinator against the manifest schema
     * @param context progress reporting, cancellation and file access for this attempt
     * @return the result; it becomes the job output
     * @throws TaskFailure when the task cannot complete; {@link TaskFailure#isRetryable()} decides
     *                     whether the coordinator may try again
     */
    TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure;
}
