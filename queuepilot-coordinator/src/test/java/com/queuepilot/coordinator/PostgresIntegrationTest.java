package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the Flyway migration and a full submit -> claim -> complete cycle against a real PostgreSQL
 * server (binaries provided by the embedded-postgres test library, no installation needed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostgresIntegrationTest {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.start();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void fullJobLifecycleWorksOnPostgres() throws Exception {
        JsonNode job = mapper.readTree(mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("taskType", "sorting", "input", Map.of("numbers", List.of(3, 1, 2))))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        JsonNode worker = mapper.readTree(mvc.perform(post("/api/worker/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "pg-worker", "concurrencyLimit", 1,
                                "supportedTasks", List.of("sorting")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String workerId = worker.get("workerId").asText();

        JsonNode assignment = mapper.readTree(mvc.perform(post("/api/worker/claim").header("X-Worker-Id", workerId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(assignment.get("jobId").asText()).isEqualTo(job.get("id").asText());

        mvc.perform(post("/api/worker/attempts/" + assignment.get("attemptId").asText() + "/complete")
                .header("X-Worker-Id", workerId).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("output", Map.of("sorted", List.of(1, 2, 3)))))).andExpect(status().isOk());

        JsonNode done = mapper.readTree(mvc.perform(get("/api/jobs/" + job.get("id").asText()))
                .andReturn().getResponse().getContentAsString());
        assertThat(done.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("output").get("sorted").toString()).isEqualTo("[1,2,3]");
    }
}
