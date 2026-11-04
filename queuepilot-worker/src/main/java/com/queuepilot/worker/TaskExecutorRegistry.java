package com.queuepilot.worker;

import com.queuepilot.task.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Registry of the task implementations this worker can run, keyed by task type.
 *
 * <p>Built-in tasks come from the worker's own classpath; extra plugin jars are loaded from the
 * plugins directory. Both are discovered with {@link ServiceLoader}, so adding a jar is all it
 * takes — no QueuePilot code changes. Only the administrator controls that directory (ADR-005).</p>
 */
public class TaskExecutorRegistry implements AutoCloseable {

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
