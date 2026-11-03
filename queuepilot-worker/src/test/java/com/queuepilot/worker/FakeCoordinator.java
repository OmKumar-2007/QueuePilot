package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol;
import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.AttemptRef;
import com.queuepilot.core.protocol.WorkerProtocol.CompletionReport;
import com.queuepilot.core.protocol.WorkerProtocol.FailureReport;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatResponse;
import com.queuepilot.core.protocol.WorkerProtocol.ProgressReport;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterRequest;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory coordinator that hands out queued assignments and records what the worker reports. */
class FakeCoordinator implements CoordinatorApi {

    final Queue<Assignment> pending = new ConcurrentLinkedQueue<>();
    final Map<String, CompletionReport> completed = new ConcurrentHashMap<>();
    final Map<String, FailureReport> failed = new ConcurrentHashMap<>();
    final Map<String, List<Integer>> progress = new ConcurrentHashMap<>();
    final Set<String> revoked = ConcurrentHashMap.newKeySet();
    final AtomicInteger maxConcurrentClaims = new AtomicInteger();
    final List<Integer> freeSlotsSeen = Collections.synchronizedList(new ArrayList<>());
    final Queue<String> commands = new ConcurrentLinkedQueue<>();
    final AtomicInteger heartbeats = new AtomicInteger();
    volatile RegisterRequest registration;
    volatile boolean deregistered;
    private final AtomicInteger outstanding = new AtomicInteger();

    static Assignment assignment(String id, String task, Map<String, Object> input, int timeoutSeconds) {
        String[] parts = task.split("@");
        return new Assignment(id, "token-" + id, 1, "job-" + id, parts[0], parts[1], WorkerProtocol.KIND_JAVA_PLUGIN,
                input, List.of(), 10_000, timeoutSeconds, null);
    }

    @Override
    public RegisterResponse register(RegisterRequest request) {
        registration = request;
        return new RegisterResponse("w-test", "secret-token", 100, 10_000, 20);
    }

    @Override
    public Optional<Assignment> claim(int freeSlots) {
        freeSlotsSeen.add(freeSlots);
        Assignment next = pending.poll();
        if (next != null) {
            maxConcurrentClaims.accumulateAndGet(outstanding.incrementAndGet(), Math::max);
        }
        return Optional.ofNullable(next);
    }

    @Override
    public HeartbeatResponse heartbeat(List<AttemptRef> activeAttempts) {
        List<String> renewed = new ArrayList<>();
        List<String> revokedNow = new ArrayList<>();
        for (AttemptRef ref : activeAttempts) {
            (revoked.contains(ref.attemptId()) ? revokedNow : renewed).add(ref.attemptId());
        }
        heartbeats.incrementAndGet();
        return new HeartbeatResponse(renewed, revokedNow, commands.poll());
    }

    @Override
    public boolean progress(String attemptId, ProgressReport report) {
        progress.computeIfAbsent(attemptId, id -> Collections.synchronizedList(new ArrayList<>())).add(report.percent());
        return !revoked.contains(attemptId);
    }

    @Override
    public boolean complete(String attemptId, CompletionReport report) {
        outstanding.decrementAndGet();
        if (revoked.contains(attemptId)) {
            return false;
        }
        completed.put(attemptId, report);
        return true;
    }

    @Override
    public boolean fail(String attemptId, FailureReport report) {
        outstanding.decrementAndGet();
        failed.put(attemptId, report);
        return true;
    }

    @Override
    public void downloadInput(String attemptId, String fencingToken, String artifactId, Path target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String uploadOutput(String attemptId, String fencingToken, Path file, String contentType) {
        return "artifact-" + file.getFileName();
    }

    @Override
    public void deregister() {
        deregistered = true;
    }

    int finished() {
        return completed.size() + failed.size();
    }
}
