package com.queuepilot.coordinator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default configuration: administrator actions need no password (local demo). */
@SpringBootTest(properties = {
        "queuepilot.admin-auth-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:qp-noauth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminWithoutPasswordTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("Admin endpoints work without credentials when admin authentication is off")
    void adminEndpointsAreOpen() throws Exception {
        mvc.perform(get("/api/admin/session")).andExpect(status().isOk()).andExpect(jsonPath("$.user").value("anonymous"));
        mvc.perform(put("/api/admin/scheduler").contentType("application/json")
                        .content("{\"activePolicy\":\"SJF\",\"agingIntervalSeconds\":5,\"agingStep\":1,\"maxActiveJobsPerClient\":500}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activePolicy").value("SJF"));
    }

    @Test
    @DisplayName("Worker endpoints still require the worker's own token")
    void workerEndpointsStayProtected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/worker/claim")
                .contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
}
