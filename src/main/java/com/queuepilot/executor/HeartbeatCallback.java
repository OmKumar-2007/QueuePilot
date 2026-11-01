package com.queuepilot.executor;

@FunctionalInterface
public interface HeartbeatCallback {
    void sendHeartbeat();
}
