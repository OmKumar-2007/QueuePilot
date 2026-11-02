package com.queuepilot.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.queuepilot.coordinator.support.CoordinatorTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F9-F11, F18, U4, U5: reporting, leases, fencing, retries, timeouts and worker failure. */
class ExecutionAndRecoveryTest extends CoordinatorTestBase {

    @Test
    void progressAndCompletionAreRecordedFromTheCurrentAttempt() throws Exception {
        String id = submitId(piJob(10_000));
        Worker worker = generalWorker("w1");
        JsonNode assignment = claim(worker);

        workerPost(worker, "/attempts/" + assignment.get("attemptId").asText() + "/progress",
                Map.of("fencingToken", assignment.get("fencingToken").asText(), "percent", 40, "message", "40% done"))
                .andExpect(status().isOk());
        assertThat(job(id).get("progressPercent").asInt()).isEqualTo(40);

        complete(worker, assignment, Map.of("estimate", 3.14)).andExpect(status().isOk());
        JsonNode done = job(id);
        assertThat(done.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.get("output").get("estimate").asDouble()).isEqualTo(3.14);
        assertThat(done.get("progressPercent").asInt()).isEqualTo(100);
    }

    @Test
    void forgedTokenAndOtherWorkersCannotUpdateAnAttempt() throws Exception {
        submitId(piJob(10_000));
        Worker owner = generalWorker("owner");
        Worker intruder = generalWorker("intruder");
        JsonNode assignment = claim(owner);
        String path = "/attempts/" + assignment.get("attemptId").asText() + "/complete";

        workerPost(owner, path, Map.of("fencingToken", "forged", "output", Map.of())).andExpect(status().isConflict());
        workerPost(intruder, path, Map.of("fencingToken", assignment.get("fencingToken").asText(), "output", Map.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_ATTEMPT"));
        mvc.perform(post("/api/worker/claim").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredLeaseIsRetriedElsewhereAndTheLateResultIsRejected() throws Exception {
        String id = submitId(piJob(10_000));
        Worker slow = generalWorker("slow");
        JsonNode first = claim(slow);

        advance(Duration.ofSeconds(5));
        heartbeat(slow, first);              // renews the lease until T0+15s
        advance(Duration.ofSeconds(9));
        heartbeat(slow);                     // the worker process is alive but no longer renews this attempt
        recovery.runOnce();
        assertThat(state(id)).isEqualTo("RUNNING");

        advance(Duration.ofSeconds(2));      // T0+16s: lease expired
        recovery.runOnce();
        JsonNode retried = job(id);
        assertThat(retried.get("state").asText()).isEqualTo("QUEUED");
        assertThat(retried.get("errorCode").asText()).isEqualTo("LEASE_EXPIRED");

        Worker healthy = generalWorker("healthy");
        assertThat(claim(healthy)).as("retry delay not yet over").isNull();
        advance(Duration.ofSeconds(2));
        JsonNode second = claim(healthy);
        assertThat(second.get("attemptNumber").asInt()).isEqualTo(2);

        // the first worker finally finishes: fenced out, nothing changes
        complete(slow, first, Map.of("estimate", 1.0)).andExpect(status().isConflict());
        assertThat(state(id)).isEqualTo("RUNNING");
        JsonNode heartbeatReply = heartbeat(slow, first);
        assertThat(heartbeatReply.get("revoked").get(0).asText()).isEqualTo(first.get("attemptId").asText());

        complete(healthy, second, Map.of("estimate", 3.1)).andExpect(status().isOk());
        assertThat(job(id).get("output").get("estimate").asDouble()).isEqualTo(3.1);

        JsonNode history = body(mvc.perform(get("/api/jobs/" + id + "/history")).andReturn());
        assertThat(history.get("attempts")).hasSize(2);
        assertThat(history.get("attempts").get(0).get("state").asText()).isEqualTo("LEASE_EXPIRED");
        assertThat(history.get("attempts").get(1).get("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void retryableFailuresStopAtTheRetryLimit() throws Exception {
        String id = submitId(piJob(10_000));       // monte-carlo-pi allows 3 retries = 4 attempts
        Worker worker = generalWorker("w");
        for (int attempt = 1; attempt <= 4; attempt++) {
            JsonNode assignment = claim(worker);
            assertThat(assignment).as("attempt " + attempt).isNotNull();
            assertThat(assignment.get("attemptNumber").asInt()).isEqualTo(attempt);
            fail(worker, assignment, true).andExpect(status().isOk());
            advance(Duration.ofSeconds(10));        // longer than any backoff (max 8s in tests)
        }
        JsonNode job = job(id);
        assertThat(job.get("state").asText()).isEqualTo("FAILED");
        assertThat(job.get("attemptsUsed").asInt()).isEqualTo(4);
        assertThat(claim(worker)).isNull();
    }

    @Test
    void permanentFailureFailsImmediately() throws Exception {
        String id = submitId(piJob(10_000));
        Worker worker = generalWorker("w");
        fail(worker, claim(worker), false).andExpect(status().isOk());
        assertThat(state(id)).isEqualTo("FAILED");
    }

    @Test
    void silentWorkerGoesOfflineAndItsWorkIsRequeued() throws Exception {
        String id = submitId(piJob(10_000));
        Worker worker = generalWorker("silent");
        JsonNode assignment = claim(worker);

        advance(Duration.ofSeconds(11));
        recovery.runOnce();

        JsonNode workers = body(mvc.perform(get("/api/workers")).andReturn());
        assertThat(workers.get(0).get("status").asText()).isEqualTo("OFFLINE");
        assertThat(state(id)).isEqualTo("QUEUED");
        JsonNode reply = heartbeat(worker, assignment);
        assertThat(reply.get("command").asText()).isEqualTo("REREGISTER");
        assertThat(claim(worker)).isNull();
    }

    @Test
    void attemptRunningPastTheTaskTimeoutIsTimedOut() throws Exception {
        String id = submitId(piJob(10_000));       // monte-carlo-pi timeout: 600 s
        Worker worker = generalWorker("w");
        JsonNode assignment = claim(worker);

        advance(Duration.ofSeconds(601));
        heartbeat(worker, assignment);             // lease still fine, but the time budget is spent
        advance(Duration.ofSeconds(4));
        recovery.runOnce();

        JsonNode job = job(id);
        assertThat(job.get("state").asText()).isEqualTo("QUEUED");
        assertThat(job.get("errorCode").asText()).isEqualTo("TIMEOUT");
        assertThat(heartbeat(worker, assignment).get("revoked")).hasSize(1);
    }

    @Test
    void failureSimulationIsAdminOnlyAndDeliveredOnTheNextHeartbeat() throws Exception {
        Worker worker = generalWorker("victim");
        mvc.perform(post("/api/admin/workers/" + worker.id() + "/simulate/kill")).andExpect(status().isUnauthorized());
        mvc.perform(asAdmin(post("/api/admin/workers/" + worker.id() + "/simulate/kill"))).andExpect(status().isOk());

        assertThat(heartbeat(worker).get("command").asText()).isEqualTo("KILL");
        assertThat(heartbeat(worker).get("command").isNull()).isTrue();
    }

    @Test
    void drainedWorkerTakesNoNewWork() throws Exception {
        submitId(piJob(10_000));
        Worker worker = generalWorker("w");
        mvc.perform(asAdmin(post("/api/admin/workers/" + worker.id() + "/drain"))).andExpect(status().isOk());
        assertThat(claim(worker)).isNull();
        mvc.perform(asAdmin(post("/api/admin/workers/" + worker.id() + "/resume"))).andExpect(status().isOk());
        assertThat(claim(worker)).isNotNull();
    }

    @Test
    void registrationNeedsTheEnrollmentSecret() throws Exception {
        postJson("/api/worker/register", Map.of("name", "rogue", "concurrencyLimit", 1, "capabilities", List.of(),
                "supportedTasks", List.of(), "enrollSecret", "guess")).andExpect(status().isForbidden());
    }
}
