package com.queuepilot.coordinator.scheduler;

import com.queuepilot.coordinator.error.Errors.ValidationException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.core.schema.ValidationError;
import com.queuepilot.core.scheduling.PolicyType;
import com.queuepilot.core.scheduling.SchedulingPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the active scheduling policy. The settings live in the database; the policy object is
 * cached and rebuilt on every change so claim requests do not hit the database for it.
 */
@Service
public class SchedulerSettingsService {

    private final SchedulerSettingsRepository repository;
    private final EventLog events;
    private volatile Snapshot cached;

    public SchedulerSettingsService(SchedulerSettingsRepository repository, EventLog events) {
        this.repository = repository;
        this.events = events;
    }

    public record SchedulerSettings(PolicyType activePolicy, int agingIntervalSeconds, int agingStep) {
    }

    private record Snapshot(SchedulerSettings settings, SchedulingPolicy policy) {
    }

    public SchedulerSettings settings() {
        return snapshot().settings();
    }

    public SchedulingPolicy activePolicy() {
        return snapshot().policy();
    }

    @Transactional
    public SchedulerSettings update(SchedulerSettings requested) {
        List<ValidationError> errors = new ArrayList<>();
        if (requested.activePolicy() == null) {
            errors.add(new ValidationError("activePolicy", "must be one of FCFS, PRIORITY_AGING, SJF"));
        }
        if (requested.agingIntervalSeconds() < 1 || requested.agingIntervalSeconds() > 3600) {
            errors.add(new ValidationError("agingIntervalSeconds", "must be 1..3600"));
        }
        if (requested.agingStep() < 0 || requested.agingStep() > 10) {
            errors.add(new ValidationError("agingStep", "must be 0..10"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        SchedulerSettingsEntity entity = repository.findById(SchedulerSettingsEntity.SINGLETON_ID).orElseThrow();
        entity.update(requested.activePolicy(), requested.agingIntervalSeconds(), requested.agingStep());
        events.record(EventType.SCHEDULER_UPDATED, null, null, null, "Scheduler set to " + requested.activePolicy()
                + " (aging " + requested.agingStep() + " per " + requested.agingIntervalSeconds() + "s)");
        cached = build(entity);
        return cached.settings();
    }

    private Snapshot snapshot() {
        Snapshot current = cached;
        if (current == null) {
            current = build(repository.findById(SchedulerSettingsEntity.SINGLETON_ID).orElseThrow());
            cached = current;
        }
        return current;
    }

    private static Snapshot build(SchedulerSettingsEntity e) {
        SchedulerSettings settings = new SchedulerSettings(e.getActivePolicy(), e.getAgingIntervalSeconds(), e.getAgingStep());
        return new Snapshot(settings, e.getActivePolicy().create(Duration.ofSeconds(e.getAgingIntervalSeconds()), e.getAgingStep()));
    }

    /** Forgets the cache; used by tests that reset the database. */
    public void reload() {
        cached = null;
    }
}
