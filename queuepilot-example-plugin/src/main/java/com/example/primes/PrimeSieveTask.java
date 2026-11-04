package com.example.primes;

import com.queuepilot.task.ProgressTracker;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.util.BitSet;

/**
 * Example third-party plugin: counts primes up to {@code limit} with the sieve of Eratosthenes.
 * Lives in its own package and module to show that a new task type needs no change to QueuePilot.
 */
public class PrimeSieveTask implements TaskExecutor {

    @Override
    public String taskType() {
        return "prime-sieve";
    }


    @Override
    public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
        int limit = input.getInt("limit", 0);
        if (limit < 2) {
            throw TaskFailure.permanent("INVALID_LIMIT", "limit must be at least 2");
        }

        BitSet composite = new BitSet(limit + 1);
        int root = (int) Math.sqrt(limit);
        ProgressTracker sieving = new ProgressTracker(context, root, 0, 70);
        for (int p = 2; p <= root; p++) {
            if (!composite.get(p)) {
                for (long multiple = (long) p * p; multiple <= limit; multiple += p) {
                    composite.set((int) multiple);
                }
            }
            sieving.advance(p, "Sieving with " + p);
        }

        ProgressTracker counting = new ProgressTracker(context, limit, 70, 100);
        long count = 0;
        int largest = 2;
        for (int n = 2; n <= limit; n++) {
            if (!composite.get(n)) {
                count++;
                largest = n;
            }
            if ((n & 0xFFFF) == 0) {
                counting.advance(n, "Counting primes");
            }
        }
        counting.advance(limit, "Done");

        return TaskOutput.create()
                .put("limit", limit)
                .put("primeCount", count)
                .put("largestPrime", largest);
    }
}
