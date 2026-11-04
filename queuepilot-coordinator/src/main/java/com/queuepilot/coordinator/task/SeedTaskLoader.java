package com.queuepilot.coordinator.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Registers the manifests of the built-in tasks at startup (copied into {@code seed-tasks/} by the
 * Maven build). Task types that already exist are left untouched, so restarts are harmless.
 */
@Component
public class SeedTaskLoader implements ApplicationRunner {

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
