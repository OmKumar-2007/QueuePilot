package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol.ContainerSpec;
import com.queuepilot.worker.container.DockerCommandBuilder;
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
        assertThat(TaskExecutorRegistry.load(null).identifiers())
                .containsExactly("file-checksum@1.0.0", "monte-carlo-pi@1.0.0", "sorting@1.0.0", "text-analysis@1.0.0");
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
            assertThat(registry.identifiers()).contains("prime-sieve@1.0.0", "sorting@1.0.0");
            var output = registry.find("prime-sieve@1.0.0").orElseThrow()
                    .execute(new com.queuepilot.task.TaskInput(Map.of("limit", 100)), new NoOpContext());
            assertThat(output.values().get("primeCount")).isEqualTo(25L);
        }
    }

    @Test
    void parsesCommandLineOptions() {
        Map<String, String> options = WorkerConfig.parse(new String[]{"--name", "w1", "--slots=3", "--containers"});
        assertThat(options).containsEntry("name", "w1").containsEntry("slots", "3").containsEntry("containers", "true");
        assertThatThrownBy(() -> WorkerConfig.parse(new String[]{"oops"})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dockerCommandAppliesEverySandboxRestriction() {
        ContainerSpec spec = new ContainerSpec("python@sha256:abc", List.of("python", "/app/run.py"), 500, 256);
        List<String> command = DockerCommandBuilder.build("docker", spec, Path.of("/tmp/attempt-1"), "qp-attempt-1");
        String joined = String.join(" ", command);

        assertThat(joined).contains("--network none", "--read-only", "--cpus 0.50", "--memory 256m",
                "--memory-swap 256m", "--pids-limit 64", "--user 65534:65534", "--cap-drop ALL",
                "--security-opt no-new-privileges", "target=/work", "--rm");
        assertThat(joined).doesNotContain("docker.sock").doesNotContain("--privileged");
        assertThat(command.subList(command.size() - 3, command.size()))
                .containsExactly("python@sha256:abc", "python", "/app/run.py");
        assertThatThrownBy(() -> DockerCommandBuilder.build("docker",
                new ContainerSpec("python:latest", List.of("x"), 500, 256), Path.of("/tmp"), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class NoOpContext implements com.queuepilot.task.TaskContext {
        public void reportProgress(int percent, String message) { }
        public boolean isCancelled() { return false; }
        public Path workDirectory() { return Path.of("."); }
        public Path inputFile(String artifactId) { throw new UnsupportedOperationException(); }
        public String saveOutputFile(Path file, String contentType) { throw new UnsupportedOperationException(); }
    }
}
