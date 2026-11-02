package com.queuepilot.coordinator.security;

import com.queuepilot.coordinator.config.QueuePilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Supplies the admin password and worker enrollment secret.
 *
 * <p>Taken from configuration (environment variables {@code QP_ADMIN_PASSWORD} /
 * {@code QP_WORKER_SECRET}) when set. Otherwise a random value is generated once and stored in
 * the git-ignored data directory, so a local setup works without committing any secret and
 * without printing it to the log.</p>
 */
@Component
public class SecretsProvider {

    private static final Logger log = LoggerFactory.getLogger(SecretsProvider.class);

    private final String adminUser;
    private final String adminPassword;
    private final String workerSecret;

    public SecretsProvider(QueuePilotProperties properties) {
        this.adminUser = properties.adminUser();
        this.adminPassword = resolve(properties.adminPassword(), properties.dataDir().resolve("admin-password.txt"), "Admin password");
        this.workerSecret = resolve(properties.workerSecret(), properties.dataDir().resolve("worker-secret.txt"), "Worker enrollment secret");
    }

    public String adminUser() {
        return adminUser;
    }

    public String adminPassword() {
        return adminPassword;
    }

    public String workerSecret() {
        return workerSecret;
    }

    private static String resolve(String configured, Path file, String label) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        try {
            if (Files.exists(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            }
            Files.createDirectories(file.getParent());
            String generated = TokenHasher.newToken();
            Files.writeString(file, generated, StandardCharsets.UTF_8);
            log.warn("{} was not configured; generated one and stored it in {}", label, file.toAbsolutePath());
            return generated;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create " + file, e);
        }
    }
}
