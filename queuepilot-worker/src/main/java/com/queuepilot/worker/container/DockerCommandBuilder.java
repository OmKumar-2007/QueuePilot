package com.queuepilot.worker.container;

import com.queuepilot.core.protocol.WorkerProtocol.ContainerSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the {@code docker run} command that sandboxes an untrusted task (F23). Every restriction is
 * explicit here so it can be reviewed and unit-tested:
 * <ul>
 *   <li>no network, read-only root filesystem, small non-executable /tmp</li>
 *   <li>CPU, memory (no swap) and process-count limits</li>
 *   <li>unprivileged user, all Linux capabilities dropped, no privilege escalation</li>
 *   <li>only the attempt's own scratch directory is mounted, at /work</li>
 * </ul>
 * The Docker socket and host directories other than the scratch directory are never mounted.
 */
public final class DockerCommandBuilder {

    public static final String CONTAINER_WORK_DIR = "/work";
    static final int PIDS_LIMIT = 64;
    static final String NOBODY = "65534:65534";

    private DockerCommandBuilder() {
    }

    public static List<String> build(String docker, ContainerSpec spec, Path hostWorkDir, String containerName) {
        if (spec.image() == null || !spec.image().contains("@sha256:")) {
            throw new IllegalArgumentException("Container image must be pinned by digest");
        }
        List<String> command = new ArrayList<>(List.of(
                docker, "run", "--rm",
                "--name", containerName,
                "--network", "none",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--cpus", String.format(Locale.ROOT, "%.2f", spec.cpuMillis() / 1000.0),
                "--memory", spec.memoryMb() + "m",
                "--memory-swap", spec.memoryMb() + "m",
                "--pids-limit", String.valueOf(PIDS_LIMIT),
                "--user", NOBODY,
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--mount", "type=bind,source=" + hostWorkDir.toAbsolutePath() + ",target=" + CONTAINER_WORK_DIR,
                "--workdir", CONTAINER_WORK_DIR,
                spec.image()));
        command.addAll(spec.command());
        return command;
    }
}
