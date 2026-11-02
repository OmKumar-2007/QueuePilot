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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
        tasks.forEach(task -> ids.add(task.get("identifier").asText()));
        assertThat(ids).containsAll(ALL_BUILTINS);
    }

    @Test
    void adminEndpointsRequireValidCredentials() throws Exception {
        mvc.perform(post("/api/admin/tasks").contentType("application/json").content(primeSieveManifest()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/tasks").with(httpBasic("admin", "wrong")).contentType("application/json")
                .content(primeSieveManifest())).andExpect(status().isUnauthorized());
    }

    @Test
    void newTaskTypeBecomesRunnableWithoutAnyCodeChange() throws Exception {
        JsonNode registered = body(mvc.perform(asAdmin(post("/api/admin/tasks")).contentType("application/json")
                .content(primeSieveManifest())).andExpect(status().isCreated()).andReturn());
        assertThat(registered.get("enabled").asBoolean()).isFalse();

        Map<String, Object> job = Map.of("taskType", "prime-sieve", "input", Map.of("limit", 1000));
        postJson("/api/jobs", job).andExpect(status().isBadRequest());

        mvc.perform(asAdmin(post("/api/admin/tasks/" + registered.get("id").asText() + "/enable"))).andExpect(status().isOk());
        String jobId = submitId(new java.util.HashMap<>(job));

        Worker builtinsOnly = generalWorker("old-worker");
        Worker withPlugin = registerWorker("plugin-worker", 2, List.of("cpu"), List.of("prime-sieve@1.0.0"));
        assertThat(claim(builtinsOnly)).isNull();
        JsonNode assignment = claim(withPlugin);
        assertThat(assignment.get("jobId").asText()).isEqualTo(jobId);
        assertThat(assignment.get("taskType").asText()).isEqualTo("prime-sieve");
    }

    @Test
    void registeredVersionsAreImmutableAndContainerTasksMustBePinned() throws Exception {
        mvc.perform(asAdmin(post("/api/admin/tasks")).contentType("application/json").content(primeSieveManifest()))
                .andExpect(status().isCreated());
        mvc.perform(asAdmin(post("/api/admin/tasks")).contentType("application/json").content(primeSieveManifest()))
                .andExpect(status().isConflict());

        String container = """
                {"taskType": "python-stats", "version": "1.0.0", "displayName": "Python", "executorKind": "CONTAINER",
                 "requiredCapabilities": [], "resources": {"cpuMillis": 500, "memoryMb": 128}, "timeoutSeconds": 30,
                 "maxRetries": 1, "defaultPriority": 5, "defaultEstimatedMs": 1000,
                 "container": {"image": "python:latest", "command": ["python", "/app/run.py"]}}""";
        mvc.perform(asAdmin(post("/api/admin/tasks")).contentType("application/json").content(container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("container.image"));
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
        assertThat(assignment.get("inputArtifactIds").get(0).asText()).isEqualTo(artifactId);
        String attemptPath = "/api/worker/attempts/" + assignment.get("attemptId").asText();
        String token = assignment.get("fencingToken").asText();

        byte[] downloaded = mvc.perform(get(attemptPath + "/artifacts/" + artifactId)
                        .header("Authorization", "Bearer " + worker.token()).header("X-Fencing-Token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(content);
        mvc.perform(get(attemptPath + "/artifacts/" + artifactId)
                        .header("Authorization", "Bearer " + worker.token()).header("X-Fencing-Token", "wrong"))
                .andExpect(status().isConflict());

        JsonNode output = body(mvc.perform(multipart(attemptPath + "/artifacts")
                        .file(new MockMultipartFile("file", "report.csv", "text/csv", "a,b\n".getBytes()))
                        .header("Authorization", "Bearer " + worker.token()).header("X-Fencing-Token", token))
                .andExpect(status().isCreated()).andReturn());
        String outputId = output.get("id").asText();

        workerPost(worker, "/attempts/" + assignment.get("attemptId").asText() + "/complete",
                Map.of("fencingToken", token, "output", Map.of("digest", "abc"), "outputArtifactIds", List.of(outputId)))
                .andExpect(status().isOk());
        assertThat(job(jobId).get("outputArtifactIds").asText()).isEqualTo(outputId);
        assertThat(mvc.perform(get("/api/artifacts/" + outputId + "/content")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo("a,b\n");
    }

    @Test
    void benchmarkReportsAreImportedByAdminsAndListedPublicly() throws Exception {
        Map<String, Object> report = Map.of("kind", "SIMULATED", "name", "unit", "config", Map.of("seed", 1),
                "environment", Map.of(), "summary", Map.of("scenarios", List.of()));
        postJson("/api/admin/benchmarks", report).andExpect(status().isUnauthorized());
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
