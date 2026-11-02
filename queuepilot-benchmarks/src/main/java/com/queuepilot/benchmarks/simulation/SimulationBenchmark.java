package com.queuepilot.benchmarks.simulation;

import com.queuepilot.benchmarks.report.Report;
import com.queuepilot.benchmarks.simulation.WorkloadGenerator.Scenario;
import com.queuepilot.core.metrics.ScheduleMetrics;
import com.queuepilot.core.metrics.Stats;
import com.queuepilot.core.scheduling.PolicyType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Benchmark A: compares FCFS, Priority with Aging and SJF on identical simulated workloads.
 * Each scenario is generated with several seeds; results are summarised across seeds.
 */
public final class SimulationBenchmark {

    public record Config(List<Scenario> scenarios, int jobs, int workers, long firstSeed, int seeds,
                         Duration agingInterval, int agingStep) {
    }

    private SimulationBenchmark() {
    }

    public static Report run(Config config) {
        Report report = new Report("SIMULATED", "Scheduling policy comparison (simulation)");
        report.config.put("jobsPerWorkload", config.jobs());
        report.config.put("workers", config.workers());
        report.config.put("seeds", config.seeds());
        report.config.put("firstSeed", config.firstSeed());
        report.config.put("agingIntervalMs", config.agingInterval().toMillis());
        report.config.put("agingStep", config.agingStep());
        report.rawHeader = List.of("scenario", "seed", "policy", "jobId", "priority", "durationMs", "arrivalMs",
                "startMs", "finishMs", "waitMs");

        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Scenario scenario : config.scenarios()) {
            Map<PolicyType, List<ScheduleMetrics>> perPolicy = new LinkedHashMap<>();
            for (int s = 0; s < config.seeds(); s++) {
                long seed = config.firstSeed() + s;
                List<SimJob> workload = WorkloadGenerator.generate(scenario, config.jobs(), seed);
                for (PolicyType type : PolicyType.values()) {
                    Simulator.Run run = Simulator.run(workload, type.create(config.agingInterval(), config.agingStep()), config.workers());
                    perPolicy.computeIfAbsent(type, t -> new ArrayList<>()).add(run.metrics());
                    for (Simulator.Record r : run.records()) {
                        report.raw.add(List.of(scenario.name(), seed, type.name(), r.jobId(), r.priority(), r.durationMs(),
                                r.arrivalMs(), r.startMs(), r.finishMs(), r.startMs() - r.arrivalMs()));
                    }
                }
            }
            List<Map<String, Object>> results = new ArrayList<>();
            perPolicy.forEach((type, runs) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("policy", type.name());
                row.put("displayName", type.displayName());
                row.put("avgWaitMs", mean(runs, ScheduleMetrics::avgWaitMs));
                row.put("avgTurnaroundMs", mean(runs, ScheduleMetrics::avgTurnaroundMs));
                row.put("p95WaitMs", mean(runs, ScheduleMetrics::p95WaitMs));
                row.put("maxWaitMs", mean(runs, ScheduleMetrics::maxWaitMs));
                row.put("makespanMs", mean(runs, ScheduleMetrics::makespanMs));
                row.put("throughputPerSec", mean(runs, ScheduleMetrics::throughputPerSec));
                row.put("utilization", mean(runs, ScheduleMetrics::utilization));
                row.put("avgWaitMsStats", Stats.of(runs.stream().map(ScheduleMetrics::avgWaitMs).toList()));
                results.add(row);
            });
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scenario", scenario.name());
            entry.put("description", scenario.description());
            entry.put("results", results);
            scenarios.add(entry);
        }
        report.summary.put("label", "SIMULATED: durations are declared inputs on a virtual clock, not measured execution");
        report.summary.put("scenarios", scenarios);
        return report;
    }

    private static double mean(List<ScheduleMetrics> runs, ToDoubleFunction<ScheduleMetrics> metric) {
        return Math.round(runs.stream().mapToDouble(metric).average().orElse(0) * 1000) / 1000.0;
    }
}
