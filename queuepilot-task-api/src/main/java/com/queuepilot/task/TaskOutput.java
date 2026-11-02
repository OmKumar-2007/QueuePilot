package com.queuepilot.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a successful execution: a small JSON-compatible map plus ids of uploaded output files.
 * Values must be JSON-friendly (numbers, strings, booleans, lists, maps).
 */
public final class TaskOutput {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<String> artifactIds = new ArrayList<>();

    public static TaskOutput create() {
        return new TaskOutput();
    }

    public TaskOutput put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public TaskOutput addArtifact(String artifactId) {
        artifactIds.add(artifactId);
        return this;
    }

    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    public List<String> artifactIds() {
        return Collections.unmodifiableList(artifactIds);
    }
}
