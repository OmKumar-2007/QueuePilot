package com.queuepilot.coordinator.job;

import com.queuepilot.core.job.JobState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface JobRepository extends JpaRepository<JobEntity, String> {

    /** Jobs a worker could claim now: QUEUED and past any retry delay, oldest first. */
    @Query("select j from JobEntity j where j.state = com.queuepilot.core.job.JobState.QUEUED "
            + "and j.readyAt <= :now order by j.submittedAt asc")
    List<JobEntity> findClaimable(@Param("now") Instant now, Pageable pageable);

    List<JobEntity> findByStateOrderBySubmittedAtAsc(JobState state, Pageable pageable);

    @Query("select j from JobEntity j join j.prerequisiteIds p where p = :prerequisiteId")
    List<JobEntity> findDependants(@Param("prerequisiteId") String prerequisiteId);

    long countByIdInAndState(Collection<String> ids, JobState state);

    @Query("select count(j) from JobEntity j where j.id in :ids and j.state in ("
            + "com.queuepilot.core.job.JobState.FAILED, com.queuepilot.core.job.JobState.CANCELLED, "
            + "com.queuepilot.core.job.JobState.SKIPPED)")
    long countBrokenAmong(@Param("ids") Collection<String> ids);

    List<JobEntity> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    List<JobEntity> findByStateOrderBySubmittedAtDesc(JobState state, Pageable pageable);

    List<JobEntity> findByWorkflowIdOrderBySubmittedAtAsc(String workflowId);

    @Query("select j.state, count(j) from JobEntity j group by j.state")
    List<Object[]> countByState();

    @Query("select j from JobEntity j where j.state = com.queuepilot.core.job.JobState.SUCCEEDED "
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
            + "and j.state = com.queuepilot.core.job.JobState.RUNNING")
    int updateProgress(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                       @Param("percent") int percent, @Param("message") String message);
}
