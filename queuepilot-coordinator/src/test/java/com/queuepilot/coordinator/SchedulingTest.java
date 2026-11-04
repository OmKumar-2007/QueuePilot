package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** F4-F7, F14: readiness, capability matching, policies, fairness and race-free claiming. */
class SchedulingTest extends CoordinatorTestBase {

    private Map<String, Object> piJob(long estimateMs, int priority, String queue) {
        Map<String, Object> job = piJob(10_000);
        job.put("estimatedMs", estimateMs);
        job.put("priority", priority);
        job.put("queue", queue);
        return job;
    }

    private List<String> claimAll(Worker worker, int count) throws Exception {
        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonNode assignment = claim(worker);
            jobIds.add(assignment.get("jobId").asText());
            complete(worker, assignment, Map.of());
        }
        return jobIds;
    }

    @Test
    void jobIsOnlyAssignedToWorkersWithThePlugin() throws Exception {
        String id = submitId(piJob(10_000));
        Worker noImplementation = registerWorker("sorter", 2, List.of("sorting"));
        Worker able = registerWorker("pi", 2, List.of("monte-carlo-pi"));

        assertThat(claim(noImplementation)).isNull();
        JsonNode assignment = claim(able);
        assertThat(assignment.get("jobId").asText()).isEqualTo(id);
        assertThat(assignment.get("input").get("samples").asLong()).isEqualTo(10_000);
        assertThat(state(id)).isEqualTo("RUNNING");
    }

    @Test
    void workerNeverExceedsItsConcurrencyLimit() throws Exception {
        submitId(piJob(10_000));
        submitId(piJob(10_000));
        Worker single = registerWorker("single", 1, ALL_BUILTINS);
        assertThat(claim(single)).isNotNull();
        assertThat(claim(single)).isNull();
    }

    @Test
    void fcfsSjfAndPriorityProduceTheirOrders() throws Exception {
        String slowEarly = submitId(piJob(9_000, 2, "default"));
        advance(Duration.ofSeconds(1));
        String urgent = submitId(piJob(5_000, 9, "default"));
        advance(Duration.ofSeconds(1));
        String quick = submitId(piJob(1_000, 5, "default"));
        Worker worker = generalWorker("w");

        setScheduler("SJF");
        assertThat(claim(worker).get("jobId").asText()).isEqualTo(quick);
        setScheduler("PRIORITY_AGING");
        assertThat(claim(worker).get("jobId").asText()).isEqualTo(urgent);
        setScheduler("FCFS");
        assertThat(claim(worker).get("jobId").asText()).isEqualTo(slowEarly);
    }

    @Test
    void queuesAreServedRoundRobin() throws Exception {
        String a1 = submitId(piJob(1_000, 5, "alpha"));
        advance(Duration.ofMillis(10));
        String a2 = submitId(piJob(1_000, 5, "alpha"));
        advance(Duration.ofMillis(10));
        String a3 = submitId(piJob(1_000, 5, "alpha"));
        advance(Duration.ofSeconds(1));
        String b1 = submitId(piJob(1_000, 5, "beta"));

        List<String> order = claimAll(generalWorker("w"), 4);

        assertThat(order).containsExactly(a1, b1, a2, a3);
    }

    @Test
    void schedulerPreviewExplainsReadyAndWaitingJobs() throws Exception {
        String ready = submitId(piJob(1_000, 5, "default"));
        Map<String, Object> later = piJob(1_000, 5, "default");
        later.put("dependsOn", List.of(ready));
        submitId(later);

        JsonNode preview = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/scheduler")).andReturn());
        assertThat(preview.get("ready").get(0).get("jobId").asText()).isEqualTo(ready);
        assertThat(preview.get("notReady").get(0).get("reason").asText()).startsWith("Waiting for 1 prerequisite");
    }

    @Test
    void concurrentClaimsNeverAssignAJobTwice() throws Exception {
        int jobCount = 30;
        for (int i = 0; i < jobCount; i++) {
            submitId(piJob(10_000));
        }
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            workers.add(registerWorker("racer-" + i, 10, ALL_BUILTINS));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<List<String>>> tasks = new ArrayList<>();
        for (Worker worker : workers) {
            tasks.add(() -> {
                List<String> claimed = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    JsonNode assignment = claim(worker);
                    if (assignment != null) {
                        claimed.add(assignment.get("jobId").asText());
                    }
                }
                return claimed;
            });
        }
        List<String> all = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<String>> future : pool.invokeAll(tasks)) {
            all.addAll(future.get());
        }
        pool.shutdown();

        Set<String> unique = new HashSet<>(all);
        assertThat(unique).hasSize(all.size());
        assertThat(all).hasSize(jobCount);
        assertThat(jdbc.queryForObject("select count(*) from attempt", Integer.class)).isEqualTo(jobCount);
    }
}
