package com.queuepilot.executor;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.JobType;
import com.queuepilot.model.TextAnalysisJob;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concrete executor for text processing, tokenization, and regex matching.
 */
public class TextAnalysisJobExecutor implements JobExecutor {

    @Override
    public JobType getSupportedType() {
        return JobType.TEXT_ANALYSIS;
    }

    @Override
    public JobResult execute(Job job, ProgressCallback progressCallback, HeartbeatCallback heartbeatCallback) throws Exception {
        if (!(job instanceof TextAnalysisJob textJob)) {
            throw new IllegalArgumentException("Unsupported job class: " + job.getClass().getName());
        }

        long startTime = System.currentTimeMillis();
        long targetDurationMs = Math.max(100L, textJob.getEstimatedDurationMs());
        int totalSteps = 10;
        long stepDelayMs = Math.max(10L, targetDurationMs / totalSteps);

        String corpus = textJob.getTextCorpus();
        progressCallback.onProgress(0, "Analyzing corpus of " + corpus.length() + " characters");
        heartbeatCallback.sendHeartbeat();

        Map<String, Integer> keywordCounts = new HashMap<>();
        for (String kw : textJob.getTargetKeywords()) {
            keywordCounts.put(kw.toLowerCase(), 0);
        }

        String[] words = corpus.toLowerCase().split("\\W+");
        int totalWords = words.length;

        for (int step = 1; step <= totalSteps; step++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Worker thread interrupted during text analysis step " + step);
            }

            int fromIdx = (step - 1) * (totalWords / totalSteps);
            int toIdx = (step == totalSteps) ? totalWords : step * (totalWords / totalSteps);

            for (int i = fromIdx; i < toIdx && i < totalWords; i++) {
                String word = words[i];
                if (keywordCounts.containsKey(word)) {
                    keywordCounts.put(word, keywordCounts.get(word) + 1);
                }
            }

            Thread.sleep(stepDelayMs);

            int currentPercent = (int) (((double) step / totalSteps) * 95);
            progressCallback.onProgress(currentPercent, String.format("Analyzed batch %d/%d (%d words)", step, totalSteps, toIdx));
            heartbeatCallback.sendHeartbeat();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        progressCallback.onProgress(100, "Text analysis complete in " + (elapsed / 1000.0) + "s");

        Map<String, Object> output = new HashMap<>();
        output.put("totalWords", totalWords);
        output.put("characterCount", corpus.length());
        output.put("keywordFrequencies", keywordCounts);
        output.put("analysisMode", textJob.getAnalysisMode());
        output.put("actualDurationMs", elapsed);

        return JobResult.success("Analyzed " + totalWords + " words across corpus", elapsed)
                .outputData(output)
                .build();
    }
}
