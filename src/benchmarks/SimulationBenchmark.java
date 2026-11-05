package benchmarks;

import benchmarks.WorkloadGenerator.Scenario;
import core.Scheduling.JobTiming;
import core.Scheduling.MetricsCalculator;
import core.Scheduling.PolicyType;
import core.Scheduling.SchedulableJob;
import core.Scheduling.ScheduleMetrics;
import core.Scheduling.SchedulingPolicy;
import core.Scheduling.Stats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;

/**
 * Benchmark A: compares FCFS, Priority with Aging and SJF on identical simulated workloads.
 * Each scenario is generated with several seeds; results are summarised across seeds.
 */
final class SimulationBenchmark {

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

/**
 * Discrete-event simulation of non-preemptive scheduling on N identical workers with a virtual
 * clock. Whenever a worker becomes free, the jobs that have arrived are ordered by the <b>same
 * {@link SchedulingPolicy} code the coordinator uses</b> and the first one starts.
 */
final class Simulator {

    private static final Instant EPOCH = Instant.EPOCH;

    private Simulator() {
    }

    public record Run(String policy, ScheduleMetrics metrics, List<Record> records) {
    }

    public record Record(String jobId, int priority, long durationMs, long arrivalMs, long startMs, long finishMs) {
    }

    public static Run run(List<SimJob> workload, SchedulingPolicy policy, int workers) {
        List<SimJob> pending = new ArrayList<>(workload);
        pending.sort(Comparator.comparingLong(SimJob::arrivalMs).thenComparing(SimJob::id));
        Map<String, SimJob> ready = new HashMap<>();
        PriorityQueue<Long> workerFreeAt = new PriorityQueue<>();
        for (int i = 0; i < workers; i++) {
            workerFreeAt.add(0L);
        }

        List<Record> records = new ArrayList<>();
        int next = 0;
        long now = 0;
        while (records.size() < workload.size()) {
            now = Math.max(now, workerFreeAt.poll());
            if (ready.isEmpty() && next < pending.size()) {
                now = Math.max(now, pending.get(next).arrivalMs());
            }
            while (next < pending.size() && pending.get(next).arrivalMs() <= now) {
                SimJob job = pending.get(next++);
                ready.put(job.id(), job);
            }
            List<SchedulableJob> candidates = ready.values().stream()
                    .map(job -> new SchedulableJob(job.id(), "default", job.priority(), job.durationMs(),
                            EPOCH.plusMillis(job.arrivalMs())))
                    .toList();
            SimJob chosen = ready.remove(policy.order(candidates, EPOCH.plusMillis(now)).get(0).id());
            long finish = now + chosen.durationMs();
            records.add(new Record(chosen.id(), chosen.priority(), chosen.durationMs(), chosen.arrivalMs(), now, finish));
            workerFreeAt.add(finish);
        }

        List<JobTiming> timings = records.stream()
                .map(r -> new JobTiming(EPOCH.plusMillis(r.arrivalMs()), EPOCH.plusMillis(r.startMs()), EPOCH.plusMillis(r.finishMs())))
                .toList();
        return new Run(policy.type().name(), MetricsCalculator.compute(timings, workers), records);
    }
}

/**
 * A synthetic job for the scheduling simulation. {@code durationMs} is a declared value: the
 * simulation assumes the job runs exactly that long, which is what makes it a simulation.
 */
record SimJob(String id, int priority, long durationMs, long arrivalMs) {
}

/**
 * Generates repeatable workloads: the same scenario, size and seed always give the same jobs, so
 * every policy is compared on exactly the same input.
 */
final class WorkloadGenerator {

    public enum Scenario {
        MIXED("Poisson arrivals; 75% short jobs (0.2-1.5 s), 25% long jobs (4-15 s); priorities 1-10"),
        LONG_FIRST("A burst of long jobs arrives just before many short ones; shows the convoy effect"),
        PRIORITY_SKEW("Overloaded system: a few early low-priority long jobs, a steady stream of high-priority short jobs; shows starvation");

        private final String description;

        Scenario(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private WorkloadGenerator() {
    }

    public static List<SimJob> generate(Scenario scenario, int count, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        List<SimJob> jobs = new ArrayList<>(count);
        long arrival = 0;
        for (int i = 0; i < count; i++) {
            String id = String.format("j%04d", i);
            switch (scenario) {
                case MIXED -> {
                    arrival += exponential(random, 600);
                    boolean longJob = random.nextDouble() < 0.25;
                    long duration = longJob ? random.nextLong(4_000, 15_001) : random.nextLong(200, 1_501);
                    jobs.add(new SimJob(id, random.nextInt(1, 11), duration, arrival));
                }
                case LONG_FIRST -> {
                    boolean longJob = i < count / 5;
                    arrival = longJob ? i * 10L : count / 5 * 10L + (i - count / 5) * 50L;
                    long duration = longJob ? random.nextLong(8_000, 12_001) : random.nextLong(300, 1_001);
                    jobs.add(new SimJob(id, 5, duration, arrival));
                }
                case PRIORITY_SKEW -> {
                    boolean lowPriority = i < Math.max(1, count / 10);
                    arrival = lowPriority ? i * 5L : (i - count / 10) * 200L;
                    long duration = lowPriority ? 5_000 : random.nextLong(400, 1_201);
                    jobs.add(new SimJob(id, lowPriority ? 1 : random.nextInt(7, 11), duration, arrival));
                }
            }
        }
        return jobs;
    }

    private static long exponential(SplittableRandom random, double meanMs) {
        return Math.round(-meanMs * Math.log(1 - random.nextDouble()));
    }
}
