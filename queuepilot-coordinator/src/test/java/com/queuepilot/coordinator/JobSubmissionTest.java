package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;

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
        assertThat(job.get("priority").asInt()).isEqualTo(5);
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
}
