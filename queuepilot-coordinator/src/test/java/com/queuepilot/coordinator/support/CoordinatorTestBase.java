package com.queuepilot.coordinator.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.coordinator.recovery.RecoveryMonitor;
import com.queuepilot.coordinator.scheduler.SchedulerSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for coordinator integration tests: full Spring context, H2 in PostgreSQL mode, a
 * controllable clock, recovery triggered manually, and helpers that speak the real HTTP API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CoordinatorTestBase.ClockConfig.class)
public abstract class CoordinatorTestBase {

    protected static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    protected static final List<String> ALL_BUILTINS =
            List.of("sorting", "text-analysis", "monte-carlo-pi", "file-checksum", "javascript");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected RecoveryMonitor recovery;
    @Autowired protected SchedulerSettingsService schedulerSettings;
    @Autowired protected com.queuepilot.core.scheduling.QueueRotation queueRotation;

    @BeforeEach
    void resetDatabase() {
        for (String table : List.of("attempt", "artifact", "job_dependency", "system_event", "job", "workflow",
                "worker", "benchmark_report")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("delete from task_definition where task_type not in ('sorting','text-analysis','monte-carlo-pi','file-checksum','javascript')");
        jdbc.update("update scheduler_settings set active_policy='FCFS', aging_interval_seconds=5, aging_step=1");
        schedulerSettings.reload();
        queueRotation.markServed(null);
        clock.set(T0);
    }

    // ---------- generic HTTP helpers ----------

    protected String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? null : mapper.readTree(content);
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    protected ResultActions adminPost(String path, Object body) throws Exception {
        return postJson(path, body);
    }

    protected void setScheduler(String policy) throws Exception {
        mvc.perform(put("/api/admin/scheduler").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("activePolicy", policy, "agingIntervalSeconds", 5, "agingStep", 1))))
                .andExpect(status().isOk());
    }

    // ---------- client helpers ----------

    protected Map<String, Object> piJob(long samples) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("taskType", "monte-carlo-pi");
        job.put("input", Map.of("samples", samples));
        return job;
    }

    protected JsonNode submit(Map<String, Object> request) throws Exception {
        return body(postJson("/api/jobs", request).andExpect(status().isCreated()).andReturn());
    }

    protected String submitId(Map<String, Object> request) throws Exception {
        return submit(request).get("id").asText();
    }

    protected JsonNode job(String id) throws Exception {
        return body(mvc.perform(get("/api/jobs/" + id)).andExpect(status().isOk()).andReturn());
    }

    protected String state(String jobId) throws Exception {
        return job(jobId).get("state").asText();
    }

    // ---------- worker helpers ----------

    protected record Worker(String id) {
    }

    protected Worker registerWorker(String name, int slots, List<String> tasks) throws Exception {
        JsonNode response = body(postJson("/api/worker/register", Map.of("name", name, "concurrencyLimit", slots,
                "supportedTasks", tasks))
                .andExpect(status().isCreated()).andReturn());
        return new Worker(response.get("workerId").asText());
    }

    protected Worker generalWorker(String name) throws Exception {
        return registerWorker(name, 4, ALL_BUILTINS);
    }

    protected ResultActions workerPost(Worker worker, String path, Object body) throws Exception {
        return mvc.perform(post("/api/worker" + path).header("X-Worker-Id", worker.id())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    /** Returns the assignment, or null when the coordinator answered 204 (nothing to do). */
    protected JsonNode claim(Worker worker) throws Exception {
        MvcResult result = workerPost(worker, "/claim", Map.of()).andReturn();
        int code = result.getResponse().getStatus();
        if (code == 204) {
            return null;
        }
        if (code != 200) {
            throw new AssertionError("Claim failed with " + code + ": " + result.getResponse().getContentAsString());
        }
        return body(result);
    }

    protected ResultActions complete(Worker worker, JsonNode assignment, Map<String, Object> output) throws Exception {
        return workerPost(worker, "/attempts/" + assignment.get("attemptId").asText() + "/complete",
                Map.of("output", output, "outputArtifactIds", List.of()));
    }

    protected ResultActions fail(Worker worker, JsonNode assignment, boolean retryable) throws Exception {
        return workerPost(worker, "/attempts/" + assignment.get("attemptId").asText() + "/fail",
                Map.of("errorCode", "BOOM", "message", "simulated failure", "retryable", retryable));
    }

    protected JsonNode heartbeat(Worker worker, JsonNode... assignments) throws Exception {
        List<String> ids = java.util.Arrays.stream(assignments).map(a -> a.get("attemptId").asText()).toList();
        return body(workerPost(worker, "/heartbeat", Map.of("activeAttemptIds", ids)).andExpect(status().isOk()).andReturn());
    }

    protected void advance(Duration duration) {
        clock.advance(duration);
    }
}
