package com.queuepilot.coordinator.event;

/**
 * The short status shown beside an event in the Logs page, e.g. QUEUED or OFFLINE. It is derived
 * rather than stored, so events recorded before this existed get a status too. The switch has no
 * default: a new {@link EventType} does not compile until it is given a status here.
 */
final class EventStatus {

    private EventStatus() {
    }

    static String of(EventType type, String message) {
        return switch (type) {
            // "Job x submitted -> BLOCKED": the job's initial state ends the message.
            case JOB_SUBMITTED -> lastWord(message);
            case JOB_RELEASED -> "QUEUED";
            case JOB_CLAIMED -> "RUNNING";
            case JOB_SUCCEEDED -> "SUCCEEDED";
            case JOB_RETRY_SCHEDULED -> "RETRYING";
            case JOB_FAILED, ATTEMPT_FAILED -> "FAILED";
            case JOB_CANCELLED -> "CANCELLED";
            case JOB_SKIPPED -> "SKIPPED";
            case ATTEMPT_TIMED_OUT -> "TIMED_OUT";
            case LEASE_EXPIRED -> "LEASE_EXPIRED";
            case STALE_UPDATE_REJECTED -> "REJECTED";
            case WORKER_REGISTERED -> "ONLINE";
            case WORKER_OFFLINE -> "OFFLINE";
            case WORKER_STATUS_CHANGED -> workerStatus(message);
            case FAILURE_SIMULATED -> "SIMULATED";
            case TASK_REGISTERED -> "REGISTERED";
            case WORKFLOW_SUBMITTED -> "SUBMITTED";
            case TASK_STATUS_CHANGED, SCHEDULER_UPDATED -> "UPDATED";
            case BENCHMARK_IMPORTED -> "IMPORTED";
        };
    }

    /** This one event type covers a status change ("from ONLINE to PAUSED"), an interrupt and a process launch. */
    private static String workerStatus(String message) {
        if (message.startsWith("Started worker process")) {
            return "STARTED";
        }
        return message.contains(" interrupted ") ? "INTERRUPTED" : lastWord(message);
    }

    private static String lastWord(String message) {
        return message.substring(message.lastIndexOf(' ') + 1);
    }
}
