package tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import taskapi.TaskExecutor.ProgressTracker;
import taskapi.TaskExecutor.TaskContext;
import taskapi.TaskExecutor.TaskFailure;
import taskapi.TaskExecutor.TaskInput;
import taskapi.TaskExecutor.TaskOutput;
import taskapi.TaskExecutor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/** The task types that ship with QueuePilot. Each one is listed in META-INF/services/taskapi.TaskExecutor. */
public final class BuiltinTasks {

    private BuiltinTasks() {
    }

    /**
     * Sorts either the given numbers or {@code count} pseudo-random numbers generated from {@code seed}.
     * Progress comes from the algorithm itself: merge passes for MERGE, heap extractions for HEAP.
     */
    public static class SortingTask implements TaskExecutor {

        private static final int RETURN_SORTED_LIMIT = 1_000;

        @Override
        public String taskType() {
            return "sorting";
        }

        @Override
        public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
            long[] data = loadData(input);
            String algorithm = input.getString("algorithm", "MERGE");
            long started = System.nanoTime();

            switch (algorithm) {
                case "MERGE" -> mergeSort(data, context);
                case "HEAP" -> heapSort(data, context);
                default -> throw TaskFailure.permanent("UNSUPPORTED_ALGORITHM", "Unknown algorithm " + algorithm);
            }

            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            TaskOutput output = TaskOutput.create()
                    .put("count", data.length)
                    .put("algorithm", algorithm)
                    .put("min", data.length == 0 ? null : data[0])
                    .put("max", data.length == 0 ? null : data[data.length - 1])
                    .put("verifiedSorted", isSorted(data))
                    .put("sortMs", elapsedMs);
            if (data.length <= RETURN_SORTED_LIMIT) {
                output.put("sorted", Arrays.stream(data).boxed().toList());
            } else {
                output.put("first10", Arrays.stream(data, 0, 10).boxed().toList());
            }
            return output;
        }

        private long[] loadData(TaskInput input) throws TaskFailure {
            if (input.has("numbers")) {
                List<Long> numbers = input.getLongList("numbers");
                return numbers.stream().mapToLong(Long::longValue).toArray();
            }
            if (!input.has("count")) {
                throw TaskFailure.permanent("MISSING_DATA", "Provide either 'numbers' or 'count'");
            }
            int count = input.getInt("count", 0);
            SplittableRandom random = new SplittableRandom(input.getLong("seed", 42));
            long[] data = new long[count];
            for (int i = 0; i < count; i++) {
                data[i] = random.nextLong(1_000_000_000L);
            }
            return data;
        }

        /** Bottom-up merge sort; each pass doubles the width of sorted runs. */
        private void mergeSort(long[] data, TaskContext context) throws TaskFailure {
            int n = data.length;
            int passes = n <= 1 ? 1 : 64 - Long.numberOfLeadingZeros(n - 1);
            ProgressTracker tracker = new ProgressTracker(context, (long) passes * n);
            long[] source = data;
            long[] target = new long[n];
            long done = 0;
            int pass = 0;
            for (int width = 1; width < n; width *= 2) {
                pass++;
                for (int left = 0; left < n; left += 2 * width) {
                    int mid = Math.min(left + width, n);
                    int right = Math.min(left + 2 * width, n);
                    merge(source, target, left, mid, right);
                    done += right - left;
                    tracker.advance(done, "Merge pass " + pass + " of " + passes);
                }
                long[] swap = source;
                source = target;
                target = swap;
            }
            if (source != data) {
                System.arraycopy(source, 0, data, 0, n);
            }
            tracker.advance((long) passes * n, "Sorted " + n + " numbers");
        }

        private static void merge(long[] source, long[] target, int left, int mid, int right) {
            int i = left;
            int j = mid;
            for (int k = left; k < right; k++) {
                if (i < mid && (j >= right || source[i] <= source[j])) {
                    target[k] = source[i++];
                } else {
                    target[k] = source[j++];
                }
            }
        }

