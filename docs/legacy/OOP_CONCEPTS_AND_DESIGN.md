# QueuePilot: Comprehensive Object-Oriented Architecture & Low-Level Design Guide

> **Course:** CS1207 Object Oriented Programming Lab  
> **Project Title:** QueuePilot – Resilient Task Execution, Job Scheduling & Worker Orchestration Platform  
> **Group:** II-19  
> **Author Team:** Om Kumar, Abhinav Singh Saini, Samyak Jain, Subhamjyoti Maharana  
> **Target Release:** Version 1.0 (Production-grade Educational Prototype)  

---

## 📑 Detailed Table of Contents

1. [Architectural Overview & Engineering Philosophy](#1-architectural-overview--engineering-philosophy)
2. [Component-by-Component OOP Mapping](#2-component-by-component-oop-mapping)
3. [Deep-Dive: Encapsulation & Information Hiding](#3-deep-dive-encapsulation--information-hiding)
4. [Deep-Dive: Inheritance & Subclass Specialization](#4-deep-dive-inheritance--subclass-specialization)
5. [Deep-Dive: Polymorphism & Dynamic Runtime Dispatch](#5-deep-dive-polymorphism--dynamic-runtime-dispatch)
6. [Deep-Dive: Abstraction & Interface Segregation](#6-deep-dive-abstraction--interface-segregation)
7. [Design Patterns Analysis](#7-design-patterns-analysis)
   - [7.1 Strategy Pattern (Scheduling Algorithms)](#71-strategy-pattern-scheduling-algorithms)
   - [7.2 Observer / Listener Pattern (Asynchronous Event Flow)](#72-observer--listener-pattern-asynchronous-event-flow)
   - [7.3 Registry & Factory Pattern (Decoupled Instantiation)](#73-registry--factory-pattern-decoupled-instantiation)
   - [7.4 Builder Pattern (Immutable Result Telemetry)](#74-builder-pattern-immutable-result-telemetry)
   - [7.5 Finite State Machine Pattern (Lifecycle Management)](#75-finite-state-machine-pattern-lifecycle-management)
8. [Concurrency, Multithreading & Synchronization](#8-concurrency-multithreading--synchronization)
9. [Graph Data Structures & DAG Cycle Detection](#9-graph-data-structures--dag-cycle-detection)
10. [Fault Tolerance, Lease Fencing & Recovery Watchdog](#10-fault-tolerance-lease-fencing--recovery-watchdog)
11. [Rigorous SOLID Principles Audit](#11-rigorous-solid-principles-audit)
12. [Professor Evaluation & Viva Defense Masterclass](#12-professor-evaluation--viva-defense-masterclass)

---

## 1. Architectural Overview & Engineering Philosophy

QueuePilot was designed from the ground up to demonstrate that modern enterprise systems are not merely web interfaces wrapping database tables, but are **sophisticated object-oriented state machines operating over concurrent physical threads**.

The platform is partitioned into six decoupled subsystems:
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PRESENTATION LAYER                                │
│       HTML5 / Modern JavaScript Single Page Interface (Polling @ 800ms)     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ JSON / REST API
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                            API & ADAPTER LAYER                              │
│  JobController  •  WorkerController  •  SchedulerController  • EventController│
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Method Invocations
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                    DISPATCH & ORCHESTRATION SUBSYSTEM                       │
│     JobQueueManager (Thread-Safe)  ◄───►  DispatchCoordinator (Thread Loop)  │
│                ▲                                        ▲                   │
│                │ Queries Runnable                       │ Assigns Lease     │
│                ▼                                        ▼                   │
│   SchedulingPolicy Hierarchy                WorkerPool (4 Threads)          │
│   (FCFS / SJF / Aging / MLFQ)               (WorkerInstances + Listeners)   │
└──────────────────┬──────────────────────────────────────┬───────────────────┘
                   │                                      │
                   ▼                                      ▼
┌──────────────────────────────────────┐┌─────────────────────────────────────┐
│          FAULT TOLERANCE             ││        PERSISTENCE LAYER            │
│   RecoveryWatchdog (1s Polling)      ││   Spring Data JPA + PostgreSQL / H2 │
│   Lease Expiry & Crash Recovery      ││   AuditService & Historical Entities│
└──────────────────────────────────────┘└─────────────────────────────────────┘
```

Every subsystem interacts exclusively through **interfaces and polymorphic contracts**, satisfying high cohesion and low coupling.

---

## 2. Component-by-Component OOP Mapping

The table below catalogs every key class in QueuePilot, detailing the exact OOP concepts it implements and its structural role:

| Component Class | Package Location | Primary OOP Concepts | Role & Responsibility |
| :--- | :--- | :--- | :--- |
| [`Job`](src/main/java/com/queuepilot/model/Job.java) | `model` | Encapsulation, Abstraction, Invariant Protection | Abstract base entity modeling universal scheduling state, timing, priorities, and dependencies. |
| [`SortingJob`](src/main/java/com/queuepilot/model/SortingJob.java) | `model` | Inheritance, Subtyping, Overloading | Concrete specialization encapsulating numerical datasets and sorting algorithm tags. |
| [`TextAnalysisJob`](src/main/java/com/queuepilot/model/TextAnalysisJob.java) | `model` | Inheritance, Subtyping | Concrete specialization encapsulating text corpus and keyword target lists. |
| [`MathComputeJob`](src/main/java/com/queuepilot/model/MathComputeJob.java) | `model` | Inheritance, Subtyping | Concrete specialization encapsulating mathematical iteration counts and computation types. |
| [`Worker`](src/main/java/com/queuepilot/model/Worker.java) | `model` | Encapsulation, State Machine | Entity tracking worker hardware identity, capability tags, assignment binding, and health. |
| [`JobResult`](src/main/java/com/queuepilot/model/JobResult.java) | `model` | Builder Pattern, Immutability | Immutable output carrier containing execution duration, telemetry data, and errors. |
| [`JobExecutor`](src/main/java/com/queuepilot/executor/JobExecutor.java) | `executor` | Interface Abstraction, Polymorphism | Contract for stepped execution emitting progress and heartbeats. |
| [`ProgressCallback`](src/main/java/com/queuepilot/executor/ProgressCallback.java) | `executor` | Functional Interface (`@FunctionalInterface`), ISP | Callback contract for granular progress percentage streaming. |
| [`HeartbeatCallback`](src/main/java/com/queuepilot/executor/HeartbeatCallback.java) | `executor` | Functional Interface, ISP | Callback contract for worker lease renewal pulses. |
| [`ExecutorRegistry`](src/main/java/com/queuepilot/executor/ExecutorRegistry.java) | `executor` | Registry Pattern, Encapsulation | Thread-safe directory mapping `JobType` to concrete polymorphic executors. |
| [`SchedulingPolicy`](src/main/java/com/queuepilot/scheduler/SchedulingPolicy.java) | `scheduler` | Strategy Pattern, Interface Abstraction | Strategy interface defining `orderJobs(...)` and capability filtering. |
| [`MlfqSchedulingPolicy`](src/main/java/com/queuepilot/scheduler/MlfqSchedulingPolicy.java) | `scheduler` | Strategy Implementation, State Aging | Multi-Level Feedback Queue with dynamic tier demotion and anti-starvation boost. |
| [`DependencyGraph`](src/main/java/com/queuepilot/dag/DependencyGraph.java) | `dag` | Data Structure Abstraction, Recursion | Graph engine managing DAG prerequisites and DFS 3-color cycle detection. |
| [`WorkerInstance`](src/main/java/com/queuepilot/worker/WorkerInstance.java) | `worker` | Multithreading (`Runnable`), Observer Pattern | Encapsulates a physical OS daemon thread executing tasks and handling simulated faults. |
| [`WorkerPool`](src/main/java/com/queuepilot/worker/WorkerPool.java) | `worker` | Composite Manager, Encapsulation | Orchestrates 4 concurrent `WorkerInstance` threads with specialized capability sets. |
| [`JobQueueManager`](src/main/java/com/queuepilot/dispatch/JobQueueManager.java) | `dispatch` | Synchronization (`ReentrantLock`), Fencing | Thread-safe central queue manager preventing race conditions and stale writes. |
| [`DispatchCoordinator`](src/main/java/com/queuepilot/dispatch/DispatchCoordinator.java) | `dispatch` | Observer (`WorkerEventListener`), Daemon Loop | Event-driven coordinator matching runnable jobs to idle capable workers. |
| [`RecoveryWatchdog`](src/main/java/com/queuepilot/recovery/RecoveryWatchdog.java) | `recovery` | Active Monitoring, Fault Recovery | Background watchdog scanning missed heartbeats and expired leases to reassign abandoned jobs. |
| [`BenchmarkService`](src/main/java/com/queuepilot/benchmark/BenchmarkService.java) | `benchmark` | Discrete-Event Simulation, Analytics | Simulates identical workloads across all 4 policies to calculate empirical performance metrics. |

---

## 3. Deep-Dive: Encapsulation & Information Hiding

### 3.1 Architectural Necessity
Encapsulation is not merely making fields `private` and generating getters/setters. In QueuePilot, encapsulation enforces **state machine invariants** and **thread-safe visibility boundaries**. Multiple threads (HTTP request threads, 4 worker execution threads, the scheduling coordinator thread, and the recovery watchdog thread) read and mutate job instances simultaneously. Without rigorous encapsulation, race conditions would corrupt states and lease tokens.

### 3.2 Concrete Implementations

#### A. Invariant Protection in Mutators
In [`Job.java`](src/main/java/com/queuepilot/model/Job.java), the `updateProgress` method ensures internal values never violate domain constraints:
```java
public synchronized void updateProgress(int percent, String message) {
    // Invariant: Progress MUST strictly reside in [0, 100]
    this.progressPercent = Math.min(100, Math.max(0, percent));
    if (message != null && !message.isBlank()) {
        this.progressMessage = message;
    }
}
```
*Why this matters:* External executors or workers cannot corrupt the progress bar with negative values or percentages exceeding $100\%$.

#### B. Defensive Immutability of Collections
In [`Job.java`](src/main/java/com/queuepilot/model/Job.java) and [`Worker.java`](src/main/java/com/queuepilot/model/Worker.java), dependencies and capability tags are stored in internal hash sets but exposed only via unmodifiable wrappers:
```java
private final Set<String> dependencies = new HashSet<>();

public Set<String> getDependencies() {
    return Collections.unmodifiableSet(dependencies);
}
```
*Why this matters:* If a client or thread calls `job.getDependencies().clear()`, Java throws `UnsupportedOperationException`. The dependency graph can only be altered through validated domain methods (`job.addDependency(...)`), preventing unauthorized mutation.

#### C. Atomic Cohesive State Transitions
A job cannot transition to `COMPLETED` without recording the `JobResult` and the timestamp `completedAt`:
```java
public synchronized void markCompleted(JobResult result) {
    this.state = JobState.COMPLETED;
    this.result = result;
    this.completedAt = Instant.now();
    this.progressPercent = 100;
    this.progressMessage = "Completed successfully";
}
```
*Why this matters:* Prevents half-updated states where a job is marked `COMPLETED` but has no completion timestamp or result object.

---

## 4. Deep-Dive: Inheritance & Subclass Specialization

### 4.1 Architectural Necessity
QueuePilot needs to schedule vastly different workloads:
1. Numerical arrays (Sorting)
2. Natural language strings (Text Analysis)
3. Large numbers and Monte Carlo iterations (Mathematical Computation)

Without inheritance, developers typically write three completely separate queue systems (`SortingQueue`, `TextQueue`, `MathQueue`). This violates the **Don't Repeat Yourself (DRY)** principle and creates massive maintenance overhead.

### 4.2 Class Hierarchy Design
Inheritance is implemented via an abstract base class [`Job`](src/main/java/com/queuepilot/model/Job.java) extended by three specialized classes:

```
                            ┌────────────────────────┐
                            │    Job (Abstract)      │
                            └───────────┬────────────┘
                                        │ extends
        ┌───────────────────────────────┼───────────────────────────────┐
        ▼                               ▼                               ▼
┌────────────────────────┐    ┌────────────────────────┐    ┌────────────────────────┐
│       SortingJob       │    │    TextAnalysisJob     │    │     MathComputeJob     │
├────────────────────────┤    ├────────────────────────┤    ├────────────────────────┤
│ - data: List<Integer>  │    │ - textCorpus: String   │    │ - computationType: Str │
│ - algorithm: String    │    │ - keywords: List<Str>  │    │ - inputParam: long     │
│ - chunkSize: int       │    │ - analysisMode: String │    │                        │
└────────────────────────┘    └────────────────────────┘    └────────────────────────┘
```

#### Code Implementation ([`SortingJob.java`](src/main/java/com/queuepilot/model/SortingJob.java))
```java
public class SortingJob extends Job {
    private final List<Integer> data;
    private final String algorithm;

    public SortingJob(String name, int priority, long estimatedDurationMs, 
                      List<Integer> data, String algorithm, Instant createdAt) {
        // Delegates common scheduling state initialization to base class
        super(name, JobType.SORTING, priority, estimatedDurationMs, createdAt);
        this.data = (data != null && !data.isEmpty()) ? new ArrayList<>(data) : generateSampleData(10_000);
        this.algorithm = (algorithm != null && !algorithm.isBlank()) ? algorithm.toUpperCase() : "QUICKSORT";
    }

    @Override
    public String getSummary() {
        return String.format("Sort %d integers using %s (est: %.1fs)", 
                data.size(), algorithm, getEstimatedDurationMs() / 1000.0);
    }
}
```

### 4.3 Why Abstract Class instead of Interface?
If `Job` were an interface:
- It could not maintain common private state variables (`id`, `basePriority`, `effectivePriority`, `retryCount`, `createdAt`, `attemptToken`, `leaseExpiresAt`).
- Every single subclass would have to duplicate the exact same 25 state variables, getters, setters, and synchronization locks.
- By making `Job` an abstract class, common state and lifecycle logic are implemented **once** in the parent, while domain-specific behavior is deferred to abstract methods.

---

## 5. Deep-Dive: Polymorphism & Dynamic Runtime Dispatch

### 5.1 Architectural Necessity
When the `WorkerInstance` thread takes a job from its assignment inbox, it must execute the job. It should **never** check the concrete class type using `instanceof` or switch statements:
```java
// ANTI-PATTERN: Brittle, violates Open/Closed Principle
if (job instanceof SortingJob) {
    runSort((SortingJob) job);
} else if (job instanceof TextAnalysisJob) {
    runText((TextAnalysisJob) job);
}
```
If we added 10 new task types, that `if-else` chain would need to be rewritten in multiple places throughout the codebase.

### 5.2 Dynamic Dispatch in Action
QueuePilot solves this using dynamic subtype polymorphism through [`JobExecutor`](src/main/java/com/queuepilot/executor/JobExecutor.java):

```java
public interface JobExecutor {
    JobType getSupportedType();
    JobResult execute(Job job, ProgressCallback progressCallback, HeartbeatCallback heartbeatCallback) throws Exception;
}
```

In [`WorkerInstance.java`](src/main/java/com/queuepilot/worker/WorkerInstance.java):
```java
// Polymorphic retrieval from the registry
JobExecutor executor = executorRegistry.getExecutor(job.getType())
        .orElseThrow(() -> new IllegalStateException("No executor for type " + job.getType()));

// Dynamic method dispatch at runtime: JVM resolves the target method
// based on whether the executor is SortingJobExecutor, TextAnalysisJobExecutor, etc.
JobResult result = executor.execute(job, progressCallback, heartbeatCallback);
```

### 5.3 Types of Polymorphism Demonstrated
1. **Subtype Polymorphism (Inclusion):** Passing any subclass of `Job` into `jobQueueManager.submitJob(Job job)` or `policy.orderJobs(List<Job> jobs)`.
2. **Dynamic Interface Dispatch:** Executing tasks via `JobExecutor.execute(...)`.
3. **Parametric Polymorphism (Generics):** Utilizing Java generic types across collections (`List<Job>`, `Map<String, Job>`, `Optional<WorkerInstance>`).
4. **Ad-hoc Polymorphism (Method Overloading):** Multiple constructors in `Job`:
   - `Job(String name, JobType type, int basePriority, long estimatedDurationMs)`
   - `Job(String name, JobType type, int basePriority, long estimatedDurationMs, Instant createdAt)` (allows deterministic historical simulation in tests and benchmarks).

---

## 6. Deep-Dive: Abstraction & Interface Segregation

### 6.1 Architectural Necessity
Abstraction establishes strict contracts between disparate modules. High-level modules should depend on high-level abstractions, not low-level concrete implementations (the **Dependency Inversion Principle**).

### 6.2 Key Abstractions

#### A. Execution Contract ([`JobExecutor.java`](src/main/java/com/queuepilot/executor/JobExecutor.java))
The executor interface isolates the computation algorithm from worker pools, database schemas, and networking:
```java
public interface JobExecutor {
    JobType getSupportedType();
    JobResult execute(Job job, ProgressCallback progressCallback, HeartbeatCallback heartbeatCallback) throws Exception;
}
```

#### B. Single Abstract Method (SAM) Functional Interfaces
In [`ProgressCallback.java`](src/main/java/com/queuepilot/executor/ProgressCallback.java) and [`HeartbeatCallback.java`](src/main/java/com/queuepilot/executor/HeartbeatCallback.java):
```java
@FunctionalInterface
public interface ProgressCallback {
    void onProgress(int progressPercent, String stepDescription);
}

@FunctionalInterface
public interface HeartbeatCallback {
    void sendHeartbeat();
}
```
*Why this matters:* Adheres strictly to the **Interface Segregation Principle (ISP)**. The executor does not receive a bulky reference to the whole `WorkerInstance` or `JobQueueManager`. It receives only the two minimal callbacks it needs.

In unit tests ([`JobExecutorTest.java`](src/test/java/com/queuepilot/executor/JobExecutorTest.java)), testing an executor requires only trivial lambdas:
```java
JobResult result = executor.execute(job, (percent, msg) -> {}, () -> {});
```
This isolates the test completely from threads, queues, or mock objects.

---

## 7. Design Patterns Analysis

### 7.1 Strategy Pattern (Scheduling Algorithms)

```
                            ┌────────────────────────┐
                            │ <<SchedulingPolicy>>   │
                            ├────────────────────────┤
                            │ + getPolicyName()      │
                            │ + orderJobs(...)       │
                            │ + selectNextJob(...)   │
                            └───────────┬────────────┘
                                        │ implements
        ┌───────────────────┬───────────┴───────────┬───────────────────┐
        ▼                   ▼                       ▼                   ▼
┌───────────────┐   ┌───────────────┐       ┌───────────────┐   ┌───────────────┐
│     FCFS      │   │      SJF      │       │Priority Aging │   │     MLFQ      │
└───────────────┘   └───────────────┘       └───────────────┘   └───────────────┘
```

#### The Problem
Operating systems employ different scheduling strategies depending on workload requirements:
- Batch systems favor First-Come, First-Served (`FCFS`).
- Throughput-focused systems favor Shortest Job First (`SJF`).
- Starvation-prone systems favor Priority with Dynamic Aging (`PRIORITY_AGING`).
- Interactive systems favor Multi-Level Feedback Queues (`MLFQ`).

Hardcoding these algorithms into the queue logic using `switch` statements would create messy, unmaintainable code.

#### The Pattern Solution
We define a Strategy interface [`SchedulingPolicy`](src/main/java/com/queuepilot/scheduler/SchedulingPolicy.java):
```java
public interface SchedulingPolicy {
    String getPolicyName();
    List<Job> orderJobs(List<Job> candidateJobs, Instant now);
    
    default Optional<Job> selectNextJob(List<Job> candidateJobs, Worker worker, Instant now) {
        return orderJobs(candidateJobs, now).stream()
                .filter(worker::canExecute)
                .findFirst();
    }
}
```

#### Four Concrete Strategies Implemented:
1. **FCFS (`FcFSSchedulingPolicy`):**
   ```java
   sorted.sort(Comparator.comparing(Job::getCreatedAt));
   ```
2. **SJF (`SjfSchedulingPolicy`):**
   ```java
   sorted.sort(Comparator
           .comparingLong(Job::getEstimatedDurationMs)
           .thenComparing(Job::getCreatedAt));
   ```
3. **Priority with Aging (`PriorityAgingSchedulingPolicy`):**
   Calculates waiting duration and dynamically ages effective priority:
   $$\text{effectivePriority} = \text{basePriority} + \left( \left\lfloor \frac{t_{\text{wait}}}{\Delta t_{\text{aging}}} \right\rfloor \times \text{boost} \right)$$
   ```java
   for (Job job : sorted) {
       job.updateEffectivePriority(now, agingIntervalSeconds, agingIncrement);
   }
   sorted.sort(Comparator.comparingInt(Job::getEffectivePriority).reversed()
           .thenComparing(Job::getCreatedAt));
   ```
4. **MLFQ (`MlfqSchedulingPolicy`):**
   Multi-tier feedback queue with dynamic demotion (Tier 0: $\le 5\text{s}$, Tier 1: $5-15\text{s}$, Tier 2: $> 15\text{s}$) and periodic anti-starvation boost resetting all waiting jobs back to Tier 0.

#### Significance
The active strategy can be swapped at runtime with a single REST call (`POST /api/scheduler/policy?name=MLFQ`) without dropping any queued jobs or stopping the worker threads.

---

### 7.2 Observer / Listener Pattern (Asynchronous Event Flow)

#### The Problem
When a worker thread finishes or crashes, several components must be notified:
1. The `JobQueueManager` must record the result and unlock dependent DAG jobs.
2. The `AuditService` must persist completion records in PostgreSQL.
3. The `DispatchCoordinator` must run a new dispatch cycle.

Having `WorkerInstance` directly call all these services creates tight circular coupling.

#### The Pattern Solution
We defined the [`WorkerEventListener`](src/main/java/com/queuepilot/worker/WorkerEventListener.java) interface:
```java
public interface WorkerEventListener {
    void onProgress(String workerId, String jobId, String attemptToken, int progressPercent, String message);
    void onHeartbeat(String workerId, String jobId, String attemptToken);
    void onJobCompleted(String workerId, String jobId, String attemptToken, JobResult result);
    void onJobFailed(String workerId, String jobId, String attemptToken, String reason, Throwable cause);
}
```
[`DispatchCoordinator`](src/main/java/com/queuepilot/dispatch/DispatchCoordinator.java) implements this listener. When a worker completes:
```java
@Override
public void onJobCompleted(String workerId, String jobId, String attemptToken, JobResult result) {
    jobQueueManager.completeJob(jobId, attemptToken, result);
    // Worker is now idle; trigger immediate dispatch for pending work!
    dispatchCycle();
}
```

---

### 7.3 Registry & Factory Pattern (Decoupled Instantiation)

- **`ExecutorRegistry`:** Acts as a directory of available computational executors mapped by `JobType`. It avoids hardcoded constructor calls across the execution engine.
- **`SchedulerPolicyFactory`:** Manages the lifecycle of scheduling strategies and maintains the volatile pointer to the currently active policy.

---

### 7.4 Builder Pattern (Immutable Result Telemetry)

In [`JobResult.java`](src/main/java/com/queuepilot/model/JobResult.java), execution output requires constructing an immutable data object with multiple optional fields:
```java
public static class Builder {
    private boolean success;
    private String message;
    private Map<String, Object> outputData;
    private long executionDurationMs;
    private Instant finishedAt;
    private String errorMessage;

    public Builder success(boolean success) { this.success = success; return this; }
    public Builder outputData(Map<String, Object> data) { this.outputData = data; return this; }
    public JobResult build() { return new JobResult(this); }
}
```
*Why this matters:* Avoids telescoping constructors (e.g. `new JobResult(true, "ok", null, 1000, now, null)`) which are error-prone and hard to read.

---

### 7.5 Finite State Machine Pattern (Lifecycle Management)

Both `Job` and `Worker` are modeled as deterministic Finite State Machines (FSM):

#### Job State Machine:
```
           ┌──────────────┐
           │  SCHEDULED   │ (Future start time)
           └──────┬───────┘
                  │ Start time reached
                  ▼
  [Submit] ──► ┌──────┐ Prereqs complete ┌────────┐ Assigned to Worker ┌─────────┐
               │BLOCKED├────────────────►│ QUEUED ├───────────────────►│ RUNNING │
               └──────┘                  └────────┘                    └──┬──┬──┬─┘
                                              ▲                           │  │  │
                                              │ Re-queued by Watchdog     │  │  │
                                              └───────────────────────────┘  │  │
                                              Success                        │  │
                                              ───────────────────────────────┘  │
                                              ▼                                 │
                                        ┌───────────┐         Max Retries       │
                                        │ COMPLETED │         Exceeded          │
                                        └───────────┘         ──────────────────┘
                                                              ▼
                                                        ┌───────────┐
                                                        │  FAILED   │
                                                        └───────────┘
```

#### Worker State Machine:
```
                 ┌──────────┐
          ┌─────►│   IDLE   │◄──────────┐
          │      └────┬─────┘           │
          │           │ Assigned Job    │ Revived
Job Done  │           ▼                 │
          │      ┌──────────┐           │
          └──────┤   BUSY   │           │
                 └────┬─────┘           │
                      │ Missed Heartbeats
                      ▼                 │
                 ┌──────────────┐       │
                 │ UNRESPONSIVE │       │
                 └────┬─────────┘       │
                      │ Thread Killed   │
                      ▼                 │
                 ┌──────────┐           │
                 │   DEAD   ├───────────┘
                 └──────────┘
```

---

## 8. Concurrency, Multithreading & Synchronization

### 8.1 Dedicated Worker Thread Pool
[`WorkerPool`](src/main/java/com/queuepilot/worker/WorkerPool.java) initializes **4 independent OS worker threads**, each backed by an instance of [`WorkerInstance`](src/main/java/com/queuepilot/worker/WorkerInstance.java) implementing Java's `Runnable`:
```java
public synchronized void start() {
    if (!running.get()) {
        running.set(true);
        executionThread = new Thread(this, "worker-thread-" + workerModel.getId());
        executionThread.setDaemon(true);
        executionThread.start();
    }
}
```

### 8.2 Synchronization Mechanisms

#### A. Explicit Mutual Exclusion via `ReentrantLock`
In [`JobQueueManager.java`](src/main/java/com/queuepilot/dispatch/JobQueueManager.java), all queue updates use explicit lock blocks:
```java
private final ReentrantLock queueLock = new ReentrantLock();

public void submitJob(Job job) {
    queueLock.lock();
    try {
        dependencyGraph.addJob(job);
        dependencyGraph.validateNoCycles();
        jobStore.put(job.getId(), job);
    } finally {
        queueLock.unlock(); // Guaranteed release even if an exception occurs
    }
}
```
*Why `ReentrantLock` instead of `synchronized` method?*
- `ReentrantLock` gives explicit control over critical section scope.
- Provides fairness policies and interruptible locking capabilities.

#### B. Thread-Safe Blocking Queues
Each `WorkerInstance` possesses a single-element assignment inbox:
```java
private final BlockingQueue<JobAssignment> assignmentInbox = new LinkedBlockingQueue<>(1);
```
Passing work between the `DispatchCoordinator` thread and the `WorkerInstance` thread uses `assignmentInbox.offer(...)` and `assignmentInbox.poll(...)`, eliminating busy-waiting.

#### C. Memory Visibility via `volatile` and Atomics
- `private volatile Instant lastHeartbeat;` guarantees that updates made by a worker thread are immediately visible to the `RecoveryWatchdog` thread without CPU caching staleness.
- `AtomicBoolean running` and `AtomicInteger totalFailuresDetected` guarantee atomic operations without full lock overhead.

---

## 9. Graph Data Structures & DAG Cycle Detection

### 9.1 The Directed Acyclic Graph (DAG) Problem
Jobs can declare prerequisites (`jobB.addDependency(jobA.getId())`). If a client accidentally submits circular dependencies ($A \to B \to C \to A$), standard queues would deadlock forever waiting for prerequisites that can never finish.

### 9.2 Graph Engine Implementation ([`DependencyGraph.java`](src/main/java/com/queuepilot/dag/DependencyGraph.java))
The graph maintains an adjacency list representation:
```java
private final Map<String, Set<String>> adjacencyList = new HashMap<>();
```

#### Cycle Detection Algorithm (DFS 3-Color Marking):
We use Depth-First Search with three state colors:
- **State 0 (UNVISITED):** Node not yet inspected.
- **State 1 (VISITING):** Node is currently on the recursion call stack.
- **State 2 (VISITED):** Node and all its subtrees have been verified acyclic.

```java
private boolean dfsHasCycle(String current, Map<String, Integer> state, List<String> path) {
    state.put(current, 1); // Mark VISITING
    path.add(current);

    for (String dep : adjacencyList.getOrDefault(current, Collections.emptySet())) {
        int depState = state.getOrDefault(dep, 0);
        if (depState == 1) {
            // Found a back-edge to a node currently in the call stack: CYCLE DETECTED!
            path.add(dep);
            return true;
        }
        if (depState == 0 && dfsHasCycle(dep, state, path)) {
            return true;
        }
    }

    path.remove(path.size() - 1);
    state.put(current, 2); // Mark VISITED
    return false;
}
```

If a cycle is detected, QueuePilot throws [`CycleDetectedException`](src/main/java/com/queuepilot/dag/CycleDetectedException.java) containing the exact path, and rejects the submission before it enters the scheduling queue.

---

## 10. Fault Tolerance, Lease Fencing & Recovery Watchdog

### 10.1 The Distributed Consensus Analogy
In real-world task platforms (like Celery, Kubernetes, or AWS SQS), workers can crash, freeze, or suffer network partitions. QueuePilot implements educational **lease-based fault tolerance**.

### 10.2 Watchdog Operation ([`RecoveryWatchdog.java`](src/main/java/com/queuepilot/recovery/RecoveryWatchdog.java))
The watchdog runs an independent background thread scanning every second:
1. **Heartbeat Timeout:** Compares `Duration.between(worker.getLastHeartbeat(), now)`. If $\Delta t > 4000\text{ms}$, marks the worker `UNRESPONSIVE`.
2. **Crash Detection:** If `worker.getStatus() == WorkerStatus.DEAD`.
3. **Lease Expiration:** If `job.getLeaseExpiresAt().isBefore(now)`.

```java
if (isDead || timedOut) {
    worker.markUnresponsive();
    String activeJobId = worker.getCurrentJobId();
    if (activeJobId != null) {
        recoverJobFromWorker(worker, activeJobId, timedOut ? "Heartbeat timeout" : "Worker crash");
    }
}
```

### 10.3 Stale-Update Fencing (SRS F11)
When Worker A crashes, its job is reclaimed and assigned to Worker B with a **new `attemptToken`**. If Worker A unexpectedly unfreezes and attempts to submit results, [`JobQueueManager.java`](src/main/java/com/queuepilot/dispatch/JobQueueManager.java) checks:
```java
if (!attemptToken.equals(job.getAttemptToken())) {
    log.warn("Fencing rejected stale completion from expired token [{}]", attemptToken);
    return false;
}
```
This guarantees that **zombie worker threads cannot overwrite newer valid data**.

---

## 11. Rigorous SOLID Principles Audit

| Principle | Meaning | How QueuePilot Conforms |
| :--- | :--- | :--- |
| **S - Single Responsibility** | A class should have one, and only one, reason to change. | `Job` only models state. `JobExecutor` only computes algorithms. `SchedulingPolicy` only orders lists. `RecoveryWatchdog` only monitors leases. |
| **O - Open / Closed** | Software entities should be open for extension, but closed for modification. | New job types (e.g. `AudioTranscodeJob`) can be introduced by creating a new subclass and executor without editing existing queue or worker code. |
| **L - Liskov Substitution** | Subtypes must be substitutable for their base types without altering correctness. | Any subclass of `Job` can be submitted to `JobQueueManager`, checked by `DependencyGraph`, and sorted by `SchedulingPolicy` seamlessly. |
| **I - Interface Segregation** | Clients should not be forced to depend on methods they do not use. | Executors depend only on minimal SAM interfaces (`ProgressCallback`, `HeartbeatCallback`) rather than entire database or queue managers. |
| **D - Dependency Inversion** | High-level modules should not depend on low-level modules; both should depend on abstractions. | `WorkerInstance` and `DispatchCoordinator` interact exclusively with `JobExecutor` and `SchedulingPolicy` interfaces. |

---

## 12. Professor Evaluation & Viva Defense Masterclass

Use these technical answers to defend your project during evaluation:

#### Q1: "Why did you use an abstract class for `Job` instead of an interface?"
> **Answer:** An interface cannot hold state. Every job in QueuePilot shares fundamental scheduling state: unique UUID, base and effective priorities, state transitions, retry counts, attempt tokens, and creation timestamps. Using an abstract class allows us to encapsulate this shared state and universal methods (`updateEffectivePriority`, `markCompleted`) once, while keeping task-specific logic abstract (`getSummary()`, `getPayload()`).

#### Q2: "How is the Strategy Pattern applied in your project?"
> **Answer:** We defined the `SchedulingPolicy` interface with the `orderJobs(...)` contract. We implemented four interchangeable strategy classes: `FCFS`, `SJF`, `Priority with Aging`, and `MLFQ`. The `DispatchCoordinator` holds a reference to the strategy interface, allowing active policies to be switched at runtime via the dashboard dropdown without modifying queue or worker code.

#### Q3: "How does your implementation prevent race conditions when 4 workers pick jobs simultaneously?"
> **Answer:** We implemented `ReentrantLock` in `JobQueueManager`. The `claimJob(...)` method atomically checks that the job is in the `QUEUED` state, verifies worker capabilities, transitions the job to `RUNNING`, and generates a unique `attemptToken` and time-limited lease before releasing the lock. This guarantees no two workers can claim the same job.

#### Q4: "What is Priority Aging and why was it necessary?"
> **Answer:** In standard priority scheduling, low-priority jobs can starve indefinitely if higher-priority jobs keep arriving. Priority Aging dynamically boosts a waiting job's effective priority:
> $$\text{effectivePriority} = \text{basePriority} + \left( \left\lfloor \frac{\text{waitingTimeSeconds}}{\text{agingInterval}} \right\rfloor \times \text{increment} \right)$$
> This guarantees that every waiting job will eventually reach top priority and execute.

#### Q5: "How does your MLFQ (Multi-Level Feedback Queue) work?"
> **Answer:** Our MLFQ implements 3 priority tiers. Short interactive tasks ($\le 5\text{s}$) enter Tier 0. Tasks exceeding their time allotment are demoted to Tier 1 ($5-15\text{s}$) or Tier 2 ($>15\text{s}$). To eliminate starvation of long compute tasks, an anti-starvation periodic boost moves all waiting jobs back to Tier 0 every configured boost interval.

#### Q6: "How do you detect and recover from worker failures?"
> **Answer:** When a job is assigned, it receives an `attemptToken` and a time-limited lease. Workers send periodic heartbeats every second. If a worker thread is killed or freezes, the `RecoveryWatchdog` detects the missed heartbeats or expired lease, marks the attempt `ABANDONED`, increments the retry count, and safely re-queues the job for another healthy worker thread. Any delayed completion from the old dead attempt is fenced out and rejected.

#### Q7: "How is the Observer pattern used in worker-coordinator communication?"
> **Answer:** `WorkerInstance` does not have hardcoded references to `JobQueueManager` or `AuditService`. Instead, it fires events through the `WorkerEventListener` interface (`onProgress`, `onHeartbeat`, `onJobCompleted`, `onJobFailed`). The `DispatchCoordinator` acts as the observer, receiving these events to update database history, unblock waiting DAG prerequisites, and trigger new scheduling cycles.
