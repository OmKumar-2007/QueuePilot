package com.queuepilot.benchmarks;

import com.queuepilot.benchmarks.report.Report;
import com.queuepilot.benchmarks.simulation.SimJob;
import com.queuepilot.benchmarks.simulation.SimulationBenchmark;
import com.queuepilot.benchmarks.simulation.Simulator;
import com.queuepilot.benchmarks.simulation.WorkloadGenerator;
import com.queuepilot.benchmarks.simulation.WorkloadGenerator.Scenario;
import com.queuepilot.core.scheduling.FcfsPolicy;
import com.queuepilot.core.scheduling.PriorityAgingPolicy;
import com.queuepilot.core.scheduling.SjfPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationTest {

    @Test
    void sameSeedGivesTheSameWorkloadAndTheSameResult() {
        List<SimJob> first = WorkloadGenerator.generate(Scenario.MIXED, 100, 7);
        List<SimJob> second = WorkloadGenerator.generate(Scenario.MIXED, 100, 7);
        assertThat(first).isEqualTo(second);
        assertThat(Simulator.run(first, new SjfPolicy(), 3).metrics())
                .isEqualTo(Simulator.run(second, new SjfPolicy(), 3).metrics());
        assertThat(WorkloadGenerator.generate(Scenario.MIXED, 100, 8)).isNotEqualTo(first);
    }

    @Test
    void handWorkedExampleMatchesTextbookFcfsAndSjf() {
        // One worker. Arrivals at t=0: A(8s), B(4s), C(1s).
        List<SimJob> jobs = List.of(new SimJob("A", 5, 8000, 0), new SimJob("B", 5, 4000, 0), new SimJob("C", 5, 1000, 0));

        // FCFS: A 0-8, B 8-12, C 12-13 -> waits 0, 8, 12 -> avg 6.667 s
        assertThat(Simulator.run(jobs, new FcfsPolicy(), 1).metrics().avgWaitMs()).isCloseTo(6666.67, org.assertj.core.data.Offset.offset(0.01));
        // SJF: C 0-1, B 1-5, A 5-13 -> waits 0, 1, 5 -> avg 2 s
        assertThat(Simulator.run(jobs, new SjfPolicy(), 1).metrics().avgWaitMs()).isEqualTo(2000);
    }

    @Test
    void everyJobRunsExactlyOnceAndNeverBeforeItArrives() {
        List<SimJob> jobs = WorkloadGenerator.generate(Scenario.PRIORITY_SKEW, 120, 3);
        Simulator.Run run = Simulator.run(jobs, new PriorityAgingPolicy(Duration.ofSeconds(1), 1), 2);
        assertThat(run.records()).hasSize(120);
        assertThat(run.records().stream().map(Simulator.Record::jobId).distinct()).hasSize(120);
        assertThat(run.records()).allSatisfy(r -> assertThat(r.startMs()).isGreaterThanOrEqualTo(r.arrivalMs()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void benchmarkShowsTheExpectedTradeOffs() {
        Report report = SimulationBenchmark.run(new SimulationBenchmark.Config(
                List.of(Scenario.LONG_FIRST, Scenario.PRIORITY_SKEW), 100, 2, 42, 3, Duration.ofSeconds(1), 1));

        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) report.summary.get("scenarios");
        Map<String, Map<String, Object>> longFirst = byPolicy(scenarios.get(0));
        // SJF minimises average waiting time when long jobs arrive first (convoy effect under FCFS)
        assertThat((double) longFirst.get("SJF").get("avgWaitMs")).isLessThan((double) longFirst.get("FCFS").get("avgWaitMs"));
        // ... but under overload SJF makes long jobs wait longest: the starvation indicator
        Map<String, Map<String, Object>> skew = byPolicy(scenarios.get(1));
        assertThat((double) skew.get("SJF").get("maxWaitMs")).isGreaterThan((double) skew.get("FCFS").get("maxWaitMs"));
        assertThat(report.raw).hasSize(2 * 3 * 3 * 100);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byPolicy(Map<String, Object> scenario) {
        Map<String, Map<String, Object>> rows = new java.util.HashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) scenario.get("results")) {
            rows.put((String) row.get("policy"), row);
        }
        return rows;
    }
}
