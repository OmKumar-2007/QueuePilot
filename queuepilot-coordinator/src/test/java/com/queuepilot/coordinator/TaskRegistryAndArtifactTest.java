package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F8, F16, F17, F21: task registry, extensibility without code changes, and file artifacts. */
class TaskRegistryAndArtifactTest extends CoordinatorTestBase {

    private String primeSieveManifest() throws Exception {
        return Files.readString(Path.of("../queuepilot-example-plugin/src/main/resources/META-INF/queuepilot/tasks/prime-sieve.json"));
    }

    @Test
    void builtinTasksAreSeededAndListed() throws Exception {
        JsonNode tasks = body(mvc.perform(get("/api/tasks")).andExpect(status().isOk()).andReturn());
        List<String> ids = new java.util.ArrayList<>();
        tasks.forEach(task -> ids.add(task.get("taskType").asText()));
        assertThat(ids).containsAll(ALL_BUILTINS);
    }

    @Test
    void newTaskTypeBecomesRunnableWithoutAnyCodeChange() throws Exception {
        Map<String, Object> job = Map.of("taskType", "prime-sieve", "input", Map.of("limit", 1000));
        postJson("/api/jobs", job).andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/tasks").contentType("application/json").content(primeSieveManifest()))
                .andExpect(status().isCreated());
        String jobId = submitId(new java.util.HashMap<>(job));

        Worker builtinsOnly = generalWorker("old-worker");
        Worker withPlugin = registerWorker("plugin-worker", 2, List.of("prime-sieve"));
        assertThat(claim(builtinsOnly)).isNull();
        JsonNode assignment = claim(withPlugin);
        assertThat(assignment.get("jobId").asText()).isEqualTo(jobId);
        assertThat(assignment.get("taskType").asText()).isEqualTo("prime-sieve");
    }

    @Test
    void taskTypesAreUniqueAndManifestsAreValidated() throws Exception {
        mvc.perform(post("/api/admin/tasks").contentType("application/json").content(primeSieveManifest()))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/admin/tasks").contentType("application/json").content(primeSieveManifest()))
                .andExpect(status().isConflict());

        String invalid = """
                {"taskType": "Bad Name", "displayName": "Bad", "timeoutSeconds": 30,
                 "maxRetries": 1, "defaultPriority": 5, "defaultEstimatedMs": 1000}""";
        mvc.perform(post("/api/admin/tasks").contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("taskType"));
    }

    @Test
    void inputFileFlowsToTheWorkerAndOutputFileBackToTheClient() throws Exception {
        byte[] content = "hello artifacts".getBytes(StandardCharsets.UTF_8);
        JsonNode upload = body(mvc.perform(multipart("/api/artifacts")
                        .file(new MockMultipartFile("file", "../../evil name.txt", "text/plain", content)))
                .andExpect(status().isCreated()).andReturn());
        String artifactId = upload.get("id").asText();
        assertThat(upload.get("originalName").asText()).isEqualTo("evil_name.txt");

        String jobId = submitId(Map.of("taskType", "file-checksum", "input", Map.of("file", artifactId)));
        postJson("/api/jobs", Map.of("taskType", "file-checksum", "input", Map.of("file", artifactId)))
                .andExpect(status().isBadRequest());

        Worker worker = generalWorker("w");
        JsonNode assignment = claim(worker);
        assertThat(assignment.get("input").get("file").asText()).isEqualTo(artifactId);
        String attemptPath = "/api/worker/attempts/" + assignment.get("attemptId").asText();

        byte[] downloaded = mvc.perform(get(attemptPath + "/artifacts/" + artifactId).header("X-Worker-Id", worker.id()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(content);
        Worker other = generalWorker("other");
        mvc.perform(get(attemptPath + "/artifacts/" + artifactId).header("X-Worker-Id", other.id()))
                .andExpect(status().isConflict());

        JsonNode output = body(mvc.perform(multipart(attemptPath + "/artifacts")
                        .file(new MockMultipartFile("file", "report.csv", "text/csv", "a,b\n".getBytes()))
                        .header("X-Worker-Id", worker.id()))
                .andExpect(status().isCreated()).andReturn());
        String outputId = output.get("id").asText();

        workerPost(worker, "/attempts/" + assignment.get("attemptId").asText() + "/complete",
                Map.of("output", Map.of("digest", "abc"), "outputArtifactIds", List.of(outputId)))
                .andExpect(status().isOk());
        assertThat(job(jobId).get("outputArtifactIds").asText()).isEqualTo(outputId);
        assertThat(mvc.perform(get("/api/artifacts/" + outputId + "/content")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo("a,b\n");
    }

    @Test
    void benchmarkReportsAreImportedAndListed() throws Exception {
        Map<String, Object> report = Map.of("kind", "SIMULATED", "name", "unit", "config", Map.of("seed", 1),
                "environment", Map.of(), "summary", Map.of("scenarios", List.of()));
        adminPost("/api/admin/benchmarks", report).andExpect(status().isCreated());
        mvc.perform(get("/api/benchmarks")).andExpect(jsonPath("$[0].kind").value("SIMULATED"));
        adminPost("/api/admin/benchmarks", Map.of("kind", "MADE_UP", "name", "x", "summary", Map.of()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overviewAndHealthReportLiveNumbers() throws Exception {
        submitId(piJob(10_000));
        generalWorker("w");
        mvc.perform(get("/api/metrics/overview")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1))
                .andExpect(jsonPath("$.jobsByState.QUEUED").value(1))
                .andExpect(jsonPath("$.onlineWorkers").value(1))
                .andExpect(jsonPath("$.totalSlots").value(4));
        mvc.perform(get("/api/health")).andExpect(jsonPath("$.database").value(true));
    }
}
