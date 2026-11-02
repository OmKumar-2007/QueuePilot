package com.queuepilot.worker;

import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import com.queuepilot.task.TaskContext;
import org.junit.jupiter.api.AfterEach;
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
        WorkerConfig config = new WorkerConfig("http://unused", "test-worker", slots, List.of("cpu"), "s",
                null, workDir, false, "docker");
        runtime = new WorkerRuntime(config, coordinator, registry, null);
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

    @Test
    void registersItsTasksAndRunsRealWorkWithinItsSlotLimit() throws Exception {
        for (int i = 0; i < 6; i++) {
            coordinator.pending.add(FakeCoordinator.assignment("a" + i, "monte-carlo-pi@1.0.0",
                    Map.of("samples", 3_000_000, "seed", i), 60));
        }
        start(2, TaskExecutorRegistry.load(null));

        await(() -> coordinator.completed.size() == 6, Duration.ofSeconds(60));

        assertThat(coordinator.registration.supportedTasks()).contains("monte-carlo-pi@1.0.0", "sorting@1.0.0");
        assertThat(coordinator.maxConcurrentClaims.get()).isLessThanOrEqualTo(2);
        double estimate = ((Number) coordinator.completed.get("a0").output().get("estimate")).doubleValue();
        assertThat(estimate).isBetween(3.10, 3.18);
        assertThat(coordinator.progress.get("a0")).isSorted().endsWith(100);
    }

    @Test
    void taskFailuresAreReportedWithTheirRetryableFlag() throws Exception {
        coordinator.pending.add(FakeCoordinator.assignment("bad-input", "sorting@1.0.0", Map.of(), 60));
        coordinator.pending.add(FakeCoordinator.assignment("unknown", "teleport@9.9.9", Map.of(), 60));
        start(1, TaskExecutorRegistry.load(null));

        await(() -> coordinator.failed.size() == 2, Duration.ofSeconds(10));

        assertThat(coordinator.failed.get("bad-input").retryable()).isFalse();
        assertThat(coordinator.failed.get("bad-input").errorCode()).isEqualTo("MISSING_DATA");
        assertThat(coordinator.failed.get("unknown").errorCode()).isEqualTo("NO_EXECUTOR");
        assertThat(coordinator.failed.get("unknown").retryable()).isTrue();
    }

    @Test
    void localTimeoutStopsTheTaskAndReportsTimeout() throws Exception {
        coordinator.pending.add(FakeCoordinator.assignment("slow", "monte-carlo-pi@1.0.0",
                Map.of("samples", 2_000_000_000), 1));
        start(1, TaskExecutorRegistry.load(null));

        await(() -> coordinator.failed.containsKey("slow"), Duration.ofSeconds(15));
        assertThat(coordinator.failed.get("slow").errorCode()).isEqualTo("TIMEOUT");
        assertThat(coordinator.failed.get("slow").retryable()).isTrue();
        assertThat(coordinator.completed).isEmpty();
    }

    @Test
    void revokedAttemptIsAbandonedSilently() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        TaskExecutor endless = new TaskExecutor() {
            public String taskType() { return "endless"; }
            public String version() { return "1.0.0"; }
            public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
                started.countDown();
                while (!context.isCancelled()) {
                    Thread.onSpinWait();
                }
                throw TaskFailure.cancelled();
            }
        };
        coordinator.pending.add(FakeCoordinator.assignment("doomed", "endless@1.0.0", Map.of(), 60));
        start(1, new TaskExecutorRegistry(Map.of("endless@1.0.0", endless)));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        coordinator.revoked.add("doomed");
        await(() -> runtime.activeAttempts() == 0, Duration.ofSeconds(5));

        assertThat(coordinator.failed).doesNotContainKey("doomed");
        assertThat(coordinator.completed).doesNotContainKey("doomed");
    }

    @Test
    void stopDeregisters() throws Exception {
        start(1, TaskExecutorRegistry.load(null));
        runtime.stop();
        assertThat(coordinator.deregistered).isTrue();
    }
}
