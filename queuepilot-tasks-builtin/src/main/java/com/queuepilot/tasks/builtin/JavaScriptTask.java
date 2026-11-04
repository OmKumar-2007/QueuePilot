package com.queuepilot.tasks.builtin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
public class JavaScriptTask implements TaskExecutor {

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
