package com.queuepilot.tasks.builtin;

import com.queuepilot.task.ProgressTracker;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Sorts either the given numbers or {@code count} pseudo-random numbers generated from {@code seed}.
 * Progress comes from the algorithm itself: merge passes for MERGE, heap extractions for HEAP.
 */
public class SortingTask implements TaskExecutor {

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
