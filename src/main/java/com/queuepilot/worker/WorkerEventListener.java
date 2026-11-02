package com.queuepilot.worker;

import com.queuepilot.model.JobResult;

public interface WorkerEventListener {

    void onProgress(String workerId, String jobId, String attemptToken, int progressPercent, String message);

    void onHeartbeat(String workerId, String jobId, String attemptToken);

    void onJobCompleted(String workerId, String jobId, String attemptToken, JobResult result);

    void onJobFailed(String workerId, String jobId, String attemptToken, String reason, Throwable cause);
}
