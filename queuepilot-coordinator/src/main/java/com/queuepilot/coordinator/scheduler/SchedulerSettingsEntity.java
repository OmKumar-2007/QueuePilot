package com.queuepilot.coordinator.scheduler;

import com.queuepilot.core.scheduling.PolicyType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Single-row table holding the administrator's scheduler configuration, so it survives restarts. */
@Entity
@Table(name = "scheduler_settings")
public class SchedulerSettingsEntity {

    public static final int SINGLETON_ID = 1;

    @Id
    private int id;
    @Enumerated(EnumType.STRING)
    private PolicyType activePolicy;
    private int agingIntervalSeconds;
    private int agingStep;

    protected SchedulerSettingsEntity() {
    }

    public void update(PolicyType policy, int agingIntervalSeconds, int agingStep) {
        this.activePolicy = policy;
        this.agingIntervalSeconds = agingIntervalSeconds;
        this.agingStep = agingStep;
    }

    public PolicyType getActivePolicy() { return activePolicy; }
    public int getAgingIntervalSeconds() { return agingIntervalSeconds; }
    public int getAgingStep() { return agingStep; }
}
