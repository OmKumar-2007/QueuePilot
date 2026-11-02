package com.queuepilot.tasks.builtin;

import com.queuepilot.task.TaskContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory TaskContext for unit tests: records progress, serves local files as artifacts. */
class FakeTaskContext implements TaskContext {

    final List<Integer> progress = new ArrayList<>();
    final Map<String, Path> inputs = new HashMap<>();
    final Map<String, Path> outputs = new HashMap<>();
    final Path workDir;
    volatile boolean cancelled;

    FakeTaskContext(Path workDir) {
        this.workDir = workDir;
    }

    @Override
    public void reportProgress(int percent, String message) {
        progress.add(percent);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Path workDirectory() {
        return workDir;
    }

    @Override
    public Path inputFile(String artifactId) throws IOException {
        Path path = inputs.get(artifactId);
        if (path == null) {
            throw new IOException("Unknown artifact " + artifactId);
        }
        return path;
    }

    @Override
    public String saveOutputFile(Path file, String contentType) throws IOException {
        String id = "out-" + outputs.size();
        Path copy = Files.createTempFile("qp-output", ".bin");
        Files.copy(file, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        outputs.put(id, copy);
        return id;
    }

    boolean progressIsMonotonicAndEndsAt100() {
        for (int i = 1; i < progress.size(); i++) {
            if (progress.get(i) < progress.get(i - 1)) {
                return false;
            }
        }
        return !progress.isEmpty() && progress.get(progress.size() - 1) == 100;
    }
}
