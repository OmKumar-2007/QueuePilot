package com.queuepilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobAttemptRepository extends JpaRepository<JobAttemptEntity, Long> {
    List<JobAttemptEntity> findByJobIdOrderByAttemptNumberAsc(String jobId);
    Optional<JobAttemptEntity> findByJobIdAndAttemptToken(String jobId, String attemptToken);
}
