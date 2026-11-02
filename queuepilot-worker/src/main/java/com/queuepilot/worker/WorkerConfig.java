package com.queuepilot.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line settings of a worker process, e.g.
 * <pre>java -jar queuepilot-worker.jar --name w1 --slots 2 --capabilities cpu,io,text</pre>
 */
public record WorkerConfig(String coordinatorUrl,
                           String name,
                           int slots,
                           List<String> capabilities,
                           String enrollSecret,
                           Path pluginsDir,
                           Path workDir,
                           boolean containersEnabled,
                           String dockerCommand) {

    public static final String USAGE = """
            Usage: java -jar queuepilot-worker.jar [options]
              --coordinator URL      coordinator base URL (default http://localhost:8080)
              --name NAME            worker name (default worker-<pid>)
              --slots N              concurrent attempts (default 2)
              --capabilities a,b     advertised capabilities (default cpu,io,text)
              --secret-file PATH     enrollment secret file (default ./data/worker-secret.txt);
                                     the QP_WORKER_SECRET environment variable takes precedence
              --plugins DIR          extra plugin jars (default ./plugins)
              --work-dir DIR         scratch space (default ./data/worker-work/<name>)
              --containers           also run CONTAINER tasks with Docker (Phase 9)
              --docker CMD           docker executable (default docker)
            """;

    public static WorkerConfig fromArgs(String[] args) throws IOException {
        Map<String, String> options = parse(args);
        String name = options.getOrDefault("name", "worker-" + ProcessHandle.current().pid());
        String secret = System.getenv("QP_WORKER_SECRET");
        if (secret == null || secret.isBlank()) {
            Path secretFile = Path.of(options.getOrDefault("secret-file", "./data/worker-secret.txt"));
            if (!Files.exists(secretFile)) {
                throw new IllegalArgumentException("No enrollment secret: set QP_WORKER_SECRET or create " + secretFile.toAbsolutePath());
            }
            secret = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
        }
        List<String> capabilities = Arrays.stream(options.getOrDefault("capabilities", "cpu,io,text").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new WorkerConfig(
                stripSlash(options.getOrDefault("coordinator", "http://localhost:8080")),
                name,
                Integer.parseInt(options.getOrDefault("slots", "2")),
                capabilities,
                secret,
                Path.of(options.getOrDefault("plugins", "./plugins")),
                Path.of(options.getOrDefault("work-dir", "./data/worker-work/" + name)),
                options.containsKey("containers"),
                options.getOrDefault("docker", "docker"));
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument " + arg);
            }
            String key = arg.substring(2);
            String value = "true";
            int equals = key.indexOf('=');
            if (equals >= 0) {
                value = key.substring(equals + 1);
                key = key.substring(0, equals);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            options.put(key, value);
        }
        return options;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
