package com.queuepilot.benchmarks.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes a report folder: config.json, environment.json, summary.json, raw.csv and a short
 * summary.md, so every published number can be traced back to raw measurements.
 */
public final class ReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ReportWriter() {
    }

    public static Path write(Report report, Path outRoot) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path dir = Files.createDirectories(outRoot.resolve(stamp + "-" + report.kind.toLowerCase()));
        MAPPER.writeValue(dir.resolve("config.json").toFile(), report.config);
        MAPPER.writeValue(dir.resolve("environment.json").toFile(), report.environment);
        MAPPER.writeValue(dir.resolve("summary.json").toFile(), report.summary);
        try (Writer csv = Files.newBufferedWriter(dir.resolve("raw.csv"), StandardCharsets.UTF_8)) {
            csv.write(String.join(",", report.rawHeader) + "\n");
            for (List<Object> row : report.raw) {
                csv.write(String.join(",", row.stream().map(ReportWriter::csvCell).toList()) + "\n");
            }
        }
        Files.writeString(dir.resolve("summary.md"), MAPPER.writeValueAsString(report.summary)
                .lines().map(line -> "    " + line).reduce("# " + report.name + "\n\n", (a, b) -> a + b + "\n"));
        return dir;
    }

    private static String csvCell(Object value) {
        String text = String.valueOf(value);
        return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /** Hardware and software facts recorded with every report. */
    public static void captureEnvironment(Map<String, Object> environment) {
        Runtime runtime = Runtime.getRuntime();
        environment.put("timestamp", Instant.now().toString());
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        environment.put("availableProcessors", runtime.availableProcessors());
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVendor", System.getProperty("java.vendor"));
        environment.put("benchmarkJvmMaxHeapMb", runtime.maxMemory() / (1024 * 1024));
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            environment.put("physicalMemoryMb", os.getTotalMemorySize() / (1024 * 1024));
        }
        environment.put("gitCommit", gitCommit());
    }

    private static String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 ? out : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    public static Map<String, Object> toPublishable(Report report) {
        return Map.of("kind", report.kind, "name", report.name, "config", report.config,
                "environment", report.environment, "summary", report.summary);
    }
}
