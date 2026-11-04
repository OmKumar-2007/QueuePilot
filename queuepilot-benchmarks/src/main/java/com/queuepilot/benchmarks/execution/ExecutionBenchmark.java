package com.queuepilot.benchmarks.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.benchmarks.report.Report;
import com.queuepilot.core.metrics.JobTiming;
import com.queuepilot.core.metrics.MetricsCalculator;
import com.queuepilot.core.metrics.ScheduleMetrics;
import com.queuepilot.core.metrics.Stats;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark B: real jobs on real worker processes. For every worker count and every task workload
 * it submits a batch of jobs, waits for all of them, and records the coordinator's timestamps.
 * Warm-up runs are executed but excluded from the summary.
 */
public final class ExecutionBenchmark {

    /** One workload: task type plus fixed input, e.g. monte-carlo-pi with 20M samples. */
    public record Workload(String taskType, Map<String, Object> input) {
        public String label() {
            return taskType + " " + input;
        }
    }

    public record Config(String coordinatorUrl, Path workerJar,
                         List<Integer> workerCounts, int slotsPerWorker, List<Workload> workloads, int jobsPerRun,
                         int warmupRuns, int repetitions, Duration runTimeout) {
    }

    private final Config config;
    private final ApiClient api;

    public ExecutionBenchmark(Config config) {
        this.config = config;
        this.api = new ApiClient(config.coordinatorUrl());
    }

    public Report run() throws IOException, InterruptedException {
        Report report = new Report("MEASURED", "Real worker execution");
        report.config.put("coordinator", config.coordinatorUrl());
        report.config.put("workerCounts", config.workerCounts());
        report.config.put("slotsPerWorker", config.slotsPerWorker());
        report.config.put("workloads", config.workloads().stream().map(Workload::label).toList());
        report.config.put("jobsPerRun", config.jobsPerRun());
        report.config.put("warmupRuns", config.warmupRuns());
        report.config.put("repetitions", config.repetitions());
        report.config.put("workersStartedByBenchmark", config.workerJar() != null);
        report.rawHeader = List.of("workers", "task", "run", "warmup", "jobId", "state", "attempts", "waitMs", "executionMs", "turnaroundMs");

        List<Map<String, Object>> groups = new ArrayList<>();
        for (int workerCount : config.workerCounts()) {
            try (WorkerProcesses workers = WorkerProcesses.start(config, workerCount, api)) {
                int slots = workers.totalSlots();
                for (Workload workload : config.workloads()) {
                    groups.add(measure(workload, workerCount, slots, report));
                }
            }
        }
        report.summary.put("label", "MEASURED: real task execution on local worker processes; results depend on this machine");
        report.summary.put("groups", groups);
        return report;
    }

