package coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Administrator worker controls: pause, resume, interrupt, and how commands reach the worker. */
class WorkerControlTest extends CoordinatorTestBase {

    private void act(Worker worker, String action) throws Exception {
        mvc.perform(post("/api/admin/workers/" + worker.id() + "/" + action)).andExpect(status().isOk());
    }

    private String workerStatus(Worker worker) throws Exception {
        for (JsonNode w : body(mvc.perform(get("/api/workers")).andReturn())) {
            if (w.get("id").asText().equals(worker.id())) {
                return w.get("status").asText();
            }
        }
        throw new AssertionError("worker missing");
    }

    @Test
    @DisplayName("A paused worker gets no new work but keeps its running job alive; resume restores it")
    void pauseBlocksClaimsButKeepsLeases() throws Exception {
        submitId(piJob(10_000));
        submitId(piJob(10_000));
        Worker worker = generalWorker("w");
        JsonNode assignment = claim(worker);

        act(worker, "pause");
        assertThat(workerStatus(worker)).isEqualTo("PAUSED");
        assertThat(heartbeat(worker, assignment).get("command").asText()).isEqualTo("PAUSE");
        assertThat(claim(worker)).isNull();

        advance(java.time.Duration.ofSeconds(8));
        heartbeat(worker, assignment);
        advance(java.time.Duration.ofSeconds(8));
        recovery.runOnce();
        assertThat(state(assignment.get("jobId").asText())).as("heartbeats keep a paused worker's lease").isEqualTo("RUNNING");

        act(worker, "resume");
        assertThat(heartbeat(worker, assignment).get("command").asText()).isEqualTo("RESUME");
        assertThat(workerStatus(worker)).isEqualTo("ONLINE");
        assertThat(claim(worker)).isNotNull();
    }

    @Test
    @DisplayName("Interrupt is delivered to the worker and its reported failure requeues the job")
    void interruptRequeuesTheJob() throws Exception {
        String id = submitId(piJob(10_000));
        Worker worker = generalWorker("w");
        JsonNode assignment = claim(worker);

        act(worker, "interrupt");
        assertThat(heartbeat(worker, assignment).get("command").asText()).isEqualTo("INTERRUPT");
        workerPost(worker, "/attempts/" + assignment.get("attemptId").asText() + "/fail",
                Map.of("errorCode", "INTERRUPTED", "message", "Interrupted by an administrator", "retryable", true))
                .andExpect(status().isOk());

        JsonNode job = job(id);
        assertThat(job.get("state").asText()).isEqualTo("QUEUED");
        assertThat(job.get("error").asText()).startsWith("INTERRUPTED");
    }

    @Test
    @DisplayName("Several commands issued before a heartbeat are delivered together, in order")
    void commandsQueueUpInOrder() throws Exception {
        Worker worker = generalWorker("w");
        act(worker, "pause");
        act(worker, "interrupt");
        act(worker, "resume");
        assertThat(heartbeat(worker).get("command").asText()).isEqualTo("PAUSE,INTERRUPT,RESUME");
        assertThat(heartbeat(worker).get("command").isNull()).isTrue();
    }

    @Test
    @DisplayName("Workers report their demo pacing so the dashboard can show it")
    void demoPacingIsVisible() throws Exception {
        postJson("/api/worker/register", Map.of("name", "paced", "concurrencyLimit", 1,
                "supportedTasks", List.of("sorting"), "demoPaceMs", 150))
                .andExpect(status().isCreated());
        JsonNode workers = body(mvc.perform(get("/api/workers")).andReturn());
        assertThat(workers.get(0).get("demoPaceMs").asLong()).isEqualTo(150);
    }

    private int registerStatus(String name) throws Exception {
        return postJson("/api/worker/register", Map.of("name", name, "concurrencyLimit", 1,
                "supportedTasks", ALL_BUILTINS, "demoPaceMs", 0)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("A fifth worker is refused while four are present, whatever their health")
    void fifthWorkerIsRefused() throws Exception {
        Worker paused = generalWorker("w1");
        Worker disabled = generalWorker("w2");
        generalWorker("w3");
        generalWorker("w4");
        act(paused, "pause");
        act(disabled, "disable");

        assertThat(registerStatus("w5")).isEqualTo(409);
        assertThat(body(mvc.perform(get("/api/workers")).andReturn())).hasSize(4);
    }

    @Test
    @DisplayName("A worker that went offline frees its place")
    void offlineWorkerFreesAPlace() throws Exception {
        Worker leaving = generalWorker("w1");
        generalWorker("w2");
        generalWorker("w3");
        generalWorker("w4");
        assertThat(registerStatus("w5")).isEqualTo(409);

        mvc.perform(post("/api/worker/deregister").header("X-Worker-Id", leaving.id())).andExpect(status().isOk());

        assertThat(registerStatus("w5")).isEqualTo(201);
    }

    @Test
    @DisplayName("Workers registering at the same moment cannot exceed the limit")
    void concurrentRegistrationsRespectTheLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "racer-" + i;
            tasks.add(() -> registerStatus(name));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : pool.invokeAll(tasks)) {
            statuses.add(future.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(4);
    }
}
