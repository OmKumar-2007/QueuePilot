package taskapi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single contract every task implementation fulfils.
 *
 * <p>Implementations are discovered by workers through {@link java.util.ServiceLoader}
 * (a line in {@code META-INF/services/taskapi.TaskExecutor}). One instance is
 * shared by all worker threads, so implementations must keep per-execution state in local
 * variables, never in fields.</p>
 */
public interface TaskExecutor {

    /** Task type identifier, e.g. {@code "sorting"}. Must match the registered manifest. */
    String taskType();

    /**
     * Performs the work. Called on a worker thread.
     *
     * @param input   input already validated by the coordinator against the manifest schema
     * @param context progress reporting, cancellation and file access for this attempt
     * @return the result; it becomes the job output
     * @throws TaskFailure when the task cannot complete; {@link TaskFailure#isRetryable()} decides
     *                     whether the coordinator may try again
     */
    TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure;

    /**
     * Services a worker offers to a running task. One context exists per execution attempt.
     */
    interface TaskContext {

        /**
         * Reports real progress from the task's own work loop. The worker throttles network calls,
         * so tasks may call this often.
         */
        void reportProgress(int percent, String message);

        /**
         * True when the attempt was revoked, timed out or the worker is shutting down.
         * Long loops must check this (or use {@link ProgressTracker}) and stop early.
         */
        boolean isCancelled();

        /** A private, empty directory for this attempt; deleted after the attempt ends. */
        Path workDirectory();

        /** Downloads (once) and returns a local copy of an input artifact referenced by the job input. */
        Path inputFile(String artifactId) throws IOException;

        /** Uploads a file as an output artifact of the job and returns its artifact id. */
        String saveOutputFile(Path file, String contentType) throws IOException;
    }

    /**
     * Read-only view of a job's JSON input with typed accessors.
     * Values arrive as the JSON parser produced them (Integer, Long, Double, String, Boolean, List).
     */
    final class TaskInput {

        private final Map<String, Object> values;

        public TaskInput(Map<String, Object> values) {
            this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public boolean has(String name) {
            return values.get(name) != null;
        }

        public String getString(String name, String defaultValue) {
            Object value = values.get(name);
            return value == null ? defaultValue : value.toString();
        }

        public long getLong(String name, long defaultValue) {
            Object value = values.get(name);
            return value instanceof Number number ? number.longValue() : defaultValue;
        }

        public int getInt(String name, int defaultValue) {
            return (int) getLong(name, defaultValue);
        }

        public double getDouble(String name, double defaultValue) {
            Object value = values.get(name);
            return value instanceof Number number ? number.doubleValue() : defaultValue;
        }

        public boolean getBoolean(String name, boolean defaultValue) {
            Object value = values.get(name);
            return value instanceof Boolean bool ? bool : defaultValue;
        }

        public List<Long> getLongList(String name) {
            List<Long> result = new ArrayList<>();
            if (values.get(name) instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Number number) {
                        result.add(number.longValue());
                    }
                }
            }
            return result;
        }

        public List<String> getStringList(String name) {
            List<String> result = new ArrayList<>();
            if (values.get(name) instanceof List<?> list) {
                for (Object item : list) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }

        public Map<String, Object> asMap() {
            return values;
        }
    }

    /**
     * Result of a successful execution: a small JSON-compatible map plus ids of uploaded output files.
     * Values must be JSON-friendly (numbers, strings, booleans, lists, maps).
     */
    final class TaskOutput {

        private final Map<String, Object> values = new LinkedHashMap<>();
        private final List<String> artifactIds = new ArrayList<>();

        public static TaskOutput create() {
            return new TaskOutput();
        }

        public TaskOutput put(String key, Object value) {
            values.put(key, value);
            return this;
        }

        public TaskOutput addArtifact(String artifactId) {
            artifactIds.add(artifactId);
            return this;
        }

        public Map<String, Object> values() {
            return Collections.unmodifiableMap(values);
        }

        public List<String> artifactIds() {
            return Collections.unmodifiableList(artifactIds);
        }
    }

    /**
     * Signals that a task could not complete.
     *
     * <p>Retryable failures (e.g. a transient I/O error) let the coordinator schedule another
     * attempt; permanent failures (e.g. input that is valid by schema but unusable) fail the job
     * immediately.</p>
     */
    class TaskFailure extends Exception {

        public static final String CANCELLED = "CANCELLED";

        private final String code;
        private final boolean retryable;

        public TaskFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public TaskFailure(String code, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }

        public static TaskFailure permanent(String code, String message) {
            return new TaskFailure(code, message, false);
        }

        public static TaskFailure retryable(String code, String message) {
            return new TaskFailure(code, message, true);
        }

        public static TaskFailure cancelled() {
            return new TaskFailure(CANCELLED, "Execution was cancelled", true);
        }

        public String getCode() {
            return code;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    /**
     * Turns "units of work done" into percentage progress and checks for cancellation.
     *
     * <p>Create one per execution (it holds state) and call {@link #advance} from the work loop.
     * Progress is only forwarded when the whole-number percentage increases, so calling it
     * millions of times is cheap.</p>
     */
    final class ProgressTracker {

        private final TaskContext context;
        private final long totalUnits;
        private final int fromPercent;
        private final int toPercent;
        private int lastPercent = -1;

        /** Maps 0..totalUnits onto 0..100 %. */
        public ProgressTracker(TaskContext context, long totalUnits) {
            this(context, totalUnits, 0, 100);
        }

        /** Maps 0..totalUnits onto fromPercent..toPercent, for tasks with several phases. */
        public ProgressTracker(TaskContext context, long totalUnits, int fromPercent, int toPercent) {
            this.context = context;
            this.totalUnits = Math.max(1, totalUnits);
            this.fromPercent = fromPercent;
            this.toPercent = toPercent;
        }

        public void advance(long unitsDone, String message) throws TaskFailure {
            if (context.isCancelled()) {
                throw TaskFailure.cancelled();
            }
            long bounded = Math.min(unitsDone, totalUnits);
            int percent = fromPercent + (int) ((toPercent - fromPercent) * bounded / totalUnits);
            if (percent > lastPercent) {
                lastPercent = percent;
                context.reportProgress(percent, message);
            }
        }
    }
}
