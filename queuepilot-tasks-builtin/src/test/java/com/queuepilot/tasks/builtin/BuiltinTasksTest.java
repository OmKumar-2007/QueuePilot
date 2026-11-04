package com.queuepilot.tasks.builtin;

import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltinTasksTest {

    @TempDir
    Path workDir;

    @Test
    void mergeAndHeapSortProduceTheSameCorrectResult() throws TaskFailure {
        for (String algorithm : List.of("MERGE", "HEAP")) {
            FakeTaskContext context = new FakeTaskContext(workDir);
            TaskOutput output = new SortingTask().execute(
                    new TaskInput(Map.of("numbers", List.of(9, -3, 7, 7, 0, 12), "algorithm", algorithm)), context);

            assertThat(output.values().get("sorted")).isEqualTo(List.of(-3L, 0L, 7L, 7L, 9L, 12L));
            assertThat(output.values().get("verifiedSorted")).isEqualTo(true);
            assertThat(context.progressIsMonotonicAndEndsAt100()).isTrue();
        }
    }

    @Test
    void sortsGeneratedDataDeterministically() throws TaskFailure {
        TaskInput input = new TaskInput(Map.of("count", 100_000, "seed", 3));
        TaskOutput first = new SortingTask().execute(input, new FakeTaskContext(workDir));
        TaskOutput second = new SortingTask().execute(input, new FakeTaskContext(workDir));

        assertThat(first.values().get("verifiedSorted")).isEqualTo(true);
        assertThat(first.values().get("first10")).isEqualTo(second.values().get("first10"));
    }

    @Test
    void sortingWithoutDataIsAPermanentFailure() {
        assertThatThrownBy(() -> new SortingTask().execute(new TaskInput(Map.of()), new FakeTaskContext(workDir)))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.isRetryable()).isFalse());
    }

    @Test
    void monteCarloEstimatesPiAndStopsWhenCancelled() throws TaskFailure {
        FakeTaskContext context = new FakeTaskContext(workDir);
        TaskOutput output = new MonteCarloPiTask().execute(new TaskInput(Map.of("samples", 2_000_000)), context);
        assertThat((double) output.values().get("estimate")).isBetween(3.12, 3.16);
        assertThat(context.progressIsMonotonicAndEndsAt100()).isTrue();

        FakeTaskContext cancelled = new FakeTaskContext(workDir);
        cancelled.cancelled = true;
        assertThatThrownBy(() -> new MonteCarloPiTask().execute(new TaskInput(Map.of("samples", 50_000_000)), cancelled))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.getCode()).isEqualTo(TaskFailure.CANCELLED));
    }

    @Test
    void textAnalysisCountsWordsAndWritesCsvArtifact() throws Exception {
        FakeTaskContext context = new FakeTaskContext(workDir);
        String text = "The cat sat. The cat ran!\nA dog? The end.";
        TaskOutput output = new TextAnalysisTask().execute(new TaskInput(Map.of("text", text, "topN", 2)), context);

        assertThat(output.values().get("wordCount")).isEqualTo(10L);
        assertThat(output.values().get("sentenceCount")).isEqualTo(4L);
        assertThat(output.values().get("lineCount")).isEqualTo(2L);
        assertThat(output.values().get("topWords")).isEqualTo(List.of(
                Map.of("word", "the", "count", 3), Map.of("word", "cat", "count", 2)));
        assertThat(output.artifactIds()).hasSize(1);
        assertThat(Files.readString(context.outputs.get(output.artifactIds().get(0)))).startsWith("word,count\nthe,3\ncat,2\n");
    }

    @Test
    void fileChecksumMatchesJdkDigest() throws Exception {
        Path file = workDir.resolve("data.bin");
        byte[] bytes = "QueuePilot checksum test".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        FakeTaskContext context = new FakeTaskContext(workDir);
        context.inputs.put("a1", file);

        TaskOutput output = new FileChecksumTask().execute(new TaskInput(Map.of("file", "a1")), context);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(output.values().get("digest")).isEqualTo(expected);
        assertThat(output.values().get("sizeBytes")).isEqualTo((long) bytes.length);
    }

    @Test
    void allBuiltinsAreDiscoverableThroughServiceLoaderWithManifests() {
        List<String> types = ServiceLoader.load(TaskExecutor.class).stream()
                .map(provider -> provider.get().taskType()).sorted().toList();
        assertThat(types).containsExactly("file-checksum", "javascript", "monte-carlo-pi", "sorting", "text-analysis");
        for (String type : types) {
            assertThat(getClass().getResource("/META-INF/queuepilot/tasks/" + type + ".json")).as(type).isNotNull();
        }
    }
}
