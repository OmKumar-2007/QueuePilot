package com.queuepilot.benchmarks.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates repeatable workloads: the same scenario, size and seed always give the same jobs, so
 * every policy is compared on exactly the same input.
 */
public final class WorkloadGenerator {

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
