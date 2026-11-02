package com.queuepilot.worker.container;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.core.protocol.WorkerProtocol.ContainerSpec;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;
import com.queuepilot.worker.WorkerTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a CONTAINER task (Phase 9). Contract with the image:
 * <ul>
 *   <li>input is the file {@code /work/input.json}</li>
 *   <li>output must be written to {@code /work/output.json} (a JSON object, at most 256 KB)</li>
 *   <li>stdout lines {@code PROGRESS <0-100> <message>} are forwarded as progress</li>
 *   <li>exit code 0 = success</li>
 * </ul>
 */
public class ContainerTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(ContainerTaskRunner.class);
    private static final Pattern PROGRESS = Pattern.compile("PROGRESS\\s+(\\d{1,3})\\s*(.*)");
    private static final long MAX_OUTPUT_BYTES = 256 * 1024;
    private static final int MAX_LOGGED_LINES = 200;

    private final String docker;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContainerTaskRunner(String docker) {
        this.docker = docker;
    }

    public boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder(docker, "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public TaskOutput run(ContainerSpec spec, TaskInput input, WorkerTaskContext context) throws TaskFailure {
        Path workDir = context.workDirectory();
        String containerName = "qp-" + workDir.getFileName();
        try {
            Files.createDirectories(workDir);
            mapper.writeValue(workDir.resolve("input.json").toFile(), input.asMap());
            Process process = new ProcessBuilder(DockerCommandBuilder.build(docker, spec, workDir, containerName))
                    .redirectErrorStream(true).start();
            // Reading stdout blocks and ignores interrupts, so a separate watcher kills the
            // container as soon as the attempt is cancelled (timeout, revoke, shutdown).
            Thread watcher = Thread.ofVirtual().start(() -> {
                while (process.isAlive()) {
                    if (context.isCancelled()) {
                        kill(containerName);
                        process.destroyForcibly();
                        return;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            try {
                forwardOutput(process, context);
                int exit = process.waitFor();
                if (context.isCancelled()) {
                    throw TaskFailure.cancelled();
                }
                if (exit != 0) {
                    // 125-127 are Docker/launch errors (image missing, daemon down): worth retrying elsewhere.
                    throw new TaskFailure("CONTAINER_EXIT_" + exit, "Container exited with code " + exit, exit >= 125);
                }
                return readOutput(workDir.resolve("output.json"));
            } catch (InterruptedException e) {
                throw TaskFailure.cancelled();
            } finally {
                watcher.interrupt();
                if (process.isAlive()) {
                    kill(containerName);
                    process.destroyForcibly();
                }
            }
        } catch (IOException e) {
            throw new TaskFailure("CONTAINER_ERROR", "Could not run container: " + e.getMessage(), true, e);
        }
    }

    private void forwardOutput(Process process, WorkerTaskContext context) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int logged = 0;
            while ((line = reader.readLine()) != null) {
                if (context.isCancelled()) {
                    return;
                }
                Matcher matcher = PROGRESS.matcher(line);
                if (matcher.matches()) {
                    context.reportProgress(Math.min(100, Integer.parseInt(matcher.group(1))), matcher.group(2));
                } else if (logged++ < MAX_LOGGED_LINES) {
                    log.info("[container] {}", line.length() > 500 ? line.substring(0, 500) : line);
                }
            }
        }
    }

    private TaskOutput readOutput(Path file) throws TaskFailure, IOException {
        if (!Files.exists(file)) {
            throw TaskFailure.permanent("NO_OUTPUT", "Container did not write /work/output.json");
        }
        if (Files.size(file) > MAX_OUTPUT_BYTES) {
            throw TaskFailure.permanent("OUTPUT_TOO_LARGE", "output.json exceeds " + MAX_OUTPUT_BYTES + " bytes");
        }
        Map<String, Object> values = mapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
        });
        TaskOutput output = TaskOutput.create();
        values.forEach(output::put);
        return output;
    }

    private void kill(String containerName) {
        try {
            new ProcessBuilder(List.of(docker, "kill", containerName)).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.warn("Could not kill container {}: {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
