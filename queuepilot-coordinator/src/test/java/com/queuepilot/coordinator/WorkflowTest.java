package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F12, F19, F20: DAG submission, cycle rejection, release on success and skip on failure. */
class WorkflowTest extends CoordinatorTestBase {

    private Map<String, Object> step(String key, List<String> dependsOn) {
        return Map.of("key", key, "taskType", "monte-carlo-pi", "input", Map.of("samples", 10_000), "dependsOn", dependsOn);
    }

    /** a -> (b, c) -> d */
    private JsonNode submitDiamond() throws Exception {
        Map<String, Object> request = Map.of("name", "diamond", "steps", List.of(
                step("d", List.of("b", "c")), step("b", List.of("a")), step("c", List.of("a")), step("a", List.of())));
        return body(postJson("/api/workflows", request).andExpect(status().isCreated()).andReturn());
    }

    private Map<String, String> jobIdsByStep(JsonNode workflow) {
        Map<String, String> ids = new HashMap<>();
        workflow.get("nodes").forEach(node -> ids.put(node.get("step").asText(), node.get("jobId").asText()));
        return ids;
    }

    @Test
    void cycleIsRejectedAndNothingIsCreated() throws Exception {
        Map<String, Object> request = Map.of("name", "loop", "steps", List.of(
                step("a", List.of("c")), step("b", List.of("a")), step("c", List.of("b"))));
        JsonNode error = body(postJson("/api/workflows", request).andExpect(status().isBadRequest()).andReturn());
        assertThat(error.get("fieldErrors").get(0).get("message").asText()).contains("dependency cycle");
        assertThat(jdbc.queryForObject("select count(*) from job", Integer.class)).isZero();
    }

    @Test
    void stepsBecomeRunnableOnlyWhenAllPrerequisitesSucceed() throws Exception {
        JsonNode workflow = submitDiamond();
        Map<String, String> ids = jobIdsByStep(workflow);
        assertThat(workflow.get("edges")).hasSize(4);
        assertThat(state(ids.get("a"))).isEqualTo("QUEUED");
        assertThat(state(ids.get("b"))).isEqualTo("BLOCKED");

        Worker worker = generalWorker("w");
        JsonNode a = claim(worker);
        assertThat(a.get("jobId").asText()).isEqualTo(ids.get("a"));
        assertThat(claim(worker)).as("b and c are still blocked").isNull();
        complete(worker, a, Map.of()).andExpect(status().isOk());

        JsonNode first = claim(worker);
        JsonNode second = claim(worker);
        assertThat(List.of(first.get("jobId").asText(), second.get("jobId").asText()))
                .containsExactlyInAnyOrder(ids.get("b"), ids.get("c"));
        complete(worker, first, Map.of());
        assertThat(state(ids.get("d"))).isEqualTo("BLOCKED");
        complete(worker, second, Map.of());
        assertThat(state(ids.get("d"))).isEqualTo("QUEUED");

        complete(worker, claim(worker), Map.of());
        JsonNode done = body(mvc.perform(get("/api/workflows/" + workflow.get("id").asText())).andReturn());
        assertThat(done.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("percentComplete").asInt()).isEqualTo(100);
    }

    @Test
    void failedPrerequisiteSkipsAllDependantsTransitively() throws Exception {
        JsonNode workflow = submitDiamond();
        Map<String, String> ids = jobIdsByStep(workflow);
        Worker worker = generalWorker("w");

        fail(worker, claim(worker), false).andExpect(status().isOk());

        assertThat(state(ids.get("a"))).isEqualTo("FAILED");
        for (String step : List.of("b", "c", "d")) {
            assertThat(state(ids.get(step))).as(step).isEqualTo("SKIPPED");
        }
        JsonNode done = body(mvc.perform(get("/api/workflows/" + workflow.get("id").asText())).andReturn());
        assertThat(done.get("state").asText()).isEqualTo("FAILED");
    }

    @Test
    void singleJobsCanDependOnExistingJobs() throws Exception {
        String first = submitId(piJob(10_000));
        Map<String, Object> dependant = piJob(10_000);
        dependant.put("dependsOn", List.of(first));
        String second = submitId(dependant);
        assertThat(state(second)).isEqualTo("BLOCKED");

        Map<String, Object> broken = piJob(10_000);
        broken.put("dependsOn", List.of("00000000-0000-0000-0000-000000000000"));
        postJson("/api/jobs", broken).andExpect(status().isBadRequest());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/jobs/" + first + "/cancel"))
                .andExpect(status().isOk());
        assertThat(state(second)).isEqualTo("SKIPPED");
    }
}
