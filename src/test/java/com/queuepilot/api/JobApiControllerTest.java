package com.queuepilot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.dto.SubmitJobRequest;
import com.queuepilot.model.JobType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class JobApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/jobs should validate and accept job submission")
    void testSubmitJob() throws Exception {
        SubmitJobRequest req = new SubmitJobRequest();
        req.setName("Api-Sort-Test");
        req.setType(JobType.SORTING);
        req.setPriority(7);
        req.setEstimatedDurationMs(5000);
        req.setAlgorithm("QUICKSORT");

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name", is("Api-Sort-Test")))
                .andExpect(jsonPath("$.type", is("SORTING")))
                .andExpect(jsonPath("$.basePriority", is(7)));
    }

    @Test
    @DisplayName("GET /api/scheduler/policy and POST /api/scheduler/policy should switch policies including MLFQ")
    void testSchedulerPolicySwitching() throws Exception {
        // Switch to MLFQ
        mockMvc.perform(post("/api/scheduler/policy?name=MLFQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePolicy", is("MLFQ")));

        // Verify active policy
        mockMvc.perform(get("/api/scheduler/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePolicy", is("MLFQ")));

        // List all available policies
        mockMvc.perform(get("/api/scheduler/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @DisplayName("GET /api/workers and simulate crash/revival should reflect in API")
    void testWorkerEndpoints() throws Exception {
        mockMvc.perform(get("/api/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].id", notNullValue()));

        mockMvc.perform(post("/api/workers/worker-3/simulate-crash"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/workers/worker-3/revive"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/benchmarks/run should calculate metrics for FCFS, SJF, PRIORITY_AGING, and MLFQ")
    void testBenchmarkExecution() throws Exception {
        mockMvc.perform(post("/api/benchmarks/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].policyName", containsInAnyOrder("FCFS", "SJF", "PRIORITY_AGING", "MLFQ")))
                .andExpect(jsonPath("$[0].avgWaitingTimeMs", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$[0].avgTurnaroundTimeMs", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$[0].throughputJobsPerSec", greaterThan(0.0)))
                .andExpect(jsonPath("$[0].workerUtilizationPercent", greaterThan(0.0)));
    }

    @Test
    @DisplayName("POST /api/demo/seed should inject 10 demonstrable jobs (10-30s)")
    void testSeedDemoWorkload() throws Exception {
        mockMvc.perform(post("/api/demo/seed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)))
                .andExpect(jsonPath("$[0].estimatedDurationMs", greaterThanOrEqualTo(10000)));
    }
}
