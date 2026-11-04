package com.queuepilot.coordinator.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Records system events. Callers invoke it inside their own transaction, so an event is stored
 * if and only if the change it describes is stored.
 */
@Service
public class EventLog {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);

    private final SystemEventRepository repository;
    private final Clock clock;

    public EventLog(SystemEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void record(EventType type, String jobId, String workerId, String attemptId, String message) {
        log.info("[{}] {}", type, message);
        repository.save(new SystemEventEntity(clock.instant(), type, jobId, workerId, attemptId, message));
    }

    public List<EventView> recent(int limit) {
        return recent(limit, null);
    }

    /** Newest first. With {@code beforeId}, the page of events older than that id, so callers can walk all of history. */
    public List<EventView> recent(int limit, Long beforeId) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<SystemEventEntity> events = beforeId == null
                ? repository.findAllByOrderByIdDesc(page)
                : repository.findByIdLessThanOrderByIdDesc(beforeId, page);
        return events.stream().map(EventView::from).toList();
    }

    public List<EventView> forJob(String jobId) {
        return repository.findByJobIdOrderByIdAsc(jobId).stream().map(EventView::from).toList();
    }

    public record EventView(long id, Instant occurredAt, EventType type, String status, String jobId, String workerId,
                            String attemptId, String message) {
        static EventView from(SystemEventEntity e) {
            return new EventView(e.getId(), e.getOccurredAt(), e.getType(), EventStatus.of(e.getType(), e.getMessage()),
                    e.getJobId(), e.getWorkerId(), e.getAttemptId(), e.getMessage());
        }
    }
}
