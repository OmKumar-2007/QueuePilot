package com.queuepilot.coordinator.worker;

import com.queuepilot.coordinator.config.QueuePilotProperties;
import com.queuepilot.coordinator.error.Errors.ConflictException;
import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts additional worker <b>processes</b> on this machine for the dashboard's "Add worker" button.
 *
 * <p>It only ever runs the QueuePilot worker jar from configuration, never anything a client sends.
 * Launched workers are ordinary independent processes (they register like any other) and use demo
 * pacing so their jobs are slow enough to watch. They are stopped when the coordinator shuts down.</p>
 */
@Component
public class WorkerLauncher {

    private static final Logger log = LoggerFactory.getLogger(WorkerLauncher.class);
    private static final Pattern NUMBERED = Pattern.compile("worker-(\\d+)");

    private final QueuePilotProperties properties;
    private final WorkerRepository workers;
    private final EventLog events;
    private final Environment environment;
    private final List<Process> launched = new ArrayList<>();

    public WorkerLauncher(QueuePilotProperties properties, WorkerRepository workers,
                          EventLog events, Environment environment) {
        this.properties = properties;
        this.workers = workers;
        this.events = events;
        this.environment = environment;
    }

    public record Launched(String name, long pid, int slots, long paceMsPerPercent) {
    }

    @Transactional
    public synchronized Launched launch(int slots) {
        if (slots < 1 || slots > 8) {
            throw new ValidationException("slots", "must be 1..8");
        }
        Path jar = properties.workerJar().toAbsolutePath();
        if (!Files.isRegularFile(jar)) {
            throw new ConflictException("Worker jar not found at " + jar + "; build it with mvnw install");
        }
        String name = "worker-" + nextNumber();
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String java = ProcessHandle.current().info().command().orElse("java");
        Path logs = properties.dataDir().resolve("logs");
        try {
            Files.createDirectories(logs);
            ProcessBuilder builder = new ProcessBuilder(java, "-jar", jar.toString(),
                    "--coordinator", "http://127.0.0.1:" + port, "--name", name, "--slots", String.valueOf(slots),
                    "--pace-ms", String.valueOf(properties.launchedWorkerPaceMs()),
                    "--work-dir", properties.dataDir().resolve("worker-work").resolve(name).toString())
                    .redirectErrorStream(true)
                    .redirectOutput(logs.resolve(name + ".log").toFile());
            Process process = builder.start();
            launched.add(process);
            events.record(EventType.WORKER_STATUS_CHANGED, null, null, null,
                    "Started worker process " + name + " (pid " + process.pid() + ", " + slots + " slots)");
            log.info("Launched {} as pid {}", name, process.pid());
            return new Launched(name, process.pid(), slots, properties.launchedWorkerPaceMs());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start a worker process", e);
        }
    }

    /** One more than the highest "worker-N" name seen so far, so names stay readable. */
    private int nextNumber() {
        int max = 0;
        for (WorkerEntity worker : workers.findAll()) {
            Matcher matcher = NUMBERED.matcher(worker.getName());
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max + 1;
    }

    @PreDestroy
    synchronized void stopLaunched() {
        launched.forEach(Process::destroy);
    }
}