        private void heapSort(long[] data, TaskContext context) throws TaskFailure {
            int n = data.length;
            ProgressTracker tracker = new ProgressTracker(context, n);
            for (int i = n / 2 - 1; i >= 0; i--) {
                siftDown(data, i, n);
            }
            for (int end = n - 1; end > 0; end--) {
                long top = data[0];
                data[0] = data[end];
                data[end] = top;
                siftDown(data, 0, end);
                tracker.advance(n - end, "Extracted " + (n - end) + " of " + n);
            }
            tracker.advance(n, "Sorted " + n + " numbers");
        }

        private static void siftDown(long[] heap, int index, int size) {
            while (true) {
                int largest = index;
                int left = 2 * index + 1;
                int right = left + 1;
                if (left < size && heap[left] > heap[largest]) {
                    largest = left;
                }
                if (right < size && heap[right] > heap[largest]) {
                    largest = right;
                }
                if (largest == index) {
                    return;
                }
                long swap = heap[index];
                heap[index] = heap[largest];
                heap[largest] = swap;
                index = largest;
            }
        }

        private static boolean isSorted(long[] data) {
            for (int i = 1; i < data.length; i++) {
                if (data[i - 1] > data[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Word statistics for inline text or an uploaded text file. Always writes the complete word
     * frequency table as a CSV output artifact.
     */
    public static class TextAnalysisTask implements TaskExecutor {

        @Override
        public String taskType() {
            return "text-analysis";
        }

        @Override
        public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
            int topN = input.getInt("topN", 10);
            Map<String, Integer> frequencies = new HashMap<>();
            Counts counts = new Counts();

            try (BufferedReader reader = openReader(input, context)) {
                ProgressTracker tracker = new ProgressTracker(context, Math.max(1, sourceLength(input, context)), 0, 90);
                long consumed = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    analyseLine(line, frequencies, counts);
                    consumed += line.length() + 1;
                    tracker.advance(consumed, "Analysed " + counts.lines + " lines");
                }
            } catch (IOException e) {
                throw new TaskFailure("IO_ERROR", "Could not read input: " + e.getMessage(), true, e);
            }

            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(frequencies.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            context.reportProgress(95, "Writing frequency table");
            String csvArtifact = writeFrequencyCsv(sorted, context);

            List<Map<String, Object>> top = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : sorted.subList(0, Math.min(topN, sorted.size()))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("word", entry.getKey());
                row.put("count", entry.getValue());
                top.add(row);
            }
            return TaskOutput.create()
                    .put("lineCount", counts.lines)
                    .put("wordCount", counts.words)
                    .put("uniqueWords", frequencies.size())
                    .put("sentenceCount", counts.sentences)
                    .put("characterCount", counts.characters)
                    .put("averageWordLength", counts.words == 0 ? 0 : Math.round(100.0 * counts.letters / counts.words) / 100.0)
                    .put("topWords", top)
                    .put("frequencyTableArtifact", csvArtifact)
                    .addArtifact(csvArtifact);
        }

        private static final class Counts {
            long lines;
            long words;
            long sentences;
            long characters;
            long letters;
        }

        private static void analyseLine(String line, Map<String, Integer> frequencies, Counts counts) {
            counts.lines++;
            counts.characters += line.length();
            StringBuilder word = new StringBuilder();
            for (int i = 0; i <= line.length(); i++) {
                char c = i < line.length() ? Character.toLowerCase(line.charAt(i)) : ' ';
                if (Character.isLetterOrDigit(c) || (c == '\'' && word.length() > 0)) {
                    word.append(c);
                    continue;
                }
                if (c == '.' || c == '!' || c == '?') {
                    counts.sentences++;
                }
                if (word.length() > 0) {
                    counts.words++;
                    counts.letters += word.length();
                    frequencies.merge(word.toString(), 1, Integer::sum);
                    word.setLength(0);
                }
            }
        }

        private BufferedReader openReader(TaskInput input, TaskContext context) throws IOException, TaskFailure {
            if (input.has("file")) {
                return Files.newBufferedReader(context.inputFile(input.getString("file", null)), StandardCharsets.UTF_8);
            }
            if (input.has("text")) {
                return new BufferedReader(new StringReader(input.getString("text", "")));
            }
            throw TaskFailure.permanent("MISSING_DATA", "Provide either 'text' or 'file'");
        }

        private long sourceLength(TaskInput input, TaskContext context) throws IOException {
            if (input.has("file")) {
                return Files.size(context.inputFile(input.getString("file", null)));
            }
            return input.getString("text", "").length();
        }

        private String writeFrequencyCsv(List<Map.Entry<String, Integer>> sorted, TaskContext context) throws TaskFailure {
            Path csv = context.workDirectory().resolve("word-frequencies.csv");
            try (Writer writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                writer.write("word,count\n");
                for (Map.Entry<String, Integer> entry : sorted) {
                    writer.write(entry.getKey() + "," + entry.getValue() + "\n");
                }
            } catch (IOException e) {
                throw new TaskFailure("IO_ERROR", "Could not write CSV: " + e.getMessage(), true, e);
            }
            try {
                return context.saveOutputFile(csv, "text/csv");
            } catch (IOException e) {
                throw new TaskFailure("UPLOAD_FAILED", "Could not upload CSV: " + e.getMessage(), true, e);
            }
        }
    }

    /**
     * Estimates pi by sampling random points in the unit square. CPU-bound, and its run time scales
     * linearly with {@code samples}, which makes it a good benchmark workload.
     */
    public static class MonteCarloPiTask implements TaskExecutor {

        private static final long CHUNK = 1_000_000;

        @Override
        public String taskType() {
            return "monte-carlo-pi";
        }

        @Override
        public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
            long samples = input.getLong("samples", 0);
            if (samples <= 0) {
                throw TaskFailure.permanent("INVALID_SAMPLES", "samples must be positive");
            }
            SplittableRandom random = new SplittableRandom(input.getLong("seed", 7));
            ProgressTracker tracker = new ProgressTracker(context, samples);

            long inside = 0;
            long done = 0;
            while (done < samples) {
                long chunk = Math.min(CHUNK, samples - done);
                for (long i = 0; i < chunk; i++) {
                    double x = random.nextDouble();
                    double y = random.nextDouble();
                    if (x * x + y * y <= 1.0) {
                        inside++;
                    }
                }
                done += chunk;
                tracker.advance(done, String.format("%,d of %,d samples", done, samples));
            }

            double estimate = 4.0 * inside / samples;
            return TaskOutput.create()
                    .put("samples", samples)
                    .put("insideCircle", inside)
                    .put("estimate", estimate)
                    .put("absoluteError", Math.abs(estimate - Math.PI));
        }
    }

    /** Computes a cryptographic digest of an uploaded file, reading it in 64 KB blocks. */
    public static class FileChecksumTask implements TaskExecutor {

        @Override
        public String taskType() {
            return "file-checksum";
        }

        @Override
        public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
            String algorithm = input.getString("algorithm", "SHA-256");
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw TaskFailure.permanent("UNSUPPORTED_ALGORITHM", "Unknown digest " + algorithm);
            }

            try {
                Path file = context.inputFile(input.getString("file", null));
                long size = Files.size(file);
                ProgressTracker tracker = new ProgressTracker(context, Math.max(1, size));
                byte[] buffer = new byte[64 * 1024];
                long read = 0;
                try (InputStream in = Files.newInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        digest.update(buffer, 0, n);
                        read += n;
                        tracker.advance(read, String.format("Hashed %,d of %,d bytes", read, size));
                    }
                }
                return TaskOutput.create()
                        .put("algorithm", algorithm)
                        .put("sizeBytes", size)
                        .put("digest", HexFormat.of().formatHex(digest.digest()));
            } catch (IOException e) {
                throw new TaskFailure("IO_ERROR", "Could not read file: " + e.getMessage(), true, e);
            }
        }
    }

    /**
     * Runs a user-supplied JavaScript file with Node.js, so users can bring their own job logic
     * without writing a Java plugin.
     *
     * <p>The uploaded script must define a function {@code run(input, progress)} (or assign it to
     * {@code module.exports}). {@code input} is the parsed JSON from the job's {@code input} field,
     * {@code progress(percent, message)} reports progress, and the value returned (or resolved, if the
     * function is async) becomes the job's {@code result}.</p>
     *
     * <p>Not sandboxed: the script runs with the worker's permissions. That is fine for this
     * local demo but not for untrusted users.</p>
     */
    public static class JavaScriptTask implements TaskExecutor {

        /** Appended after the user's script: calls run(), prints progress lines, writes the result file. */
        private static final String HARNESS = """

                ;(async () => {
                  const fs = require('fs');
                  const fn = typeof run === 'function' ? run : module.exports;
                  if (typeof fn !== 'function') throw new Error('The script must define a function run(input, progress)');
                  const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                  const progress = (percent, message) => console.log('PROGRESS ' + Math.round(percent) + ' ' + (message || ''));
                  const result = await fn(input, progress);
                  fs.writeFileSync(process.argv[3], JSON.stringify(result === undefined ? null : result));
                })().catch(e => { console.error(e && e.stack ? e.stack : String(e)); process.exit(1); });
                """;
        private static final int MAX_LOG_LINES = 50;

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String taskType() {
            return "javascript";
        }

        @Override
        public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
            String inputJson = input.getString("input", "{}");
            try {
                mapper.readTree(inputJson);
            } catch (IOException e) {
                throw TaskFailure.permanent("BAD_INPUT", "'input' is not valid JSON: " + e.getMessage());
            }

            Path dir = context.workDirectory();
            try {
                Files.createDirectories(dir);
                Path script = dir.resolve("job.js");
                Path inputFile = dir.resolve("input.json");
                Path resultFile = dir.resolve("result.json");
                String userCode = Files.readString(context.inputFile(input.getString("script", null)));
                Files.writeString(script, userCode + HARNESS);
                Files.writeString(inputFile, inputJson);

                Process process = startNode(script, inputFile, resultFile);
                List<String> logs = new ArrayList<>();
                // Read the script's output on a separate thread so this thread can watch for cancellation.
                Thread reader = Thread.ofVirtual().start(() -> readOutput(process, context, logs));
                try {
                    while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                        if (context.isCancelled()) {
                            process.destroyForcibly();
                            throw TaskFailure.cancelled();
                        }
                    }
                    reader.join();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    throw TaskFailure.cancelled();
                }

                if (process.exitValue() != 0) {
                    throw TaskFailure.permanent("SCRIPT_ERROR", "Script exited with code " + process.exitValue()
                            + (logs.isEmpty() ? "" : ": " + String.join("\n", logs)));
                }
                Object result = mapper.readValue(resultFile.toFile(), new TypeReference<Object>() {
                });
                return TaskOutput.create().put("result", result).put("logs", logs);
            } catch (IOException e) {
                throw new TaskFailure("IO_ERROR", e.getMessage(), true, e);
            }
        }

        private static Process startNode(Path script, Path inputFile, Path resultFile) throws TaskFailure {
            try {
                return new ProcessBuilder("node", script.toString(), inputFile.toString(), resultFile.toString())
                        .redirectErrorStream(true).start();
            } catch (IOException e) {
                throw TaskFailure.permanent("NODE_NOT_FOUND", "Node.js ('node') must be installed on the worker machine");
            }
        }

        /** Forwards "PROGRESS n message" lines to the coordinator and keeps the rest as logs. */
        private static void readOutput(Process process, TaskContext context, List<String> logs) {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    String[] parts = line.split(" ", 3);
                    if (parts[0].equals("PROGRESS") && parts.length >= 2 && parts[1].matches("\\d{1,3}")) {
                        context.reportProgress(Math.min(100, Integer.parseInt(parts[1])), parts.length == 3 ? parts[2] : "");
                    } else if (logs.size() < MAX_LOG_LINES) {
                        logs.add(line);
                    }
                }
            } catch (IOException ignored) {
                // The process was killed; nothing more to read.
            }
        }
    }
}
