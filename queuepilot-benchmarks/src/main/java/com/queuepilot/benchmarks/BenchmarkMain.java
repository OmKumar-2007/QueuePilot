package com.queuepilot.benchmarks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.benchmarks.execution.ApiClient;
import com.queuepilot.benchmarks.execution.ExecutionBenchmark;
import com.queuepilot.benchmarks.report.Report;
import com.queuepilot.benchmarks.report.ReportWriter;
import com.queuepilot.benchmarks.simulation.SimulationBenchmark;
import com.queuepilot.benchmarks.simulation.WorkloadGenerator.Scenario;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone benchmark runner, independent of the coordinator's code (it only uses queuepilot-core
 * and the public HTTP API).
 */
public final class BenchmarkMain {

    private static final String USAGE = """
            Usage:
              java -jar queuepilot-benchmarks.jar simulate [--jobs 200] [--workers 4] [--seed 42] [--seeds 5]
                   [--scenarios MIXED,LONG_FIRST,PRIORITY_SKEW] [--out benchmark-results] [--publish ...]
              java -jar queuepilot-benchmarks.jar execute [--coordinator http://localhost:8080]
                   [--worker-jar queuepilot-worker/target/queuepilot-worker-1.0.0-exec.jar] [--worker-counts 1,2,4]
                   [--slots 1] [--workloads "monte-carlo-pi:samples=20000000;sorting:count=2000000"]
                   [--jobs 8] [--warmup 1] [--reps 3] [--out benchmark-results] [--publish]
            Publishing (--publish) imports the report into the coordinator dashboard (--coordinator URL).
            """;

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !(args[0].equals("simulate") || args[0].equals("execute"))) {
            System.out.println(USAGE);
            return;
        }
        Map<String, String> options = parse(Arrays.copyOfRange(args, 1, args.length));
        String coordinator = options.getOrDefault("coordinator", "http://localhost:8080");

        Report report = args[0].equals("simulate") ? simulate(options) : execute(options, coordinator);
        ReportWriter.captureEnvironment(report.environment);
        Path dir = ReportWriter.write(report, Path.of(options.getOrDefault("out", "benchmark-results")));
        System.out.println("Report written to " + dir.toAbsolutePath());

        if (options.containsKey("publish")) {
            new ApiClient(coordinator).post("/api/admin/benchmarks", ReportWriter.toPublishable(report));
            System.out.println("Report published to " + coordinator);
        }
    }

    private static Report simulate(Map<String, String> options) {
        List<Scenario> scenarios = Arrays.stream(options.getOrDefault("scenarios", "MIXED,LONG_FIRST,PRIORITY_SKEW").split(","))
                .map(String::trim).map(Scenario::valueOf).toList();
        SimulationBenchmark.Config config = new SimulationBenchmark.Config(scenarios,
                Integer.parseInt(options.getOrDefault("jobs", "200")),
                Integer.parseInt(options.getOrDefault("workers", "4")),
                Long.parseLong(options.getOrDefault("seed", "42")),
                Integer.parseInt(options.getOrDefault("seeds", "5")),
                Duration.ofMillis(Long.parseLong(options.getOrDefault("aging-interval-ms", "1000"))),
                Integer.parseInt(options.getOrDefault("aging-step", "1")));
        Report report = SimulationBenchmark.run(config);
        printSimulation(report);
        return report;
    }

    @SuppressWarnings("unchecked")
    private static void printSimulation(Report report) {
        for (Map<String, Object> scenario : (List<Map<String, Object>>) report.summary.get("scenarios")) {
            System.out.println("\n" + scenario.get("scenario") + " - " + scenario.get("description"));
            System.out.printf("  %-16s %12s %14s %12s %12s %10s%n", "policy", "avg wait ms", "avg turnaround", "p95 wait", "max wait", "jobs/s");
            for (Map<String, Object> row : (List<Map<String, Object>>) scenario.get("results")) {
                System.out.printf("  %-16s %12.0f %14.0f %12.0f %12.0f %10.3f%n", row.get("policy"), row.get("avgWaitMs"),
                        row.get("avgTurnaroundMs"), row.get("p95WaitMs"), row.get("maxWaitMs"), row.get("throughputPerSec"));
            }
        }
    }

    private static Report execute(Map<String, String> options, String coordinator)
            throws Exception {
        List<ExecutionBenchmark.Workload> workloads = new ArrayList<>();
        for (String spec : options.getOrDefault("workloads", "monte-carlo-pi:samples=20000000;sorting:count=2000000").split(";")) {
            String[] parts = spec.trim().split(":", 2);
            Map<String, Object> input = new LinkedHashMap<>();
            if (parts.length > 1) {
                for (String pair : parts[1].split(",")) {
                    String[] kv = pair.split("=", 2);
                    input.put(kv[0].trim(), parseValue(kv[1].trim()));
                }
            }
            workloads.add(new ExecutionBenchmark.Workload(parts[0].trim(), input));
        }
        String workerJar = options.get("worker-jar");
        ExecutionBenchmark.Config config = new ExecutionBenchmark.Config(coordinator,
                workerJar == null ? null : Path.of(workerJar),
                Arrays.stream(options.getOrDefault("worker-counts", "1,2").split(",")).map(String::trim).map(Integer::parseInt).toList(),
                Integer.parseInt(options.getOrDefault("slots", "1")),
                workloads,
                Integer.parseInt(options.getOrDefault("jobs", "8")),
                Integer.parseInt(options.getOrDefault("warmup", "1")),
                Integer.parseInt(options.getOrDefault("reps", "3")),
                Duration.ofMinutes(Long.parseLong(options.getOrDefault("run-timeout-min", "10"))));
        System.out.println("Running execution benchmark against " + coordinator + " ...");
        return new ExecutionBenchmark(config).run();
    }

    private static Object parseValue(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument " + args[i] + "\n" + USAGE);
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    /** Used by tests and docs: the JSON a report would publish. */
    static String toJson(Report report) throws Exception {
        return new ObjectMapper().writeValueAsString(ReportWriter.toPublishable(report));
    }
}
