package com.queuepilot.coordinator.scheduler;

import com.queuepilot.coordinator.attempt.AttemptRepository;
import com.queuepilot.coordinator.attempt.AttemptService;
import com.queuepilot.coordinator.job.JobEntity;
import com.queuepilot.coordinator.job.JobRepository;
import com.queuepilot.coordinator.worker.WorkerEntity;
import com.queuepilot.coordinator.worker.WorkerRepository;
import com.queuepilot.core.job.AttemptState;
import com.queuepilot.core.job.JobState;
import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.scheduling.PriorityAgingPolicy;
import com.queuepilot.core.scheduling.QueueRotation;
import com.queuepilot.core.scheduling.SchedulableJob;
import com.queuepilot.core.scheduling.SchedulingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Makes the dispatch decision when a worker asks for work (U3, ADR-006):
 * readiness filter (query) -> task-type filter -> queue rotation -> active policy -> atomic claim.
 *
 * <p>This class only orders and matches; it never looks at task inputs or task code.</p>
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    /** Upper bound of candidates considered per decision; keeps each claim cheap. */
    static final int CANDIDATE_LIMIT = 500;

    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final AttemptRepository attempts;
    private final AttemptService attemptService;
    private final SchedulerSettingsService settings;
    private final QueueRotation rotation;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SchedulerService(JobRepository jobs, WorkerRepository workers, AttemptRepository attempts,
                            AttemptService attemptService, SchedulerSettingsService settings, QueueRotation rotation,
                            TransactionTemplate transactions, Clock clock) {
        this.jobs = jobs;
        this.workers = workers;
        this.attempts = attempts;
        this.attemptService = attemptService;
        this.settings = settings;
        this.rotation = rotation;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Optional<Assignment> claim(String workerId) {
        WorkerEntity worker = workers.findById(workerId).orElse(null);
        if (worker == null || !worker.getStatus().acceptsWork()) {
            return Optional.empty();
        }
        if (attempts.countByWorkerIdAndState(workerId, AttemptState.RUNNING) >= worker.getConcurrencyLimit()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Map<String, JobEntity> eligible = new LinkedHashMap<>();
        for (JobEntity job : jobs.findClaimable(now, PageRequest.of(0, CANDIDATE_LIMIT))) {
            if (worker.canRun(job)) {
                eligible.put(job.getId(), job);
            }
        }
        List<SchedulableJob> candidates = eligible.values().stream().map(JobEntity::toSchedulable).toList();
        for (SchedulableJob candidate : rotation.arrange(candidates, settings.activePolicy(), now)) {
            Optional<Assignment> assignment = tryClaim(candidate.id(), workerId);
            if (assignment.isPresent()) {
                rotation.markServed(candidate.queue());
                return assignment;
            }
        }
        return Optional.empty();
    }

    /** Each claim attempt is its own transaction; losing a race to another worker just moves on. */
    private Optional<Assignment> tryClaim(String jobId, String workerId) {
        try {
            Optional<Assignment> result = transactions.execute(status -> attemptService.startAttempt(jobId, workerId));
            return result == null ? Optional.empty() : result;
        } catch (OptimisticLockingFailureException e) {
            log.debug("Job {} was claimed concurrently; trying the next candidate", jobId);
            return Optional.empty();
        }
    }

    /**
     * Explains the current dispatch order for the dashboard: claimable jobs in the order the next
     * decisions would take them (ignoring which worker asks), plus jobs that are not yet runnable.
     */
    public QueuePreview preview() {
        Instant now = clock.instant();
        SchedulingPolicy policy = settings.activePolicy();
        List<JobEntity> claimable = jobs.findClaimable(now, PageRequest.of(0, CANDIDATE_LIMIT));
        Map<String, JobEntity> byId = new LinkedHashMap<>();
        claimable.forEach(job -> byId.put(job.getId(), job));

        List<QueuePreview.Entry> ready = new ArrayList<>();
        int rank = 1;
        for (SchedulableJob job : rotation.arrange(claimable.stream().map(JobEntity::toSchedulable).toList(), policy, now)) {
            JobEntity entity = byId.get(job.id());
            Long effective = policy instanceof PriorityAgingPolicy aging ? aging.effectivePriority(job, now) : null;
            ready.add(new QueuePreview.Entry(rank++, job.id(), entity.label(), entity.getTaskType(), job.queue(),
                    job.priority(), effective, job.estimatedMs(), job.submittedAt(), "Ready"));
        }

        List<QueuePreview.Entry> waiting = new ArrayList<>();
        PageRequest page = PageRequest.of(0, 100);
        for (JobEntity job : jobs.findByStateOrderBySubmittedAtAsc(JobState.QUEUED, page)) {
            if (job.getReadyAt() != null && job.getReadyAt().isAfter(now)) {
                waiting.add(waitingEntry(job, "Retry delay until " + job.getReadyAt()));
            }
        }
        for (JobEntity job : jobs.findByStateOrderBySubmittedAtAsc(JobState.BLOCKED, page)) {
            waiting.add(waitingEntry(job, "Waiting for " + job.getPrerequisiteIds().size() + " prerequisite(s)"));
        }
        return new QueuePreview(settings.settings(), rotation.lastServedQueue(), ready, waiting);
    }

    private static QueuePreview.Entry waitingEntry(JobEntity job, String reason) {
        return new QueuePreview.Entry(null, job.getId(), job.label(), job.getTaskType(), job.getQueueName(),
                job.getPriority(), null, job.getEstimatedMs(), job.getSubmittedAt(), reason);
    }

    public record QueuePreview(SchedulerSettingsService.SchedulerSettings settings, String lastServedQueue,
                               List<Entry> ready, List<Entry> notReady) {
        public record Entry(Integer rank, String jobId, String name, String task, String queue, int priority,
                            Long effectivePriority, long estimatedMs, Instant submittedAt, String reason) {
        }
    }
}
