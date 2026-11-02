package com.queuepilot.core.job;

import com.queuepilot.core.metrics.JobTiming;
import com.queuepilot.core.metrics.MetricsCalculator;
import com.queuepilot.core.metrics.ScheduleMetrics;
import com.queuepilot.core.metrics.Stats;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class JobLifecycleTest {

    @Test
    void allowsTheDocumentedHappyPathAndRetry() {
        assertThat(JobStateMachine.canTransition(JobState.QUEUED, JobState.RUNNING)).isTrue();
        assertThat(JobStateMachine.canTransition(JobState.RUNNING, JobState.SUCCEEDED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobState.RUNNING, JobState.QUEUED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobState.BLOCKED, JobState.SKIPPED)).isTrue();
    }

    @Test
    void terminalStatesAreFinalAndRunningJobsCannotBeCancelled() {
        for (JobState terminal : List.of(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED, JobState.SKIPPED)) {
            for (JobState target : JobState.values()) {
                assertThat(JobStateMachine.canTransition(terminal, target)).isFalse();
            }
        }
        assertThatThrownBy(() -> JobStateMachine.check(JobState.RUNNING, JobState.CANCELLED))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(JobState.RUNNING.isCancellable()).isFalse();
    }

    @Test
    void retryPolicyIsBoundedAndBacksOffExponentially() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertThat(policy.delayBefore(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayBefore(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayBefore(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delayBefore(4)).isEqualTo(Duration.ofSeconds(10));

        // maxRetries = 2 -> attempts 1, 2, 3 allowed
        assertThat(RetryPolicy.canRetry(1, 2)).isTrue();
        assertThat(RetryPolicy.canRetry(2, 2)).isTrue();
        assertThat(RetryPolicy.canRetry(3, 2)).isFalse();
    }

    @Test
    void metricsFollowTheTextbookDefinitions() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        // two jobs on one slot: A runs 0-4s, B (submitted at 0) waits 4s and runs 4-6s
        List<JobTiming> timings = List.of(
                new JobTiming(t, t, t.plusSeconds(4)),
                new JobTiming(t, t.plusSeconds(4), t.plusSeconds(6)));

        ScheduleMetrics metrics = MetricsCalculator.compute(timings, 1);

        assertThat(metrics.avgWaitMs()).isEqualTo(2000);
        assertThat(metrics.avgTurnaroundMs()).isEqualTo(5000);
        assertThat(metrics.maxWaitMs()).isEqualTo(4000);
        assertThat(metrics.makespanMs()).isEqualTo(6000);
        assertThat(metrics.throughputPerSec()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(metrics.utilization()).isEqualTo(1.0);
    }

    @Test
    void statsUseNearestRankPercentiles() {
        Stats stats = Stats.of(List.of(5, 1, 4, 2, 3, 6, 7, 8, 9, 10));
        assertThat(stats.median()).isEqualTo(5);
        assertThat(stats.p95()).isEqualTo(10);
        assertThat(stats.mean()).isEqualTo(5.5);
        assertThat(Stats.of(List.of()).count()).isZero();
    }
}
