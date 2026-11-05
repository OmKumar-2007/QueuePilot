package worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import taskapi.TaskExecutor;
import worker.CoordinatorApi.HttpCoordinatorClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Entry point of a worker process. */
public final class WorkerMain {

    private WorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(WorkerConfig.USAGE);
            return;
        }
        WorkerConfig config;
        try {
            config = WorkerConfig.fromArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(WorkerConfig.USAGE);
            System.exit(2);
            return;
        }

        TaskExecutorRegistry registry = TaskExecutorRegistry.load(config.pluginsDir());
        WorkerRuntime runtime = new WorkerRuntime(config, new HttpCoordinatorClient(config.coordinatorUrl()), registry);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop, "qp-shutdown"));
        try {
            runtime.start();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

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

    /**
     * Registry of the task implementations this worker can run, keyed by task type.
     *
     * <p>Built-in tasks come from the worker's own classpath; extra plugin jars are loaded from the
     * plugins directory. Both are discovered with {@link ServiceLoader}, so adding a jar is all it
     * takes — no QueuePilot code changes. Only the administrator controls that directory (ADR-005).</p>
     */
    public static class TaskExecutorRegistry implements AutoCloseable {

        private static final Logger log = LoggerFactory.getLogger(TaskExecutorRegistry.class);

        private final Map<String, TaskExecutor> executors;
        private final ClassLoader loader;

        TaskExecutorRegistry(Map<String, TaskExecutor> executors) {
            this(executors, null);
        }

        private TaskExecutorRegistry(Map<String, TaskExecutor> executors, ClassLoader loader) {
            this.executors = Collections.unmodifiableMap(new TreeMap<>(executors));
            this.loader = loader;
        }

        public static TaskExecutorRegistry load(Path pluginsDir) throws IOException {
            ClassLoader loader = pluginClassLoader(pluginsDir);
            Map<String, TaskExecutor> found = new TreeMap<>();
            for (TaskExecutor executor : ServiceLoader.load(TaskExecutor.class, loader)) {
                TaskExecutor previous = found.putIfAbsent(executor.taskType(), executor);
                if (previous != null) {
                    log.warn("Ignoring duplicate implementation of {} ({})", executor.taskType(), executor.getClass().getName());
                }
            }
            log.info("Loaded task implementations: {}", found.keySet());
            return new TaskExecutorRegistry(found, loader);
        }

        private static ClassLoader pluginClassLoader(Path pluginsDir) throws IOException {
            ClassLoader parent = TaskExecutor.class.getClassLoader();
            if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
                return parent;
            }
            List<URL> jars = new ArrayList<>();
            try (Stream<Path> files = Files.list(pluginsDir)) {
                for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    jars.add(toUrl(jar));
                    log.info("Plugin jar: {}", jar.getFileName());
                }
            }
            return jars.isEmpty() ? parent : new URLClassLoader(jars.toArray(URL[]::new), parent);
        }

        private static URL toUrl(Path jar) {
            try {
                return jar.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Bad plugin path " + jar, e);
            }
        }

        public Optional<TaskExecutor> find(String taskType) {
            return Optional.ofNullable(executors.get(taskType));
        }

        public List<String> taskTypes() {
            return List.copyOf(executors.keySet());
        }

        /** Releases plugin jar files (on Windows an open class loader keeps them locked). */
        @Override
        public void close() throws IOException {
            if (loader instanceof URLClassLoader urlLoader) {
                urlLoader.close();
            }
        }
    }
}
