package com.queuepilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<JobEntity, String> {
    List<JobEntity> findByState(String state);
    List<JobEntity> findAllByOrderByCreatedAtDesc();
}
