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
        @DefaultValue("1000") long claimPollMs,
        @DefaultValue("true") boolean recoveryEnabled,
        @DefaultValue("2000") long retryBaseDelayMs,
        @DefaultValue("60000") long retryMaxDelayMs,
        @DefaultValue("52428800") long maxArtifactBytes,
        @DefaultValue("65536") int maxInputBytes,
        @DefaultValue("262144") int maxOutputBytes,
        @DefaultValue("admin") String adminUser,
        String adminPassword,
        String workerSecret) {
}
