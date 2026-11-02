package com.queuepilot.scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Factory and registry for SchedulingPolicy instances.
 */
@Component
public class SchedulerPolicyFactory {

    private final Map<String, SchedulingPolicy> policies = new HashMap<>();
    private volatile String activePolicyName = "FCFS";

    @Autowired
    public SchedulerPolicyFactory(FcFSSchedulingPolicy fcfs,
                                  SjfSchedulingPolicy sjf,
                                  PriorityAgingSchedulingPolicy priorityAging,
                                  MlfqSchedulingPolicy mlfq) {
        registerPolicy(fcfs);
        registerPolicy(sjf);
        registerPolicy(priorityAging);
        registerPolicy(mlfq);
    }

    public SchedulerPolicyFactory() {
        registerPolicy(new FcFSSchedulingPolicy());
        registerPolicy(new SjfSchedulingPolicy());
        registerPolicy(new PriorityAgingSchedulingPolicy());
        registerPolicy(new MlfqSchedulingPolicy());
    }

    public synchronized void registerPolicy(SchedulingPolicy policy) {
        if (policy != null) {
            policies.put(policy.getPolicyName().toUpperCase(), policy);
        }
    }

    public synchronized Optional<SchedulingPolicy> getPolicy(String policyName) {
        if (policyName == null) return Optional.empty();
        return Optional.ofNullable(policies.get(policyName.toUpperCase()));
    }

    public synchronized SchedulingPolicy getActivePolicy() {
        return policies.getOrDefault(activePolicyName, policies.get("FCFS"));
    }

    public synchronized void setActivePolicyName(String policyName) {
        if (policyName != null && policies.containsKey(policyName.toUpperCase())) {
            this.activePolicyName = policyName.toUpperCase();
        } else {
            throw new IllegalArgumentException("Unknown scheduling policy: " + policyName);
        }
    }

    public synchronized String getActivePolicyName() {
        return activePolicyName;
    }

    public synchronized Map<String, SchedulingPolicy> getAvailablePolicies() {
        return Map.copyOf(policies);
    }
}
