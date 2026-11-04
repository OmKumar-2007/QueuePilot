package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol;
import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatResponse;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterRequest;
import com.queuepilot.core.protocol.WorkerProtocol.RegisterResponse;
import com.queuepilot.worker.WorkerTaskContext.CancelReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wires a worker together and runs its three activities:
 * <ul>
 *   <li><b>commands</b> from the coordinator (on heartbeat replies): PAUSE / RESUME, INTERRUPT,
 *       FREEZE (stop heartbeating for a while), KILL (exit abruptly), REREGISTER,</li>
 *   <li><b>claim loop</b> (one thread): asks for work whenever a slot is free,</li>
 *   <li><b>execution pool</b> ({@code slots} threads): runs {@link AttemptRunner}s,</li>
 *   <li><b>heartbeat</b> (scheduled): renews leases of all active attempts and obeys commands.</li>
 * </ul>
 * A {@link Semaphore} with one permit per slot guarantees the worker never holds more attempts than
 * it advertised.
 */
public class WorkerRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

    private final WorkerConfig config;
    private final CoordinatorApi api;
    private final TaskExecutorRegistry registry;
    private final ActiveAttempts active = new ActiveAttempts();
    private final Semaphore slots;
    private final ExecutorService pool;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean();
    private final PauseGate pauseGate = new PauseGate();
    private volatile long frozenUntilMillis;
    /** How long to wait before asking again when there was no work. */
    private static final long CLAIM_POLL_MS = 1000;
    private volatile String workerId;
    private Thread claimThread;

    public WorkerRuntime(WorkerConfig config, CoordinatorApi api, TaskExecutorRegistry registry) {
        this.config = config;
        this.api = api;
        this.registry = registry;
        this.slots = new Semaphore(config.slots());
        this.pool = Executors.newFixedThreadPool(config.slots(), runnable -> {
            Thread thread = new Thread(runnable, "qp-exec-" + config.name());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Registers (retrying until the coordinator is reachable) and starts claiming and heartbeating. */
    public void start() throws InterruptedException {
        RegisterResponse registration = registerWithRetry();
        running.set(true);
        scheduler.scheduleAtFixedRate(this::heartbeatSafely, registration.heartbeatIntervalMs(),
                registration.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
        claimThread = new Thread(this::claimLoop, "qp-claim-" + config.name());
        claimThread.start();
    }

    public String workerId() {
        return workerId;
    }

    public int activeAttempts() {
        return active.size();
    }

    public boolean isPaused() {
        return pauseGate.isPaused();
    }

    public boolean isFrozen() {
        return System.currentTimeMillis() < frozenUntilMillis;
    }

    private RegisterResponse registerWithRetry() throws InterruptedException {
        RegisterRequest request = new RegisterRequest(config.name(), config.slots(), registry.taskTypes(),
                config.paceMsPerPercent());
        long backoff = 1000;
        while (true) {
            try {
                RegisterResponse response = api.register(request);
                workerId = response.workerId();
                log.info("Registered as {} ({}) with {} slots; tasks {}{}", config.name(), workerId,
                        config.slots(), registry.taskTypes(),
                        config.paceMsPerPercent() > 0 ? "; DEMO PACING " + config.paceMsPerPercent() + " ms per percent" : "");
                return response;
            } catch (CoordinatorApi.CoordinatorException e) {
                if (e.status() == 400) {
                    throw new IllegalStateException("Registration rejected: " + e.getMessage());
                }
                log.warn("Coordinator not reachable ({}); retrying in {} ms", e.getMessage(), backoff);
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 15_000);
            }
        }
    }

    private void claimLoop() {
        while (running.get()) {
            try {
                pauseGate.awaitOpen(() -> !running.get());
                slots.acquire();
                Optional<Assignment> assignment = claimSafely();
                if (assignment.isEmpty()) {
                    slots.release();
                    Thread.sleep(CLAIM_POLL_MS);
                    continue;
                }
                pool.submit(new AttemptRunner(assignment.get(), registry, api, active, scheduler,
                        config.workDir(), config.paceMsPerPercent(), pauseGate, slots::release));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Optional<Assignment> claimSafely() {
        try {
            return api.claim();
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Claim failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void heartbeatSafely() {
        if (isFrozen()) {
            return;
        }
        try {
            HeartbeatResponse response = api.heartbeat(active.ids());
            response.revoked().forEach(id -> {
                log.warn("Coordinator revoked attempt {}; stopping it", id);
                active.cancel(id, CancelReason.REVOKED);
            });
            handleCommand(response.command());
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected heartbeat error", e);
        }
    }

    private void handleCommand(String commands) {
        if (commands == null || commands.isBlank()) {
            return;
        }
        for (String command : commands.split(",")) {
            switch (command.trim()) {
                case WorkerProtocol.COMMAND_KILL -> {
                    log.error("KILL received (failure simulation): exiting immediately without reporting");
                    Runtime.getRuntime().halt(137);
                }
                case WorkerProtocol.COMMAND_FREEZE -> {
                    log.error("FREEZE received (failure simulation): no heartbeats for {} ms, tasks keep running", config.freezeMs());
                    frozenUntilMillis = System.currentTimeMillis() + config.freezeMs();
                }
                case WorkerProtocol.COMMAND_PAUSE -> {
                    log.warn("PAUSE received: no new work, running tasks hold at their next checkpoint");
                    pauseGate.pause();
                }
                case WorkerProtocol.COMMAND_RESUME -> {
                    log.info("RESUME received");
                    pauseGate.resume();
                }
                case WorkerProtocol.COMMAND_INTERRUPT -> {
                    log.warn("INTERRUPT received: stopping {} running attempt(s)", active.size());
                    active.cancelAll(CancelReason.INTERRUPTED);
                }
                case WorkerProtocol.COMMAND_REREGISTER -> reregister();
                default -> log.warn("Unknown command {}", command);
            }
        }
    }

    private void reregister() {
        log.warn("Coordinator no longer recognises this worker; re-registering");
        active.cancelAll(CancelReason.REVOKED);
        pauseGate.resume();
        try {
            registerWithRetry();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Graceful shutdown: stop claiming, cancel running attempts, deregister (their jobs are retried). */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        log.info("Shutting down worker {}", config.name());
        if (claimThread != null) {
            claimThread.interrupt();
        }
        active.cancelAll(CancelReason.SHUTDOWN);
        pauseGate.resume();
        pool.shutdownNow();
        scheduler.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
            api.deregister();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CoordinatorApi.CoordinatorException e) {
            log.warn("Deregistration failed: {}", e.getMessage());
        }
    }
}
