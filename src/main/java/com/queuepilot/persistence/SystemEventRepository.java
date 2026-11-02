package com.queuepilot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemEventRepository extends JpaRepository<SystemEventEntity, Long> {
    List<SystemEventEntity> findTop50ByOrderByTimestampDesc();
}
