package com.queuepilot.worker;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line settings of a worker process, e.g.
 * <pre>java -jar queuepilot-worker.jar --name w1 --slots 2</pre>
 */
public record WorkerConfig(String coordinatorUrl,
                           String name,
                           int slots,
                           Path pluginsDir,
                           Path workDir,
                           long paceMsPerPercent,
                           long freezeMs) {

    public static final String USAGE = """
            Usage: java -jar queuepilot-worker.jar [options]
              --coordinator URL      coordinator base URL (default http://localhost:8080)
              --name NAME            worker name (default worker-<pid>)
              --slots N              concurrent attempts (default 2)
              --plugins DIR          extra plugin jars (default ./plugins)
              --work-dir DIR         scratch space (default ./data/worker-work/<name>)
              --pace-ms N            demo pacing: wait N ms per percent of progress so jobs can be
                                     watched (default 0 = full speed; never use for benchmarks)
              --freeze-ms N          how long a FREEZE simulation stops heartbeats (default 20000)
            """;

    public static WorkerConfig fromArgs(String[] args) {
        Map<String, String> options = parse(args);
        String name = options.getOrDefault("name", "worker-" + ProcessHandle.current().pid());
        return new WorkerConfig(
                stripSlash(options.getOrDefault("coordinator", "http://localhost:8080")),
                name,
                Integer.parseInt(options.getOrDefault("slots", "2")),
                Path.of(options.getOrDefault("plugins", "./plugins")),
                Path.of(options.getOrDefault("work-dir", "./data/worker-work/" + name)),
                Long.parseLong(options.getOrDefault("pace-ms", "0")),
                Long.parseLong(options.getOrDefault("freeze-ms", "20000")));
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
