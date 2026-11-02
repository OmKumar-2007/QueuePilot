package com.queuepilot.executor;

import com.queuepilot.model.JobType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry Pattern for managing JobExecutors.
 * Satisfies SRS F8: built-in executors with extensible interface.
 */
@Component
public class ExecutorRegistry {

    private final Map<JobType, JobExecutor> registry = new EnumMap<>(JobType.class);

    public ExecutorRegistry() {
        register(new SortingJobExecutor());
        register(new TextAnalysisJobExecutor());
        register(new MathComputeJobExecutor());
    }

    public synchronized void register(JobExecutor executor) {
        if (executor != null) {
            registry.put(executor.getSupportedType(), executor);
        }
    }

    public synchronized Optional<JobExecutor> getExecutor(JobType jobType) {
        return Optional.ofNullable(registry.get(jobType));
    }

    public synchronized boolean hasExecutor(JobType jobType) {
        return registry.containsKey(jobType);
    }

    public synchronized Map<JobType, JobExecutor> getAllExecutors() {
        return Collections.unmodifiableMap(new EnumMap<>(registry));
    }
}
