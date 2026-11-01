package com.queuepilot.executor;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.JobType;

/**
 * Common interface for built-in task execution.
 * Demonstrates abstraction, polymorphic dispatch, and uniform error reporting.
 */
public interface JobExecutor {

    /**
     * Identifies which JobType this executor handles.
     */
    JobType getSupportedType();

    /**
     * Executes the given job synchronously in chunks.
     * Emits periodic progress updates and heartbeats.
     * Respects thread interruption for failure simulation.
     *
     * @param job The job instance to execute
     * @param progressCallback Callback to record progress % and description
     * @param heartbeatCallback Callback to emit worker heartbeats
     * @return JobResult detailing success or failure
     * @throws InterruptedException if worker was killed or interrupted
     * @throws Exception if an unhandled computational error occurs
     */
    JobResult execute(Job job, ProgressCallback progressCallback, HeartbeatCallback heartbeatCallback) throws Exception;
}
