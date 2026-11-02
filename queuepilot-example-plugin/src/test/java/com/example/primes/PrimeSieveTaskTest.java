package com.example.primes;

import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrimeSieveTaskTest {

    private static class Context implements TaskContext {
        final List<Integer> progress = new ArrayList<>();
        public void reportProgress(int percent, String message) { progress.add(percent); }
        public boolean isCancelled() { return false; }
        public Path workDirectory() { return Path.of("."); }
        public Path inputFile(String artifactId) { throw new UnsupportedOperationException(); }
        public String saveOutputFile(Path file, String contentType) { throw new UnsupportedOperationException(); }
    }

    @Test
    void countsPrimesBelowOneMillion() throws TaskFailure {
        Context context = new Context();
        TaskOutput output = new PrimeSieveTask().execute(new TaskInput(Map.of("limit", 1_000_000)), context);

        assertThat(output.values().get("primeCount")).isEqualTo(78_498L);
        assertThat(output.values().get("largestPrime")).isEqualTo(999_983);
        assertThat(context.progress.get(context.progress.size() - 1)).isEqualTo(100);
    }
}
