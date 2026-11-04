package com.queuepilot.tasks.builtin;

import com.queuepilot.task.ProgressTracker;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Word statistics for inline text or an uploaded text file. Always writes the complete word
 * frequency table as a CSV output artifact.
 */
public class TextAnalysisTask implements TaskExecutor {

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
