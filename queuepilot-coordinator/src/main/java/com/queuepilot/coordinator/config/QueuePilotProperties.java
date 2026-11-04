package com.queuepilot.coordinator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * All QueuePilot settings ({@code queuepilot.*} in application.yml). Timing values are in
 * milliseconds; see docs/architecture/coordinator-worker-protocol.md for their meaning.
 */
@ConfigurationProperties(prefix = "queuepilot")
public record QueuePilotProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("2000") long heartbeatIntervalMs,
        @DefaultValue("10000") long leaseDurationMs,
        @DefaultValue("10000") long workerTimeoutMs,
        @DefaultValue("true") boolean recoveryEnabled,
        @DefaultValue("queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar") Path workerJar,
        @DefaultValue("150") long launchedWorkerPaceMs) {
}
