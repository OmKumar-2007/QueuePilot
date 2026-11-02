package com.queuepilot.worker;

import com.queuepilot.core.protocol.WorkerProtocol.AttemptRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attempts currently executing in this worker. Shared by the pool threads (add/remove), the
 * heartbeat thread (list/revoke) and shutdown, hence a concurrent map.
 */
public class ActiveAttempts {

    private record Entry(String fencingToken, WorkerTaskContext context) {
    }

    private final Map<String, Entry> attempts = new ConcurrentHashMap<>();

    public void add(String attemptId, String fencingToken, WorkerTaskContext context) {
        attempts.put(attemptId, new Entry(fencingToken, context));
    }

    public void remove(String attemptId) {
        attempts.remove(attemptId);
    }

    public List<AttemptRef> refs() {
        return attempts.entrySet().stream()
                .map(e -> new AttemptRef(e.getKey(), e.getValue().fencingToken()))
                .toList();
    }

    public void cancel(String attemptId, WorkerTaskContext.CancelReason reason) {
        Entry entry = attempts.get(attemptId);
        if (entry != null) {
            entry.context().cancel(reason);
        }
    }

    public void cancelAll(WorkerTaskContext.CancelReason reason) {
        attempts.values().forEach(entry -> entry.context().cancel(reason));
    }

    public int size() {
        return attempts.size();
    }
}
