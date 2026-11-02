package com.queuepilot.executor;

@FunctionalInterface
public interface ProgressCallback {
    void onProgress(int progressPercent, String stepDescription);
}
