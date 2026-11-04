package com.queuepilot.coordinator.metrics;

import com.queuepilot.coordinator.attempt.AttemptRepository;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventLog.EventView;
import com.queuepilot.coordinator.job.JobEntity;
import com.queuepilot.coordinator.job.JobRepository;
import com.queuepilot.coordinator.worker.WorkerEntity;
import com.queuepilot.coordinator.worker.WorkerRepository;
import com.queuepilot.core.job.AttemptState;
import com.queuepilot.core.job.JobState;
import com.queuepilot.core.job.WorkerStatus;
import com.queuepilot.core.metrics.JobTiming;
import com.queuepilot.core.metrics.MetricsCalculator;
import com.queuepilot.core.metrics.ScheduleMetrics;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Live numbers for the overview page, computed from persisted data (F15, NFR2). */
@Service
public class MetricsService {

    static final Duration THROUGHPUT_WINDOW = Duration.ofMinutes(5);

    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final AttemptRepository attempts;
    private final EventLog events;
    private final Clock clock;

    public MetricsService(JobRepository jobs, WorkerRepository workers, AttemptRepository attempts, EventLog events, Clock clock) {
        this.jobs = jobs;
        this.workers = workers;
        this.attempts = attempts;
        this.events = events;
        this.clock = clock;
    }

    public record Overview(Instant generatedAt,
                           long totalJobs,
                           Map<JobState, Long> jobsByState,
                           int onlineWorkers,
                           int totalWorkers,
                           int totalSlots,
                           long runningAttempts,
                           double slotUtilization,
                           double throughputPerMinute,
                           ScheduleMetrics recentSucceeded,
                           List<EventView> recentEvents) {
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Instant now = clock.instant();
        Map<JobState, Long> byState = new EnumMap<>(JobState.class);
        for (JobState state : JobState.values()) {
            byState.put(state, 0L);
        }
        long total = 0;
        for (Object[] row : jobs.countByState()) {
            byState.put((JobState) row[0], (Long) row[1]);
            total += (Long) row[1];
        }

        List<WorkerEntity> allWorkers = workers.findAll();
        List<WorkerEntity> online = workers.findByStatusIn(EnumSet.of(WorkerStatus.ONLINE, WorkerStatus.DRAINING, WorkerStatus.PAUSED));
        int slots = online.stream().mapToInt(WorkerEntity::getConcurrencyLimit).sum();
        long running = attempts.countByState(AttemptState.RUNNING);

        List<JobEntity> recent = jobs.findSucceededSince(now.minus(THROUGHPUT_WINDOW), PageRequest.of(0, 1000));
        double perMinute = recent.size() / (double) THROUGHPUT_WINDOW.toMinutes();
        List<JobTiming> timings = recent.stream()
                .filter(job -> job.getStartedAt() != null)
                .map(job -> new JobTiming(job.getSubmittedAt(), job.getStartedAt(), job.getFinishedAt()))
                .toList();

        return new Overview(now, total, byState, online.size(), allWorkers.size(), slots, running,
                slots == 0 ? 0 : Math.min(1.0, running / (double) slots), perMinute,
                MetricsCalculator.compute(timings, Math.max(1, slots)), events.recent(15));
    }
}
