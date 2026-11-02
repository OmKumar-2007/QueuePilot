package com.queuepilot.core.job;

/** Thrown when code tries to move a job or attempt into a state the lifecycle does not allow. */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
