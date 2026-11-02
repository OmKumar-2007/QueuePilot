package com.queuepilot.worker.container;

import com.queuepilot.core.protocol.WorkerProtocol.ContainerSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the sandbox against a real Docker daemon. Skipped (not passed!) when Docker is not
 * available; the test report shows these as skipped so nobody mistakes them for verified.
 */
class ContainerIsolationTest {

    private static String image;

    @TempDir
    Path workDir;

    @BeforeAll
    static void requireDocker() throws Exception {
        Assumptions.assumeTrue(new ContainerTaskRunner("docker").dockerAvailable(), "Docker is not available");
        run(List.of("docker", "pull", "alpine:3.20"));
        String digest = run(List.of("docker", "inspect", "--format", "{{index .RepoDigests 0}}", "alpine:3.20")).trim();
        Assumptions.assumeTrue(digest.contains("@sha256:"), "Could not resolve image digest");
        image = digest;
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(120, TimeUnit.SECONDS);
        return output;
    }

    private int exitCodeOf(String shell) throws Exception {
        List<String> command = new ArrayList<>(DockerCommandBuilder.build("docker",
                new ContainerSpec(image, List.of("sh", "-c", shell), 500, 64), workDir, "qp-test-" + System.nanoTime()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }

    @Test
    void hasNoNetwork() throws Exception {
        assertThat(exitCodeOf("wget -q -T 3 -O /dev/null http://example.com")).isNotZero();
    }

    @Test
    void rootFilesystemIsReadOnlyButWorkDirIsWritable() throws Exception {
        assertThat(exitCodeOf("touch /etc/qp-test")).isNotZero();
        assertThat(exitCodeOf("echo '{}' > /work/output.json")).isZero();
        assertThat(Files.exists(workDir.resolve("output.json"))).isTrue();
    }

    @Test
    void runsAsUnprivilegedUser() throws Exception {
        assertThat(exitCodeOf("test \"$(id -u)\" = 65534")).isZero();
    }

    @Test
    void processCountIsLimited() throws Exception {
        assertThat(exitCodeOf("for i in $(seq 1 200); do sleep 5 & done; wait")).isNotZero();
    }
}
