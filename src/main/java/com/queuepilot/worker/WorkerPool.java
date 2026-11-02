package com.queuepilot.worker;

import com.queuepilot.executor.ExecutorRegistry;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobType;
import com.queuepilot.model.Worker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the collection of concurrent WorkerInstances.
 * Satisfies SRS F6, F7, and the 4-worker concurrent demo scenario.
 */
@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final ExecutorRegistry executorRegistry;
    private final Map<String, WorkerInstance> workers = new ConcurrentHashMap<>();
    private WorkerEventListener eventListener;

    @Value("${queuepilot.workers.default-count:4}")
    private int defaultWorkerCount;

    @Autowired
    public WorkerPool(ExecutorRegistry executorRegistry) {
        this.executorRegistry = executorRegistry;
    }

    public synchronized void setEventListener(WorkerEventListener eventListener) {
        this.eventListener = eventListener;
        for (WorkerInstance instance : workers.values()) {
            instance.setEventListener(eventListener);
        }
    }

    @PostConstruct
    public synchronized void initializeDefaultPool() {
        if (!workers.isEmpty()) {
            return;
        }

        // Configure 4 workers as defined in QueuePilot demo requirements:
        // Worker 1: Generalist (all capabilities)
        // Worker 2: Sorting specialist
        // Worker 3: Text analysis specialist
        // Worker 4: Math computation specialist
        createAndRegisterWorker("worker-1", "Generalist Worker 1", Set.of(
                JobType.SORTING.getDefaultCapabilityTag(),
                JobType.TEXT_ANALYSIS.getDefaultCapabilityTag(),
                JobType.MATH_COMPUTE.getDefaultCapabilityTag()
        ));

        createAndRegisterWorker("worker-2", "Sorting Worker 2", Set.of(
                JobType.SORTING.getDefaultCapabilityTag()
        ));

        createAndRegisterWorker("worker-3", "Text Worker 3", Set.of(
                JobType.TEXT_ANALYSIS.getDefaultCapabilityTag()
        ));

        createAndRegisterWorker("worker-4", "Math Worker 4", Set.of(
                JobType.MATH_COMPUTE.getDefaultCapabilityTag()
        ));

        startAll();
        log.info("Initialized default pool with {} concurrent workers.", workers.size());
    }

    public synchronized WorkerInstance createAndRegisterWorker(String id, String name, Set<String> capabilities) {
        Worker model = new Worker(id, name, capabilities);
        WorkerInstance instance = new WorkerInstance(model, executorRegistry, eventListener);
        workers.put(id, instance);
        return instance;
    }

    public synchronized void startAll() {
        for (WorkerInstance instance : workers.values()) {
            instance.start();
        }
    }

    @PreDestroy
    public synchronized void stopAll() {
        log.info("Shutting down worker pool...");
        for (WorkerInstance instance : workers.values()) {
            instance.stop();
        }
    }

    public Optional<WorkerInstance> getWorkerInstance(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    public Optional<Worker> getWorkerModel(String workerId) {
        return getWorkerInstance(workerId).map(WorkerInstance::getWorkerModel);
    }

    public List<Worker> getAllWorkerModels() {
        List<Worker> list = new ArrayList<>();
        for (WorkerInstance inst : workers.values()) {
            list.add(inst.getWorkerModel());
        }
        return list;
    }

    public List<WorkerInstance> getAllWorkerInstances() {
        return new ArrayList<>(workers.values());
    }

    /**
     * Finds an idle, healthy worker that possesses all required capabilities for the job.
     */
    public synchronized Optional<WorkerInstance> findAvailableWorkerFor(Job job) {
        if (job == null) return Optional.empty();

        return workers.values().stream()
                .filter(WorkerInstance::isIdle)
                .filter(inst -> inst.getWorkerModel().canExecute(job))
                .findFirst();
    }

    /**
     * Finds all idle, healthy workers capable of running tasks.
     */
    public synchronized List<WorkerInstance> getAvailableWorkers() {
        return workers.values().stream()
                .filter(WorkerInstance::isIdle)
                .toList();
    }

    public synchronized boolean simulateCrash(String workerId) {
        WorkerInstance inst = workers.get(workerId);
        if (inst != null) {
            inst.simulateCrash();
            return true;
        }
        return false;
    }

    public synchronized boolean simulateFreeze(String workerId, boolean freeze) {
        WorkerInstance inst = workers.get(workerId);
        if (inst != null) {
            inst.setFrozen(freeze);
            return true;
        }
        return false;
    }

    public synchronized boolean reviveWorker(String workerId) {
        WorkerInstance inst = workers.get(workerId);
        if (inst != null) {
            inst.revive();
            return true;
        }
        return false;
    }
}
