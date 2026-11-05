package coordinator;

import coordinator.EventLog.EventView;
import core.Jobs.AttemptState;
import core.Jobs.JobState;
import core.Jobs.WorkerStatus;
import core.Scheduling.JobTiming;
import core.Scheduling.MetricsCalculator;
import core.Scheduling.ScheduleMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Records system events. Callers invoke it inside their own transaction, so an event is stored
 * if and only if the change it describes is stored.
 */
@Service
class EventLog {

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

/** Live numbers for the overview page, computed from persisted data (F15, NFR2). */
@Service
class MetricsService {

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

/**
 * Permanently deletes all logs and the finished jobs that nothing still needs. A finished job is
 * kept while an unfinished job depends on it, or while its workflow is unfinished: a BLOCKED job
 * only starts once it can see all its prerequisites SUCCEEDED, so removing one would strand it.
 */
@Service
class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private static final String ACTIVE = "('BLOCKED', 'QUEUED', 'RUNNING')";
    private static final String REMOVABLE_JOBS = """
            select j.id from job j
            where j.state not in %1$s
              and not exists (select 1 from job_dependency d join job dependant on dependant.id = d.job_id
                              where d.prerequisite_id = j.id and dependant.state in %1$s)
              and not exists (select 1 from job sibling
                              where sibling.workflow_id = j.workflow_id and sibling.state in %1$s)
            """.formatted(ACTIVE);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ArtifactService artifacts;

    public HistoryService(JdbcTemplate jdbc, TransactionTemplate transactions, ArtifactService artifacts) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.artifacts = artifacts;
    }

    public record Cleared(int jobs, int logs, int files, int jobsKept) {
    }

    private record Removed(Cleared cleared, List<String> fileIds) {
    }

    public Cleared clear() {
        Removed removed = transactions.execute(status -> removeRows());
        // Only after the commit: a rolled-back clear must not have lost any files.
        artifacts.deleteFiles(removed.fileIds());
        log.info("History cleared: {}", removed.cleared());
        return removed.cleared();
    }

    private Removed removeRows() {
        List<String> jobIds = jdbc.queryForList(REMOVABLE_JOBS, String.class);
        List<String> fileIds = new ArrayList<>();
        for (String jobId : jobIds) {
            fileIds.addAll(jdbc.queryForList("select id from artifact where job_id = ?", String.class, jobId));
        }
        List<Object[]> args = jobIds.stream().map(id -> new Object[] {id}).toList();
        jdbc.batchUpdate("delete from attempt where job_id = ?", args);
        jdbc.batchUpdate("delete from artifact where job_id = ?", args);
        jdbc.batchUpdate("delete from job_dependency where job_id = ?", args);
        jdbc.batchUpdate("delete from job_dependency where prerequisite_id = ?", args);
        jdbc.batchUpdate("delete from job where id = ?", args);
        jdbc.update("delete from workflow where id not in (select workflow_id from job where workflow_id is not null)");
        int logs = jdbc.update("delete from system_event");
        Integer kept = jdbc.queryForObject("select count(*) from job where state not in " + ACTIVE, Integer.class);
        return new Removed(new Cleared(jobIds.size(), logs, fileIds.size(), kept), fileIds);
    }
}