    private Map<String, Object> measure(Workload workload, int workerCount, int slots, Report report)
            throws IOException, InterruptedException {
        List<Double> execMedians = new ArrayList<>();
        List<Double> throughputs = new ArrayList<>();
        List<Double> utilizations = new ArrayList<>();
        List<Double> turnarounds = new ArrayList<>();
        List<Double> cpuAverages = new ArrayList<>();
        List<Double> memoryAverages = new ArrayList<>();
        int succeeded = 0;
        int total = 0;
        int retries = 0;

        for (int run = 0; run < config.warmupRuns() + config.repetitions(); run++) {
            boolean warmup = run < config.warmupRuns();
            SystemSampler sampler = SystemSampler.start();
            List<JsonNode> jobs = runBatch(workload, "bench-" + workerCount + "w-" + run);
            SystemSampler.Sample usage = sampler.stop();

            List<JobTiming> timings = new ArrayList<>();
            List<Double> executions = new ArrayList<>();
            for (JsonNode job : jobs) {
                boolean ok = "SUCCEEDED".equals(job.get("state").asText());
                Instant submitted = Instant.parse(job.get("submittedAt").asText());
                Instant started = job.get("startedAt").isNull() ? null : Instant.parse(job.get("startedAt").asText());
                Instant finished = Instant.parse(job.get("finishedAt").asText());
                if (started != null) {
                    JobTiming timing = new JobTiming(submitted, started, finished);
                    timings.add(timing);
                    executions.add((double) timing.executionMs());
                    report.raw.add(List.of(workerCount, workload.taskType(), run, warmup, job.get("id").asText(),
                            job.get("state").asText(), job.get("attemptsUsed").asInt(), timing.waitMs(),
                            timing.executionMs(), timing.turnaroundMs()));
                }
                if (!warmup) {
                    total++;
                    succeeded += ok ? 1 : 0;
                    retries += Math.max(0, job.get("attemptsUsed").asInt() - 1);
                }
            }
            if (warmup || timings.isEmpty()) {
                continue;
            }
            ScheduleMetrics metrics = MetricsCalculator.compute(timings, slots);
            execMedians.add(Stats.of(executions).median());
            throughputs.add(metrics.throughputPerSec());
            utilizations.add(metrics.utilization());
            turnarounds.add(metrics.avgTurnaroundMs());
            cpuAverages.add(usage.cpuLoadAvg());
            memoryAverages.add(usage.memoryUsedMbAvg());
        }

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("workers", workerCount);
        group.put("totalSlots", slots);
        group.put("task", workload.taskType());
        group.put("input", workload.input());
        group.put("jobsPerRun", config.jobsPerRun());
        group.put("repetitions", config.repetitions());
        group.put("executionMedianMs", Stats.of(execMedians));
        group.put("avgTurnaroundMs", Stats.of(turnarounds));
        group.put("throughputPerSec", Stats.of(throughputs));
        group.put("utilization", Stats.of(utilizations));
        group.put("successRate", total == 0 ? 0 : succeeded / (double) total);
        group.put("retries", retries);
        group.put("systemCpuLoad", Stats.of(cpuAverages));
        group.put("systemMemoryUsedMb", Stats.of(memoryAverages));
        System.out.printf("  %d worker(s) x %d slot(s), %-40s median exec %.0f ms, throughput %.2f jobs/s%n",
                workerCount, config.slotsPerWorker(), workload.label(), Stats.of(execMedians).median(),
                Stats.of(throughputs).median());
        return group;
    }

    /** Submits one batch in its own queue and polls until every job is terminal. */
    private List<JsonNode> runBatch(Workload workload, String queue) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < config.jobsPerRun(); i++) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("taskType", workload.taskType());
            request.put("input", workload.input());
            request.put("queue", queue);
            ids.add(api.post("/api/jobs", request).get("id").asText());
        }
        Set<String> terminal = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED");
        long deadline = System.nanoTime() + config.runTimeout().toNanos();
        while (true) {
            List<JsonNode> jobs = new ArrayList<>();
            Set<String> unfinished = new HashSet<>();
            for (String id : ids) {
                JsonNode job = api.get("/api/jobs/" + id);
                jobs.add(job);
                if (!terminal.contains(job.get("state").asText())) {
                    unfinished.add(id);
                }
            }
            if (unfinished.isEmpty()) {
                return jobs;
            }
            if (System.nanoTime() > deadline) {
                throw new IOException(unfinished.size() + " jobs did not finish within " + config.runTimeout());
            }
            Thread.sleep(250);
        }
    }

    /** Samples machine-wide CPU load and memory use while a batch runs (workers are local processes). */
    static final class SystemSampler {
        record Sample(double cpuLoadAvg, double memoryUsedMbAvg) {
        }

        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        private final List<Double> cpu = new ArrayList<>();
        private final List<Double> memory = new ArrayList<>();

        static SystemSampler start() {
            SystemSampler sampler = new SystemSampler();
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                sampler.timer.scheduleAtFixedRate(() -> {
                    double load = os.getCpuLoad();
                    synchronized (sampler) {
                        if (load >= 0) {
                            sampler.cpu.add(load);
                        }
                        sampler.memory.add((os.getTotalMemorySize() - os.getFreeMemorySize()) / (1024.0 * 1024.0));
                    }
                }, 0, 250, TimeUnit.MILLISECONDS);
            }
            return sampler;
        }

        synchronized Sample stop() {
            timer.shutdownNow();
            return new Sample(Stats.of(cpu).mean(), Stats.of(memory).mean());
        }
    }
}
