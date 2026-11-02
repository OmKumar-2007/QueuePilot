package com.queuepilot.core.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static SchedulableJob job(String id, int priority, long estimateMs, int submittedSecond) {
        return new SchedulableJob(id, "default", priority, estimateMs, T0.plusSeconds(submittedSecond));
    }

    private static List<String> ids(List<SchedulableJob> jobs) {
        return jobs.stream().map(SchedulableJob::id).toList();
    }

    private final List<SchedulableJob> jobs = List.of(
            job("long-early", 2, 30_000, 0),
            job("short-late", 5, 1_000, 20),
            job("urgent-mid", 9, 10_000, 10));

    @Test
    void fcfsOrdersBySubmissionTime() {
        assertThat(ids(new FcfsPolicy().order(jobs, T0.plusSeconds(30))))
                .containsExactly("long-early", "urgent-mid", "short-late");
    }

    @Test
    void sjfOrdersByEstimate() {
        assertThat(ids(new SjfPolicy().order(jobs, T0.plusSeconds(30))))
                .containsExactly("short-late", "urgent-mid", "long-early");
    }

    @Test
    void sjfBreaksEqualEstimatesBySubmissionTime() {
        List<SchedulableJob> equal = List.of(job("b", 1, 500, 5), job("a", 1, 500, 1));
        assertThat(ids(new SjfPolicy().order(equal, T0))).containsExactly("a", "b");
    }

    @Test
    void priorityWithoutWaitingPrefersHighestPriority() {
        PriorityAgingPolicy policy = new PriorityAgingPolicy(Duration.ofSeconds(60), 1);
        assertThat(ids(policy.order(jobs, T0.plusSeconds(20))))
                .containsExactly("urgent-mid", "short-late", "long-early");
    }

    @Test
    void agingLetsALongWaitingLowPriorityJobOvertake() {
        PriorityAgingPolicy policy = new PriorityAgingPolicy(Duration.ofSeconds(5), 1);
        Instant now = T0.plusSeconds(60);
        // long-early waited 60s -> 2 + 12 = 14; urgent-mid waited 50s -> 9 + 10 = 19; short-late 40s -> 5 + 8 = 13
        assertThat(policy.effectivePriority(jobs.get(0), now)).isEqualTo(14);
        assertThat(ids(policy.order(jobs, now))).containsExactly("urgent-mid", "long-early", "short-late");

        // with faster aging the waiting time dominates: 2 + 60*5 = 302 beats 9 + 50*5 = 259
        PriorityAgingPolicy aggressive = new PriorityAgingPolicy(Duration.ofSeconds(1), 5);
        assertThat(ids(aggressive.order(jobs, now)).get(0)).isEqualTo("long-early");
    }

    @Test
    void policiesDoNotModifyTheirInput() {
        List<SchedulableJob> input = List.copyOf(jobs);
        new SjfPolicy().order(input, T0);
        assertThat(input).isEqualTo(jobs);
    }

    @Test
    void policyTypeCreatesMatchingPolicy() {
        for (PolicyType type : PolicyType.values()) {
            assertThat(type.create(Duration.ofSeconds(5), 1).type()).isEqualTo(type);
        }
    }
}
