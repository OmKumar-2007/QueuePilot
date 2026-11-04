package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.worker.HttpCoordinatorClient;
import com.queuepilot.worker.TaskExecutorRegistry;
import com.queuepilot.worker.WorkerConfig;
import com.queuepilot.worker.WorkerRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a real coordinator on a random port, four real worker runtimes talking to it over
 * HTTP, real task plugins, the real recovery monitor and real time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:qp-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "queuepilot.recovery-enabled=true",
        "queuepilot.recovery-interval-ms=200",
        "queuepilot.heartbeat-interval-ms=300",
        "queuepilot.lease-duration-ms=2000",
        "queuepilot.worker-timeout-ms=2000"})
@ActiveProfiles("test")
class RealWorkersEndToEndTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @TempDir
    Path workDir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WorkerRuntime> workers = new ArrayList<>();

    @BeforeEach
    void clean() {
        for (String table : List.of("attempt", "artifact", "job_dependency", "system_event", "job", "workflow", "worker")) {
            jdbc.update("delete from " + table);
        }
    }

    @AfterEach
    void stopWorkers() {
        workers.forEach(WorkerRuntime::stop);
    }

    private WorkerRuntime startWorker(String name, long paceMs, long freezeMs) throws Exception {
        WorkerConfig config = new WorkerConfig("http://127.0.0.1:" + port, name, 1, null, workDir.resolve(name),
                paceMs, freezeMs);
        WorkerRuntime runtime = new WorkerRuntime(config, new HttpCoordinatorClient(config.coordinatorUrl()),
                TaskExecutorRegistry.load(null));
        runtime.start();
        workers.add(runtime);
        return runtime;
    }

    private JsonNode call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " -> " + response.body()).isLessThan(300);
        return response.body().isEmpty() ? null : mapper.readTree(response.body());
    }

    private String submitPi(long samples) throws Exception {
        return call("POST", "/api/jobs", Map.of("taskType", "monte-carlo-pi", "input", Map.of("samples", samples))).get("id").asText();
    }

    private JsonNode job(String id) throws Exception {
        return call("GET", "/api/jobs/" + id, null);
    }

    private static <T> T await(Supplier<T> probe, Duration timeout, String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T value = probe.get();
            if (value != null && !Boolean.FALSE.equals(value)) {
                return value;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private JsonNode jobInState(String id, String state) {
        try {
            JsonNode job = job(id);
            return state.equals(job.get("state").asText()) ? job : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode runningWithProgress(String id) {
        try {
            JsonNode job = job(id);
            return "RUNNING".equals(job.get("state").asText()) && job.get("progressPercent").asInt() > 5 ? job : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Four worker processes run four jobs at the same time, one each")
    void fourWorkersRunFourJobsConcurrently() throws Exception {
        for (int i = 1; i <= 4; i++) {
            startWorker("e2e-" + i, 15, 20_000);
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(submitPi(2_000_000));
        }
        await(() -> {
            try {
                return call("GET", "/api/jobs?state=RUNNING", null).size() == 4;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, Duration.ofSeconds(20), "4 jobs running at once");

        Set<String> usedWorkers = new HashSet<>();
        for (String id : ids) {
            JsonNode done = await(() -> jobInState(id, "SUCCEEDED"), Duration.ofSeconds(30), "job " + id);
            assertThat(done.get("output").get("demoPacingMsPerPercent").asLong()).isEqualTo(15);
            usedWorkers.add(call("GET", "/api/jobs/" + id + "/history", null).get("attempts").get(0).get("workerId").asText());
        }
        assertThat(usedWorkers).hasSize(4);
    }

    @Test
    @DisplayName("Interrupting a worker stops its job, which is retried and completed")
    void interruptedJobIsRetriedAndCompletes() throws Exception {
        startWorker("e2e-a", 30, 20_000);
        startWorker("e2e-b", 30, 20_000);
        String id = submitPi(2_000_000);
        JsonNode running = await(() -> runningWithProgress(id), Duration.ofSeconds(20), "job running");

        call("POST", "/api/admin/workers/" + running.get("currentWorkerId").asText() + "/interrupt", null);

        JsonNode done = await(() -> jobInState(id, "SUCCEEDED"), Duration.ofSeconds(30), "job after interrupt");
        JsonNode attempts = call("GET", "/api/jobs/" + id + "/history", null).get("attempts");
        assertThat(attempts.get(0).get("error").asText()).startsWith("INTERRUPTED");
        assertThat(done.get("attemptsUsed").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("A paused worker holds its job (still RUNNING, no progress) until resumed")
    void pausedWorkerHoldsItsJobUntilResumed() throws Exception {
        startWorker("e2e-p", 30, 20_000);
        String id = submitPi(2_000_000);
        JsonNode running = await(() -> runningWithProgress(id), Duration.ofSeconds(20), "job running");
        String workerId = running.get("currentWorkerId").asText();

        call("POST", "/api/admin/workers/" + workerId + "/pause", null);
        Thread.sleep(1200);
        int frozenProgress = job(id).get("progressPercent").asInt();
        Thread.sleep(2500); // longer than the 2 s lease: heartbeats must keep it alive
        JsonNode paused = job(id);
        assertThat(paused.get("state").asText()).isEqualTo("RUNNING");
        assertThat(paused.get("progressPercent").asInt()).isEqualTo(frozenProgress);
        assertThat(call("GET", "/api/workers", null).get(0).get("status").asText()).isEqualTo("PAUSED");

        call("POST", "/api/admin/workers/" + workerId + "/resume", null);
        JsonNode done = await(() -> jobInState(id, "SUCCEEDED"), Duration.ofSeconds(30), "job after resume");
        assertThat(done.get("attemptsUsed").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("A frozen worker loses its job to another worker, then thaws and re-registers")
    void frozenWorkerIsRecoveredFrom() throws Exception {
        startWorker("e2e-frozen", 60, 4_000);
        String id = submitPi(2_000_000);
        JsonNode running = await(() -> runningWithProgress(id), Duration.ofSeconds(20), "job running");
        String frozenId = running.get("currentWorkerId").asText();

        call("POST", "/api/admin/workers/" + frozenId + "/simulate/freeze", null);
        startWorker("e2e-rescuer", 10, 20_000);

        JsonNode done = await(() -> jobInState(id, "SUCCEEDED"), Duration.ofSeconds(40), "job recovered");
        JsonNode attempts = call("GET", "/api/jobs/" + id + "/history", null).get("attempts");
        assertThat(attempts.get(0).get("workerId").asText()).isEqualTo(frozenId);
        assertThat(attempts.get(0).get("state").asText()).isIn("ABANDONED", "LEASE_EXPIRED");
        assertThat(attempts.get(attempts.size() - 1).get("workerId").asText()).isNotEqualTo(frozenId);
        assertThat(done.get("output").get("estimate").asDouble()).isBetween(3.10, 3.18);

        // after thawing, the frozen process re-registers under a new id and is ONLINE again
        await(() -> {
            try {
                for (JsonNode worker : call("GET", "/api/workers", null)) {
                    if (worker.get("name").asText().equals("e2e-frozen") && worker.get("status").asText().equals("ONLINE")) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, Duration.ofSeconds(20), "frozen worker back online");
    }
}
