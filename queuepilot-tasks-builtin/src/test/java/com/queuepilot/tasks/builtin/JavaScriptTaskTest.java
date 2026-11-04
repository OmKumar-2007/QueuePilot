package com.queuepilot.tasks.builtin;

import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs real scripts with Node.js; skipped (not passed) when node is not installed. */
class JavaScriptTaskTest {

    @TempDir
    Path workDir;

    @BeforeAll
    static void requireNode() {
        boolean found;
        try {
            found = new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            found = false;
        }
        Assumptions.assumeTrue(found, "Node.js is not installed");
    }

    private TaskOutput run(String code, String inputJson) throws Exception {
        FakeTaskContext context = new FakeTaskContext(workDir);
        Path script = Files.writeString(Files.createTempFile("user", ".js"), code);
        context.inputs.put("script-1", script);
        return new JavaScriptTask().execute(new TaskInput(Map.of("script", "script-1", "input", inputJson)), context);
    }

    @Test
    @DisplayName("runs the user's run() function and returns its result, logs and progress")
    void runsUserFunction() throws Exception {
        FakeTaskContext context = new FakeTaskContext(workDir);
        Path script = Files.writeString(Files.createTempFile("user", ".js"), """
                function run(input, progress) {
                  progress(50, 'half way');
                  console.log('hello from js');
                  return { doubled: input.n * 2 };
                }
                """);
        context.inputs.put("s", script);
        TaskOutput output = new JavaScriptTask().execute(new TaskInput(Map.of("script", "s", "input", "{\"n\": 21}")), context);

        assertThat(output.values().get("result")).isEqualTo(Map.of("doubled", 42));
        assertThat(output.values().get("logs")).isEqualTo(List.of("hello from js"));
        assertThat(context.progress).contains(50);
    }

    @Test
    @DisplayName("supports async functions and module.exports")
    void supportsAsyncAndModuleExports() throws Exception {
        TaskOutput output = run("module.exports = async (input) => input.words.join(' ');", "{\"words\": [\"a\", \"b\"]}");
        assertThat(output.values().get("result")).isEqualTo("a b");
    }

    @Test
    @DisplayName("an exception thrown by the script fails the job permanently with the error message")
    void scriptErrorIsPermanentFailure() {
        assertThatThrownBy(() -> run("function run() { throw new Error('boom'); }", "{}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.isRetryable()).isFalse();
                    assertThat(f.getMessage()).contains("boom");
                });
    }

    @Test
    @DisplayName("invalid JSON input is rejected before running the script")
    void badJsonInput() {
        assertThatThrownBy(() -> run("function run() { return 1; }", "{not json"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.getCode()).isEqualTo("BAD_INPUT"));
    }
}
