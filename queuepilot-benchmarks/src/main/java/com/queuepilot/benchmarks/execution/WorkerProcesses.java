package com.queuepilot.benchmarks.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Starts and stops the worker processes for one benchmark configuration. Without a worker jar the
 * benchmark uses whatever workers are already online and reports that in its config.
 */
final class WorkerProcesses implements AutoCloseable {

    private final List<Process> processes = new ArrayList<>();
    private final int totalSlots;

    private WorkerProcesses(int totalSlots) {
        this.totalSlots = totalSlots;
    }

    static WorkerProcesses start(ExecutionBenchmark.Config config, int count, ApiClient api)
            throws IOException, InterruptedException {
        if (config.workerJar() == null) {
            return new WorkerProcesses(onlineSlots(api, null));
        }
        String runId = "bench-" + System.currentTimeMillis() + "-";
        WorkerProcesses started = new WorkerProcesses(count * config.slotsPerWorker());
        Path logDir = Path.of("benchmark-results", "worker-logs");
        logDir.toFile().mkdirs();
        for (int i = 0; i < count; i++) {
            String name = runId + i;
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-jar", config.workerJar().toString(),
                    "--coordinator", config.coordinatorUrl(), "--name", name,
                    "--slots", String.valueOf(config.slotsPerWorker()),
                    "--work-dir", Path.of("benchmark-results", "work", name).toString())
                    .redirectErrorStream(true)
                    .redirectOutput(logDir.resolve(name + ".log").toFile());
            started.processes.add(builder.start());
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (onlineSlots(api, runId) < count * config.slotsPerWorker()) {
            if (System.currentTimeMillis() > deadline) {
                started.close();
                throw new IOException("Workers did not come online within 60 s (see benchmark-results/worker-logs)");
            }
            Thread.sleep(500);
        }
        return started;
    }

    /** Slots of ONLINE workers, optionally only those whose name starts with {@code prefix}. */
    private static int onlineSlots(ApiClient api, String prefix) throws IOException, InterruptedException {
        int slots = 0;
        Set<String> seen = new HashSet<>();
        for (JsonNode worker : api.get("/api/workers")) {
            boolean matches = prefix == null || worker.get("name").asText().startsWith(prefix);
            if (matches && "ONLINE".equals(worker.get("status").asText()) && seen.add(worker.get("id").asText())) {
                slots += worker.get("concurrencyLimit").asInt();
            }
        }
        return slots;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    int totalSlots() {
        return Math.max(1, totalSlots);
    }

    @Override
    public void close() {
        for (Process process : processes) {
            process.destroy();
        }
        for (Process process : processes) {
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
