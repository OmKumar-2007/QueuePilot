package com.queuepilot.coordinator.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SystemEventRepository extends JpaRepository<SystemEventEntity, Long> {

    List<SystemEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<SystemEventEntity> findByIdLessThanOrderByIdDesc(long id, Pageable pageable);

    List<SystemEventEntity> findByJobIdOrderByIdAsc(String jobId);
}
