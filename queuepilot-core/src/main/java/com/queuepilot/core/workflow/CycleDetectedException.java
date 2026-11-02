package com.queuepilot.core.workflow;

import java.util.List;

/** Thrown when a set of dependencies contains a cycle, e.g. A -> B -> A. */
public class CycleDetectedException extends RuntimeException {

    private final List<String> cycle;

    public CycleDetectedException(List<String> cycle) {
        super("Dependency cycle: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> getCycle() {
        return cycle;
    }
}
