package com.example.nqueens;

import com.queuepilot.task.ProgressTracker;
import com.queuepilot.task.TaskContext;
import com.queuepilot.task.TaskExecutor;
import com.queuepilot.task.TaskFailure;
import com.queuepilot.task.TaskInput;
import com.queuepilot.task.TaskOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * Solves the N-Queens problem: place n queens on an n x n board so that no two attack each other.
 *
 * <p>Counts all solutions with bitmask backtracking and returns the first solution found. The
 * search is split into one branch per (row 0, row 1) queen placement, and progress is reported
 * after each branch, so it reflects the real search.</p>
 *
 * <p>With {@code requireSolution = true} a board without any solution (n = 2 or 3) is a permanent
 * failure, which makes this task handy for demonstrating failed jobs.</p>
 */
public class NQueensTask implements TaskExecutor {

    @Override
    public String taskType() {
        return "n-queens";
    }


    @Override
    public TaskOutput execute(TaskInput input, TaskContext context) throws TaskFailure {
        int n = input.getInt("n", 0);
        if (n < 1 || n > 16) {
            throw TaskFailure.permanent("INVALID_N", "n must be between 1 and 16");
        }
        int[] first = firstSolution(n);
        long count = countSolutions(n, context);
        if (count == 0 && input.getBoolean("requireSolution", false)) {
            throw TaskFailure.permanent("NO_SOLUTION", "There is no way to place " + n + " queens on a " + n + "x" + n + " board");
        }

        TaskOutput output = TaskOutput.create()
                .put("n", n)
                .put("solutionCount", count);
        if (first != null) {
            List<Integer> columns = new ArrayList<>();
            for (int column : first) {
                columns.add(column);
            }
            output.put("firstSolution", columns).put("board", board(first));
        }
        return output;
    }

    /** Counts all solutions; each first-two-rows placement is one unit of progress. */
    private long countSolutions(int n, TaskContext context) throws TaskFailure {
        int all = (1 << n) - 1;
        if (n == 1) {
            return 1;
        }
        ProgressTracker tracker = new ProgressTracker(context, (long) n * n);
        long count = 0;
        long branches = 0;
        for (int c0 = 0; c0 < n; c0++) {
            int bit0 = 1 << c0;
            for (int c1 = 0; c1 < n; c1++) {
                int bit1 = 1 << c1;
                boolean attacked = (bit1 & (bit0 | (bit0 << 1) | (bit0 >> 1))) != 0;
                if (!attacked) {
                    int columns = bit0 | bit1;
                    int leftDiagonals = ((bit0 << 1) | bit1) << 1;
                    int rightDiagonals = ((bit0 >> 1) | bit1) >> 1;
                    count += solve(all, columns, leftDiagonals & all, rightDiagonals);
                }
                branches++;
                tracker.advance(branches, "Searched " + branches + " of " + (n * n) + " opening placements");
            }
        }
        return count;
    }

    /** Classic bitmask backtracking: each set bit in "free" is a column where a queen can go in the next row. */
    private static long solve(int all, int columns, int leftDiagonals, int rightDiagonals) {
        if (columns == all) {
            return 1;
        }
        long count = 0;
        int free = all & ~(columns | leftDiagonals | rightDiagonals);
        while (free != 0) {
            int bit = free & -free;
            free -= bit;
            count += solve(all, columns | bit, ((leftDiagonals | bit) << 1) & all, (rightDiagonals | bit) >> 1);
        }
        return count;
    }

    /** First solution in lexicographic order, as the queen's column for each row; null if none exists. */
    private static int[] firstSolution(int n) {
        int[] queens = new int[n];
        return place(queens, 0, n) ? queens : null;
    }

    private static boolean place(int[] queens, int row, int n) {
        if (row == n) {
            return true;
        }
        for (int column = 0; column < n; column++) {
            if (safe(queens, row, column)) {
                queens[row] = column;
                if (place(queens, row + 1, n)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean safe(int[] queens, int row, int column) {
        for (int r = 0; r < row; r++) {
            if (queens[r] == column || Math.abs(queens[r] - column) == row - r) {
                return false;
            }
        }
        return true;
    }

    private static List<String> board(int[] queens) {
        List<String> rows = new ArrayList<>();
        for (int queen : queens) {
            StringBuilder row = new StringBuilder();
            for (int column = 0; column < queens.length; column++) {
                row.append(column == queen ? "Q" : ".");
                if (column < queens.length - 1) {
                    row.append(' ');
                }
            }
            rows.add(row.toString());
        }
        return rows;
    }
}
