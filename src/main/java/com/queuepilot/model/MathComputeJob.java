package com.queuepilot.model;

/**
 * Concrete Job subclass for heavy mathematical computation (Monte Carlo Pi, Prime Factorization, etc.).
 */
public class MathComputeJob extends Job {

    private final String computationType;
    private final long inputParam;

    public MathComputeJob(String name, int priority, long estimatedDurationMs,
                          String computationType, long inputParam) {
        super(name, JobType.MATH_COMPUTE, priority, estimatedDurationMs);
        this.computationType = (computationType != null && !computationType.isBlank())
                ? computationType.toUpperCase()
                : "MONTE_CARLO_PI";
        this.inputParam = inputParam > 0 ? inputParam : 50_000_000L;
    }

    public String getComputationType() {
        return computationType;
    }

    public long getInputParam() {
        return inputParam;
    }

    @Override
    public String getSummary() {
        return String.format("Compute %s with param %d (est: %.1fs)",
                computationType, inputParam, getEstimatedDurationMs() / 1000.0);
    }

    @Override
    public Object getPayload() {
        return String.format("Type=%s, Param=%d", computationType, inputParam);
    }
}
