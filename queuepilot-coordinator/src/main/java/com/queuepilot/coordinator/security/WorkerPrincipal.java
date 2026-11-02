package com.queuepilot.coordinator.security;

/** The authenticated identity of a worker process on /api/worker/** requests. */
public record WorkerPrincipal(String workerId) {
}
