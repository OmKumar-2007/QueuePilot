package com.queuepilot.coordinator.task;

/** How a task's code is run by workers (ADR-005). */
public enum ExecutorKind {
    /** A trusted Java plugin loaded by the worker. */
    JAVA_PLUGIN,
    /** A container image run by the worker inside a sandbox. */
    CONTAINER
}
