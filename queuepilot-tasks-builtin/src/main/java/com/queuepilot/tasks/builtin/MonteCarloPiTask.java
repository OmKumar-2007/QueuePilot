package com.queuepilot.tasks.builtin;

import com.queuepilot.task.ProgressTracker;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.util.SplittableRandom;

/**
 * Estimates pi by sampling random points in the unit square. CPU-bound, and its run time scales
 * linearly with {@code samples}, which makes it a good benchmark workload.
 */
public class MonteCarloPiTask implements TaskExecutor {

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
