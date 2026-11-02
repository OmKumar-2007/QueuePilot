package com.queuepilot.dto;

import com.queuepilot.model.JobType;

import java.time.Instant;
import java.util.List;

public class SubmitJobRequest {

    private String name;
    private JobType type;
    private int priority = 5;
    private long estimatedDurationMs = 15000;
    private List<String> dependencies;

    // Type-specific optional overrides
    private String algorithm;
    private String textCorpus;
    private List<String> targetKeywords;
    private String computationType;
    private Long inputParam;

    private Instant scheduledStartTime;
    private Instant deadline;

    public SubmitJobRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public long getEstimatedDurationMs() { return estimatedDurationMs; }
    public void setEstimatedDurationMs(long estimatedDurationMs) { this.estimatedDurationMs = estimatedDurationMs; }
    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getTextCorpus() { return textCorpus; }
    public void setTextCorpus(String textCorpus) { this.textCorpus = textCorpus; }
    public List<String> getTargetKeywords() { return targetKeywords; }
    public void setTargetKeywords(List<String> targetKeywords) { this.targetKeywords = targetKeywords; }
    public String getComputationType() { return computationType; }
    public void setComputationType(String computationType) { this.computationType = computationType; }
    public Long getInputParam() { return inputParam; }
    public void setInputParam(Long inputParam) { this.inputParam = inputParam; }
    public Instant getScheduledStartTime() { return scheduledStartTime; }
    public void setScheduledStartTime(Instant scheduledStartTime) { this.scheduledStartTime = scheduledStartTime; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
}
