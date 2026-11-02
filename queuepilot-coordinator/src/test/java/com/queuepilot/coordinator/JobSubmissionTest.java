package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F1-F3, F13, F14: submission, validation, persistence, cancellation, scheduling and quotas. */
class JobSubmissionTest extends CoordinatorTestBase {

    private int jobRows() {
        return jdbc.queryForObject("select count(*) from job", Integer.class);
    }

    @Test
    void acceptedJobIsPersistedAndQueuedWithTaskDefaults() throws Exception {
        JsonNode job = submit(piJob(1_000_000));

        assertThat(job.get("state").asText()).isEqualTo("QUEUED");
        assertThat(job.get("taskVersion").asText()).isEqualTo("1.0.0");
        assertThat(job.get("priority").asInt()).isEqualTo(5);
        assertThat(job.get("maxRetries").asInt()).isEqualTo(3);
        assertThat(jobRows()).isEqualTo(1);
        assertThat(job(job.get("id").asText()).get("input").get("samples").asLong()).isEqualTo(1_000_000);
    }

    @Test
    void missingRequiredFieldIsRejectedAndNothingIsStored() throws Exception {
        postJson("/api/jobs", Map.of("taskType", "monte-carlo-pi", "input", Map.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("samples"))
                .andExpect(jsonPath("$.trace").doesNotExist());
        assertThat(jobRows()).isZero();
    }

    @Test
    void rejectsUnknownTaskTypesUnknownFieldsOutOfRangeValuesAndBadPriority() throws Exception {
        postJson("/api/jobs", Map.of("taskType", "rm-rf", "input", Map.of())).andExpect(status().isBadRequest());
        postJson("/api/jobs", Map.of("taskType", "monte-carlo-pi", "input", Map.of("samples", 5000, "shell", "ls")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("shell"));
        postJson("/api/jobs", Map.of("taskType", "monte-carlo-pi", "input", Map.of("samples", 5)))
                .andExpect(status().isBadRequest());
        postJson("/api/jobs", Map.of("taskType", "monte-carlo-pi", "input", Map.of("samples", 5000), "priority", 11))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("priority"));
        mvc.perform(post("/api/jobs").contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(jobRows()).isZero();
    }

    @Test
    void futureStartTimeIsScheduledAndReleasedWhenTheTimeComes() throws Exception {
        Map<String, Object> request = piJob(10_000);
        request.put("startAt", T0.plusSeconds(60).toString());
        String id = submitId(request);
        assertThat(state(id)).isEqualTo("SCHEDULED");

        recovery.runOnce();
        assertThat(state(id)).isEqualTo("SCHEDULED");

        advance(Duration.ofSeconds(61));
        recovery.runOnce();
        assertThat(state(id)).isEqualTo("QUEUED");
    }

    @Test
    void queuedJobCanBeCancelledButRunningJobCannot() throws Exception {
        String queued = submitId(piJob(10_000));
        mvc.perform(post("/api/jobs/" + queued + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));

        String running = submitId(piJob(10_000));
        claim(generalWorker("w1"));
        mvc.perform(post("/api/jobs/" + running + "/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        assertThat(state(running)).isEqualTo("RUNNING");
    }

    @Test
    void perClientActiveJobLimitIsEnforced() throws Exception {
        setScheduler("FCFS", 2);
        Map<String, Object> request = piJob(10_000);
        mvc.perform(post("/api/jobs").header("X-Client-Id", "alice").contentType("application/json").content(json(request)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/jobs").header("X-Client-Id", "alice").contentType("application/json").content(json(request)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/jobs").header("X-Client-Id", "alice").contentType("application/json").content(json(request)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        // other clients are unaffected
        mvc.perform(post("/api/jobs").header("X-Client-Id", "bob").contentType("application/json").content(json(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void deadlineStatusIsReported() throws Exception {
        Map<String, Object> request = piJob(10_000);
        request.put("deadline", T0.plusSeconds(120).toString());
        String id = submitId(request);
        assertThat(job(id).get("deadlineStatus").asText()).isEqualTo("AT_RISK");

        advance(Duration.ofSeconds(200));
        assertThat(job(id).get("deadlineStatus").asText()).isEqualTo("OVERDUE");

        Map<String, Object> past = piJob(10_000);
        past.put("deadline", T0.minusSeconds(5).toString());
        postJson("/api/jobs", past).andExpect(status().isBadRequest());
    }
}
