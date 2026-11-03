package com.queuepilot.worker;

import java.util.function.BooleanSupplier;

/**
 * Lets an administrator pause a worker. While paused, the claim loop takes no new work and running
 * tasks wait at their next progress checkpoint. Heartbeats continue, so the coordinator keeps the
 * leases alive: a paused worker is healthy, just not making progress.
 */
public class PauseGate {

    private boolean paused;

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Blocks while paused, returning early as soon as {@code stopWaiting} becomes true. */
    public synchronized void awaitOpen(BooleanSupplier stopWaiting) throws InterruptedException {
        while (paused && !stopWaiting.getAsBoolean()) {
            wait(100);
        }
    }
}
