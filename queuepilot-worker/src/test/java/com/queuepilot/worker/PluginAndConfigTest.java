package com.queuepilot.worker;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginAndConfigTest {

    @TempDir
    Path pluginsDir;

    @Test
    void loadsBuiltinTasksFromTheClasspath() throws Exception {
        assertThat(TaskExecutorRegistry.load(null).taskTypes())
                .containsExactly("file-checksum", "javascript", "monte-carlo-pi", "sorting", "text-analysis");
    }

    @Test
    void loadsAnExternalPluginJarDroppedIntoThePluginsFolder() throws Exception {
        Path target = Path.of("../queuepilot-example-plugin/target");
        Path jar = null;
        if (Files.isDirectory(target)) {
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(target, "queuepilot-example-plugin-*.jar")) {
                for (Path candidate : jars) {
                    jar = candidate;
                }
            }
        }
        Assumptions.assumeTrue(jar != null, "example plugin jar not built yet (run mvn package)");
        Files.copy(jar, pluginsDir.resolve("prime-sieve.jar"));

        try (TaskExecutorRegistry registry = TaskExecutorRegistry.load(pluginsDir)) {
            assertThat(registry.taskTypes()).contains("prime-sieve", "sorting");
            var output = registry.find("prime-sieve").orElseThrow()
                    .execute(new com.queuepilot.task.TaskInput(Map.of("limit", 100)), new NoOpContext());
            assertThat(output.values().get("primeCount")).isEqualTo(25L);
        }
    }

    @Test
    void parsesCommandLineOptions() {
        Map<String, String> options = WorkerConfig.parse(new String[]{"--name", "w1", "--slots=3", "--verbose"});
        assertThat(options).containsEntry("name", "w1").containsEntry("slots", "3").containsEntry("verbose", "true");
        assertThatThrownBy(() -> WorkerConfig.parse(new String[]{"oops"})).isInstanceOf(IllegalArgumentException.class);
    }


    private static final class NoOpContext implements com.queuepilot.task.TaskContext {
        public void reportProgress(int percent, String message) { }
        public boolean isCancelled() { return false; }
        public Path workDirectory() { return Path.of("."); }
        public Path inputFile(String artifactId) { throw new UnsupportedOperationException(); }
        public String saveOutputFile(Path file, String contentType) { throw new UnsupportedOperationException(); }
    }
}
