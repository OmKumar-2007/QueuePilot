package com.queuepilot.coordinator.attempt;

import com.queuepilot.core.job.AttemptState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AttemptRepository extends JpaRepository<AttemptEntity, String> {

    List<AttemptEntity> findByJobIdOrderByAttemptNumberAsc(String jobId);

    List<AttemptEntity> findByWorkerIdAndState(String workerId, AttemptState state);

    List<AttemptEntity> findByState(AttemptState state);

    long countByWorkerIdAndState(String workerId, AttemptState state);

    long countByState(AttemptState state);

    @Query("select a.id from AttemptEntity a where a.state = com.queuepilot.core.job.AttemptState.RUNNING "
            + "and a.leaseExpiresAt < :now")
    List<String> findExpiredLeaseIds(@Param("now") Instant now);

    @Query("select a.workerId, a.state, count(a) from AttemptEntity a group by a.workerId, a.state")
    List<Object[]> countByWorkerAndState();
}
