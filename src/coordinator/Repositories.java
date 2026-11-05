package coordinator;

import core.Jobs.AttemptState;
import core.Jobs.JobState;
import core.Jobs.WorkerStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface JobRepository extends JpaRepository<JobEntity, String> {

    /** Jobs a worker could claim now: QUEUED and past any retry delay, oldest first. */
    @Query("select j from JobEntity j where j.state = QUEUED "
            + "and j.readyAt <= :now order by j.submittedAt asc")
    List<JobEntity> findClaimable(@Param("now") Instant now, Pageable pageable);

    List<JobEntity> findByStateOrderBySubmittedAtAsc(JobState state, Pageable pageable);

    @Query("select j from JobEntity j join j.prerequisiteIds p where p = :prerequisiteId")
    List<JobEntity> findDependants(@Param("prerequisiteId") String prerequisiteId);

    long countByIdInAndState(Collection<String> ids, JobState state);

    @Query("select count(j) from JobEntity j where j.id in :ids and j.state in (FAILED, CANCELLED, SKIPPED)")
    long countBrokenAmong(@Param("ids") Collection<String> ids);

    List<JobEntity> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    List<JobEntity> findByStateOrderBySubmittedAtDesc(JobState state, Pageable pageable);

    List<JobEntity> findByWorkflowIdOrderBySubmittedAtAsc(String workflowId);

    @Query("select j.state, count(j) from JobEntity j group by j.state")
    List<Object[]> countByState();

    @Query("select j from JobEntity j where j.state = SUCCEEDED "
            + "and j.finishedAt >= :since order by j.finishedAt desc")
    List<JobEntity> findSucceededSince(@Param("since") Instant since, Pageable pageable);

    /**
     * Progress is written with a conditional bulk update: it only applies while the attempt is
     * current, and it does not bump the optimistic-lock version, so frequent progress reports never
     * make a claim or completion fail.
     */
    @Modifying
    @Query("update JobEntity j set j.progressPercent = :percent, j.progressMessage = :message "
            + "where j.id = :jobId and j.currentAttemptId = :attemptId "
            + "and j.state = RUNNING")
    int updateProgress(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                       @Param("percent") int percent, @Param("message") String message);
}

interface AttemptRepository extends JpaRepository<AttemptEntity, String> {

    List<AttemptEntity> findByJobIdOrderByAttemptNumberAsc(String jobId);

    List<AttemptEntity> findByWorkerIdAndState(String workerId, AttemptState state);

    List<AttemptEntity> findByState(AttemptState state);

    long countByWorkerIdAndState(String workerId, AttemptState state);

    long countByState(AttemptState state);

    @Query("select a.id from AttemptEntity a where a.state = RUNNING "
            + "and a.leaseExpiresAt < :now")
    List<String> findExpiredLeaseIds(@Param("now") Instant now);

    @Query("select a.workerId, a.state, count(a) from AttemptEntity a group by a.workerId, a.state")
    List<Object[]> countByWorkerAndState();
}

interface WorkerRepository extends JpaRepository<WorkerEntity, String> {

    List<WorkerEntity> findByStatusIn(Collection<WorkerStatus> statuses);

    @Query("select w.id from WorkerEntity w where w.status in (ONLINE, "
            + "DRAINING, PAUSED) and w.lastHeartbeatAt < :cutoff")
    List<String> findSilentWorkerIds(@Param("cutoff") Instant cutoff);

    List<WorkerEntity> findAllByOrderByNameAsc();
}

interface TaskDefinitionRepository extends JpaRepository<TaskDefinitionEntity, String> {

    Optional<TaskDefinitionEntity> findByTaskType(String taskType);

    List<TaskDefinitionEntity> findAllByOrderByTaskTypeAsc();
}

interface WorkflowRepository extends JpaRepository<WorkflowEntity, String> {

    List<WorkflowEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

interface ArtifactRepository extends JpaRepository<ArtifactEntity, String> {

    List<ArtifactEntity> findByJobId(String jobId);
}

interface SystemEventRepository extends JpaRepository<SystemEventEntity, Long> {

    List<SystemEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<SystemEventEntity> findByIdLessThanOrderByIdDesc(long id, Pageable pageable);

    List<SystemEventEntity> findByJobIdOrderByIdAsc(String jobId);
}

interface BenchmarkReportRepository extends JpaRepository<BenchmarkReportEntity, String> {

    List<BenchmarkReportEntity> findAllByOrderByCreatedAtDesc();
}

interface SchedulerSettingsRepository extends JpaRepository<SchedulerSettingsEntity, Integer> {
}
