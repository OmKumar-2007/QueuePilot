package com.queuepilot.coordinator.workflow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, String> {

    List<WorkflowEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
