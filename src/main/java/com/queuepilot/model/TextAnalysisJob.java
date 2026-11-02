package com.queuepilot.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Concrete Job subclass for text tokenization, keyword extraction, and frequency analysis.
 */
public class TextAnalysisJob extends Job {

    private final String textCorpus;
    private final List<String> targetKeywords;
    private final String analysisMode;

    public TextAnalysisJob(String name, int priority, long estimatedDurationMs, 
                           String textCorpus, List<String> targetKeywords, String analysisMode) {
        super(name, JobType.TEXT_ANALYSIS, priority, estimatedDurationMs);
        this.textCorpus = (textCorpus != null && !textCorpus.isBlank()) 
                ? textCorpus 
                : generateDefaultCorpus();
        this.targetKeywords = targetKeywords != null ? new ArrayList<>(targetKeywords) : List.of("queue", "pilot", "worker", "schedule", "thread");
        this.analysisMode = (analysisMode != null && !analysisMode.isBlank()) ? analysisMode.toUpperCase() : "WORD_FREQUENCY";
    }

    private static String generateDefaultCorpus() {
        return "QueuePilot is a resilient distributed task execution platform designed for academic and demonstration purposes. "
                + "It showcases scheduling algorithms such as First Come First Served, Shortest Job First, and Priority Aging. "
                + "Concurrent workers pick up tasks from safe priority queues and report heartbeats and progress. "
                + "When a worker fails or terminates abruptly, the recovery watchdog reclaims abandoned leases and retries the job.";
    }

    public String getTextCorpus() {
        return textCorpus;
    }

    public List<String> getTargetKeywords() {
        return Collections.unmodifiableList(targetKeywords);
    }

    public String getAnalysisMode() {
        return analysisMode;
    }

    @Override
    public String getSummary() {
        return String.format("Analyze text (%d chars, mode=%s, %d target keywords, est: %.1fs)",
                textCorpus.length(), analysisMode, targetKeywords.size(), getEstimatedDurationMs() / 1000.0);
    }

    @Override
    public Object getPayload() {
        return String.format("Corpus length: %d chars, mode: %s", textCorpus.length(), analysisMode);
    }
}
