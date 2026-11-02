package com.queuepilot.core.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueueRotationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static SchedulableJob job(String id, String queue, int second) {
        return new SchedulableJob(id, queue, 5, 1000, T0.plusSeconds(second));
    }

    private final List<SchedulableJob> jobs = List.of(
            job("a1", "alpha", 1), job("a2", "alpha", 2), job("a3", "alpha", 3),
            job("b1", "beta", 4),
            job("g1", "gamma", 5));

    @Test
    void startsWithFirstQueueAndKeepsPolicyOrderInsideQueues() {
        QueueRotation rotation = new QueueRotation();
        List<String> ids = rotation.arrange(jobs, new FcfsPolicy(), T0).stream().map(SchedulableJob::id).toList();
        assertThat(ids).containsExactly("a1", "a2", "a3", "b1", "g1");
    }

    @Test
    void servesQueuesRoundRobinSoOneBusyQueueCannotMonopolise() {
        QueueRotation rotation = new QueueRotation();
        List<SchedulableJob> remaining = new java.util.ArrayList<>(jobs);
        List<String> dispatched = new java.util.ArrayList<>();

        while (!remaining.isEmpty()) {
            SchedulableJob next = rotation.arrange(remaining, new FcfsPolicy(), T0).get(0);
            dispatched.add(next.id());
            rotation.markServed(next.queue());
            remaining.remove(next);
        }

        // FCFS alone would give a1 a2 a3 b1 g1; with rotation the queues take turns.
        assertThat(dispatched).containsExactly("a1", "b1", "g1", "a2", "a3");
    }

    @Test
    void wrapsAroundWhenLastServedQueueIsTheLastName() {
        QueueRotation rotation = new QueueRotation();
        rotation.markServed("zeta");
        assertThat(rotation.arrange(jobs, new FcfsPolicy(), T0).get(0).queue()).isEqualTo("alpha");
    }
}
