package com.queuepilot.coordinator.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskDefinitionRepository extends JpaRepository<TaskDefinitionEntity, String> {

    Optional<TaskDefinitionEntity> findByTaskType(String taskType);

    List<TaskDefinitionEntity> findAllByOrderByTaskTypeAsc();
}
