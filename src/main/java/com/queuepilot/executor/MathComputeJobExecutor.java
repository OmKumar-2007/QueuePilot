package com.queuepilot.executor;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.JobType;
import com.queuepilot.model.MathComputeJob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Concrete executor for mathematical and scientific computations.
 */
public class MathComputeJobExecutor implements JobExecutor {

    @Override
    public JobType getSupportedType() {
        return JobType.MATH_COMPUTE;
    }

    @Override
    public JobResult execute(Job job, ProgressCallback progressCallback, HeartbeatCallback heartbeatCallback) throws Exception {
        if (!(job instanceof MathComputeJob mathJob)) {
            throw new IllegalArgumentException("Unsupported job class: " + job.getClass().getName());
        }

        long startTime = System.currentTimeMillis();
        long targetDurationMs = Math.max(100L, mathJob.getEstimatedDurationMs());
        int totalSteps = 10;
        long stepDelayMs = Math.max(10L, targetDurationMs / totalSteps);

        String type = mathJob.getComputationType();
        progressCallback.onProgress(0, "Initiating " + type + " computation");
        heartbeatCallback.sendHeartbeat();

        Map<String, Object> output = new HashMap<>();

        if ("PRIME_FACTORIZATION".equalsIgnoreCase(type)) {
            long n = mathJob.getInputParam();
            List<Long> factors = new ArrayList<>();
            long temp = n;

            for (int step = 1; step <= totalSteps; step++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Worker thread interrupted during prime factorization");
                }

                // Simulate incremental factorization pass
                if (step == 1) {
                    while (temp % 2 == 0) {
                        factors.add(2L);
                        temp /= 2;
                    }
                }
                long limit = (long) Math.sqrt(n);
                long stepStart = 3 + (step - 1) * (limit / totalSteps);
                long stepEnd = Math.min(limit, 3 + step * (limit / totalSteps));

                for (long d = Math.max(3, stepStart); d <= stepEnd; d += 2) {
                    while (temp % d == 0) {
                        factors.add(d);
                        temp /= d;
                    }
                }

                Thread.sleep(stepDelayMs);
                int currentPercent = (int) (((double) step / totalSteps) * 95);
                progressCallback.onProgress(currentPercent, String.format("Factorization window %d-%d scanned", stepStart, stepEnd));
                heartbeatCallback.sendHeartbeat();
            }

            if (temp > 2) {
                factors.add(temp);
            }
            output.put("inputNumber", n);
            output.put("primeFactors", factors);
        } else {
            // Default to Monte Carlo Pi simulation
            long totalSamples = mathJob.getInputParam();
            long insideCircle = 0;
            Random rand = new Random();

            for (int step = 1; step <= totalSteps; step++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Worker thread interrupted during Monte Carlo simulation");
                }

                long batchSamples = Math.max(1000L, totalSamples / totalSteps);
                for (long i = 0; i < batchSamples; i++) {
                    double x = rand.nextDouble();
                    double y = rand.nextDouble();
                    if (x * x + y * y <= 1.0) {
                        insideCircle++;
                    }
                }

                Thread.sleep(stepDelayMs);
                int currentPercent = (int) (((double) step / totalSteps) * 95);
                double currentPi = 4.0 * insideCircle / ((long) step * batchSamples);
                progressCallback.onProgress(currentPercent, String.format("Monte Carlo batch %d/%d (Approx π ≈ %.5f)", step, totalSteps, currentPi));
                heartbeatCallback.sendHeartbeat();
            }

            double estimatedPi = 4.0 * insideCircle / totalSamples;
            output.put("totalSamples", totalSamples);
            output.put("estimatedPi", estimatedPi);
            output.put("absoluteError", Math.abs(Math.PI - estimatedPi));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        progressCallback.onProgress(100, type + " successfully completed in " + (elapsed / 1000.0) + "s");
        output.put("actualDurationMs", elapsed);

        return JobResult.success(type + " computation finished", elapsed)
                .outputData(output)
                .build();
    }
}
