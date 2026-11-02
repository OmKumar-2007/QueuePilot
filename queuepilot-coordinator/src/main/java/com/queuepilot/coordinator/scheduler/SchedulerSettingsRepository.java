package com.queuepilot.coordinator.scheduler;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulerSettingsRepository extends JpaRepository<SchedulerSettingsEntity, Integer> {
}
