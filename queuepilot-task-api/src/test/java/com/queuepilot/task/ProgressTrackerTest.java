package com.queuepilot.task;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTrackerTest {

    private static class RecordingContext implements TaskContext {
        final List<Integer> reported = new ArrayList<>();
        boolean cancelled;

        public void reportProgress(int percent, String message) { reported.add(percent); }
        public boolean isCancelled() { return cancelled; }
        public Path workDirectory() { return Path.of("."); }
        public Path inputFile(String artifactId) { throw new UnsupportedOperationException(); }
        public String saveOutputFile(Path file, String contentType) { throw new UnsupportedOperationException(); }
    }

    @Test
    void reportsEachPercentageOnceAndInOrder() throws TaskFailure {
        RecordingContext context = new RecordingContext();
        ProgressTracker tracker = new ProgressTracker(context, 1_000);

        for (int done = 0; done <= 1_000; done++) {
            tracker.advance(done, "working");
        }

        assertEquals(101, context.reported.size());
        assertEquals(0, context.reported.get(0));
        assertEquals(100, context.reported.get(100));
    }

    @Test
    void mapsOntoAPhaseRange() throws TaskFailure {
        RecordingContext context = new RecordingContext();
        ProgressTracker tracker = new ProgressTracker(context, 10, 50, 100);

        tracker.advance(5, "half");
        tracker.advance(10, "done");

        assertEquals(List.of(75, 100), context.reported);
    }

    @Test
    void throwsRetryableCancellationWhenContextIsCancelled() {
        RecordingContext context = new RecordingContext();
        context.cancelled = true;
        ProgressTracker tracker = new ProgressTracker(context, 10);

        TaskFailure failure = assertThrows(TaskFailure.class, () -> tracker.advance(1, "x"));
        assertEquals(TaskFailure.CANCELLED, failure.getCode());
        assertTrue(context.reported.isEmpty());
    }

    @Test
    void taskInputConvertsJsonNumbers() {
        TaskInput input = new TaskInput(Map.of("count", 5, "big", 5_000_000_000L, "ratio", 0.5,
                "items", List.of(3, 1L, 2)));

        assertEquals(5, input.getInt("count", 0));
        assertEquals(5_000_000_000L, input.getLong("big", 0));
        assertEquals(0.5, input.getDouble("ratio", 0));
        assertEquals(List.of(3L, 1L, 2L), input.getLongList("items"));
        assertEquals("fallback", input.getString("missing", "fallback"));
    }
}
