package com.queuepilot.worker;


import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attempts currently executing in this worker. Shared by the pool threads (add/remove), the
 * heartbeat thread (list/revoke) and shutdown, hence a concurrent map.
 */
public class ActiveAttempts {

    private final Map<String, WorkerTaskContext> attempts = new ConcurrentHashMap<>();

    public void add(String attemptId, WorkerTaskContext context) {
        attempts.put(attemptId, context);
    }

    public void remove(String attemptId) {
        attempts.remove(attemptId);
    }

    public List<String> ids() {
        return List.copyOf(attempts.keySet());
    }

    public void cancel(String attemptId, WorkerTaskContext.CancelReason reason) {
        WorkerTaskContext context = attempts.get(attemptId);
        if (context != null) {
            context.cancel(reason);
        }
    }

    public void cancelAll(WorkerTaskContext.CancelReason reason) {
        attempts.values().forEach(context -> context.cancel(reason));
    }

    public int size() {
        return attempts.size();
    }
}
