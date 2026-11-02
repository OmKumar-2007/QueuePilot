package com.queuepilot.task;

/**
 * Turns "units of work done" into percentage progress and checks for cancellation.
 *
 * <p>Create one per execution (it holds state) and call {@link #advance} from the work loop.
 * Progress is only forwarded when the whole-number percentage increases, so calling it
 * millions of times is cheap.</p>
 */
public final class ProgressTracker {

    private final TaskContext context;
    private final long totalUnits;
    private final int fromPercent;
    private final int toPercent;
    private int lastPercent = -1;

    /** Maps 0..totalUnits onto 0..100 %. */
    public ProgressTracker(TaskContext context, long totalUnits) {
        this(context, totalUnits, 0, 100);
    }

    /** Maps 0..totalUnits onto fromPercent..toPercent, for tasks with several phases. */
    public ProgressTracker(TaskContext context, long totalUnits, int fromPercent, int toPercent) {
        this.context = context;
        this.totalUnits = Math.max(1, totalUnits);
        this.fromPercent = fromPercent;
        this.toPercent = toPercent;
    }

    public void advance(long unitsDone, String message) throws TaskFailure {
        if (context.isCancelled()) {
            throw TaskFailure.cancelled();
        }
        long bounded = Math.min(unitsDone, totalUnits);
        int percent = fromPercent + (int) ((toPercent - fromPercent) * bounded / totalUnits);
        if (percent > lastPercent) {
            lastPercent = percent;
            context.reportProgress(percent, message);
        }
    }
}
