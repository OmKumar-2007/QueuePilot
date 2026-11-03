package com.example.nqueens;

import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NQueensTaskTest {

    private static class Context implements TaskContext {
        final List<Integer> progress = new ArrayList<>();
        public void reportProgress(int percent, String message) { progress.add(percent); }
        public boolean isCancelled() { return false; }
        public Path workDirectory() { return Path.of("."); }
        public Path inputFile(String artifactId) { throw new UnsupportedOperationException(); }
        public String saveOutputFile(Path file, String contentType) { throw new UnsupportedOperationException(); }
    }

    private static TaskOutput run(Map<String, Object> input, Context context) throws TaskFailure {
        return new NQueensTask().execute(new TaskInput(input), context);
    }

    @ParameterizedTest(name = "{0} queens have {1} solutions")
    @DisplayName("Counts match the known N-Queens sequence (OEIS A000170)")
    @CsvSource({"1,1", "2,0", "3,0", "4,2", "5,10", "6,4", "7,40", "8,92", "9,352", "10,724", "12,14200"})
    void countsMatchKnownValues(int n, long expected) throws TaskFailure {
        assertThat(run(Map.of("n", n), new Context()).values().get("solutionCount")).isEqualTo(expected);
    }

    @Test
    @DisplayName("The first solution is a valid placement and the board shows it")
    void firstSolutionIsValid() throws TaskFailure {
        Context context = new Context();
        TaskOutput output = run(Map.of("n", 8), context);

        @SuppressWarnings("unchecked")
        List<Integer> queens = (List<Integer>) output.values().get("firstSolution");
        assertThat(queens).containsExactly(0, 4, 7, 5, 2, 6, 1, 3);
        for (int a = 0; a < queens.size(); a++) {
            for (int b = a + 1; b < queens.size(); b++) {
                assertThat(queens.get(a)).isNotEqualTo(queens.get(b));
                assertThat(Math.abs(queens.get(a) - queens.get(b))).isNotEqualTo(b - a);
            }
        }
        assertThat(output.values().get("board")).asList().first().isEqualTo("Q . . . . . . .");
        assertThat(context.progress).isSorted().endsWith(100);
    }

    @Test
    @DisplayName("requireSolution turns an unsolvable board into a permanent failure")
    void unsolvableBoardCanFail() throws TaskFailure {
        assertThat(run(Map.of("n", 3), new Context()).values().get("solutionCount")).isEqualTo(0L);
        assertThatThrownBy(() -> run(Map.of("n", 3, "requireSolution", true), new Context()))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.getCode()).isEqualTo("NO_SOLUTION");
                    assertThat(f.isRetryable()).isFalse();
                });
    }
}
