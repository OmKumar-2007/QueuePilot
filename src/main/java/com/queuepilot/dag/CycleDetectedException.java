package com.queuepilot.dag;

import java.util.List;

public class CycleDetectedException extends RuntimeException {

    private final List<String> cyclePath;

    public CycleDetectedException(String message, List<String> cyclePath) {
        super(message + (cyclePath != null ? " Cycle path: " + String.join(" -> ", cyclePath) : ""));
        this.cyclePath = cyclePath;
    }

    public List<String> getCyclePath() {
        return cyclePath;
    }
}
