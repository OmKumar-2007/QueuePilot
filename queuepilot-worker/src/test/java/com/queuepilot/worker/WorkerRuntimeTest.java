package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerRuntimeTest {

    @TempDir
    Path workDir;

    private WorkerRuntime runtime;
    private final FakeCoordinator coordinator = new FakeCoordinator();

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private void start(int slots, TaskExecutorRegistry registry) throws Exception {
        start(slots, registry, 0, 20_000);
    }

    private void start(int slots, TaskExecutorRegistry registry, long paceMs, long freezeMs) throws Exception {
        WorkerConfig config = new WorkerConfig("http://unused", "test-worker", slots, null, workDir, paceMs, freezeMs);
        runtime = new WorkerRuntime(config, coordinator, registry);
        runtime.start();
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    /** A task that counts to {@code steps}, reporting each step; it never finishes on its own if steps is huge. */
    private static TaskExecutor counter(String type, CountDownLatch started) {
        return new TaskExecutor() {
            public String taskType() { return type; }
            public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
                started.countDown();
                long steps = input.getLong("steps", 100);
                var tracker = new com.queuepilot.task.ProgressTracker(context, steps);
                for (long i = 1; i <= steps; i++) {
                    try {
                        Thread.sleep(input.getLong("stepMs", 0));
                    } catch (InterruptedException e) {
                        throw TaskFailure.cancelled();
                    }
                    tracker.advance(i, "step " + i);
                }
                return TaskOutput.create().put("steps", steps);
            }
        };
    }

    @Test
    @DisplayName("Worker registers its tasks and runs real work without exceeding its slot limit")
    void registersItsTasksAndRunsRealWorkWithinItsSlotLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            coordinator.pending.add(FakeCoordinator.assignment("a" + i, "monte-carlo-pi",
                    Map.of("samples", 3_000_000, "seed", i), 60));
        }
        start(2, TaskExecutorRegistry.load(null));

        await(() -> coordinator.completed.size() == 6, Duration.ofSeconds(60));

        assertThat(coordinator.registration.supportedTasks()).contains("monte-carlo-pi", "sorting");
        assertThat(coordinator.maxConcurrentClaims.get()).isLessThanOrEqualTo(2);
        double estimate = ((Number) coordinator.completed.get("a0").output().get("estimate")).doubleValue();
        assertThat(estimate).isBetween(3.10, 3.18);
        assertThat(coordinator.progress.get("a0")).isSorted().endsWith(100);
        assertThat(coordinator.completed.get("a0").output()).doesNotContainKey("demoPacingMsPerPercent");
    }

    @Test
    @DisplayName("Task failures are reported with the task's retryable flag")
    void taskFailuresAreReportedWithTheirRetryableFlag() throws Exception {
        coordinator.pending.add(FakeCoordinator.assignment("bad-input", "sorting", Map.of(), 60));
        coordinator.pending.add(FakeCoordinator.assignment("unknown", "teleport@9.9.9", Map.of(), 60));
        start(1, TaskExecutorRegistry.load(null));

        await(() -> coordinator.failed.size() == 2, Duration.ofSeconds(10));

        assertThat(coordinator.failed.get("bad-input").retryable()).isFalse();
        assertThat(coordinator.failed.get("bad-input").errorCode()).isEqualTo("MISSING_DATA");
        assertThat(coordinator.failed.get("unknown").errorCode()).isEqualTo("NO_EXECUTOR");
        assertThat(coordinator.failed.get("unknown").retryable()).isTrue();
    }

    @Test
    @DisplayName("A task running past its timeout is stopped and reported as TIMEOUT")
    void localTimeoutStopsTheTaskAndReportsTimeout() throws Exception {
        coordinator.pending.add(FakeCoordinator.assignment("slow", "monte-carlo-pi",
                Map.of("samples", 2_000_000_000), 1));
        start(1, TaskExecutorRegistry.load(null));

        await(() -> coordinator.failed.containsKey("slow"), Duration.ofSeconds(15));
        assertThat(coordinator.failed.get("slow").errorCode()).isEqualTo("TIMEOUT");
        assertThat(coordinator.failed.get("slow").retryable()).isTrue();
        assertThat(coordinator.completed).isEmpty();
    }

    @Test
    @DisplayName("An attempt revoked by the coordinator is abandoned without reporting anything")
    void revokedAttemptIsAbandonedSilently() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        coordinator.pending.add(FakeCoordinator.assignment("doomed", "count", Map.of("steps", 1_000_000, "stepMs", 1), 60));
        start(1, new TaskExecutorRegistry(Map.of("count", counter("count", started))));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        coordinator.revoked.add("doomed");
        await(() -> runtime.activeAttempts() == 0, Duration.ofSeconds(5));

        assertThat(coordinator.failed).doesNotContainKey("doomed");
        assertThat(coordinator.completed).doesNotContainKey("doomed");
    }

    @Test
    @DisplayName("Demo pacing slows a job down to at least pace x 100 ms and marks the output")
    void demoPacingMakesJobsWatchable() throws Exception {
        coordinator.pending.add(FakeCoordinator.assignment("paced", "sorting", Map.of("numbers", List.of(3, 1, 2)), 60));
        long startedAt = System.nanoTime();
        start(1, TaskExecutorRegistry.load(null), 20, 20_000);

        await(() -> coordinator.completed.containsKey("paced"), Duration.ofSeconds(20));

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2_000);
        assertThat(coordinator.completed.get("paced").output()).containsEntry("demoPacingMsPerPercent", 20L)
                .containsEntry("sorted", List.of(1L, 2L, 3L));
        assertThat(coordinator.progress.get("paced")).isSorted().hasSizeGreaterThan(3).endsWith(100);
        assertThat(coordinator.registration.demoPaceMs()).isEqualTo(20);
    }

    @Test
    @DisplayName("PAUSE holds a running job and stops claiming; RESUME lets it finish")
    void pauseHoldsWorkAndResumeContinues() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        coordinator.pending.add(FakeCoordinator.assignment("long", "count", Map.of("steps", 100, "stepMs", 30), 60));
        start(1, new TaskExecutorRegistry(Map.of("count", counter("count", started))));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        coordinator.commands.add(WorkerProtocol.COMMAND_PAUSE);
        await(runtime::isPaused, Duration.ofSeconds(5));
        Thread.sleep(500);
        int progressWhilePaused = coordinator.progress.getOrDefault("long", List.of()).size();
        coordinator.pending.add(FakeCoordinator.assignment("queued", "count", Map.of("steps", 1), 60));
        Thread.sleep(800);

        assertThat(coordinator.progress.getOrDefault("long", List.of())).hasSize(progressWhilePaused);
        assertThat(coordinator.pending).hasSize(1);
        assertThat(coordinator.completed).isEmpty();
        int heartbeatsBefore = coordinator.heartbeats.get();
        Thread.sleep(300);
        assertThat(coordinator.heartbeats.get()).as("heartbeats continue while paused").isGreaterThan(heartbeatsBefore);

        coordinator.commands.add(WorkerProtocol.COMMAND_RESUME);
        await(() -> coordinator.completed.containsKey("long") && coordinator.completed.containsKey("queued"), Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("INTERRUPT stops running jobs and reports them as INTERRUPTED (retryable)")
    void interruptStopsRunningJobs() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        coordinator.pending.add(FakeCoordinator.assignment("i1", "count", Map.of("steps", 1_000_000, "stepMs", 1), 60));
        coordinator.pending.add(FakeCoordinator.assignment("i2", "count", Map.of("steps", 1_000_000, "stepMs", 1), 60));
        start(2, new TaskExecutorRegistry(Map.of("count", counter("count", started))));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        coordinator.commands.add(WorkerProtocol.COMMAND_INTERRUPT);
        await(() -> coordinator.failed.size() == 2, Duration.ofSeconds(5));

        assertThat(coordinator.failed.values()).allSatisfy(f -> {
            assertThat(f.errorCode()).isEqualTo("INTERRUPTED");
            assertThat(f.retryable()).isTrue();
        });
    }

    @Test
    @DisplayName("FREEZE stops heartbeats for the configured time, then the worker thaws by itself")
    void freezeStopsHeartbeatsTemporarily() throws Exception {
        start(1, TaskExecutorRegistry.load(null), 0, 600);
        await(() -> coordinator.heartbeats.get() >= 2, Duration.ofSeconds(5));

        coordinator.commands.add(WorkerProtocol.COMMAND_FREEZE);
        await(runtime::isFrozen, Duration.ofSeconds(5));
        int frozenAt = coordinator.heartbeats.get();
        Thread.sleep(400);
        assertThat(coordinator.heartbeats.get()).isEqualTo(frozenAt);

        await(() -> coordinator.heartbeats.get() > frozenAt, Duration.ofSeconds(5));
        assertThat(runtime.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("Stopping a worker deregisters it from the coordinator")
    void stopDeregisters() throws Exception {
        start(1, TaskExecutorRegistry.load(null));
        runtime.stop();
        assertThat(coordinator.deregistered).isTrue();
    }
}
