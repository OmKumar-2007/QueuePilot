package coordinator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.Jobs.RetryPolicy;
import core.Scheduling.QueueRotation;
import core.Validation.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class QueuePilotCoordinatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueuePilotCoordinatorApplication.class, args);
    }
}

/**
 * All QueuePilot settings ({@code queuepilot.*} in application.yml). Timing values are in
 * milliseconds; see docs/architecture/coordinator-worker-protocol.md for their meaning.
 */
@ConfigurationProperties(prefix = "queuepilot")
record QueuePilotProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("2000") long heartbeatIntervalMs,
        @DefaultValue("10000") long leaseDurationMs,
        @DefaultValue("10000") long workerTimeoutMs,
        @DefaultValue("true") boolean recoveryEnabled,
        @DefaultValue("target/worker/queuepilot-worker-1.0.0-exec.jar") Path workerJar,
        @DefaultValue("150") long launchedWorkerPaceMs) {
}

/** Beans for the framework-free core classes, plus an injectable clock so time can be controlled in tests. */
@Configuration
class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy() {
        // Retry n waits 2 s * 2^(n-1), at most 60 s.
        return new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(60));
    }

    @Bean
    public InputValidator inputValidator() {
        return new InputValidator();
    }

    @Bean
    public QueueRotation queueRotation() {
        return new QueueRotation();
    }
}

/** Converts between JSON text stored in the database and Java objects. */
@Component
class JsonCodec {

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value cannot be converted to JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return read(json, LinkedHashMap.class);
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Stored JSON is invalid", e);
        }
    }
}

/**
 * Registers the manifests of the built-in tasks at startup (copied into {@code seed-tasks/} by the
 * Maven build). Task types that already exist are left untouched, so restarts are harmless.
 */
@Component
class SeedTaskLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedTaskLoader.class);

    private final TaskRegistryService registry;
    private final ObjectMapper objectMapper;

    public SeedTaskLoader(TaskRegistryService registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:seed-tasks/*.json");
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                TaskManifest manifest = objectMapper.readValue(in, TaskManifest.class);
                if (registry.find(manifest.taskType()).isEmpty()) {
                    registry.register(manifest);
                }
            } catch (RuntimeException e) {
                log.error("Could not seed task manifest {}: {}", resource.getFilename(), e.getMessage());
            }
        }
    }
}
