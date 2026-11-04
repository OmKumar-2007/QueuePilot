package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Clear history: removes logs and finished jobs, but never anything unfinished work relies on. */
class HistoryTest extends CoordinatorTestBase {

    private JsonNode clearHistory() throws Exception {
        return body(postJson("/api/admin/history/clear", Map.of()).andExpect(status().isOk()).andReturn());
    }

    private int rows(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void finishNext(Worker worker) throws Exception {
        complete(worker, claim(worker), Map.of()).andExpect(status().isOk());
    }

    private Map<String, Object> dependsOn(String... prerequisites) {
        Map<String, Object> request = new HashMap<>(piJob(10_000));
        request.put("dependsOn", List.of(prerequisites));
        return request;
    }

    @Test
    void removesLogsAndFinishedJobsButKeepsQueuedAndRunningOnes() throws Exception {
        Worker worker = generalWorker("w");
        String done = submitId(piJob(10_000));
        finishNext(worker);
        String failed = submitId(piJob(10_000));
        fail(worker, claim(worker), false).andExpect(status().isOk());
        String running = submitId(piJob(10_000));
        JsonNode runningAttempt = claim(worker);
        String queued = submitId(piJob(10_000));

        JsonNode cleared = clearHistory();

        assertThat(cleared.get("jobs").asInt()).isEqualTo(2);
        assertThat(cleared.get("jobsKept").asInt()).isZero();
        assertThat(rows("system_event")).isZero();
        assertThat(rows("job")).isEqualTo(2);
        assertThat(rows("attempt")).isEqualTo(1);
        mvc.perform(get("/api/jobs/" + done)).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/" + failed)).andExpect(status().isNotFound());
        assertThat(state(queued)).isEqualTo("QUEUED");

        complete(worker, runningAttempt, Map.of()).andExpect(status().isOk());
        assertThat(state(running)).isEqualTo("SUCCEEDED");
    }

    @Test
    void succeededPrerequisiteOfABlockedJobIsKeptSoTheJobCanStillStart() throws Exception {
        Worker worker = generalWorker("w");
        String finished = submitId(piJob(10_000));
        finishNext(worker);
        String pending = submitId(piJob(10_000));
        String blocked = submitId(dependsOn(finished, pending));

        JsonNode cleared = clearHistory();

        assertThat(cleared.get("jobs").asInt()).isZero();
        assertThat(cleared.get("jobsKept").asInt()).isEqualTo(1);
        assertThat(state(finished)).isEqualTo("SUCCEEDED");

        finishNext(worker);
        assertThat(state(pending)).isEqualTo("SUCCEEDED");
        assertThat(state(blocked)).isEqualTo("QUEUED");
    }

    @Test
    void workflowsAreRemovedOnlyOnceAllTheirStepsAreFinished() throws Exception {
        Map<String, Object> first = Map.of("key", "first", "taskType", "monte-carlo-pi", "input", Map.of("samples", 10_000));
        Map<String, Object> second = Map.of("key", "second", "taskType", "monte-carlo-pi", "input", Map.of("samples", 10_000),
                "dependsOn", List.of("first"));
        postJson("/api/workflows", Map.of("name", "two-steps", "steps", List.of(first, second)))
                .andExpect(status().isCreated());
        Worker worker = generalWorker("w");

        finishNext(worker);
        clearHistory();
        assertThat(rows("workflow")).isEqualTo(1);
        assertThat(rows("job")).isEqualTo(2);

        finishNext(worker);
        clearHistory();
        assertThat(rows("workflow")).isZero();
        assertThat(rows("job")).isZero();
    }

    @Test
    void filesOfRemovedJobsAreDeletedAndUnusedUploadsAreLeftAlone() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String used = body(mvc.perform(multipart("/api/artifacts").file(new MockMultipartFile("file", "a.txt", "text/plain", content)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String unused = body(mvc.perform(multipart("/api/artifacts").file(new MockMultipartFile("file", "b.txt", "text/plain", content)))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        submitId(Map.of("taskType", "file-checksum", "input", Map.of("file", used)));
        finishNext(generalWorker("w"));

        assertThat(clearHistory().get("files").asInt()).isEqualTo(1);

        mvc.perform(get("/api/artifacts/" + used + "/content")).andExpect(status().isNotFound());
        mvc.perform(get("/api/artifacts/" + unused + "/content")).andExpect(status().isOk());
    }
}
